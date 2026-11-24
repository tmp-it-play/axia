"""2022년 프랑스 총선 1차 내무부 개표를 선거구·정치계열별로 집계한다.

원표: https://www.data.gouv.fr/datasets/elections-legislatives-des-12-et-19-juin-2022-resultats-du-1er-tour
행정구역 식별자는 지도 생성에 사용한 Natural Earth admin-1 GeoJSON을 따른다.
"""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path

WORLD = Path(__file__).resolve().parents[2] / "simulation/src/main/resources/world"
OVERSEAS = {
    "ZA": "GP", "ZB": "MQ", "ZC": "GF", "ZD": "RE", "ZM": "YT",
    "ZN": "NC", "ZP": "PF", "ZS": "PM", "ZW": "WF", "ZX": "MF",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="내무부의 세미콜론 구분 선거구별 원표")
    parser.add_argument("admin1", type=Path, help="Natural Earth admin-1 GeoJSON")
    parser.add_argument("--output", type=Path, default=WORLD / "france_districts.json")
    args = parser.parse_args()
    features = json.loads(args.admin1.read_text())["features"]
    codes = {}
    for feature in features:
        props = feature["properties"]
        if props["adm0_a3"] == "FRA":
            codes[props["iso_3166_2"].removeprefix("FR-")] = props["adm1_code"]
    regions = {r["id"]: r["name"] for r in json.loads((WORLD / "world.json").read_text())["regions"]}
    districts = []
    with args.results.open(encoding="latin1", newline="") as stream:
        rows = csv.reader(stream, delimiter=";")
        next(rows)
        for row in rows:
            department, name, number = row[:3]
            votes = Counter()
            for bloc, count in zip(row[23::9], row[24::9], strict=True):
                votes[bloc] += int(count)
            valid = int(row[16])
            if sum(votes.values()) != valid:
                raise ValueError(f"유효표 합계 불일치: {department}-{number}")
            region = codes.get(OVERSEAS.get(department, department))
            districts.append(dict(
                code=f"{department}-{number}", region=region or f"FR-{department}",
                area=regions.get(region, "재외 프랑스인" if department == "ZZ" else name),
                registered=int(row[5]), valid=valid, votes=votes,
            ))
    if len(districts) != 577 or len({r["code"] for r in districts}) != 577:
        raise ValueError("577개 고유 선거구가 필요합니다")
    args.output.write_text("[\n" + ",\n".join(json.dumps(r, ensure_ascii=False) for r in districts) + "\n]\n")


if __name__ == "__main__":
    main()
