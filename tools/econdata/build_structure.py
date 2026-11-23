"""World Bank 지표로 국가별 구조 초기값(structure.json)을 만든다. economy.json과 따로 두어 거시 초기값을 바꾸지 않는다.

항목: 교역 개방도(수출+수입, GDP 대비), 수출 비중, 총 자원 지대·석유 지대(GDP 대비), 병력(명), 합계출산율,
조출생률·조사망률(인구 1,000명당), 기대수명, 도시화율, 순이동(명, 연간),
부가가치 구성(농업·산업·제조업·서비스, GDP 대비)과 제조업 내 중고위 기술·식품·섬유 비중.
2019~2023년 중 가장 최근 값을 쓰고, 없으면 같은 소득 그룹의 중앙값으로 채워 estimated에 적는다.

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
        "tradeOpenness": 1.1, "exportShare": 0.62, "resourceRents": 0.0, "oilRents": 0.0, "armedForces": 169000,
        "fertility": 0.87, "birthRate": 0.0058, "deathRate": 0.0089, "lifeExpectancy": 80.2, "urbanShare": 0.8,
        "netMigration": 20000, "agricultureValueAdded": 0.015, "industryValueAdded": 0.37, "manufacturingValueAdded": 0.32,
        "servicesValueAdded": 0.615, "techManufacturing": 0.75, "foodManufacturing": 0.05, "textileManufacturing": 0.03,
    },
    "PRK": {"tradeOpenness": 0.1, "exportShare": 0.02, "resourceRents": 0.08, "armedForces": 1280000},
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

    merged, groups = {}, {}
    for country in states:
        codes = SOURCE_CODES.get(country, [country])
        groups[country] = INCOME_OVERRIDES.get(country) or next((income[code] for code in codes if code in income), None)
        merged[country] = {}
        for field, (_, scale) in INDICATORS.items():
            value = next((latest(series[field][code], YEARS) for code in codes if code in series[field]), None)
            merged[country][field] = None if value is None else value * scale
        for field, value in MANUAL.get(country, {}).items():
            merged[country][field] = value

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
            if low is not None:
                value = max(low, min(high, value))
            entry[field] = round(value, 5) if abs(value) < 1000 else round(value)
        entry["estimated"] = sorted(estimated)
        entries.append(entry)
    OUT.write_text(
        json.dumps({"baseYear": 2023, "source": "World Bank WDI (CC BY 4.0)", "countries": entries}, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8",
    )
    for field in INDICATORS:
        print(field, sum(1 for e in entries if field in e["estimated"]))


if __name__ == "__main__":
    main()
