"""2023년 World Bank 곡물 생산과 EIA 원유 생산·석유 소비 통계로 commodities.json을 생성한다.

    python3 tools/econdata/build_commodities.py [--cache-dir <원본 응답 저장 폴더>]

생산량 결측은 null로 남긴다. 석유 소비량 결측은 관측국 합계에서 구한 GDP당 소비량으로
추정한 뒤, 전체 게임 국가 합이 1인 원유 수요 배분 가중치를 만든다.
"""

import argparse
import json
import math
from pathlib import Path
from urllib.parse import urlencode

from build_economy import ROOT, SOURCE_CODES, WORLD, download

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
KOSOVO_CROPS = "https://askdata.rks-gov.net/pxweb/en/ASKdata/ASKdata__Agriculture__Agricultural%20Household%20Survey/aeb02.px/"


def fetch(url, cache_dir, filename):
    return json.loads(download(url, cache_dir / filename if cache_dir else None))


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
    print("석유 소비 추정", estimated_use)


if __name__ == "__main__":
    main()
