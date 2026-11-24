"""IMF BPM6 연간 투자소득 수취·지급과 세부 항목을 2024년 1월 시작 자료로 만든다.

python3 tools/econdata/build_investment_income.py [--cache-dir 원본_디렉터리]
연간 명목 미 달러. 총액과 세부 항목의 부호, 보고된 0과 결측을 각각 보존한다.
"""

import argparse
import json
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlencode
from xml.etree import ElementTree

from build_economy import WORLD, download
from build_reserve_assets import COUNTRY_ALIASES
from investment_income_supplements import national_accounts, national_net_income
from remittance_supplements import USD_PER_EUR_2023

OUT = WORLD.with_name("investment_income.json")
FIRST_YEAR = 2014
HISTORICAL_FIRST_YEAR = 1994
LAST_YEAR = 2023
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

# Eurostat bop_c6_a 수취·지급표. 직접투자 채무소득 합계(D4Q)에서 순수 이자(D41)를 분리한 원표다.
# https://ec.europa.eu/eurostat/databrowser/view/bop_c6_a/default/table?lang=en
EUROSTAT_COUNTRIES = {
    "AL": "ALB", "AT": "AUT", "BA": "BIH", "BE": "BEL", "BG": "BGR", "CH": "CHE", "CY": "CYP",
    "CZ": "CZE", "DE": "DEU", "DK": "DNK", "EE": "EST", "EL": "GRC", "ES": "ESP", "FI": "FIN",
    "FR": "FRA", "HR": "HRV", "HU": "HUN", "IE": "IRL", "IS": "ISL", "IT": "ITA", "LT": "LTU",
    "LU": "LUX", "LV": "LVA", "MD": "MDA", "ME": "MNE", "MK": "MKD", "MT": "MLT", "NL": "NLD",
    "NO": "NOR", "PL": "POL", "PT": "PRT", "RO": "ROU", "RS": "SRB", "SE": "SWE", "SI": "SVN",
    "SK": "SVK", "TR": "TUR", "UK": "GBR", "XK": "KOS",
}


def eurostat_table(cache, flow, sector, items, cache_name):
    # WRL_REST는 보고국 외 전 세계, S1은 모든 부문, 금액은 백만 EUR.
    filters = {"freq": "A", "currency": "MIO_EUR", "sector10": sector, "sectpart": "S1", "stk_flow": flow,
               "partner": "WRL_REST", "time": str(LAST_YEAR)}
    query = urlencode({**filters, "bop_item": items}, doseq=True)
    url = f"https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/bop_c6_a?{query}"
    data = json.loads(download(url, cache / f"eurostat-investment-income-{cache_name}-{LAST_YEAR}.json" if cache else None),
                      parse_float=Decimal)
    indexes = {k: d["category"]["index"] for k, d in data["dimension"].items()}
    if any(set(indexes[k]) != {v} for k, v in filters.items()) or [k for k in data["id"] if k not in filters] != ["bop_item", "geo"]:
        raise ValueError("Eurostat 투자소득 단위·보고 범위가 달라졌습니다.")
    result = {}
    for geo, position in indexes["geo"].items():
        if geo not in EUROSTAT_COUNTRIES:
            continue
        offsets = [str(indexes["bop_item"][item] * len(indexes["geo"]) + position) for item in items]
        if all(data["value"].get(o) is not None for o in offsets):
            result[EUROSTAT_COUNTRIES[geo]] = [data["value"][o] for o in offsets]
    return result


