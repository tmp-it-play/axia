"""V-Dem Regimes of the World(Our World in Data 경유)로 모든 주권국의 2023년 체제 분류(regimes.json)를 만든다.

사용법: python3 tools/politicsdata/build_regimes.py [--cache-dir <원본 저장 폴더>]
"""

import argparse
import csv
import io
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORLD = ROOT / "simulation/src/main/resources/world/world.json"
OUT = ROOT / "simulation/src/main/resources/world/regimes.json"
URL = "https://ourworldindata.org/grapher/political-regime.csv?csvType=full&useColumnShortNames=true"
YEAR = "2023"
LABELS = {
    "Closed autocracy": "CLOSED_AUTOCRACY",
    "Electoral autocracy": "ELECTORAL_AUTOCRACY",
    "Electoral democracy": "ELECTORAL_DEMOCRACY",
    "Liberal democracy": "LIBERAL_DEMOCRACY",
}
# OWID 코드와 우리 국가 코드가 다른 경우.
ALIASES = {"OWID_KOS": "KOS", "PSE": "PSX", "SSD": "SDS"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    cache = args.cache_dir / "political-regime.csv" if args.cache_dir else None
    if cache and cache.exists():
        text = cache.read_text(encoding="utf-8")
    else:
        text = subprocess.run(["curl", "-sfL", "-m", "120", URL], check=True, capture_output=True).stdout.decode("utf-8")
        if cache:
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_text(text, encoding="utf-8")
    states = {c["id"] for c in json.loads(WORLD.read_text(encoding="utf-8"))["countries"] if c["kind"] == "STATE"}
    regimes = {}
    for row in csv.DictReader(io.StringIO(text)):
        code = ALIASES.get(row["code"], row["code"])
        if row["year"] == YEAR and code in states and row["regime_row_owid"] in LABELS:
            regimes[code] = LABELS[row["regime_row_owid"]]
    OUT.write_text(
        json.dumps(
            {
                "sourceNote": "V-Dem Regimes of the World 2023(Our World in Data 경유, CC BY 4.0). 자료가 없는 소국·미승인국은 빠져 있다.",
                "regimes": dict(sorted(regimes.items())),
            },
            ensure_ascii=False,
            indent=1,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"{len(regimes)} / {len(states)} states, missing: {sorted(states - regimes.keys())}")


if __name__ == "__main__":
    main()
