"""Build division-level first preferences and distributions from the AEC 2022 national count."""

import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

SOURCE = "https://results.aec.gov.au/27966/Website/Downloads/HouseDopByDivisionDownload-27966.csv"
WA = "https://www.aec.gov.au/redistributions/2023/wa/final-report/files/Redistribution%20of-Western-Australia-into-electoral-divisions-September-2024.pdf"
REGIONS = dict(NSW="AUS-2654", VIC="AUS-2656", QLD="AUS-2657", WA="AUS-2651",
               SA="AUS-2655", TAS="AUS-2660", ACT="AUS-2653", NT="AUS-2650")
PARTIES = {"ALP": "노동당", "LP": "자유·국민 연합", "LNP": "자유·국민 연합", "NP": "자유·국민 연합",
           "CLP": "자유·국민 연합", "GRN": "녹색당", "GVIC": "녹색당", "IND": "무소속", "": "무소속",
           "XEN": "중도연합", "KAP": "캐터의 호주당"}
# The AEC WA final report, Appendix K, gives the origin of Bullwinkel's 115,935 electors.
ORIGINS = {"Burt": 7430, "Canning": 10877, "Durack": 14365, "Hasluck": 56422, "O'Connor": 1364, "Swan": 25477}


def count(rows):
    first = [r for r in rows if r["CountNumber"] == "0" and r["CalculationType"] == "Preference Count"]
    first.sort(key=lambda r: int(r["BallotPosition"]))
    index = {r["CandidateID"]: i for i, r in enumerate(first)}
    parties = [PARTIES.get(r["PartyAb"], r["PartyAb"]) for r in first]
    votes = [int(r["CalculationValue"]) for r in first]
    transfers = [[0] * len(first) for _ in first]
    rounds = defaultdict(dict)
    for r in rows:
        if r["CalculationType"] == "Transfer Count" and r["CountNumber"] != "0":
            rounds[r["CountNumber"]][index[r["CandidateID"]]] = int(r["CalculationValue"])
    for movement in rounds.values():
        losers = [i for i, v in movement.items() if v < 0]
        if not losers:
            continue
        assert len(losers) == 1
        transfers[losers[0]] = [max(0, movement.get(i, 0)) for i in range(len(first))]
    return dict(name=first[0]["DivisionNm"], region=REGIONS[first[0]["StateAb"]], parties=parties,
                first=votes, transfers=transfers)


def new_division(original):
    votes = Counter()
    flows = defaultdict(Counter)
    for name, electors in ORIGINS.items():
        row = original[name]
        weight = electors / sum(row["first"])
        for i, party in enumerate(row["parties"]):
            votes[party] += weight * row["first"][i]
            for j, value in enumerate(row["transfers"][i]):
                to = row["parties"][j]
                if party != to:
                    flows[party][to] += weight * value
    parties = sorted(votes)
    return dict(name="Bullwinkel", region=REGIONS["WA"], parties=parties,
                first=[round(votes[p]) for p in parties], transfers=[[round(flows[p][q]) for q in parties] for p in parties])


def main():
    with Path(sys.argv[1]).open(encoding="utf-8-sig") as raw:
        next(raw)
        groups = defaultdict(list)
        for row in csv.DictReader(raw):
            groups[row["DivisionNm"]].append(row)
    original = {name: count(rows) for name, rows in groups.items()}
    national = Counter()
    for row in original.values():
        for key, votes in zip(row["parties"], row["first"]):
            national[key] += votes
    # NSW and Victoria each lose one division, WA gains one. Other retained divisions keep their 2022 count.
    divisions = [row for name, row in original.items() if name not in {"North Sydney", "Higgins"}]
    divisions.append(new_division(original))
    assert sorted(Counter(r["region"] for r in divisions).values()) == [2, 3, 5, 10, 16, 30, 38, 46]
    output = dict(source=SOURCE, redistribution=WA, baseline=national,
                  divisions=sorted(divisions, key=lambda row: (row["region"], row["name"])))
    path = Path(__file__).resolve().parents[2] / "simulation/src/main/resources/world/australian_divisions.json"
    path.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