def eurostat_accounts(cache):
    items = ("D4P__F", "D4S__D__F5", "D43S__D__F5", "D4S__P__F5", "D4432__P__F52", "D422__O__F5",
             "D41__D__FLA", "D41__O__FLA", "D41__P__F3")
    # 작성지침 1.b.2.4.2의 준비자산 이자는 거주 부문 S121, 상대 부문 S1의 수취로만 보고한다.
    # https://ec.europa.eu/eurostat/documents/39118/18471074/vademecum-balance-payments.pdf
    reserves = eurostat_table(cache, "CRE", "S121", ("D41__R__FLA",), "reserve-interest")
    currency = Decimal(str(USD_PER_EUR_2023)) * 1_000_000
    accounts = {}
    for direction, flow, name in (("paid", "DEB", "debits"), ("received", "CRE", "credits")):
        for country, values in eurostat_table(cache, flow, "S1", items, name).items():
            if direction == "received" and country not in reserves:
                continue
            total, direct, direct_retained, portfolio, fund_retained, withdrawals, direct_interest, other_interest, bond_interest = values
            interest = direct_interest + other_interest + bond_interest
            if direction == "received":
                interest += reserves[country][0]
            # 직접투자·증권투자 지분소득에서 같은 표의 재투자분을 빼면 분배소득이 남는다.
            # 준비자산의 비이자 소득은 배당으로 나눠 공표하지 않으므로 총액에만 남긴다.
            accounts.setdefault(country, {})[direction] = {
                "year": LAST_YEAR, "source": "EUROSTAT_BOP", "total": round(total * currency),
                "dividends": round((direct - direct_retained + portfolio - fund_retained + withdrawals) * currency),
                "interest": round(interest * currency), "reinvested": round((direct_retained + fund_retained) * currency),
            }
    return accounts


def imf_reports(cache, first=FIRST_YEAR, last=LAST_YEAR, countries=()):
    # SDMX의 SCALE은 표시 배율이다. OBS_VALUE는 이미 달러 단위다.
    selected = "+".join(sorted(countries))
    key = selected + ".CD_T+DB_T." + "+".join(INDICATORS) + ".USD.A"
    url = f"https://api.imf.org/external/sdmx/2.1/data/IMF.STA,BOP/{key}?startPeriod={first}&endPeriod={last}"
    suffix = f"-{selected}" if selected else ""
    raw = download(url, cache / f"imf-bop-investment-income-{first}-{last}{suffix}.xml" if cache else None)
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
            if not value.is_finite() or not first <= year <= last or indicator in report:
                raise ValueError(f"국제수지 중복 또는 관측값 오류: {entry}, {obs.attrib}")
            report[indicator] = value
    if not reports and not selected:
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


def build(states, imf, national, net_income):
    unknown = (national.keys() | net_income.keys()) - set(states)
    if unknown:
        raise ValueError(f"알 수 없는 보완 자료 국가 코드: {unknown}")
    countries = {}
    for country in states:
        entries = {}
        for direction in DIRECTIONS.values():
            reports = imf.get((COUNTRY_ALIASES.get(country, country), direction), {})
            year = max((y for y, r in reports.items() if TOTAL in r), default=None)
            local = national.get(country, {}).get(direction)
            # 같은 연도면 직접투자 이자까지 구분하는 두 상세표를 우선한다.
            if local and (year is None or local["year"] > year
                          or local["year"] == year and local["source"] in ("EUROSTAT_BOP", "US_BEA")):
                entries[direction] = local
            else:
                entries[direction] = observation(reports[year], year) if year is not None else None
        if country in net_income and not any(entries.values()):
            entries["netOnly"] = net_income[country]
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
    reports = imf_reports(cache)
    national = national_accounts()
    for country, entries in eurostat_accounts(cache).items():
        national.setdefault(country, {}).update(entries)
    net_income = national_net_income()
    bundle = build(states, reports, national, net_income)
    missing = [COUNTRY_ALIASES.get(c, c) for c, entries in bundle["countries"].items() if not any(entries.values())]
    if missing:
        historical = imf_reports(cache, HISTORICAL_FIRST_YEAR, FIRST_YEAR - 1, missing)
        for key, years in historical.items():
            reports.setdefault(key, {}).update(years)
        bundle = build(states, reports, national, net_income)
    OUT.write_text(json.dumps(bundle, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
