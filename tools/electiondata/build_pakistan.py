"""Build the 2023 constituency reference from the 2018 ECP candidate-return archive.

The checked-in crosswalk uses district names from the 30 November 2023 ECP Form 7.
Reference numbers indicate 2018 seats covering the same district or predecessor area,
not identical polling-station boundaries. Wider district pools are used after splits.
"""
import argparse
import csv
import io
import json
import subprocess
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
WORLD = HERE.parents[1] / "simulation/src/main/resources/world"
RETURNS = ("https://raw.githubusercontent.com/colincookman/pakistan_election_results_2018/"
           "81761f218106a8b054e4b70651d002f4050a1696/data/pk_candidate_data_consolidated_090418.csv")
KOREAN = {
    "Pakistan Tehreek-e-Insaf": "파키스탄 정의운동(PTI) 계열",
    "Pakistan Muslim League (Nawaz)": "파키스탄 무슬림연맹 나와즈파(PML-N)",
    "Pakistan Peoples Party Parliamentarians": "파키스탄 인민당(PPPP)",
    "Independents": "무소속",
    "Muttahidda Majlis-e-Amal Pakistan": "통합행동위원회(MMA) 계열",
    "Tehreek-e-Labbaik Pakistan": "테흐리크에라바이크 파키스탄(TLP)",
    "Grand Democratic Alliance": "대민주연합(GDA)",
    "Awami National Party": "아와미 국민당(ANP)",
    "Muttahida Qaumi Movement Pakistan": "통합민족운동 파키스탄(MQM-P)",
    "Pakistan Muslim League (Qaid-e-Azam)": "파키스탄 무슬림연맹 카이드파(PML-Q)",
    "Balochistan Awami Party": "발루치스탄 아와미당(BAP)",
    "Balochistan National Party": "발루치스탄 국민당(BNP)",
    "Awami Muslim League Pakistan": "아와미 무슬림연맹(AML)",
    "Jamhoori Wattan Party": "잠후리 와탄당(JWP)",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", nargs="?", type=Path, help="local copy of the pinned CSV")
    args = parser.parse_args()
    raw = (args.archive.read_bytes() if args.archive else
           subprocess.check_output(["curl", "--fail", "--silent", "--show-error", "--location", RETURNS]))
    totals = Counter()
    valid = Counter()
    strongest = defaultdict(Counter)
    def record(seat, party, votes):
        name = KOREAN.get(party, party)
        totals[name] += votes
        valid[seat] += votes
        # Independent candidates stand separately in a single-member constituency.
        strongest[seat][name] = max(votes, strongest[seat][name])

    for r in csv.DictReader(io.StringIO(raw.decode("utf-8-sig"))):
        if r["election_date"] != "2018-07-25" or r["assembly"] != "National" or r["candidate_votes"] in ("", "NA"):
            continue
        seat = int(r["constituency_number"].removeprefix("NA-"))
        record(seat, r["candidate_party"], int(r["candidate_votes"]))

    if len(valid) != 270 or sum(valid.values()) != 53_123_733:
        raise ValueError("July 2018 reference must contain all 270 contested seats")

    # ECP Form 47, seats postponed until October 14. Each independent is a separate row.
    # https://dnd.com.pk/wp-content/uploads/2018/10/Form-47-NA-60-Rawalpindi-IV.pdf
    # https://dnd.com.pk/wp-content/uploads/2018/10/Form-47-NA-103-Faisalabad-III.pdf
    delayed = {
        60: {"Independents": [405, 56, 817, 387], "Pakistan Awami League": [178],
             "Tehreek-e-Labbaik Pakistan": [2288], "Pakistan Muslim League (Nawaz)": [43836],
             "Pakistan Tehreek-e-Insaf": [44483]},
        103: {"Independents": [179, 322, 20242, 443, 547, 382], "Pakistan Peoples Party Parliamentarians": [25113],
              "Pakistan Muslim League (Nawaz)": [77539], "Pakistan Tehreek-e-Insaf": [66627],
              "Allah-o-Akbar Tehreek": [6976], "Tehreek-e-Labbaik Pakistan": [5518]},
    }
    for seat, returns in delayed.items():
        if seat in valid:
            raise ValueError(f"Duplicate reference for NA-{seat}")
        for party, counts in returns.items():
            for votes in counts:
                record(seat, party, votes)
    if valid[60] != 92_450 or valid[103] != 203_888:
        raise ValueError("October constituency totals disagree with Form 47")
    parties = [name for name, count in totals.most_common()]
    opening = [{"name": name, "votes": totals[name]} | ({"other": True} if name == "무소속" else {}) for name in parties]
    districts = []
    with (HERE / "pakistan_constituencies.csv").open() as f:
        for row in csv.DictReader(f):
            refs = [int(x) for x in row["reference_2018"].split()]
            if not refs or any(s not in valid for s in refs):
                raise ValueError(f"Missing returns for {row}")
            district_valid = sum(valid[s] for s in refs)
            scores = Counter()
            for s in refs:
                scores.update(strongest[s])
            districts.append({"number": int(row["number"]), "name": row["name"], "province": row["province"],
                              "region": row["map_region"], "votes": {name: scores[name] for name in parties if scores[name]},
                              "valid": district_valid})
    if [d["number"] for d in districts] != list(range(1, 267)):
        raise ValueError("Expected consecutive NA-1 through NA-266")
    dump = lambda obj: json.dumps(obj, ensure_ascii=False, separators=(", ", ": "))
    (WORLD / "pakistan_districts.json").write_text("[\n" + ",\n".join(" " + dump(d) for d in districts) + "\n]\n")
    path = WORLD / "elections.json"
    text = path.read_text()
    start = text.index('    "parties": [', text.index(' "PAK": {')) + len('    "parties": [')
    end = text.index('\n    ]', start)
    path.write_text(text[:start] + "\n" + ",\n".join("     "+dump(row) for row in opening) + text[end:])
    print(f"{len(parties)} party/independent groups, {sum(totals.values()):,} historical votes, {len(districts)} constituencies")


if __name__ == "__main__":
    main()
