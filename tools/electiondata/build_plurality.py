"""Compile UK 2019 notional and Canadian 2021 returns for the 2013 and 2023 electoral maps.

Arguments: UK Parliament candidacies CSV, ONS constituency centre JSON, Natural Earth
v5.1.2 ADM1 GeoJSON, Elections Canada 44th election Table 12, 343-FED transposition CSV.
"""
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

DEST = Path(__file__).resolve().parents[2] / "simulation/src/main/resources/world"
UK = {"Con": "보수당", "Lab": "노동당", "SNP": "스코틀랜드 국민당", "LD": "자유민주당",
      "DUP": "민주연합당", "SF": "신페인", "PC": "플라이드 컴리", "SDLP": "사회민주노동당",
      "APNI": "동맹당", "Green": "녹색당", "BRX": "리폼 UK", "UUP": "얼스터 연합당"}
CA = {"Libéral": "자유당", "Conservateur": "보수당", "NPD-Nouveau Parti démocratique": "신민주당",
      "Bloc Québécois": "퀘벡 블록", "Parti Vert": "녹색당", "Parti populaire - PPC": "캐나다 인민당",
      "Indépendant(e)": "무소속", "Aucune appartenance": "무소속"}
CA343 = {"Liberal Party of Canada": "자유당", "Conservative Party of Canada": "보수당",
         "New Democratic Party": "신민주당", "Bloc Québécois": "퀘벡 블록", "Green Party of Canada": "녹색당",
         "People's Party of Canada": "캐나다 인민당", "Independent": "무소속", "No Affiliation": "무소속"}
CAN_REGIONS = dict(zip("10 11 12 13 24 35 46 47 48 59 60 61 62".split(),
                        [686, 687, 685, 684, 683, 682, 630, 631, 632, 633, 636, 635, 634]))
UK_SOURCE = "https://electionresults.parliament.uk/general-elections/5/candidacies.csv"
UK_CENTRES = "https://services1.arcgis.com/ESMARspQHYMw9BZ9/arcgis/rest/services/Westminster_Parliamentary_Constituencies_July_2024_Boundaries_UK_BGC/FeatureServer/0"
CA_OLD = "https://www.elections.ca/res/rep/off/ovr2021app/53/data_donnees/table_tableau12.csv"
CA_NEW = "https://elections.ca/res/rep/tra/2023rep/csv/Transposition_343_FED_Vote_Count.csv"


def read(path):
    with open(path, encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def write(name, source, rows, expected):
    assert len(rows) == expected and all(r["parties"] for r in rows)
    output = dict(source=source, districts=sorted(rows, key=lambda r: (r["region"], r["name"])))
    (DEST / (name + ".json")).write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


def inside(x, y, ring):
    odd = False
    px, py = ring[-1]
    for nx, ny in ring:
        if (ny > y) != (py > y) and x < (px - nx) * (y - ny) / (py - ny) + nx:
            odd = not odd
        px, py = nx, ny
    return odd


def uk_regions(points, natural):
    shapes = []
    for f in natural["features"]:
        p = f["properties"]
        if p["adm0_a3"] != "GBR":
            continue
        geom = f["geometry"]
        for polygon in (geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]):
            xs, ys = zip(*polygon[0])
            shapes.append((min(xs), max(xs), min(ys), max(ys), polygon, p))
    ids = {}
    for feature in points["features"]:
        a = feature["attributes"]
        x, y = a["LONG"], a["LAT"]
        hit = next((p for xmin, xmax, ymin, ymax, poly, p in shapes if xmin <= x <= xmax and ymin <= y <= ymax
                    and inside(x, y, poly[0]) and not any(inside(x, y, hole) for hole in poly[1:])), None)
        if hit is None:
            unit = {"E":"England", "W":"Wales", "S":"Scotland", "N":"Northern Ireland"}[a["PCON24CD"][0]]
            hit = min((p for *_, p in shapes if p["geonunit"] == unit),
                      key=lambda p: (p["longitude"] - x) ** 2 * .33 + (p["latitude"] - y) ** 2)
        ids[a["PCON24CD"]] = hit["adm1_code"]
    return ids


def district(name, region):
    return dict(name=name, region=region, parties=[], votes=[])


def populate(row, party, votes):
    if party and votes > 0:
        row["parties"].append(party)
        row["votes"].append(votes)


def main():
    uk, points, natural, old, new = sys.argv[1:]
    areas = uk_regions(json.loads(Path(points).read_text()), json.loads(Path(natural).read_text()))
    by_code = {}
    for r in read(uk):
        assert r["General election is notional"] == "true"
        code = r["Constituency geographic code"]
        row = by_code.setdefault(code, district(r["Constituency name"], areas[code]))
        name = "의장" if r["Candidate is standing as Commons Speaker"] == "true" else "무소속" if r["Candidate is standing as independent"] == "true" else UK.get(r["Main party abbreviation"])
        populate(row, name, int(r["Candidate vote count"]))
    write("british_constituencies", [UK_SOURCE, UK_CENTRES], list(by_code.values()), 650)
    by_code = {}
    for r in read(old):
        code = r["Electoral District Number/Numéro de circonscription"]
        row = by_code.setdefault(code, district(r["Electoral District Name/Nom de circonscription"], f"CAN-{CAN_REGIONS[code[:2]]}"))
        candidate = r["Candidate/Candidat"]
        party = next((name for key, name in CA.items() if candidate.endswith(key)), None)
        populate(row, party, int(r["Votes Obtained/Votes obtenus"]))
    write("canadian_2013_order", [CA_OLD], list(by_code.values()), 338)
    rows = []
    for r in read(new):
        row = district(r["FED_EN"], f"CAN-{CAN_REGIONS[r['PROV_ID']]}")
        for field, value in r.items():
            if value.strip() not in {"NA", ""} and field.split("/")[0] in CA343:
                populate(row, CA343[field.split("/")[0]], int(value))
        rows.append(row)
    write("canadian_2023_order", [CA_NEW], rows, 343)


if __name__ == "__main__":
    main()
