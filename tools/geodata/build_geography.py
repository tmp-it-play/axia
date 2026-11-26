"""지도 형상 파일(world-map.bin)에서 국가별 지리 요약을 만든다.

- 중심점(면적 가중, 위경도), 면적(km²), 해안 접근 여부, 육상 인접국
- 인접은 서로 다른 나라의 행정구역 고리가 같은 꼭짓점을 공유하는지로 판단한다(형상은 공유 경계선으로 간소화돼 있다).
- 해안 접근은 그 나라 행정구역 꼭짓점이 해안선 경계에 있는지로 판단한다.
- 중심점은 본국 고리를 1,000km 단일 연결로 묶은 덩어리 중 면적이 가장 큰 덩어리로 구한다(알래스카, 해외 영토·도서 제외). 인접과 해안은 종속 영역(territory)을 뺀 본국 행정구역만 본다.

사용법: python3 tools/geodata/build_geography.py
"""

import json
import math
import struct
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAP_IN = ROOT / "desktopApp/src/main/resources/map/world-map.bin"
WORLD_IN = ROOT / "simulation/src/main/resources/world/world.json"
OUT = ROOT / "simulation/src/main/resources/world/geography.json"
EARTH_RADIUS_KM = 6371.0
BORDER_COAST = 0
BORDER_REGION = 2
LINK_KM = 1000.0


def distance_km(a, b):
    """두 (경도, 위도) 사이의 대권 거리."""
    lon1, lat1, lon2, lat2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(min(1.0, h)))


def read_utf(stream):
    (length,) = struct.unpack(">H", stream.read(2))
    return stream.read(length).decode("utf-8")


def read_points(stream):
    (count,) = struct.unpack(">i", stream.read(4))
    values = struct.unpack(f">{count * 2}f", stream.read(count * 8))
    return [(values[i * 2], values[i * 2 + 1]) for i in range(count)]


def key(point):
    return (round(point[0], 4), round(point[1], 4))


def read_map():
    with MAP_IN.open("rb") as stream:
        assert read_utf(stream) == "AXIA-MAP"
        (version,) = struct.unpack(">i", stream.read(4))
        assert version == 4, version
        (countries,) = struct.unpack(">i", stream.read(4))
        for _ in range(countries):
            read_utf(stream)
            stream.read(1)
        (region_count,) = struct.unpack(">i", stream.read(4))
        regions = []
        for _ in range(region_count):
            region_id = read_utf(stream)
            (ring_count,) = struct.unpack(">i", stream.read(4))
            regions.append((region_id, [read_points(stream) for _ in range(ring_count)]))
        (border_count,) = struct.unpack(">i", stream.read(4))
        coast = set()
        for _ in range(border_count):
            (kind,) = struct.unpack(">b", stream.read(1))
            if kind == BORDER_REGION:
                read_utf(stream)
            points = read_points(stream)
            if kind == BORDER_COAST:
                coast.update(key(point) for point in points)
    return regions, coast


def mainland(parts):
    """고리 중심점을 단일 연결로 묶고 면적 합이 가장 큰 덩어리를 돌려준다."""
    cluster = list(range(len(parts)))

    def find(i):
        while cluster[i] != i:
            cluster[i] = cluster[cluster[i]]
            i = cluster[i]
        return i

    for i in range(len(parts)):
        for j in range(i + 1, len(parts)):
            if distance_km(parts[i][1], parts[j][1]) <= LINK_KM:
                cluster[find(i)] = find(j)
    groups = defaultdict(list)
    for i, part in enumerate(parts):
        groups[find(i)].append(part)
    return max(groups.values(), key=lambda group: sum(a for a, _ in group))


def ring_area_centroid(ring):
    """경위도 고리의 구면 근사 면적(km²)과 중심점. 위도 코사인으로 경도 폭을 줄인다."""
    area = 0.0
    cx = cy = 0.0
    for (x0, y0), (x1, y1) in zip(ring, ring[1:] + ring[:1]):
        cross = x0 * y1 - x1 * y0
        area += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    if abs(area) < 1e-12:
        return 0.0, ring[0]
    centroid = (cx / (3 * area), cy / (3 * area))
    scale = (math.pi / 180 * EARTH_RADIUS_KM) ** 2 * math.cos(math.radians(centroid[1]))
    return abs(area) / 2 * scale, centroid


def main():
    world = json.loads(WORLD_IN.read_text(encoding="utf-8"))
    states = {country["id"] for country in world["countries"] if country["kind"] == "STATE"}
    owner = {region["id"]: region["country"] for region in world["regions"]}
    dependent = {region["id"] for region in world["regions"] if "territory" in region}
    regions, coast = read_map()
    vertex_owners = defaultdict(set)
    parts = defaultdict(list)
    coastal = set()
    for region_id, rings in regions:
        country = owner[region_id]
        for ring in rings:
            if region_id in dependent:
                continue
            parts[country].append(ring_area_centroid(ring))
            for point in ring:
                k = key(point)
                vertex_owners[k].add(country)
                if k in coast:
                    coastal.add(country)
    neighbors = defaultdict(set)
    for owners in vertex_owners.values():
        if len(owners) > 1:
            for a in owners:
                neighbors[a].update(owners - {a})
    entries = []
    for country in sorted(states):
        near = mainland(parts[country])
        area = sum(a for a, _ in parts[country])
        near_area = sum(a for a, _ in near)
        sx = sum(a * c[0] for a, c in near)
        sy = sum(a * c[1] for a, c in near)
        entries.append(
            {
                "id": country,
                "latitude": round(sy / near_area, 3),
                "longitude": round(sx / near_area, 3),
                "areaKm2": round(area),
                "coastal": country in coastal,
                "neighbors": sorted(n for n in neighbors[country] if n in states),
            },
        )
    OUT.write_text(
        json.dumps(
            {
                "sourceNote": "tools/geodata/build_geography.py가 world-map.bin(Natural Earth 10m 간소화)에서 계산했다. 면적은 구면 근사다.",
                "countries": entries,
            },
            ensure_ascii=False,
            indent=1,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"{len(entries)} states, {sum(len(e['neighbors']) for e in entries) // 2} borders, {len(coastal & states)} coastal")


if __name__ == "__main__":
    main()
