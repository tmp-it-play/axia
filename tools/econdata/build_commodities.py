"""2023년 World Bank 곡물 생산, FAO 곡물 국내 공급과 EIA 원유 생산·석유 소비로 commodities.json을 생성한다.

    python3 tools/econdata/build_commodities.py [--cache-dir <원본 응답 저장 폴더>]

생산량 결측은 null로 남긴다. 석유 소비량 결측은 관측국 합계에서 구한 GDP당 소비량으로
추정한 뒤, 전체 게임 국가 합이 1인 원유 수요 배분 가중치를 만든다.
"""

import argparse
import csv
import io
import json
import math
import zipfile
from pathlib import Path
from urllib.parse import urlencode

from build_economy import ROOT, SOURCE_CODES, WORLD, download
from un_m49 import UN_M49, game_codes_by_m49

BASE_YEAR = 2023
WORLD_DIR = ROOT / "simulation/src/main/resources/world"
EIA = "https://api.eia.gov/v2/international/data/"
WDI = "https://api.worldbank.org/v2/country/all/indicator/AG.PRD.CREL.MT"
DAYS = 365  # 2023년 일평균 천 배럴을 연간 배럴로 환산한다.

# WDI 결측 두 곳은 2023년 공식 통계 표의 값을 보존해 재생성할 때마다 지역 전체 원본을 받지 않는다.
# FAOSTAT QCL, area 214 / item 1717 / element 5510 / Y2023. WDI CHN은 FAO area 41(중국 본토)이다.
# 코소보 통계청 농가 조사 aeb02, 밀·옥수수·보리·호밀·귀리·기타 곡물의 생산량(톤) 합계.
SUPPLEMENTAL_CEREALS = {"TWN": 1_692_109.09, "KOS": 477_149.06}
FAO_ASIA = "https://bulks-faostat.fao.org/production/Production_Crops_Livestock_E_Asia.zip"
FOOD_BALANCES = "https://bulks-faostat.fao.org/production/FoodBalanceSheets_E_All_Data.zip"
FBS_CATALOG_2023 = "https://data.apps.fao.org/catalog/en/dataset/2f264bb6-1238-459a-bf8b-0e2d0a16804a"
FBS_QUERY_2023 = ("https://data.apps.fao.org/catalog/dataset/5c00a4e6-0ec8-4191-a0c0-a7cd5fda3674/"
                  "resource/91b9d43c-55c4-4a2a-9b25-78f2fabba28b/download/fct-fbs-food-balances.query.sql")
FBS_ARCHIVE = "https://api.data.apps.fao.org/api/v2/bigquery?" + urlencode({"sql_url": FBS_QUERY_2023, "item_code": 2905})
KOSOVO_CROPS = "https://askdata.rks-gov.net/pxweb/en/ASKdata/ASKdata__Agriculture__Agricultural%20Household%20Survey/aeb02.px/"


def fetch(url, cache_dir, filename):
    return json.loads(download(url, cache_dir / filename if cache_dir else None))


def prior_cereal_supply(candidates, iso_by_m49, cache_dir):
    """FAO 지리공간 플랫폼에서 계속 공개하는 2010~2023판의 곡물 국내 공급 추계."""
    if not candidates:
        return {}
    raw = download(FBS_ARCHIVE, cache_dir / "fbs-geoplatform-2905-2010-2023.csv" if cache_dir else None)
    rows = list(csv.DictReader(io.StringIO(raw.decode("utf-8-sig"))))
    if max(int(row["year"]) for row in rows) != 2023:
        raise ValueError("FAO 지리공간 플랫폼의 수급표 판본이 바뀌었습니다. 구판 대용치를 다시 확인하세요.")
    archived = {}
    field = "domestic_supply_quantity_1000_tonnes"
    for row in rows:
        country = iso_by_m49.get(row["m49_code"].zfill(3))
        if country not in candidates or row["year"] != str(BASE_YEAR) or not row[field]:
            continue
        if row[field + "_flag"] != "Estimated value":
            continue
        tonnes = float(row[field]) * 1000
        if (row["item_code"] != "2905" or row["item"] != "Cereals - Excluding Beer"
                or country in archived or not math.isfinite(tonnes) or tonnes < 0):
            raise ValueError(f"FAO 지리공간 플랫폼의 곡물 국내 공급 오류: {country}")
        archived[country] = tonnes
    return archived


