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
from xml.etree import ElementTree as ET
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
JP_TABLE = "https://www.e-stat.go.jp/stat-search/file-download?statInfId=000040306668&fileKind=0"
BR_TABLE = "https://ftp.ibge.gov.br/Projecao_da_Populacao/Projecao_da_Populacao_2024/projecoes_2024_tab4_indicadores.xlsx"
VN_TABLE = "https://www.nso.gov.vn/wp-content/uploads/2025/06/NGTK-Cuc-TK-2024_BQ_PDF.pdf"

# Fixed PDF extracts in the source tables' own province names. Thailand:
# December 2566, rightmost total column (Thai and non-Thai registrants).
# Egypt: Egypt in Figures 2024, printed p. 4, both sexes, 1 January.
PDF_TOTALS = {
    "EGY": (105914499, "2024-01-01", "이집트 CAPMAS 주별 인구추계 (Egypt in Figures 2024, 4쪽)",
            "https://mediadr.sis.gov.eg/bitstream/handle/123456789/58352/CAPMAS20240300_005.pdf?isAllowed=y&sequence=1"),
    # BPS rounds to 100 people. The province sum is 100 below the printed Indonesia total.
    "IDN": (278696100, "2023-06", "인도네시아 BPS 주별 연중 인구추계 (반텐 통계연감 2025, 표 14.1)",
            "https://banten.bps.go.id/en/publication/2025/02/28/3308511080de4eb42086c9a8/banten-province-in-figures-2025.html"),
    "THA": (66052615, "2023-12-31", "태국 내무부 등록행정국 주별 등록인구 (외국 국적 포함)",
            "https://www.bora.dopa.go.th/wp-content/uploads/2024/01/pop_province_6612.pdf"),
}

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


def population_table(data):
    """Read columns A–E of the first sheet of the two published XLSX tables."""
    ns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    with ZipFile(io.BytesIO(data)) as archive:
        # Only t and r/t are displayed text; rPh/t is a pronunciation guide.
        strings = [item.findtext(ns + "t") or "".join(t.text or "" for t in item.findall(f"{ns}r/{ns}t"))
                   for item in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
        with archive.open("xl/worksheets/sheet1.xml") as sheet:
            for _, row in ET.iterparse(sheet, events=("end",)):
                if row.tag != ns + "row":
                    continue
                values = {}
                for cell in row.findall(ns + "c"):
                    col = cell.attrib["r"].rstrip("0123456789")
                    value = cell.findtext(ns + "v")
                    if col in ("A", "B", "C", "D", "E") and value is not None:
                        values[col] = strings[int(value)] if cell.get("t") == "s" else value
                yield values
                row.clear()


def japanese_register(data):
    rows = list(population_table(data))
    if [r.get("E") for r in rows[2:6]] != ["2024年", "人口", "計", "人"]:
        raise ValueError("Unexpected column for Japan's 2024 total registered residents")
    total, = (int(r["E"]) for r in rows if r.get("B") == "合計")
    counts = {}
    for row in rows:
        code = row.get("A", "")
        if len(code) == 6 and code.isdigit():
            prefecture = code[:2]
            if prefecture in counts or int(row["C"]) + int(row["D"]) != int(row["E"]):
                raise ValueError(f"Invalid population register entry: {code}")
            counts[prefecture] = int(row["E"])
    return counts, total


def brazilian_projection(data):
    rows = population_table(data)
    headers = next((r for r in rows if r.get("A") == "ANO"), {})
    if [headers.get(k) for k in "BCDE"] != ["CÓD.", "SIGLA", "LOCAL", "POP_T"]:
        raise ValueError("Unexpected columns in IBGE's population projection")
    observed = {}
    for row in rows:
        if row.get("A") != "2023":
            continue
        # IBGE code 0 denotes Brazil, 1–5 the five supra-state regions.
        if 1 <= int(row["B"]) <= 5:
            continue
        code = row["C"]
        if code in observed:
            raise ValueError(f"Duplicate IBGE area: {code}")
        observed[code] = int(row["E"])
    total = observed.pop("BR")
    return observed, total


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

    def collect(country, observations, total, date, source, url, urban=None):
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
        if urban is not None:
            if urban.keys() != observations.keys() or any(type(n) is not int or not 0 <= n <= observations[code] for code, n in urban.items()):
                raise ValueError(f"Invalid regional urban population: {country}")
            result[country]["urbanCounts"] = {
                region: sum(urban[code] for code in parts) for region, parts in sorted(mapping.items())
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

    jp, jp_total = japanese_register(fetch(JP_TABLE, "japan-register-2024-01.xlsx"))
    collect("JPN", jp, jp_total, "2024-01-01", "일본 총무성 주민기본대장 (외국인 포함, 표 24-01)", JP_TABLE)

    br, br_total = brazilian_projection(fetch(BR_TABLE, "ibge-projection-2024-indicators.xlsx"))
    collect("BRA", br, br_total, "2023-07-01", "브라질 IBGE 주별 인구추계 (2024년 개정판)", BR_TABLE)

    # 베트남 통계연감 2024, 표 21·24의 2023년 열(단위 0.1천 명). 2025년 행정 통합 전 63개 성·시다.
    vn, urban = {}, {}
    with Path(__file__).with_name("vietnam_population_2023.csv").open(encoding="utf-8", newline="") as file:
        for row in csv.DictReader(file):
            province = row["province"]
            if province in vn:
                raise ValueError(f"Duplicate Vietnamese province: {province}")
            vn[province], urban[province] = int(row["population"]), int(row["urban_population"])
    # 지역 공표값의 합이 전국 공표값 100,309,200명보다 200명 작다. 배분 분모는 지역 합계를 쓴다.
    if sum(urban.values()) != 38_219_700:
        raise ValueError("Vietnam's urban counts do not reconcile to the published table")
    collect("VNM", vn, 100_309_000, "2023년 연평균", "베트남 통계청 성·시별 총인구·도시인구", VN_TABLE, urban)

    extracts = defaultdict(dict)
    with Path(__file__).with_name("region_population_pdf_extracts.csv").open(encoding="utf-8", newline="") as file:
        for row in csv.DictReader(file):
            country, label = row["country"], row["source_label"]
            if country not in PDF_TOTALS or label in extracts[country]:
                raise ValueError(f"Unknown report or duplicate row: {country} {label}")
            extracts[country][label] = int(row["population"])
    for country, (total, date, source, url) in PDF_TOTALS.items():
        collect(country, extracts[country], total, date, source, url)

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
