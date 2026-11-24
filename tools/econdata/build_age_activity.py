"""플레이 국가의 연령별 경제활동참가율과 유럽 노령연금 수급 자료를 생성한다.

python3 tools/econdata/build_age_activity.py [--cache-dir 원본_디렉터리]
ILO 기준은 2023년이다. 우크라이나는 전면 침공 전 마지막 2021년 노동력조사를 쓴다.
Eurostat 연금 모듈 모집단은 50~74세이므로 15~64세 분모는 전체 EU-LFS에서 별도로 구한다.
"""

import argparse
import csv
import io
import itertools
import json
from pathlib import Path

from build_economy import ROOT, download

WORLD_DIR = ROOT / "simulation/src/main/resources/world"
ILO = "https://rplumber.ilo.org/data/indicator/"
EU = "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/"
AGE_BANDS = {"AGE_YTHADULT_Y15-64": "workingAgeParticipation", "AGE_AGGREGATE_YGE65": "olderParticipation"}
EU_COUNTRIES = {"DEU": "DE", "EST": "EE", "FIN": "FI", "FRA": "FR", "ITA": "IT", "LTU": "LT", "LVA": "LV", "POL": "PL"}


def ilo_rates(cache, series, year, country=None):
    values = {}
    for band, field in AGE_BANDS.items():
        query = f"?id={series}_A&time={year}&sex=SEX_T&classif1={band}"
        if country:
            query += f"&ref_area={country}"
        name = f"{series}-{year}-{band}.csv"
        raw = download(ILO + query, cache / name if cache else None)
        for row in csv.DictReader(io.StringIO(raw.decode("utf-8-sig"))):
            if row["time"] != str(year) or row["sex"] != "SEX_T" or row["classif1"] != band or row["indicator"] != series:
                raise ValueError(f"Unexpected observation: {row}")
            value = float(row["obs_value"]) / 100
            report = values.setdefault((row["ref_area"], row["source"]), {})
            if not 0 <= value <= 1 or field in report:
                raise ValueError(f"Invalid or duplicate rate: {row}")
            report[field] = round(value, 6)
    pairs = {}
    for (code, source), rates in values.items():
        if set(rates) != set(AGE_BANDS.values()):
            continue
        if code in pairs:
            raise ValueError(f"Multiple paired sources for {code} in {series}")
        pairs[code] = {**rates, "year": year, "source": source, "series": series}
    return pairs


def eurostat(cache, dataset, query):
    name = dataset + "-2023-age-activity.json"
    raw = download(f"{EU}{dataset}?sex=T&time=2023&unit=THS_PER&{query}", cache / name if cache else None)
    table = json.loads(raw)
    dimensions = [table["dimension"][axis]["category"]["index"] for axis in table["id"]]
    rows = {}
    for i, keys in enumerate(itertools.product(*(sorted(d, key=d.get) for d in dimensions))):
        if str(i) not in table["value"]:
            continue
        row = dict(zip(table["id"], keys))
        # 조기 연금 모듈은 연금 종류별, 전체 인구 테이블은 국가별 한 행이다.
        key = (row["geo"], row["wstatus"], row.get("spdepb"))
        if key in rows:
            raise ValueError(f"Unexpected duplicate in {dataset}: {row}")
        rows[key] = table["value"][str(i)]
    return rows


def early_pensions(cache):
    pensions = eurostat(cache, "lfso_23pens01", "age=Y50-64")
    population = eurostat(cache, "lfsa_pganws", "age=Y15-64&citizen=TOTAL&wstatus=POP")
    results = {}
    for state, geo in EU_COUNTRIES.items():
        age_group = pensions[geo, "POP", "TOTAL"]
        working_age = population[geo, "POP", None]
        # ANY에는 노령과 장애 중복 수급자가 한 번 들어간다. 장애만 받는 사람을 제외한다.
        recipients = pensions[geo, "POP", "ANY"] - pensions[geo, "POP", "DIS_P_DIS_OTH_ONL"]
        not_employed = pensions[geo, "NEMP", "ANY"] - pensions[geo, "NEMP", "DIS_P_DIS_OTH_ONL"]
        if not 0 <= not_employed <= recipients <= age_group <= working_age:
            raise ValueError(f"Inconsistent 50–64 pension aggregates for {geo}")
        results[state] = {
            "recipientsOfWorkingAge": round(recipients / working_age, 7),
            "nonEmployedOfWorkingAge": round(not_employed / working_age, 7),
            "nonEmployedOfAgeBand": round(not_employed / age_group, 7),
        }
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    cache = parser.parse_args().cache_dir
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
    reported = ilo_rates(cache, "EAP_DWAP_SEX_AGE_RT", 2023)
    # 대만 행정원 주계총처 2023 인력자원조사 연보 표 4·6(천 명), 표 8(공표율).
    # https://ws.dgbas.gov.tw/001/Upload/463/ebook/ebook_279843/pdf/full.pdf
    # 같은 조사의 15세 이상과 65세 이상 인구·경제활동인구 차이로 15~64세 참가율을 재구성한다.
    reported["TWN"] = {
        "workingAgeParticipation": round((11943 - 412) / (20168 - 4159), 6),
        "olderParticipation": 0.0991,
        "year": 2023,
        "source": "DGBAS 2023 Manpower Survey Tables 4, 6, 8",
        "series": "TWN_MANPOWER_SURVEY",
    }
    modeled = ilo_rates(cache, "EAP_2WAP_SEX_AGE_RT", 2023)
    older = ilo_rates(cache, "EAP_DWAP_SEX_AGE_RT", 2021, "UKR")
    pensions = early_pensions(cache)
    countries = {}
    for state in sorted(json.loads((WORLD_DIR / "playable.json").read_text())["countries"]):
        report = reported.get(state) or modeled.get(state) or older.get(state)
        if report is None:
            raise ValueError(f"No paired age-specific participation rates for {state}")
        countries[state] = report
        if state in pensions:
            report["earlyPension"] = pensions[state]
    outfile = WORLD_DIR / "age_activity.json"
    outfile.write_text(json.dumps(countries, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