def cereal_domestic_supply(states, cache_dir):
    """FBS 품목 2905(맥주 제외 곡물)·요소 5301(국내 공급)을 게임 국가별 연간 톤으로 읽는다."""
    # FAO의 156/158은 본토/대만이다. 중국 전체(159)와 별도 속령은 합산하지 않는다.
    iso_by_m49 = game_codes_by_m49(cache_dir)
    archive = download(FOOD_BALANCES, cache_dir / "FoodBalanceSheets_E_All_Data.zip" if cache_dir else None)
    supplies = {}
    # 현행 수급표에서 국가 행이 없거나 미수집(L)일 때만 FAO에 공개된 이전 판본을 확인한다.
    archive_candidates = {"NIC", "SYC"} & states
    with zipfile.ZipFile(io.BytesIO(archive)) as data, data.open("FoodBalanceSheets_E_All_Data.csv") as file:
        rows = csv.DictReader(io.TextIOWrapper(file, encoding="utf-8-sig", newline=""))
        last_year = max(int(key[1:]) for key in rows.fieldnames if key.startswith("Y") and key[1:].isdigit())
        for row in rows:
            if row["Item Code"] != "2905" or row["Element Code"] != "5301":
                continue
            country = iso_by_m49.get(row["Area Code (M49)"].lstrip("'"))
            raw = row[f"Y{BASE_YEAR}"]
            flag = row[f"Y{BASE_YEAR}F"]
            if raw or flag != "L":
                archive_candidates.discard(country)
            if country not in states or not raw or flag not in {"A", "E", "I", "X"}:
                continue
            if row["Unit"] != "1000 t" or row["Item"] != "Cereals - Excluding Beer" or row["Element"] != "Domestic supply quantity":
                raise ValueError(f"FBS 곡물 국내 공급 단위·계열 오류: {country}")
            tonnes = float(raw) * 1000
            if country in supplies or not math.isfinite(tonnes) or tonnes < 0:
                raise ValueError(f"FBS 곡물 국내 공급량 중복 또는 오류: {country} {raw}")
            supplies[country] = tonnes
    if not {"CHN", "TWN", "IND", "USA"}.issubset(supplies):
        raise ValueError("FBS 곡물 국내 공급 응답 또는 국가 코드 매핑이 불완전합니다.")
    prior = prior_cereal_supply(archive_candidates, iso_by_m49, cache_dir)
    return supplies, last_year, prior


