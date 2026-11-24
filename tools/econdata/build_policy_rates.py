"""2023년 12월 말 BIS 관측치로 central_banks.json의 보완 금리 표를 재생성한다.

python3 tools/econdata/build_policy_rates.py [--cache-dir DIR]
국가별 결정문에서 선택한 정책수단과 통화동맹의 공동 금리는 central_banks.json에서 관리한다.
이 생성기는 그 밖의 국가에 BIS의 마지막 영업일 관측치를 보완한다.
"""
import argparse
import csv
import io
import json
import math
from decimal import Decimal
from pathlib import Path

from build_economy import ROOT, download

PERIOD = "2023-12"
SOURCE = "https://data.bis.org/topics/CBPOL"
API = f"https://stats.bis.org/api/v2/data/dataflow/BIS/WS_CBPOL/1.0/M.?startPeriod={PERIOD}&endPeriod={PERIOD}&format=csv"
WORLD_DIR = ROOT / "simulation/src/main/resources/world"

# BIS REF_AREA(ISO 2자리)와 게임 국가 ID. 홍콩은 지도상 독립 경제가 아니고 XM은 유로권 합계다.
AREAS = {
    "AR": "ARG", "AU": "AUS", "BR": "BRA", "CA": "CAN", "CH": "CHE", "CL": "CHL",
    "CN": "CHN", "CO": "COL", "CZ": "CZE", "DK": "DNK", "GB": "GBR", "HU": "HUN",
    "ID": "IDN", "IL": "ISR", "IN": "IND", "IS": "ISL", "JP": "JPN", "KR": "KOR",
    "KW": "KWT", "MA": "MAR", "MK": "MKD", "MX": "MEX", "MY": "MYS", "NO": "NOR",
    "NZ": "NZL", "PE": "PER", "PH": "PHL", "PL": "POL", "RO": "ROU", "RS": "SRB",
    "RU": "RUS", "SA": "SAU", "SE": "SWE", "TH": "THA", "TR": "TUR", "US": "USA",
    "ZA": "ZAF",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)
    raw = download(API, args.cache_dir / f"bis-cbpol-{PERIOD}.csv" if args.cache_dir else None)
    path = WORLD_DIR / "central_banks.json"
    banks = json.loads(path.read_text(encoding="utf-8"))
    published = banks["nationalPolicyRates"]
    if published["period"] != PERIOD:
        raise ValueError("National and BIS policy rates must describe the same month")
    covered = set(banks["banks"]) | set(published["rates"]) | {member for union in banks["unions"] for member in union["members"]}
    rates = {}
    for row in csv.DictReader(io.StringIO(raw.decode("utf-8-sig"))):
        area = row["REF_AREA"]
        if (row["FREQ"], row["TIME_PERIOD"], row["UNIT_MEASURE"], row["UNIT_MULT"]) != ("M", PERIOD, "368", "0"):
            raise ValueError(f"Unexpected BIS period, frequency or unit for {area}")
        if area in {"HK", "XM"} or not row["OBS_VALUE"] or row["OBS_STATUS"] != "A":
            continue
        country = AREAS[area]
        if country in covered:
            continue
        rate = float(Decimal(row["OBS_VALUE"]) / 100)
        if not math.isfinite(rate) or country in rates:
            raise ValueError(f"Invalid or duplicate rate for {country}")
        rates[country] = rate
    if not rates:
        raise ValueError("No BIS policy-rate observations")
    banks["bisPolicyRates"] = {"period": PERIOD, "source": SOURCE, "rates": dict(sorted(rates.items()))}
    path.write_text(json.dumps(banks, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(rates)} supplementary BIS policy rates for {PERIOD}")


if __name__ == "__main__":
    main()
