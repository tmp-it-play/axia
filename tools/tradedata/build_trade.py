"""UN Comtrade 공개 API로 국가별 상품 수출 상대 비중(trade.json)을 만든다.

- 2023년 수출(flowCode=X, HS 전체)을 쓰고, 없으면 2022년, 그래도 없으면 상대국들이 보고한 그 나라로부터의 수입(미러)을 쓴다.
- 홍콩·마카오처럼 다른 나라에 속한 영역은 소유국에 합친다. Comtrade의 "Other Asia, nes"(490)는 대만으로 본다.
- 상대 비중이 0.2% 이상인 나라만 최대 40곳 남긴다. 나머지는 시뮬레이션이 중력 모형으로 채운다.

사용법: python3 tools/tradedata/build_trade.py [--cache-dir <원본 응답 저장 폴더>]
"""

import argparse
import json
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORLD = ROOT / "simulation/src/main/resources/world/world.json"
OUT = ROOT / "simulation/src/main/resources/world/trade.json"
API = "https://comtradeapi.un.org/public/v1/preview/C/A/HS"
REFERENCE = "https://comtradeapi.un.org/files/v1/app/reference"
ALIASES = {"S19": "TWN", "XKX": "KOS", "PSE": "PSX", "SSD": "SDS"}
MIN_SHARE = 0.002
MAX_PARTNERS = 40
PAUSE_SECONDS = 1.5
# 원자료 이상치. 이란은 기밀 행선지를 "Other Asia, nes"(490)로 신고해 대만으로 잘못 잡히고,
# 파푸아뉴기니의 대북한 수입 보고는 코드 오류로 보인다.
EXCLUDED = {("IRN", "TWN"), ("PRK", "PNG")}


def download(url, cache_file):
    if cache_file is not None and cache_file.exists():
        return cache_file.read_bytes()
    for attempt in range(4):
        result = subprocess.run(["curl", "-sfL", "-m", "120", url], capture_output=True)
        if result.returncode == 0:
            break
        time.sleep(10 * (attempt + 1))
    else:
        raise SystemExit(f"download failed: {url}")
    time.sleep(PAUSE_SECONDS)
    if cache_file is not None:
        cache_file.write_bytes(result.stdout)
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    cache = args.cache_dir
    if cache:
        cache.mkdir(parents=True, exist_ok=True)

    def cached(name):
        return cache / name if cache else None

    world = json.loads(WORLD.read_text(encoding="utf-8"))
    states = {c["id"] for c in world["countries"] if c["kind"] == "STATE"}
    owners = {t["id"]: t["owner"] for t in world["territories"]}

    def our_id(iso):
        iso = ALIASES.get(iso, iso)
        if iso in states:
            return iso
        return owners.get(iso)

    partners = json.loads(download(f"{REFERENCE}/partnerAreas.json", cached("partners.json")))["results"]
    reporters = json.loads(download(f"{REFERENCE}/Reporters.json", cached("reporters.json")))["results"]
    partner_ids = {p["PartnerCode"]: our_id(p.get("PartnerCodeIsoAlpha3", "")) for p in partners if not p["isGroup"]}
    reporter_codes = {}
    for r in reporters:
        iso = our_id(r.get("reporterCodeIsoAlpha3", ""))
        if iso in states and not r["isGroup"] and iso not in reporter_codes:
            reporter_codes[iso] = r["reporterCode"]

    flows, sources = {}, {}
    for country in sorted(states):
        code = reporter_codes.get(country)
        if code is None:
            continue
        for year in (2023, 2022):
            url = f"{API}?reporterCode={code}&period={year}&flowCode=X&cmdCode=TOTAL&partner2Code=0&customsCode=C00&motCode=0"
            rows = json.loads(download(url, cached(f"X-{country}-{year}.json"))).get("data") or []
            values = {}
            for row in rows:
                partner = partner_ids.get(row["partnerCode"])
                if partner and partner != country and row.get("primaryValue"):
                    values[partner] = values.get(partner, 0.0) + row["primaryValue"]
            if values:
                flows[country], sources[country] = values, f"reported {year}"
                break
    for country in sorted(states - flows.keys()):
        codes = sorted(k for k, v in partner_ids.items() if v == country)
        values = {}
        for partner_code in codes:
            url = f"{API}?partnerCode={partner_code}&period=2023&flowCode=M&cmdCode=TOTAL&partner2Code=0&customsCode=C00&motCode=0"
            rows = json.loads(download(url, cached(f"M-{country}-{partner_code}.json"))).get("data") or []
            for row in rows:
                reporter = our_id(row.get("reporterISO", "")) or next((k for k, v in reporter_codes.items() if v == row["reporterCode"]), None)
                if reporter and reporter != country and row.get("primaryValue"):
                    values[reporter] = values.get(reporter, 0.0) + row["primaryValue"]
        if values:
            flows[country], sources[country] = values, "mirror 2023"

    countries = {}
    for country, values in sorted(flows.items()):
        values = {p: v for p, v in values.items() if (country, p) not in EXCLUDED}
        total = sum(values.values())
        top = sorted(((p, v / total) for p, v in values.items()), key=lambda x: -x[1])
        kept = [(p, s) for p, s in top if s >= MIN_SHARE][:MAX_PARTNERS]
        countries[country] = {"source": sources[country], "partners": {p: round(s, 4) for p, s in kept}}
    OUT.write_text(
        json.dumps(
            {
                "sourceNote": "UN Comtrade(공개 API) 상품 수출, HS 전체. 영역은 소유국에 합쳤고 'Other Asia, nes'는 대만으로 보았다. 서비스 교역은 포함하지 않는다.",
                "countries": countries,
            },
            ensure_ascii=False,
            indent=1,
        )
        + "\n",
        encoding="utf-8",
    )
    missing = sorted(states - flows.keys())
    print(f"{len(countries)} countries, missing {len(missing)}: {missing}")


if __name__ == "__main__":
    main()
