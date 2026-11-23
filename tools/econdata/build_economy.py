"""IMF WEO·GFS·WoRLD와 World Bank 지표로 2024년 1월 1일 시작 시점의 국가별 경제 초기 상태(economy.json)를 만든다.

사용법:
    python3 tools/econdata/build_economy.py [--cache-dir <원본 응답 저장 폴더>]

기준 연도는 2023년이다. 세입 구성은 IMF GFS(정부범위별), WoRLD, WDI 순으로 찾는다.
다른 항목은 IMF WEO, World Bank 순으로 값을 찾고, 둘 다 없으면 같은 소득 그룹의 중앙값으로
채운 뒤 estimated 목록에 기록한다. 두 출처 모두에 없는 국가는 MANUAL 표의 대략적인 값을 쓴다.
다운로드는 시스템 인증서를 쓰는 curl로 한다.
"""

import argparse
import json
import math
import statistics
import subprocess
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORLD = ROOT / "simulation/src/main/resources/world/world.json"
OUT = ROOT / "simulation/src/main/resources/world/economy.json"

BASE_YEAR = 2023
FIRST_YEAR = 2010
RECENT_YEARS = range(2019, BASE_YEAR + 1)
POTENTIAL_YEARS = [2015, 2016, 2017, 2018, 2019, 2023]
VOLATILITY_YEARS = range(2010, 2020)

IMF_INDICATORS = {
    "gdp": "NGDPD",  # 명목 GDP, 달러
    "growth": "NGDP_RPCH",  # 실질 성장률, %
    "inflation": "PCPIPCH",  # 소비자물가 상승률(연평균), %
    "unemployment": "LUR",  # 실업률, %
    "population": "LP",  # 인구, 명
    "revenue": "GGR_NGDP",  # 일반정부 세입, GDP 대비 %
    "expense": "GGX_NGDP",  # 일반정부 세출, GDP 대비 %
    "debt": "GGXWDG_NGDP",  # 일반정부 총부채, GDP 대비 %
    "primaryBalance": "GGXONLB_NGDP",  # 기초재정수지, GDP 대비 %
    "balance": "GGXCNL_NGDP",  # 재정수지, GDP 대비 %
}

WORLD_BANK_INDICATORS = {
    "gdp": "NY.GDP.MKTP.CD",
    "growth": "NY.GDP.MKTP.KD.ZG",
    "population": "SP.POP.TOTL",
    "populationGrowth": "SP.POP.GROW",
    "cpi": "FP.CPI.TOTL.ZG",
    "deflator": "NY.GDP.DEFL.KD.ZG",
    "unemployment": "SL.UEM.TOTL.ZS",
    "revenue": "GC.REV.XGRT.GD.ZS",
    "expense": "GC.XPN.TOTL.GD.ZS",
    "debt": "GC.DOD.TOTL.GD.ZS",
    "interestOfRevenue": "GC.XPN.INTP.RV.ZS",
    "agricultureEmployment": "SL.AGR.EMPL.ZS",  # 고용 대비 %
    "industryEmployment": "SL.IND.EMPL.ZS",
    "servicesEmployment": "SL.SRV.EMPL.ZS",
    "selfEmployment": "SL.EMP.SELF.ZS",
    "elderlyShare": "SP.POP.65UP.TO.ZS",  # 인구 대비 %
    "childShare": "SP.POP.0014.TO.ZS",
    "gini": "SI.POV.GINI",
    "defense": "MS.MIL.XPND.GD.ZS",  # GDP 대비 %
    "education": "SE.XPD.TOTL.GD.ZS",
    "health": "SH.XPD.GHED.GD.ZS",
    "incomeProfitTaxShare": "GC.TAX.YPKG.RV.ZS",  # 세입 대비 %
    "consumptionTaxShare": "GC.TAX.GSRV.RV.ZS",
    "socialContributionShare": "GC.REV.SOCL.ZS",
    "tradeTaxShare": "GC.TAX.INTT.RV.ZS",
}