def eia_series(product, activity, cache_dir):
    query = urlencode({
        "api_key": "DEMO_KEY", "frequency": "annual", "data[]": "value",
        "facets[productId][]": product, "facets[activityId][]": activity, "facets[unit][]": "TBPD",
        "start": BASE_YEAR, "end": BASE_YEAR, "length": 1000,
    })
    response = fetch(EIA + "?" + query, cache_dir, f"eia-{product}-{activity}-{BASE_YEAR}.json")["response"]
    rows = response["data"]
    if not rows or len(rows) != int(response["total"]):
        raise ValueError("EIA 응답이 비어 있거나 여러 페이지로 나뉘었습니다.")
    values = {}
    for row in rows:
        country = row["countryRegionId"]
        if row["countryRegionTypeId"] != "c" and country != "WORL":
            continue
        if (row["period"], row["unit"], row["productId"], row["activityId"]) != (str(BASE_YEAR), "TBPD", product, activity):
            raise ValueError(f"EIA 요청과 다른 연도·단위·계열: {row}")
        # 기간 중 비존재(3), 다른 지역에 포함(4), 비공개(6) 등은 숫자 0과 다르다.
        # 플래그 5는 표에서만 반올림된 소량이므로 API의 실제 수치를 보존한다.
        if row["dataFlagId"] not in (None, "5"):
            continue
        value = float(row["value"])
        if country in values or not math.isfinite(value) or value < 0:
            raise ValueError(f"EIA 중복 국가 또는 잘못된 물량: {row}")
        values[country] = value
    return values


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)

    metadata, rows = fetch(
        WDI + f"?date={BASE_YEAR}&format=json&per_page=400",
        args.cache_dir, f"AG.PRD.CREL.MT-{BASE_YEAR}.json",
    )
    if metadata["pages"] != 1 or len(rows) != metadata["total"]:
        raise ValueError("WDI 곡물 생산 응답이 불완전합니다.")
    grain = {r["countryiso3code"]: r["value"] for r in rows if r["value"] is not None}
    crude = eia_series("57", "1", args.cache_dir)  # Crude oil including lease condensate, production
    oil_use = eia_series("5", "2", args.cache_dir)  # Petroleum and other liquids, consumption
    economy = json.loads((WORLD_DIR / "economy.json").read_text(encoding="utf-8"))
    if economy["baseYear"] != BASE_YEAR:
        raise ValueError("GDP와 상품 기준 연도가 다릅니다.")
    gdp = {r["id"]: r["gdp"] for r in economy["countries"]}
    states = [r["id"] for r in json.loads(WORLD.read_text(encoding="utf-8"))["countries"] if r["kind"] == "STATE"]
    domestic_supply, fbs_end_year, prior_supply = cereal_domestic_supply(set(states), args.cache_dir)

    entries, observed_use = [], {}
    for country in states:
        codes = SOURCE_CODES.get(country, [country])
        eia_codes = ["XKS"] if country == "KOS" else codes
        cereal = next((grain[c] for c in codes if c in grain), SUPPLEMENTAL_CEREALS.get(country))
        oil = next((crude[c] for c in eia_codes if c in crude), None)
        use = next((oil_use[c] for c in eia_codes if c in oil_use), None)
        if use is not None:
            observed_use[country] = use
        entries.append({
            "id": country,
            "cerealTonnes": cereal,
            "cerealDomesticSupplyTonnes": domestic_supply.get(country, prior_supply.get(country)),
            **({"cerealSupplyPriorEditionEndYear": 2023} if country in prior_supply else {}),
            "crudeBarrels": round(oil * 1000 * DAYS, 2) if oil is not None else None,
        })

    use_per_dollar = sum(observed_use.values()) / sum(gdp[c] for c in observed_use)
    weights = {c: observed_use.get(c, gdp[c] * use_per_dollar) for c in states}
    total_use = sum(weights.values())
    for entry in entries:
        entry["oilDemandWeight"] = round(weights[entry["id"]] / total_use, 14)
    if not math.isclose(sum(e["oilDemandWeight"] for e in entries), 1, abs_tol=1e-11):
        raise ValueError("원유 수요 가중치 합계가 1에서 벗어납니다.")

    estimated_use = ", ".join(sorted(set(states) - observed_use.keys()))
    note = (
        f"{BASE_YEAR}년. 곡물은 World Bank WDI AG.PRD.CREL.MT(FAO)의 연간 수확량이며 세계 집계는 {grain['WLD']:,.2f}톤이다. "
        "대만은 FAOSTAT QCL의 Cereals, primary(국가 214, 품목 1717), 코소보는 통계청 농가 조사 aeb02의 곡물 6종 합계로 보완한다. "
        f"{FAO_ASIA} {KOSOVO_CROPS} "
        f"cerealDomesticSupplyTonnes는 FAOSTAT Food Balances(2010~{fbs_end_year})의 {BASE_YEAR}년 Cereals - Excluding Beer(2905), "
        "Domestic supply quantity(5301)를 천 톤에서 톤으로 환산한 연간 공급 추계다. 식용·사료·종자·가공·손실 등 전체 국내 이용을 포함한다. "
        f"구판 사용국({', '.join(sorted(prior_supply)) or '없음'})은 FAO 지리공간 플랫폼이 공개한 2010~2023판의 같은 계열·연도 추계다. "
        f"{FBS_CATALOG_2023} 이 값으로 게임 내 곡물 총생산을 국가별 수요에 배분하며, "
        f"null에는 수록국의 인구 가중 1인당 공급량을 적용한다. {FOOD_BALANCES} {UN_M49} "
        f"원유는 미국 EIA 국제에너지통계의 원유·리스 콘덴세이트 생산(57/1, TBPD)에 1,000 × {DAYS}를 곱한 연간 배럴이다. "
        f"EIA 세계 일평균은 {crude['WORL']:,.3f}천 배럴이다. 국별 생산량 0은 출처에 실린 값, null은 해당 국가의 자료가 없다는 뜻이다. "
        "oilDemandWeight는 EIA 석유·기타 액체연료 소비(5/2, TBPD)를 대용치로 삼아 원유 수요를 배분하는 가중치다. "
        f"소비 결측 국가({estimated_use})에는 관측국의 합산 소비/GDP를 적용했다. 합계 1의 분모는 게임에 등장하는 국가들의 소비량이다. "
        "https://data.worldbank.org/indicator/AG.PRD.CREL.MT https://www.eia.gov/opendata/browser/international"
    )
    output = {"baseYear": BASE_YEAR, "sourceNote": note, "countries": entries}
    (WORLD_DIR / "commodities.json").write_text(json.dumps(output, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for field in ("cerealTonnes", "crudeBarrels"):
        print(field, "관측", sum(e[field] is not None for e in entries), "/", len(entries))
    print("FAO 곡물 국내 공급", "현행", len(domestic_supply), "이전 판본", len(prior_supply),
          "미수록", ", ".join(sorted(set(states) - domestic_supply.keys() - prior_supply.keys())))
    print("석유 소비 추정", estimated_use)


if __name__ == "__main__":
    main()
