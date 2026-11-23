"""Build regional population observations at the 2024 scenario opening.

python3 tools/geodata/build_region_population.py [--cache-dir /path/to/raw-sources]
The crosswalk covers all domestic regions of each included state. Territories
outside the US or French national statistical perimeter are left unobserved.
"""
import argparse
import csv
import io
import json
from collections import defaultdict
from pathlib import Path
from zipfile import ZipFile

from build_regions import download

ROOT = Path(__file__).resolve().parents[2]
WORLD = ROOT / "simulation/src/main/resources/world"
CODES = Path(__file__).with_name("region_population_codes.csv")

US_CSV = "https://www2.census.gov/programs-surveys/popest/datasets/2020-2023/state/totals/NST-EST2023-ALLDATA.csv"
CA_ZIP = "https://www150.statcan.gc.ca/n1/tbl/csv/17100009-eng.zip"
EU_TABLE = "demo_r_pjangrp3"
EU_API = f"https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/{EU_TABLE}?time=2024&sex=T&age=TOTAL&unit=NR"
EU_PAGE = f"https://ec.europa.eu/eurostat/databrowser/view/{EU_TABLE}/default/table?lang=en"
KO_PAGE = "https://www.jeonnam.go.kr/contentsView.do?menuId=welfare0504000000"

# Fixed extract of the Ministry of the Interior and Safety register reproduced
# in Jeonnam's December 2023 table. Column: 전체 인구 / 계 (persons, all ages).
# The resident registration series excludes foreign nationals.
KOREA = {
    "서울": 9386034, "부산": 3293362, "대구": 2374960, "인천": 2997410,
    "광주": 1419237, "대전": 1442216, "울산": 1103661, "세종": 386525,
    "경기": 13630821, "강원": 1527807, "충북": 1593469, "충남": 2130119,
    "전북": 1754757, "전남": 1804217, "경북": 2554324, "경남": 3251158,
    "제주": 675252,
}


def build(cache):
    def fetch(url, name):
        return download(url, cache / name if cache else None)

    world = json.loads((WORLD / "world.json").read_text(encoding="utf-8"))
    region_names = {r["id"]: r["name"] for r in world["regions"]}
    crosswalk = defaultdict(dict)
    with CODES.open(encoding="utf-8", newline="") as table:
        for row in csv.DictReader(table):
            country, region = row["country"], row["region"]
            if region_names.get(region) != row["region_name"]:
                raise ValueError(f"Unknown or mismatched map region: {region} {row['region_name']}")
            if region in crosswalk[country]:
                raise ValueError(f"Duplicate mapped region: {region}")
            crosswalk[country][region] = row["source_code"].split("+")
    result = {}

    def collect(country, observations, total, date, source, url):
        mapping = crosswalk[country]
        expected = {r["id"] for r in world["regions"] if r["country"] == country and not r.get("territory")}
        codes = [code for parts in mapping.values() for code in parts]
        if (set(mapping) != expected or len(codes) != len(set(codes)) or set(codes) != set(observations)):
            raise ValueError(f"Incomplete or overlapping statistical geography: {country}")
        counts = {region: sum(observations[code] for code in parts) for region, parts in mapping.items()}
        if any(type(n) is not int or n < 0 for n in observations.values()) or sum(counts.values()) != total or total <= 0:
            raise ValueError(f"Regional populations do not reconcile to national total {total}: {country}")
        result[country] = {
            "date": date, "source": source, "url": url, "total": total,
            "counts": dict(sorted(counts.items())),
        }

    # Puerto Rico (FIPS 72) also appears at level 040 but is outside the
    # national resident population benchmark for the 50 states and DC.
    rows = list(csv.DictReader(io.StringIO(fetch(US_CSV, "us-vintage2023.csv").decode("latin-1"))))
    us_states = {r["STATE"]: int(r["POPESTIMATE2023"]) for r in rows if r["SUMLEV"] == "040" and r["STATE"] != "72"}
    us_total, = (int(r["POPESTIMATE2023"]) for r in rows if r["SUMLEV"] == "010")
    collect("USA", us_states, us_total, "2023-07-01", "미국 인구조사국 PEP 2023 (50개 주·D.C.)", US_CSV)

    with ZipFile(io.BytesIO(fetch(CA_ZIP, "statcan-17100009-eng.zip"))) as archive:
        with archive.open("17100009.csv") as table:
            rows = [r for r in csv.DictReader(io.TextIOWrapper(table, encoding="utf-8-sig"))
                    if r["REF_DATE"] == "2024-01-01"]
    if any(r["UOM"] != "Persons" or r["SCALAR_FACTOR"] != "units" for r in rows):
        raise ValueError("Statistics Canada quarterly population must be reported in persons")
    ca = {r["DGUID"]: int(r["VALUE"]) for r in rows}
    ca_total = ca.pop("2021A000011124")
    collect("CAN", ca, ca_total, "2024-01-01", "캐나다 통계청 17-10-0009-01", "https://doi.org/10.25318/1710000901-eng")

    collect("KOR", KOREA, 51325329, "2023-12-31", "행정안전부 주민등록인구 (외국인 제외)", KO_PAGE)

    data = json.loads(fetch(EU_API, "eurostat-demo_r_pjangrp3-2024.json"))
    for dimension, code in {"freq": "A", "sex": "T", "unit": "NR", "age": "TOTAL", "time": "2024"}.items():
        if data["dimension"][dimension]["category"]["index"] != {code: 0}:
            raise ValueError(f"Unexpected Eurostat dimension {dimension}")
    if set(data["id"]) != {"freq", "sex", "unit", "age", "geo", "time"}:
        raise ValueError("Unexpected dimensions in Eurostat regional statistics")
    # With all other dimensions fixed, the JSON-stat observation offset is
    # exactly the geo index. Exclude missing observations for obsolete codes.
    indices = data["dimension"]["geo"]["category"]["index"]
    values = {code: data["value"][str(i)] for code, i in indices.items() if str(i) in data["value"]}
    for country, prefix, code_length in [("DEU", "DE", 3), ("FRA", "FR", 5), ("POL", "PL", 4), ("LTU", "LT", 5), ("TUR", "TR", 5)]:
        observed = {code: n for code, n in values.items() if code.startswith(prefix) and len(code) == code_length}
        collect(country, observed, values[prefix], "2024-01-01", "Eurostat 지역별 인구", EU_PAGE)
    if set(result) != set(crosswalk):
        raise ValueError("Crosswalk has countries without a population source")
    return dict(sorted(result.items()))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path)
    options = parser.parse_args()
    if options.cache_dir:
        options.cache_dir.mkdir(parents=True, exist_ok=True)
    countries = build(options.cache_dir)
    (WORLD / "region_population.json").write_text(json.dumps(countries, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for country, data in countries.items():
        print(f"{country}: {len(data['counts'])} regions, {data['total']:,} people")
