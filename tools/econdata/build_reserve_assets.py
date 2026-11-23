"""2024-01-01 시작 준비자산(금 제외, USD). IMF 국제유동성 2023년 12월, 이어서 국제투자대조표 기말 관측을 쓴다.

python3 tools/econdata/build_reserve_assets.py [--cache-dir 원본_디렉터리]
IMF 기말 자료가 없으면 WDI의 2023년 연간 준비자산을 대용치로 표시한다.
IMF SDMX OBS_VALUE는 기본 단위의 금액이다. SCALE은 표시 배율이므로 다시 곱하지 않는다.
국제투자대조표는 금을 명시적으로 차감하거나 금 외의 세 상위 항목이 모두 보고된 경우만 합산한다.
"""

import argparse
import csv
import io
import json
import math
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from build_economy import ROOT, WORLD, download

OUT = ROOT / "simulation/src/main/resources/world/reserve_assets.json"
BASE = "https://api.imf.org/external/sdmx/2.1/data/IMF.STA,"
# IMF의 서안·가자지구(WBG), 남수단(SSD). IMF는 코소보를 KOS로 제공한다.
COUNTRY_ALIASES = {"PSX": "WBG", "SDS": "SSD"}
WDI_INDICATOR = "FI.RES.XGLD.CD"
WDI_ALIASES = {"PSX": "PSE", "SDS": "SSD", "KOS": "XKX"}
IL_INDICATOR = "RXF11_REVS"
NON_GOLD = ("R_F12_MV", "R_FK_MV", "R_FR2")  # SDR, IMF 포지션, 기타 준비자산 전체.
IIP_INDICATORS = ("R", "R_F11_MV", *NON_GOLD)
IIP_KEY = "+".join(IIP_INDICATORS)


def observations(cache, dataset, key, frequency, period, api_period, indicators):
    url = f"{BASE}{dataset}/{key}?startPeriod={api_period}&endPeriod={api_period}"
    path = cache / f"{dataset}-{frequency}-{api_period}.xml" if cache else None
    root = ET.fromstring(download(url, path))
    values = {}
    for series in root.findall(".//{*}Series"):
        indicator = series.attrib["INDICATOR"]
        if (indicator not in indicators or series.attrib["UNIT"] != "USD"
                or series.attrib["FREQUENCY"] != frequency
                or (dataset == "IIP" and series.attrib["BOP_ACCOUNTING_ENTRY"] != "A_P")):
            raise ValueError(f"Unexpected series in {url}: {series.attrib}")
        for obs in series.findall("{*}Obs"):
            if obs.attrib["TIME_PERIOD"] != period:
                raise ValueError(f"Unexpected period in {url}: {obs.attrib}")
            if obs.get("DERIVATION_TYPE") != "O" or obs.get("OBS_VALUE") is None:
                continue
            # USD 센트까지 보존한다. 일부 금 잔액은 계산 오차로 -3.5e-12 USD로 보고된다.
            value = round(float(obs.attrib["OBS_VALUE"]), 2)
            if not math.isfinite(value) or value < 0:
                raise ValueError(f"Invalid reserve stock: {series.attrib}, {obs.attrib}")
            report = values.setdefault(series.attrib["COUNTRY"], {})
            if indicator in report:
                raise ValueError(f"Duplicate {indicator} for {series.attrib['COUNTRY']}")
            report[indicator] = value if value != 0.0 else 0.0
    if not values:
        raise ValueError(f"Empty observation set in {url}")
    return values


def wdi_reserves(cache):
    url = f"https://api.worldbank.org/v2/en/indicator/{WDI_INDICATOR}?downloadformat=csv"
    archive = download(url, cache / f"{WDI_INDICATOR}.zip" if cache else None)
    with zipfile.ZipFile(io.BytesIO(archive)) as files:
        name, = (n for n in files.namelist() if n.startswith(f"API_{WDI_INDICATOR}_DS2_") and n.endswith(".csv"))
        rows = csv.reader(files.read(name).decode("utf-8-sig").splitlines())
        header = next(row for row in rows if row and row[0] == "Country Name")
        country, indicator, year = (header.index(key) for key in ("Country Code", "Indicator Code", "2023"))
        amounts = {}
        for row in rows:
            if not row or not row[year]:
                continue
            amount = float(row[year])
            if row[indicator] != WDI_INDICATOR or not math.isfinite(amount) or amount < 0:
                raise ValueError(f"Unexpected WDI observation for {row[country]}")
            amounts[row[country]] = round(amount, 2)
        return amounts


def reserve_observation(state, liquidity, quarterly, annual, wdi):
    country = COUNTRY_ALIASES.get(state, state)
    if country in liquidity:
        return {"usd": liquidity[country][IL_INDICATOR], "source": "IL_DECEMBER"}
    for period, records in (("Q4", quarterly), ("YEAR", annual)):
        report = records.get(country, {})
        if "R" in report and "R_F11_MV" in report:
            amount = report["R"] - report["R_F11_MV"]
            if amount < 0:
                raise ValueError(f"{country}: monetary gold exceeds total reserves")
            return {"usd": round(amount, 2), "source": f"IIP_{period}_MINUS_GOLD"}
        if all(component in report for component in NON_GOLD):
            return {"usd": round(sum(report[key] for key in NON_GOLD), 2), "source": f"IIP_{period}_COMPONENTS"}
    code = WDI_ALIASES.get(state, state)
    if code in wdi:
        return {"usd": wdi[code], "source": "WDI_YEAR_PROXY"}
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    cache = args.cache_dir
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
    liquidity = observations(cache, "IL", f".{IL_INDICATOR}.USD.M", "M", "2023-M12", "2023-12", [IL_INDICATOR])
    quarterly = observations(cache, "IIP", f".A_P.{IIP_KEY}.USD.Q", "Q", "2023-Q4", "2023-Q4", IIP_INDICATORS)
    annual = observations(cache, "IIP", f".A_P.{IIP_KEY}.USD.A", "A", "2023", "2023", IIP_INDICATORS)
    wdi = wdi_reserves(cache)
    # 동일 통화권의 국가별 보고분을 사용한다. ECB 등 통화권 합계·지역·세계 합계는 제외한다.
    world = json.loads(WORLD.read_text(encoding="utf-8"))
    states = sorted(country["id"] for country in world["countries"] if country["kind"] == "STATE")
    amounts = {code: reserve_observation(code, liquidity, quarterly, annual, wdi) for code in states}
    OUT.write_text(json.dumps({"year": 2023, "unit": "USD", "countries": amounts}, indent=2) + "\n", encoding="utf-8")
    count = sum(value is not None for value in amounts.values())
    print(f"{OUT.name}: {count}/{len(states)} observations, {len(states) - count} missing")


if __name__ == "__main__":
    main()
