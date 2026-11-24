"""2014~2023년 공표 송금액과 세계은행의 국가·양자 추계로 월 결제의 연간 기준액을 생성한다."""

import argparse
import csv
import io
import json
import math
from pathlib import Path

from build_economy import WORLD, download
from remittance_supplements import (
    BOP_REMITTANCES_USD_2023,
    FISCAL_BOP_REMITTANCES,
    PARTIAL_REMITTANCES,
    UNCHANGED_ESTIMATE_SINCE,
    WORLD_BANK_REMITTANCE_ESTIMATES,
)

MATRIX_URL = "https://data360files.worldbank.org/data360-data/data/WB_KNOMAD/WB_KNOMAD_BRE.csv"
INDICATORS = {"received": "BX.TRF.PWKR.CD.DT", "paid": "BM.TRF.PWKR.CD.DT"}
CODES = {"PSX": "PSE", "SDS": "SSD", "KOS": "XKX"}
OUT = WORLD.with_name("remittances.json")


def wdi_totals(direction, state_codes, cache):
    code = INDICATORS[direction]
    url = f"https://api.worldbank.org/v2/country/all/indicator/{code}?date=2014:2023&format=json&per_page=5000"
    meta, rows = json.loads(download(url, cache / f"wdi-{code}-2014-2023.json" if cache else None))
    if int(meta["pages"]) != 1 or len(rows) != int(meta["total"]):
        raise ValueError(f"WDI 응답이 완전하지 않습니다: {code}")
    result = {}
    for row in rows:
        if row["countryiso3code"] not in state_codes:
            continue
        value = row["value"]
        year = int(row["date"])
        if not 2014 <= year <= 2023 or row["indicator"]["id"] != code:
            raise ValueError(f"WDI 연도·지표 불일치: {row}")
        if value is not None:
            if not math.isfinite(value) or value < 0:
                raise ValueError(f"WDI 송금액 오류: {row}")
            series = result.setdefault(row["countryiso3code"], {})
            if year in series:
                raise ValueError(f"WDI 중복 관측: {row}")
            series[year] = value
    return result


def knomad_matrix(cache):
    data = download(MATRIX_URL, cache / "bre.csv" if cache else None)
    matrix = {}
    for row in csv.DictReader(io.StringIO(data.decode("utf-8-sig"))):
        if row["TIME_PERIOD"] != "2021":
            continue
        if row["INDICATOR"] != "WB_KNOMAD_BRE" or "(US$ million)" not in row["INDICATOR_LABEL"]:
            raise ValueError("KNOMAD 지표 또는 금액 단위 불일치")
        if not row["COMP_BREAKDOWN_1"].startswith("WB_KNOMAD_"):
            raise ValueError(f"KNOMAD 수취국 코드 오류: {row}")
        key = row["REF_AREA"], row["COMP_BREAKDOWN_1"].removeprefix("WB_KNOMAD_")
        value = float(row["OBS_VALUE"]) * 1_000_000
        if key in matrix or not math.isfinite(value) or value < 0:
            raise ValueError(f"KNOMAD 경로 오류: {key}")
        matrix[key] = value
    if matrix.get(("WLD", "WLD"), 0) <= 0:
        raise ValueError("KNOMAD 2021년 합계 누락")
    return matrix


def opening_total(country, direction, reported, matrix_total):
    national = BOP_REMITTANCES_USD_2023.get(country, {}).get(direction)
    if 2023 in reported:
        return {"usd": round(reported[2023]), "year": 2023, "source": "WDI"}
    if national is not None:
        if not isinstance(national, int) or national < 0:
            raise ValueError(f"보완 송금액 오류: {national}")
        return {"usd": national, "year": 2023, "source": "NATIONAL_BOP"}
    fiscal = FISCAL_BOP_REMITTANCES.get(country, {}).get(direction)
    if fiscal is not None:
        return {"usd": fiscal, "year": 2023, "source": "FISCAL_BOP_SEPTEMBER"}
    estimate = WORLD_BANK_REMITTANCE_ESTIMATES.get(country) if direction == "received" else None
    if estimate and estimate[0] > max(reported, default=0):
        year, usd = estimate
        benchmark = {"usd": usd, "year": year, "source": "WORLD_BANK_ESTIMATE"}
        if country in UNCHANGED_ESTIMATE_SINCE:
            benchmark["unchangedSince"] = UNCHANGED_ESTIMATE_SINCE[country]
        return benchmark
    if reported:
        year = max(reported)
        return {"usd": round(reported[year]), "year": year, "source": "WDI"}
    if matrix_total is not None:
        return {"usd": round(matrix_total), "year": 2021, "source": "KNOMAD"}
    partial = PARTIAL_REMITTANCES.get(country, {}).get(direction)
    if partial:
        year, usd, source = partial
        return {"usd": usd, "year": year, "source": source}
    return None


def build(codes, reports, matrix):
    countries = {}
    for state, code in codes.items():
        countries[state] = {
            "received": opening_total(state, "received", reports["received"].get(code, {}), matrix.get(("WLD", code))),
            "paid": opening_total(state, "paid", reports["paid"].get(code, {}), matrix.get((code, "WLD"))),
            "destinations": {},
        }
    states = {code: state for state, code in codes.items()}
    for (sender, recipient), value in sorted(matrix.items()):
        if sender not in states or recipient not in states or sender == recipient or not value:
            continue
        payer, payee = countries[states[sender]], countries[states[recipient]]
        # 양쪽 총액 중 어느 쪽도 넘기지 않는다. 연간 1달러 미만과 상대 미배정액은 잔액에 남는다.
        outgoing = value / matrix[sender, "WLD"] * payer["paid"]["usd"]
        incoming = value / matrix["WLD", recipient] * payee["received"]["usd"]
        matched = math.floor(min(outgoing, incoming))
        if matched:
            payer["destinations"][states[recipient]] = matched
    incoming = dict.fromkeys(countries, 0)
    for payer in countries.values():
        if sum(payer["destinations"].values()) > (payer["paid"] or {"usd": 0})["usd"]:
            raise ValueError("양자 지급액이 국가 총액을 초과합니다")
        for recipient, usd in payer["destinations"].items():
            incoming[recipient] += usd
    for state, total in incoming.items():
        if total > (countries[state]["received"] or {"usd": 0})["usd"]:
            raise ValueError(f"양자 수취액이 국가 총액을 초과합니다: {state}")
    return {"unit": "annual_USD", "countries": countries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path)
    cache = parser.parse_args().cache_dir
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
    rows = json.loads(WORLD.read_text(encoding="utf-8"))["countries"]
    codes = {row["id"]: CODES.get(row["id"], row["id"]) for row in rows if row["kind"] == "STATE"}
    reports = {direction: wdi_totals(direction, set(codes.values()), cache) for direction in INDICATORS}
    output = build(codes, reports, knomad_matrix(cache))
    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
