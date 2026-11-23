"""플레이 가능 국가의 1차 행정구역별 면적과 도시 인구(regions.json)를 만든다.

- 면적: 지도 형상(world-map.bin)의 구면 근사 면적
- 도시 인구: Natural Earth 10m 거주지(populated places)의 POP_MAX를 행정구역 다각형 안에 들어가는 점끼리 합친다.
- 시뮬레이션은 도시 인구 비중으로 도시 인구를, 면적 비중으로 농촌 인구를 나눠 지역 인구를 추정한다.

사용법: python3 tools/geodata/build_regions.py [--cache-dir <원본 저장 폴더>]
"""

import argparse
import json
import subprocess
from pathlib import Path

from build_geography import read_map, ring_area_centroid

ROOT = Path(__file__).resolve().parents[2]
WORLD_IN = ROOT / "simulation/src/main/resources/world/world.json"
PLAYABLE_IN = ROOT / "simulation/src/main/resources/world/playable.json"
OUT = ROOT / "simulation/src/main/resources/world/regions.json"
PLACES_URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_populated_places_simple.geojson"


def download(url, cache_file):
    if cache_file is not None and cache_file.exists():
        return cache_file.read_bytes()
    data = subprocess.run(["curl", "-sfL", "-m", "300", url], check=True, capture_output=True).stdout
    if cache_file is not None:
        cache_file.write_bytes(data)
    return data


def inside(x, y, ring):
    hit = False
    for (x0, y0), (x1, y1) in zip(ring, ring[1:] + ring[:1]):
        if (y0 > y) != (y1 > y) and x < (x1 - x0) * (y - y0) / (y1 - y0) + x0:
            hit = not hit
    return hit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)
    world = json.loads(WORLD_IN.read_text(encoding="utf-8"))
    playable = set(json.loads(PLAYABLE_IN.read_text(encoding="utf-8"))["countries"])
    owner = {r["id"]: r["country"] for r in world["regions"]}
    regions, _ = read_map()
    targets = []
    for region_id, rings in regions:
        if owner[region_id] not in playable:
            continue
        xs = [p[0] for ring in rings for p in ring]
        ys = [p[1] for ring in rings for p in ring]
        area = sum(ring_area_centroid(ring)[0] for ring in rings)
        targets.append([region_id, rings, (min(xs), min(ys), max(xs), max(ys)), area, 0.0])
    places = json.loads(download(PLACES_URL, args.cache_dir / "places.geojson" if args.cache_dir else None))["features"]
    for feature in places:
        x, y = feature["geometry"]["coordinates"][:2]
        population = feature["properties"].get("pop_max") or 0
        if population <= 0:
            continue
        for target in targets:
            x0, y0, x1, y1 = target[2]
            if x0 <= x <= x1 and y0 <= y <= y1 and any(inside(x, y, ring) for ring in target[1]):
                target[4] += population
                break
    out = {
        region_id: {"areaKm2": round(area), "cityPopulation": round(city)}
        for region_id, _, _, area, city in sorted(targets, key=lambda t: t[0])
    }
    OUT.write_text(
        json.dumps(
            {
                "sourceNote": "면적은 world-map.bin 구면 근사, 도시 인구는 Natural Earth 10m populated places의 POP_MAX 합(광역 도시권 인구라 겹칠 수 있다). 퍼블릭 도메인.",
                "regions": out,
            },
            ensure_ascii=False,
            indent=1,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"{len(out)} regions, {sum(1 for v in out.values() if v['cityPopulation'] > 0)} with cities")


if __name__ == "__main__":
    main()