SOCIETY_FIELDS = [
    "agricultureEmployment",
    "industryEmployment",
    "servicesEmployment",
    "selfEmployment",
    "elderlyShare",
    "childShare",
    "gini",
]
BUDGET_FIELDS = [
    "defense",
    "education",
    "health",
    "incomeProfitTaxShare",
    "consumptionTaxShare",
    "socialContributionShare",
    "tradeTaxShare",
]

TAX_COMPONENTS = {
    "incomeTaxShare": "G1111",
    "corporateTaxShare": "G1112",
    "consumptionTaxShare": "G114",
    "socialContributionShare": "G121",
    "tradeTaxShare": "G115",
}
WDI_TAX_FIELDS = {"incomeProfitTaxShare", "consumptionTaxShare", "socialContributionShare", "tradeTaxShare"}
# 일반정부가 비면 범위가 넓은 중앙정부 자료부터 일반정부 세입 구성의 대용값으로 쓴다.
GFS_SECTORS = {
    "S13": "GFS_GENERAL",
    "S1321": "GFS_CENTRAL_INCL_SOCIAL_SECURITY",
    "S1311": "GFS_CENTRAL_EXCL_SOCIAL_SECURITY",
    "S1311B": "GFS_BUDGETARY_CENTRAL",
}


# World Bank의 소득·이익세는 개인소득세와 법인세의 합이다. 법인세 비중은 소득 그룹별 대략적인 템플릿으로 나눈다.
CORPORATE_SHARE_OF_INCOME_TAX = {"HIC": 0.3, "UMC": 0.45, "LMC": 0.5, "LIC": 0.5}

# 국방, 교육, 보건 지출 합이 기초지출의 이 비율을 넘으면 비율을 유지한 채 줄인다.
MAX_CORE_SPENDING_SHARE = 0.8

# 우리 국가 코드(Natural Earth ADM0_A3)와 출처 코드가 다른 경우.
SOURCE_CODES = {"KOS": ["KOS", "XKX"], "PSX": ["PSE", "WBG"], "SDS": ["SSD"]}

# World Bank 소득 그룹에 없는 국가.
INCOME_OVERRIDES = {"TWN": "HIC", "CYN": "UMC", "SOL": "LIC", "VAT": "HIC"}

# IMF와 World Bank 모두에 없는 값. 각국 공식 통계와 국제기구 추정을 바탕으로 한 2023년 대략값.
MANUAL = {
    "CYN": {"gdp": 4.5e9, "growth": 0.04, "population": 0.39e6, "inflation": 0.6, "unemployment": 0.07},
    "SOL": {"gdp": 3.4e9, "growth": 0.03, "population": 5.7e6, "inflation": 0.05, "unemployment": 0.2},
    "VAT": {"gdp": 0.03e9, "growth": 0.0, "population": 800, "inflation": 0.02, "unemployment": 0.0},
    "TWN": {"agricultureEmployment": 0.045, "industryEmployment": 0.355, "servicesEmployment": 0.6, "selfEmployment": 0.17, "defense": 0.024},
    "PRK": {"gdp": 30e9},
    "ERI": {"gdp": 2.3e9},
}

FIELDS = [
    "gdp",
    "population",
    "growth",
    "populationGrowth",
    "inflation",
    "unemployment",
    "revenue",
    "expense",
    "debt",
    "interest",
    "potentialGrowth",
    "growthVolatility",
    *SOCIETY_FIELDS,
    *BUDGET_FIELDS,
]
REQUIRED = {"gdp", "population"}
MIN_DEBT_FOR_RATE = 0.1


def download(url, cache_file):
    if cache_file is not None and cache_file.exists():
        return cache_file.read_bytes()
    data = subprocess.run(["curl", "-sfL", "-m", "180", url], check=True, capture_output=True).stdout
    if cache_file is not None:
        cache_file.write_bytes(data)
    return data


