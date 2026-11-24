"""2021 Bundestag first/second votes, recast by the Federal Returning Officer on the 2025 boundaries."""
import csv
import json
import sys
from pathlib import Path
from urllib.request import urlopen

URL = "https://www.bundeswahlleiterin.de/dam/jcr/201df610-802b-4121-9e6b-5d04504df5c8/btwkr25_umrechnung_btw21.csv"
LANDS = dict(zip("SH MV HH NI HB BB ST BE NW SN HE TH RP BY BW SL".split(),
                 [1579, 3488, 1578, 1576, 1575, 3487, 1600, 1599, 1572, 1601, 1574, 1577, 1580, 1591, 1573, 1581]))
PARTIES = {"SPD": "사회민주당", "CDU": "기독민주연합", "CSU": "기독사회연합", "FDP": "자유민주당",
           "DIE LINKE": "좌파당", "AfD": "독일을 위한 대안", "GRÜNE": "녹색당", "SSW": "남슐레스비히 유권자연합"}
raw = Path(sys.argv[1]).read_bytes() if len(sys.argv) > 1 else urlopen(URL).read()
rows = list(csv.reader(raw.decode("utf-8-sig").splitlines(), delimiter=";"))
header = next(i for i, row in enumerate(rows) if row[0] == "Wkr-Nr.")
columns = {name: rows[header].index(code) for code, name in PARTIES.items()}
districts = []
for row in rows[header + 2:]:
    if not row[0].isdigit() or not 1 <= int(row[0]) <= 299 or row[1] not in LANDS:
        continue
    districts.append(dict(number=int(row[0]), name=row[2], region=f"DEU-{LANDS[row[1]]}",
                          first=[int(row[i] or 0) for i in columns.values()],
                          second=[int(row[i + 1] or 0) for i in columns.values()]))
assert [d["number"] for d in districts] == list(range(1, 300))
data = dict(source="Die Bundeswahlleiterin, Wiesbaden 2024. Ergebnisse 2021, umgerechnet auf die Wahlkreise 2025.",
            url=URL, license="Datenlizenz Deutschland – Namensnennung – Version 2.0, https://www.govdata.de/dl-de/by-2-0",
            parties=list(columns), districts=districts)
out = Path(__file__).resolve().parents[2] / "simulation/src/main/resources/world/german_districts.json"
out.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")
