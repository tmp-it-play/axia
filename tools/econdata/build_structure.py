"""World Bank WDI와 유엔·국가별 국민계정으로 구조 초기값(structure.json)을 만든다.

항목: 교역 개방도(수출+수입, GDP 대비), 수출 비중, 총 자원 지대·석유 지대(GDP 대비), 병력(명), 합계출산율,
조출생률·조사망률(인구 1,000명당), 기대수명, 도시화율, 순이동(명, 연간),
부가가치 구성(농업·산업·제조업·서비스, GDP 대비)과 제조업 내 중고위 기술·식품·섬유 비중.
2019~2023년 WDI 중 가장 최근 값을 쓴다. 교역·산업 비중 결측은 2023년 유엔·대만·북키프로스·소말릴란드 자료로 보완하고,
인구동태와 도시화는 각국 공식 통계와 UN WPP·WUP로 보완한다. 수동·중앙값 등 게임 추정은 estimated에 기록한다.

사용법:
    python3 tools/econdata/build_structure.py [--cache-dir <원본 응답 저장 폴더>]
"""

import argparse
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_economy import SOURCE_CODES, INCOME_OVERRIDES, WORLD, ROOT, download, latest  # noqa: E402
from local_accounts import load_local_accounts  # noqa: E402
from local_population import DEMOGRAPHY  # noqa: E402
from national_accounts import taiwan_accounts, unsd_accounts  # noqa: E402

OUT = ROOT / "simulation/src/main/resources/world/structure.json"
YEARS = range(2019, 2024)

INDICATORS = {
    "tradeOpenness": ("NE.TRD.GNFS.ZS", 0.01),
    "exportShare": ("NE.EXP.GNFS.ZS", 0.01),
    "resourceRents": ("NY.GDP.TOTL.RT.ZS", 0.01),
    "oilRents": ("NY.GDP.PETR.RT.ZS", 0.01),
    "armedForces": ("MS.MIL.TOTL.P1", 1.0),
    "fertility": ("SP.DYN.TFRT.IN", 1.0),
    "birthRate": ("SP.DYN.CBRT.IN", 0.001),
    "deathRate": ("SP.DYN.CDRT.IN", 0.001),
    "lifeExpectancy": ("SP.DYN.LE00.IN", 1.0),
    "urbanShare": ("SP.URB.TOTL.IN.ZS", 0.01),
    "netMigration": ("SM.POP.NETM", 1.0),
    "agricultureValueAdded": ("NV.AGR.TOTL.ZS", 0.01),
    "industryValueAdded": ("NV.IND.TOTL.ZS", 0.01),
    "manufacturingValueAdded": ("NV.IND.MANF.ZS", 0.01),
    "servicesValueAdded": ("NV.SRV.TOTL.ZS", 0.01),
    "techManufacturing": ("NV.MNF.TECH.ZS.UN", 0.01),
    "foodManufacturing": ("NV.MNF.FBTO.ZS.UN", 0.01),
    "textileManufacturing": ("NV.MNF.TXTL.ZS.UN", 0.01),
}

# World Bank에 없는 국가의 대략값. 대만은 내정부·주계총처 2023년 통계, 나머지는 국제기구 추정 기반.
MANUAL = {
    "TWN": {
        "resourceRents": 0.0, "oilRents": 0.0, "armedForces": 169000,
        "techManufacturing": 0.75,
    },
    "PRK": {"resourceRents": 0.08, "armedForces": 1280000},
}

# UNIDO INDSTAT, ISIC Rev. 3, 변수 20(당해가격 자국통화 부가가치), 분모 D(전체 제조업).
# 콩고공화국 2022년 항목 15는 결합코드 15A로 16(담배)도 포함한다. 에리트레아는 2023년 17+18(섬유·의류).
# 같은 조사연도·표 안에서 WDI와 동일한 산업 범위가 확인된 경우에만 대체한다.
# https://stat.unido.org/unido-statistics-portal-api
# https://stat.unido.org/portal/dataset/getDataset/INDSTAT/3
INDSTAT_SHARES = {
    "COG": {"foodManufacturing": 497_100_000_000 / 782_700_000_000},
    "ERI": {"textileManufacturing": (160_815_566 + 13_365_998) / 1_229_173_540},
}