def load_imf(cache_dir):
    """IMF WEO 지표. OBS_VALUE는 기본 단위(달러, 명, %)이며 SCALE 속성은 표시용이다."""
    series = {}
    for key, code in IMF_INDICATORS.items():
        url = f"https://api.imf.org/external/sdmx/2.1/data/IMF.RES,WEO/.{code}.A?startPeriod={FIRST_YEAR}&endPeriod={BASE_YEAR}"
        root = ElementTree.fromstring(download(url, cache_dir / f"imf-{code}.xml" if cache_dir else None))
        values = {}
        for element in root.iter():
            if element.tag.endswith("Series"):
                country = element.get("COUNTRY")
                for observation in element:
                    value = observation.get("OBS_VALUE")
                    if value not in (None, "", "NaN"):
                        values.setdefault(country, {})[int(observation.get("TIME_PERIOD"))] = float(value)
        series[key] = values
    return series


def load_world_bank(cache_dir):
    series = {}
    for key, code in WORLD_BANK_INDICATORS.items():
        url = f"https://api.worldbank.org/v2/country/all/indicator/{code}?format=json&date={FIRST_YEAR}:{BASE_YEAR}&per_page=20000"
        data = json.loads(download(url, cache_dir / f"{code}.json" if cache_dir else None))
        values = {}
        for row in data[1] or []:
            if row["value"] is not None:
                values.setdefault(row["countryiso3code"], {})[int(row["date"])] = float(row["value"])
        series[key] = values
    income_data = json.loads(download("https://api.worldbank.org/v2/country?format=json&per_page=400", cache_dir / "countries.json" if cache_dir else None))
    income = {row["id"]: row["incomeLevel"]["id"] for row in income_data[1] if row["region"]["value"] != "Aggregates"}
    return series, income


def load_revenue_compositions(cache_dir):
    """같은 해·출처·정부범위에서 총세입과 다섯 세목이 모두 보고된 구성만 반환한다."""
    codes = ["G1", *TAX_COMPONENTS.values()]
    gfs_indicators = "+".join(f"{code}_T" for code in codes)
    gfs_sectors = "+".join(GFS_SECTORS)
    gfs_key = f".{gfs_sectors}.G1.{gfs_indicators}.POGDP_PT.A"
    sources = (
        ("IMF.STA,GFS_SOO", gfs_key, "gfs-revenue", "_T"),
        ("IMF.FAD,WORLD", "", "world-revenue", "_POGDP_PT_R"),
    )
    observations = {}
    for flow, key, filename, suffix in sources:
        url = f"https://api.imf.org/external/sdmx/2.1/data/{flow}/{key}?startPeriod={BASE_YEAR}&endPeriod={BASE_YEAR}"
        cache = cache_dir / f"imf-{filename}-{BASE_YEAR}.xml" if cache_dir else None
        root = ElementTree.fromstring(download(url, cache))
        found = False
        for series in root.iter("Series"):
            indicator = series.get("INDICATOR", "")
            if not indicator.endswith(suffix):
                continue
            code = indicator.removesuffix(suffix)
            if code not in codes or series.get("TYPE_OF_TRANSFORMATION") != "POGDP_PT" or series.get("FREQUENCY") != "A":
                continue
            if flow == "IMF.STA,GFS_SOO":
                source = GFS_SECTORS.get(series.get("SECTOR"))
                if source is None or series.get("GFS_GRP") != "G1":
                    continue
            else:
                # WoRLD는 국가별 사용 정부범위를 공개 자료에 싣지 않는다.
                source = "WORLD_UNSPECIFIED"
            bundle = observations.setdefault((series.attrib["COUNTRY"], source), {})
            for observation in series:
                if observation.get("TIME_PERIOD") != str(BASE_YEAR) or observation.get("OBS_VALUE") in (None, ""):
                    continue
                if observation.get("STATUS") in ("NA", "NP"):
                    continue
                found = True
                value = float(observation.attrib["OBS_VALUE"])
                if code in bundle:
                    raise ValueError(f"Duplicate {flow} revenue observation: {series.attrib}")
                bundle[code] = value
        if not found:
            raise ValueError(f"No annual revenue observations in {flow} for {BASE_YEAR}")

    result = {}
    for (country, source), values in observations.items():
        if not all(code in values and math.isfinite(values[code]) and values[code] >= 0 for code in codes):
            continue
        total = values["G1"]
        if total <= 0 or sum(values[code] for code in TAX_COMPONENTS.values()) > total:
            continue
        shares = {field: round(values[code] / total, 8) for field, code in TAX_COMPONENTS.items()}
        shares["otherRevenueShare"] = round(1 - sum(shares.values()), 8)
        if shares["otherRevenueShare"] < 0:
            raise ValueError(f"Rounded tax shares exceed total revenue: {country} {source}")
        result[country, source] = shares
    return result


