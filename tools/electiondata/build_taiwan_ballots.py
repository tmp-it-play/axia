"""Extract pre-scenario CEC 2020 constituency, indigenous and national list votes."""
import csv
import io
import json
from pathlib import Path
import sys
from urllib.parse import quote
from urllib.request import urlopen
import zipfile

URL = "https://data.cec.gov.tw/" + quote("選舉資料庫/votedata.zip")
archive = zipfile.ZipFile(sys.argv[1] if len(sys.argv) > 1 else io.BytesIO(urlopen(URL).read()))
REGIONS = dict(zip("63000 65000 68000 66000 67000 64000 10002 10004 10005 10007 10008 10009 10010 10013 10014 10015 10016 10017 10018 10020 09007 09020".split(),
                   [1166, 1167, 1168, 1174, 1160, 1156, 1163, 1162, 1165, 1169, 1173, 1176, 1170, 1158, 1177, 1172, 3414, 1164, 1161, 1171, "LIENCHIANG", 3415]))
PARTIES = {"中國國民黨": "중국국민당", "民主進步黨": "민주진보당", "台灣民眾黨": "대만민중당", "時代力量": "시대역량", "無黨籍及未經政黨推薦": "무소속"}
def records(election, file):
    path = f"votedata/votedata/voteData/2020總統立委/{election}/{file}.csv"
    return csv.reader(archive.read(path).decode("utf-8-sig").splitlines())
ballots = {}
lists = {}
for kind, key in [("區域立委", ""), ("山地立委", "산지 원주민"), ("平地立委", "평지 원주민"), ("不分區政黨", "list")]:
    parties = dict(records(kind, "elpaty"))
    candidates = {(tuple(r[:3]), r[5]): PARTIES.get(parties[r[7]], parties[r[7]]) for r in records(kind, "elcand")}
    for r in records(kind, "elctks"):
        if r[3:6] != ["000", "0000", "0000"]:
            continue
        if key:
            if r[:3] != ["00", "000", "00"]:
                continue
            code = ("00", "000", "01")
        else:
            code = tuple(r[:3])
            if (code, r[6]) not in candidates:
                continue
        party = candidates[(code, r[6])]
        if key == "list":
            lists[party] = int(r[7])
        else:
            area = key or f"TWN-{REGIONS[r[0] + r[1]]}/{int(r[2])}"
            ballots.setdefault(area, []).append({"party": party, "votes": int(r[7])})
assert len(ballots) == 75 and len(lists) == 19
result = {"source": "Central Election Commission, Taiwan. 2020 legislative election. Government Data Open License v1.0, https://data.gov.tw/license", "url": URL, "lists": lists, "districts": ballots}
out = Path(__file__).resolve().parents[2] / "simulation/src/main/resources/world/taiwan_ballots.json"
out.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
