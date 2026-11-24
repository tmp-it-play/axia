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
    DOMINICAN_FORMAL_RECEIPTS,
    ECUADOR_TOTALS,
    FISCAL_BOP_REMITTANCES,
    PARTIAL_REMITTANCES,
    RECEIPT_COMPONENTS_2023,
    SOMALIA_PERSONAL_PAYMENTS,
    SPAIN_BOP_MILLION_EUR,
    UNCHANGED_ESTIMATE_SINCE,
    USD_PER_EUR_2023,
    WORLD_BANK_REMITTANCE_ESTIMATES,
    partner_benchmarks,
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


def opening_total(country, direction, reported, matrix_total, partner_report):
    if country == "ESP":
        amount = round(SPAIN_BOP_MILLION_EUR[direction] * 1_000_000 * USD_PER_EUR_2023)
        return {"usd": amount, "year": 2023, "source": "NATIONAL_BOP", "scope": "Eurostat · 전 세계 피용자보수와 가계 간 개인이전"}
    if country == "SOM" and direction == "paid" and 2023 not in reported:
        return {"usd": SOMALIA_PERSONAL_PAYMENTS, "year": 2023, "source": "BANK_CHANNELS",
                "scope": "중앙은행 · 은행·송금업자의 개인 지급만, 소말릴란드의 별도 몫 미보고"}
    national = BOP_REMITTANCES_USD_2023.get(country, {}).get(direction)
    if 2023 in reported:
        return {"usd": round(reported[2023]), "year": 2023, "source": "WDI"}
    if national is not None:
        if not isinstance(national, int) or national < 0:
            raise ValueError(f"보완 송금액 오류: {national}")
        result = {"usd": national, "year": 2023, "source": "NATIONAL_BOP"}
        if country == "LBY" and direction == "received":
            result["scope"] = "중앙은행 공표 수취 0 · 네덜란드와 이탈리아의 지급 신고와 차이 있음"
        return result
    fiscal = FISCAL_BOP_REMITTANCES.get(country, {}).get(direction)
    if fiscal is not None:
        return {"usd": fiscal, "year": 2023, "source": "FISCAL_BOP_SEPTEMBER"}
    if direction == "received" and country in RECEIPT_COMPONENTS_2023:
        usd, source, scope = RECEIPT_COMPONENTS_2023[country]
        return {"usd": usd, "year": 2023, "source": source, "scope": scope}
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
    if partner_report and (matrix_total is None or partner_report["source"] == "WAGE_PAYMENT_FLOOR"
                           and partner_report["usd"] > matrix_total):
        return partner_report
    if matrix_total is not None:
        return {"usd": round(matrix_total), "year": 2021, "source": "KNOMAD"}
    partial = PARTIAL_REMITTANCES.get(country, {}).get(direction)
    if partial:
        year, usd, source = partial
        return {"usd": usd, "year": year, "source": source}
    return None


def build(codes, reports, matrix):
    partner_reports, observed = partner_benchmarks()
    countries = {}
    for state, code in codes.items():
        countries[state] = {
            "received": opening_total(state, "received", reports["received"].get(code, {}),
                                      matrix.get(("WLD", code)), partner_reports.get((state, "received"))),
            "paid": opening_total(state, "paid", reports["paid"].get(code, {}),
                                  matrix.get((code, "WLD")), partner_reports.get((state, "paid"))),
            "destinations": {},
        }
    budget_paid = {state: (data["paid"] or {"usd": 0})["usd"] for state, data in countries.items()}
    budget_received = {state: (data["received"] or {"usd": 0})["usd"] for state, data in countries.items()}
    # 에콰도르가 속령 등 번들 밖 상대에게 배정한 금액은 KNOMAD로 다른 국가에 배정하지 않는다.
    for direction, budget, index in [("paid", budget_paid, 0), ("received", budget_received, 1)]:
        with_partner = sum(usd for route, usd in observed.items() if route[index] == "ECU")
        outside = ECUADOR_TOTALS[direction] - with_partner
        if outside < 0:
            raise ValueError(f"에콰도르 양자 {direction} 합이 중앙은행 총액을 초과합니다")
        budget["ECU"] -= outside
    # 도미니카공화국에 들어온 푸에르토리코 송금은 번들 내 송금국을 정할 수 없는 잔액이다.
    budget_received["DOM"] -= DOMINICAN_FORMAL_RECEIPTS["PRI"]
    for (sender, recipient), usd in sorted(observed.items()):
        budget_paid[sender] -= usd
        budget_received[recipient] -= usd
        if usd <= 0 or min(budget_paid[sender], budget_received[recipient]) < 0:
            raise ValueError(f"상대국 공표 송금이 연간 기준액을 초과합니다: {sender}, {recipient}")
        countries[sender]["destinations"][recipient] = usd

    states = {code: state for state, code in codes.items()}
    estimates = {}
    estimated_paid = dict.fromkeys(countries, 0)
    estimated_received = dict.fromkeys(countries, 0)
    for (sender, recipient), value in sorted(matrix.items()):
        if sender not in states or recipient not in states or sender == recipient or not value:
            continue
        payer_id, payee_id = states[sender], states[recipient]
        payer, payee = countries[payer_id], countries[payee_id]
        # 국경 간 피용자보수 하한의 나머지는 송금국에 배정만 하고, 이민 인구 비중으로 수취국을 정하지 않는다.
        if (payer_id, payee_id) in observed or payer["paid"]["source"] == "WAGE_PAYMENT_FLOOR":
            continue
        # 양쪽 총액 중 어느 쪽도 넘기지 않는다. 연간 1달러 미만과 상대 미배정액은 잔액에 남는다.
        outgoing = value / matrix[sender, "WLD"] * payer["paid"]["usd"]
        incoming = value / matrix["WLD", recipient] * payee["received"]["usd"]
        matched = math.floor(min(outgoing, incoming))
        if matched:
            estimates[payer_id, payee_id] = matched
            estimated_paid[payer_id] += matched
            estimated_received[payee_id] += matched

    # 확정 경로를 먼저 배정한다. 종전 추계가 남은 양쪽 예산 안에 들면 금액을 그대로 유지한다.
    for (sender, recipient), usd in estimates.items():
        matched = min(usd, usd * budget_paid[sender] // estimated_paid[sender],
                      usd * budget_received[recipient] // estimated_received[recipient])
        if matched:
            countries[sender]["destinations"][recipient] = matched
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