def apply_revenue_compositions(entries, compositions):
    for entry in entries:
        country = entry["id"]
        codes = SOURCE_CODES.get(country, [country])
        for source in [*GFS_SECTORS.values(), "WORLD_UNSPECIFIED"]:
            shares = next((compositions[code, source] for code in codes if (code, source) in compositions), None)
            if shares is not None:
                entry["budget"].update(shares)
                entry["budget"]["revenueCompositionSource"] = source
                entry["estimated"] = sorted(set(entry["estimated"]) - WDI_TAX_FIELDS)
                break
        else:
            budget = entry["budget"]
            budget["revenueCompositionSource"] = "WDI_MODEL"
            # 소수 넷째 자리로 반올림한 WDI 구성의 합을 1로 맞춘다. 보고된 0은 그대로 둔다.
            fields = [*TAX_COMPONENTS, "otherRevenueShare"]
            largest = max(fields, key=budget.get)
            budget[largest] = round(budget[largest] + 1.0 - sum(budget[field] for field in fields), 4)


def latest(values, years):
    for year in sorted(years, reverse=True):
        if year in values:
            return values[year]
    return None


def pct(value):
    return None if value is None else value / 100.0


def history_stats(history):
    potential = [history[year] for year in POTENTIAL_YEARS if year in history]
    volatility = [history[year] for year in VOLATILITY_YEARS if year in history]
    return (
        pct(statistics.median(potential)) if len(potential) >= 3 else None,
        pct(statistics.pstdev(volatility)) if len(volatility) >= 5 else None,
    )


def from_imf(codes, series):
    def pick(key):
        for code in codes:
            if code in series[key]:
                return series[key][code]
        return {}

    def get(key, years=RECENT_YEARS):
        return latest(pick(key), years)

    population = pick("population")
    population_growth = None
    if BASE_YEAR in population and BASE_YEAR - 1 in population and population[BASE_YEAR - 1] > 0:
        population_growth = population[BASE_YEAR] / population[BASE_YEAR - 1] - 1
    primary_balance, balance = get("primaryBalance"), get("balance")
    potential, volatility = history_stats(pick("growth"))
    return {
        "gdp": get("gdp"),
        "population": get("population", [BASE_YEAR]),
        "growth": pct(get("growth", [BASE_YEAR])),
        "populationGrowth": population_growth,
        "inflation": pct(get("inflation", [BASE_YEAR])),
        "unemployment": pct(get("unemployment", [BASE_YEAR])),
        "revenue": pct(get("revenue")),
        "expense": pct(get("expense")),
        "debt": pct(get("debt")),
        "interest": pct(primary_balance - balance) if primary_balance is not None and balance is not None else None,
        "potentialGrowth": potential,
        "growthVolatility": volatility,
    }


