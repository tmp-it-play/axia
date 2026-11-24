"""IMF BPM6 연간 투자소득 수취·지급과 세부 항목을 2024년 1월 시작 자료로 만든다.

python3 tools/econdata/build_investment_income.py [--cache-dir 원본_디렉터리]
연간 명목 미 달러. 총액과 세부 항목의 부호, 보고된 0과 결측을 각각 보존한다.
"""

import argparse
import json
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree

from build_economy import WORLD, download
from build_reserve_assets import COUNTRY_ALIASES
from investment_income_supplements import national_accounts

OUT = WORLD.with_name("investment_income.json")
TOTAL = "_T_F_D4P"
DISTRIBUTED = "D_F5_D42S"
FDI_REINVESTED = "D_F5_D43S"
FDI_EQUITY = "D_F5_D4S"
STOCK_DIVIDENDS = "P_F51_D42"
FUND_DIVIDENDS = "P_F52_D4431"
FUND_REINVESTED = "P_F52_D4432"
FUND_INCOME = "P_F52_D4S"
PORTFOLIO_EQUITY = "P_F5_D4S"
WITHDRAWALS = "O_F5_D422"
INTEREST = ("P_F3_D41", "O_D41", "R_F_D41")  # 채권, 기타투자, 준비자산 이자
INDICATORS = (TOTAL, DISTRIBUTED, FDI_REINVESTED, FDI_EQUITY, STOCK_DIVIDENDS, FUND_DIVIDENDS,
              FUND_REINVESTED, FUND_INCOME, PORTFOLIO_EQUITY, WITHDRAWALS, *INTEREST)
DIRECTIONS = {"CD_T": "received", "DB_T": "paid"}


def imf_reports(cache):
    # SDMX의 SCALE은 표시 배율이다. OBS_VALUE는 이미 달러 단위다.
    key = ".CD_T+DB_T." + "+".join(INDICATORS) + ".USD.A"
    url = f"https://api.imf.org/external/sdmx/2.1/data/IMF.STA,BOP/{key}?startPeriod=2019&endPeriod=2023"
    raw = download(url, cache / "imf-bop-investment-income-2019-2023.xml" if cache else None)
    reports = {}
    for series in ElementTree.fromstring(raw).findall(".//{*}Series"):
        entry = series.attrib
        indicator = entry["INDICATOR"]
        if (entry["UNIT"] != "USD" or entry["FREQUENCY"] != "A" or entry["METHODOLOGY"] != "BPM6"
                or indicator not in INDICATORS):
            raise ValueError(f"국제수지 보고 범위·지표 불일치: {entry}")
        direction = DIRECTIONS[entry["BOP_ACCOUNTING_ENTRY"]]
        for obs in series.findall("{*}Obs"):
            if obs.get("DERIVATION_TYPE") != "O" or obs.get("OBS_VALUE") is None:
                continue
            year = int(obs.attrib["TIME_PERIOD"])
            report = reports.setdefault((entry["COUNTRY"], direction), {}).setdefault(year, {})
            value = Decimal(obs.attrib["OBS_VALUE"])
            if not value.is_finite() or not 2019 <= year <= 2023 or indicator in report:
                raise ValueError(f"국제수지 중복 또는 관측값 오류: {entry}, {obs.attrib}")
            report[indicator] = value
    if not reports:
        raise ValueError("IMF 투자소득 보고가 없습니다.")
    return reports


def component(part, parent, sibling):
    """항목이 미보고이면 같은 방향·연도의 상위 합계와 나머지 한 항목에서 복원한다."""
    if part is not None:
        return part
    if parent is not None and sibling is not None:
        return parent - sibling
    return None


def identified_sum(*values):
    reported = [v for v in values if v is not None]
    return round(sum(reported)) if reported else None


def observation(report, year):
    get = report.get
    direct_dividends = component(get(DISTRIBUTED), get(FDI_EQUITY), get(FDI_REINVESTED))
    direct_reinvested = component(get(FDI_REINVESTED), get(FDI_EQUITY), get(DISTRIBUTED))
    # 포트폴리오 지분소득을 주식 배당, 펀드 분배금, 펀드 재투자로 나눈다.
    stocks = component(get(STOCK_DIVIDENDS), get(PORTFOLIO_EQUITY), get(FUND_INCOME))
    fund_dividends = component(get(FUND_DIVIDENDS), get(FUND_INCOME), get(FUND_REINVESTED))
    fund_reinvested = component(get(FUND_REINVESTED), get(FUND_INCOME), get(FUND_DIVIDENDS))
    if stocks is not None and fund_dividends is not None:
        portfolio_dividends = stocks + fund_dividends
        fund_reinvested = component(fund_reinvested, get(PORTFOLIO_EQUITY), portfolio_dividends)
    elif get(PORTFOLIO_EQUITY) is not None and fund_reinvested is not None:
        portfolio_dividends = get(PORTFOLIO_EQUITY) - fund_reinvested
    else:
        portfolio_dividends = stocks if stocks is not None else fund_dividends
    # 직접투자 채무소득 D_FL_D4Q에는 보험계약자 귀속소득도 들어간다. FISIM 차감 전 이자(D41G)는 메모 항목이다.
    # 순수 이자로 분리할 수 없는 금액은 전체 투자소득 안에 보존한다.
    return {
        "year": year, "source": "IMF_BOP", "total": round(report[TOTAL]),
        "dividends": identified_sum(direct_dividends, portfolio_dividends, get(WITHDRAWALS)),
        "interest": identified_sum(*(get(code) for code in INTEREST)),
        "reinvested": identified_sum(direct_reinvested, fund_reinvested),
    }


def build(states, imf, national):
    if not national.keys() <= set(states):
        raise ValueError(f"알 수 없는 보완 자료 국가 코드: {national.keys() - set(states)}")
    countries = {}
    for country in states:
        entries = {}
        for direction in DIRECTIONS.values():
            reports = imf.get((COUNTRY_ALIASES.get(country, country), direction), {})
            year = max((y for y, r in reports.items() if TOTAL in r), default=None)
            local = national.get(country, {}).get(direction)
            if local and (year is None or local["year"] > year):
                entries[direction] = local
            else:
                entries[direction] = observation(reports[year], year) if year is not None else None
        countries[country] = entries
    return {"unit": "annual_USD", "countries": countries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path)
    cache = parser.parse_args().cache_dir
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
    world = json.loads(WORLD.read_text(encoding="utf-8"))
    states = sorted(c["id"] for c in world["countries"] if c["kind"] == "STATE")
    bundle = build(states, imf_reports(cache), national_accounts())
    OUT.write_text(json.dumps(bundle, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