LIMITS = {
    "tradeOpenness": (0.05, 4.0),
    "exportShare": (0.01, 2.5),
    "resourceRents": (0.0, 0.7),
    "oilRents": (0.0, 0.6),
    "fertility": (0.7, 7.5),
    "birthRate": (0.004, 0.05),
    "deathRate": (0.001, 0.03),
    "lifeExpectancy": (45.0, 88.0),
    "urbanShare": (0.1, 1.0),
    "agricultureValueAdded": (0.0, 0.7),
    "industryValueAdded": (0.03, 0.9),
    "manufacturingValueAdded": (0.0, 0.5),
    "servicesValueAdded": (0.1, 0.95),
    "techManufacturing": (0.0, 0.9),
    "foodManufacturing": (0.0, 0.9),
    "textileManufacturing": (0.0, 0.7),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)
    world = json.loads(WORLD.read_text(encoding="utf-8"))
    states = [country["id"] for country in world["countries"] if country["kind"] == "STATE"]
    series = {}
    for field, (code, _) in INDICATORS.items():
        url = f"https://api.worldbank.org/v2/country/all/indicator/{code}?format=json&date=2019:2023&per_page=20000"
        data = json.loads(download(url, args.cache_dir / f"{code}.json" if args.cache_dir else None))
        values = {}
        for row in data[1] or []:
            if row["value"] is not None:
                values.setdefault(row["countryiso3code"], {})[int(row["date"])] = float(row["value"])
        series[field] = values
    income_data = json.loads(
        download("https://api.worldbank.org/v2/country?format=json&per_page=400", args.cache_dir / "countries.json" if args.cache_dir else None),
    )
    income = {row["id"]: row["incomeLevel"]["id"] for row in income_data[1] if row["region"]["value"] != "Aggregates"}

    accounts = unsd_accounts(args.cache_dir)
    accounts["TWN"] = taiwan_accounts(args.cache_dir)
    accounts.update(load_local_accounts())
    local_sources = {"TWN": "DGBAS", "CYN": "TRNC_STATS_BOP", "SOL": "SOMALILAND_CSD"}
    merged, groups, account_fields = {}, {}, {}
    for country in states:
        codes = SOURCE_CODES.get(country, [country])
        groups[country] = INCOME_OVERRIDES.get(country) or next((income[code] for code in codes if code in income), None)
        merged[country] = {}
        account_fields[country] = set()
        for field, (_, scale) in INDICATORS.items():
            value = next((latest(series[field][code], YEARS) for code in codes if code in series[field]), None)
            merged[country][field] = None if value is None else value * scale
            if value is None and field in accounts.get(country, {}):
                merged[country][field] = accounts[country][field]
                account_fields[country].add(field)
            elif value is None and field in DEMOGRAPHY.get(country, {}):
                merged[country][field] = DEMOGRAPHY[country][field]
            elif value is None and field in INDSTAT_SHARES.get(country, {}):
                merged[country][field] = INDSTAT_SHARES[country][field]
        merged[country].update(MANUAL.get(country, {}))

    medians = {}
    for field in INDICATORS:
        by_group = {}
        for country, values in merged.items():
            if values[field] is not None and field != "netMigration" and field != "armedForces":
                by_group.setdefault(groups[country], []).append(values[field])
        medians[field] = {group: statistics.median(values) for group, values in by_group.items() if values}

    entries = []
    for country in states:
        values = merged[country]
        estimated = {field for field, value in values.items() if value is None} | set(MANUAL.get(country, {}))
        entry = {"id": country}
        for field in INDICATORS:
            value = values[field]
            if value is None:
                # 병력과 순이동은 인구 규모에 따라 달라 중앙값을 쓸 수 없다. 0으로 두고 시뮬레이션이 국방비로 추정한다.
                if field in ("armedForces", "netMigration"):
                    value = 0.0
                else:
                    group_median = medians[field].get(groups[country])
                    value = group_median if group_median is not None else statistics.median(medians[field].values())
            low, high = LIMITS.get(field, (None, None))
            if low is not None and field not in account_fields[country]:
                value = max(low, min(high, value))
            entry[field] = round(value, 5) if abs(value) < 1000 else round(value)
        if account_fields[country]:
            source = local_sources.get(country, "UNSD_AMA")
            entry["nationalAccounts"] = {source: sorted(account_fields[country])}
        entry["estimated"] = sorted(estimated)
        entries.append(entry)
    bundle = {
        "baseYear": 2023,
        "source": (
            "World Bank WDI (CC BY 4.0). nationalAccounts: UNSD AMA, including UN estimates "
            "(https://unstats.un.org/unsd/snaama/downloads); Taiwan DGBAS, current prices, 2023 "
            "(https://data.gov.tw/dataset/44222, https://data.gov.tw/dataset/44224); "
            "TRNC_STATS_BOP: Northern Cyprus Statistical Institute Table 1 "
            "(https://istatistik.gov.ct.tr/Portals/39/Tablo1_GSMH_2024_1.pdf), State Planning Organization "
            "(https://www.devplan.org/arsiv/programlar/OVP-2025-27.pdf), Central Bank BPM6 Table 1 "
            "(https://www.kktcmerkezbankasi.org/sites/default/files/2024%20-%C3%96demeler%20Dengesi%20Tablolar%C4%B1-Analitik%20Sunum.pdf); "
            "SOMALILAND_CSD: GDP Report 2024, Table 1, experimental estimates "
            "(https://www.govsomaliland.org/uploads/files/2025/09/2025-09-06-05-36-34-2988-1757136994.pdf). "
            "Demography: Northern Cyprus 2023 and 2024 Statistical Yearbooks B4; Taiwan Ministry of the Interior "
            "demographic statistics and life tables, fourth ICCPR/ICESCR national report and UN WPP 2024 net migration. "
            "Urbanization: UN WUP 2025, national definitions, 2023 (Taiwan and the Holy See). "
            "Manufacturing: UNIDO INDSTAT ISIC Rev. 3 (Congo food/beverages/tobacco 2022, Eritrea textiles/clothing 2023)."
        ),
        "countries": entries,
    }
    OUT.write_text(json.dumps(bundle, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for field in INDICATORS:
        print(field, sum(1 for e in entries if field in e["estimated"]))


if __name__ == "__main__":
    main()