def from_world_bank(codes, series):
    def pick(key):
        for code in codes:
            if code in series[key]:
                return series[key][code]
        return {}

    def get(key, years=RECENT_YEARS):
        return latest(pick(key), years)

    revenue, interest_of_revenue = get("revenue"), get("interestOfRevenue")
    inflation = get("cpi", [BASE_YEAR]) or get("deflator", [BASE_YEAR]) or get("cpi") or get("deflator")
    potential, volatility = history_stats(pick("growth"))
    return {
        "gdp": get("gdp"),
        "population": get("population", [BASE_YEAR]),
        "growth": pct(get("growth", [BASE_YEAR])),
        "populationGrowth": pct(get("populationGrowth", [BASE_YEAR])),
        "inflation": pct(inflation),
        "unemployment": pct(get("unemployment", [BASE_YEAR])),
        "revenue": pct(revenue),
        "expense": pct(get("expense")),
        "debt": pct(get("debt")),
        "interest": pct(interest_of_revenue * revenue / 100.0) if revenue is not None and interest_of_revenue is not None else None,
        "potentialGrowth": potential,
        "growthVolatility": volatility,
        **{field: pct(get(field)) for field in SOCIETY_FIELDS + BUDGET_FIELDS if field != "gini"},
        "gini": pct(get("gini", range(FIRST_YEAR, BASE_YEAR + 1))),
    }


def society_entry(values, group):
    employment = [values["agricultureEmployment"], values["industryEmployment"], values["servicesEmployment"]]
    total = sum(employment) or 1.0
    return {
        "incomeGroup": group,
        "agricultureEmployment": round(employment[0] / total, 4),
        "industryEmployment": round(employment[1] / total, 4),
        "servicesEmployment": round(employment[2] / total, 4),
        "selfEmployment": round(clamp(values["selfEmployment"], 0.02, 0.9), 4),
        "elderlyShare": round(clamp(values["elderlyShare"], 0.01, 0.4), 4),
        "childShare": round(clamp(values["childShare"], 0.08, 0.55), 4),
        "gini": round(clamp(values["gini"], 0.2, 0.7), 4),
    }


def budget_entry(values, group, primary_spending):
    core = [values["defense"], values["education"], values["health"]]
    limit = primary_spending * MAX_CORE_SPENDING_SHARE
    scale = min(1.0, limit / sum(core)) if sum(core) > 0 else 1.0
    taxes = [values["incomeProfitTaxShare"], values["consumptionTaxShare"], values["socialContributionShare"], values["tradeTaxShare"]]
    taxes = [clamp(value, 0.0, 1.0) for value in taxes]
    if sum(taxes) > 1.0:
        taxes = [value / sum(taxes) for value in taxes]
    corporate = CORPORATE_SHARE_OF_INCOME_TAX.get(group, 0.45)
    return {
        "defense": round(core[0] * scale, 5),
        "education": round(core[1] * scale, 5),
        "health": round(core[2] * scale, 5),
        "incomeTaxShare": round(taxes[0] * (1 - corporate), 4),
        "corporateTaxShare": round(taxes[0] * corporate, 4),
        "consumptionTaxShare": round(taxes[1], 4),
        "socialContributionShare": round(taxes[2], 4),
        "tradeTaxShare": round(taxes[3], 4),
        "otherRevenueShare": round(max(0.0, 1.0 - sum(taxes)), 4),
    }


def clamp(value, low, high):
    return max(low, min(high, value))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)

    world = json.loads(WORLD.read_text(encoding="utf-8"))
    states = [country["id"] for country in world["countries"] if country["kind"] == "STATE"]
    imf = load_imf(args.cache_dir)
    world_bank, income = load_world_bank(args.cache_dir)

    merged, sources, groups = {}, {}, {}
    for country in states:
        codes = SOURCE_CODES.get(country, [country])
        candidates = [("imf", from_imf(codes, imf)), ("wb", from_world_bank(codes, world_bank)), ("manual", MANUAL.get(country, {}))]
        merged[country], sources[country] = {}, {}
        for field in FIELDS:
            for source, values in candidates:
                if values.get(field) is not None:
                    merged[country][field] = values[field]
                    sources[country][field] = source
                    break
            else:
                merged[country][field] = None
        groups[country] = INCOME_OVERRIDES.get(country) or next((income[code] for code in codes if code in income), None)
        missing = [field for field in REQUIRED if merged[country][field] is None]
        if missing or groups[country] is None:
            raise SystemExit(f"{country}: 필수 값 {missing} 또는 소득 그룹이 없습니다. MANUAL 또는 INCOME_OVERRIDES에 추가하세요.")

    medians = {}
    for field in FIELDS:
        by_group = {}
        for country, values in merged.items():
            if values[field] is not None:
                by_group.setdefault(groups[country], []).append(values[field])
        everyone = [value for values in by_group.values() for value in values]
        medians[field] = {group: statistics.median(values) for group, values in by_group.items()}
        medians[field][None] = statistics.median(everyone)

    # 부채가 아주 작으면 이자 ÷ 부채로 구한 실효금리가 불안정하므로, 그런 국가는 소득 그룹 중앙값을 쓴다.
    rates = {}
    for country, values in merged.items():
        if values["debt"] is not None and values["interest"] is not None and values["debt"] >= MIN_DEBT_FOR_RATE:
            rates.setdefault(groups[country], []).append(values["interest"] / values["debt"])
    median_rates = {group: statistics.median(values) for group, values in rates.items()}
    median_rates[None] = statistics.median([rate for values in rates.values() for rate in values])

    entries = []
    for country in states:
        values = merged[country]
        estimated = {field for field in FIELDS if values[field] is None}
        for field in estimated:
            values[field] = medians[field].get(groups[country], medians[field][None])
        estimated |= {field for field, source in sources[country].items() if source == "manual"}

        debt = clamp(values["debt"], 0.0, 3.0)
        interest = clamp(values["interest"], 0.0, 0.2)
        if debt >= MIN_DEBT_FOR_RATE:
            interest_rate = interest / debt
        else:
            interest_rate = median_rates.get(groups[country], median_rates[None])
            interest = interest_rate * debt
            estimated.add("interest")
        primary_spending = clamp(values["expense"] - interest, 0.03, 0.8)
        entries.append(
            {
                "id": country,
                "population": round(values["population"]),
                "populationGrowth": round(clamp(values["populationGrowth"], -0.05, 0.06), 5),
                "gdp": round(values["gdp"]),
                "growth": round(clamp(values["growth"], -0.3, 0.3), 5),
                "potentialGrowth": round(clamp(values["potentialGrowth"], -0.01, 0.07), 5),
                "growthVolatility": round(clamp(values["growthVolatility"], 0.005, 0.05), 5),
                "inflation": round(clamp(values["inflation"], -0.02, 3.0), 5),
                "unemployment": round(clamp(values["unemployment"], 0.005, 0.4), 5),
                "revenue": round(clamp(values["revenue"], 0.03, 0.7), 5),
                "primarySpending": round(primary_spending, 5),
                "debt": round(debt, 5),
                "interestRate": round(clamp(interest_rate, 0.005, 0.25), 5),
                "society": society_entry(values, groups[country]),
                "budget": budget_entry(values, groups[country], primary_spending),
                "estimated": sorted(estimated),
            },
        )

    apply_revenue_compositions(entries, load_revenue_compositions(args.cache_dir))
    OUT.write_text(json.dumps({"baseYear": BASE_YEAR, "countries": entries}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"countries={len(entries)} without median/manual flags={sum(1 for entry in entries if not entry['estimated'])}")
    for source in [*GFS_SECTORS.values(), "WORLD_UNSPECIFIED", "WDI_MODEL"]:
        count = sum(entry["budget"]["revenueCompositionSource"] == source for entry in entries)
        print(f"  revenue composition: {source}={count}")
    for field in FIELDS:
        from_imf_count = sum(1 for country in states if sources[country].get(field) == "imf")
        estimated_count = sum(1 for entry in entries if field in entry["estimated"])
        print(f"  {field}: weo={from_imf_count} estimated={estimated_count}")
    print(f"economy: {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
