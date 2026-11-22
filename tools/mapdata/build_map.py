"""Natural Earth 원본으로 세계 정의(world.json)와 지도 형상(world-map.bin)을 만든다.

사용법:
    python3 tools/mapdata/build_map.py --ne-dir <Natural Earth GeoJSON 폴더>

필요한 원본 (Natural Earth 5.1.2, geojson 폴더):
    ne_10m_admin_0_countries.geojson
    ne_10m_admin_1_states_provinces.geojson

지역 경계는 이웃 지역과 공유하는 경계선(arc) 단위로 간소화한다. 공유 경계선은 한 번만 간소화되므로
이웃한 두 지역 사이에 틈이나 겹침이 생기지 않는다.
"""

import argparse
import json
import struct
from collections import defaultdict
from pathlib import Path

FORMAT_NAME = "AXIA-MAP"
FORMAT_VERSION = 1
PALETTE_SIZE = 8
QUANTUM = 1e-6

BORDER_COAST = 0
BORDER_COUNTRY = 1
BORDER_REGION = 2

# 2024-01-01 실효 지배 기준 보정. 키는 Natural Earth ADM0_A3, 값은 소유 국가.
OWNER_OVERRIDES = {
    "SAH": "MAR",  # 서사하라: 대부분 모로코가 실효 지배
    "KAS": "IND",  # 시아첸 빙하: 인도가 실효 지배
    "USG": "USA",  # 관타나모 기지: 미국이 운영
    "PSX": "PSX",  # 팔레스타인: 독립된 단위로 유지
}

# 어느 국가도 소유하지 않는 지역.
UNCLAIMED = {"ATA", "PGA"}

# Natural Earth 한국어 이름의 오류 수정과 한국에서 통용되는 이름.
COUNTRY_NAME_OVERRIDES = {
    "CHN": "중국",
    "TWN": "대만",
    "PRK": "북한",
    "TUR": "튀르키예",
    "AUS": "호주",
    "PGA": "스프래틀리 군도",
}

ROOT = Path(__file__).resolve().parents[2]
WORLD_OUT = ROOT / "simulation/src/main/resources/world/world.json"
GEOMETRY_OUT = ROOT / "desktopApp/src/main/resources/map/world-map.bin"


def first_name(properties, *keys):
    for key in keys:
        if properties.get(key):
            return properties[key]
    return None


def country_name(properties):
    return COUNTRY_NAME_OVERRIDES.get(properties["ADM0_A3"]) or first_name(properties, "NAME_KO", "NAME")


def resolve_owners(admin0):
    by_admin = {feature["properties"]["ADMIN"]: feature["properties"] for feature in admin0}
    owners = {}
    for feature in admin0:
        properties = feature["properties"]
        code = properties["ADM0_A3"]
        if code in OWNER_OVERRIDES:
            owners[code] = OWNER_OVERRIDES[code]
        elif code in UNCLAIMED:
            owners[code] = code
        else:
            sovereign = by_admin.get(properties["SOVEREIGNT"])
            owners[code] = sovereign["ADM0_A3"] if sovereign else code
    return owners


def polygons_of(geometry):
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    raise ValueError(f"Unsupported geometry: {geometry['type']}")


def quantize(point):
    return (round(point[0] / QUANTUM), round(point[1] / QUANTUM))


def clean_ring(raw):
    ring = []
    for point in raw:
        q = quantize(point)
        if not ring or ring[-1] != q:
            ring.append(q)
    while len(ring) > 1 and ring[0] == ring[-1]:
        ring.pop()
    return ring if len(set(ring)) >= 3 else None


def signed_area(ring):
    area = 0
    for i, (x1, y1) in enumerate(ring):
        x2, y2 = ring[(i + 1) % len(ring)]
        area += x1 * y2 - x2 * y1
    return area


def oriented(ring, exterior):
    """외곽 고리는 반시계, 구멍은 시계 방향으로 맞춘다. 같은 국가의 지역을 non-zero 규칙으로 함께 채우기 위함이다."""
    counter_clockwise = signed_area(ring) > 0
    return ring if counter_clockwise == exterior else ring[::-1]


def load_rings(admin1, owners):
    """모든 지역 고리를 (지역 인덱스, 고리) 목록으로 읽는다."""
    regions = []
    rings = []
    for feature in sorted(admin1, key=lambda item: item["properties"]["adm1_code"]):
        properties = feature["properties"]
        index = len(regions)
        regions.append({"id": properties["adm1_code"], "owner": owners[properties["adm0_a3"]]})
        for polygon in polygons_of(feature["geometry"]):
            for ring_index, raw in enumerate(polygon):
                ring = clean_ring(raw)
                if ring is not None:
                    rings.append((index, oriented(ring, exterior=ring_index == 0)))
    return regions, rings


def find_junctions(rings):
    neighbors = defaultdict(set)
    for _, ring in rings:
        count = len(ring)
        for i, point in enumerate(ring):
            neighbors[point].add(ring[i - 1])
            neighbors[point].add(ring[(i + 1) % count])
    return {point for point, adjacent in neighbors.items() if len(adjacent) != 2}


def split_ring(ring, junctions):
    """고리를 교차점 사이의 경계선으로 나눈다. 교차점이 없으면 가장 작은 꼭짓점에서 시작하는 닫힌 선 하나가 된다."""
    starts = [i for i, point in enumerate(ring) if point in junctions]
    if not starts:
        start = min(range(len(ring)), key=lambda i: ring[i])
        rotated = ring[start:] + ring[:start]
        return [rotated + [rotated[0]]]
    rotated = ring[starts[0]:] + ring[:starts[0]]
    offsets = [i - starts[0] for i in starts] + [len(ring)]
    closed = rotated + [rotated[0]]
    return [closed[offsets[k]:offsets[k + 1] + 1] for k in range(len(offsets) - 1)]


def perpendicular_distance(point, start, end):
    (px, py), (ax, ay), (bx, by) = point, start, end
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
    return abs(dy * px - dx * py + bx * ay - by * ax) / (dx * dx + dy * dy) ** 0.5


def farthest(points, first, last):
    best_index, best_distance = -1, -1.0
    for i in range(first + 1, last):
        distance = perpendicular_distance(points[i], points[first], points[last])
        if distance > best_distance:
            best_index, best_distance = i, distance
    return best_index, best_distance


def douglas_peucker(points, tolerance):
    """두 끝점을 유지하는 Douglas-Peucker. 내부 점이 있으면 가장 먼 점 하나는 남겨 모양이 사라지지 않게 한다."""
    if len(points) <= 2:
        return points
    keep = {0, len(points) - 1}
    pivot, _ = farthest(points, 0, len(points) - 1)
    keep.add(pivot)
    stack = [(0, pivot), (pivot, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        if last - first < 2:
            continue
        index, distance = farthest(points, first, last)
        if distance > tolerance:
            keep.add(index)
            stack.append((first, index))
            stack.append((index, last))
    return [points[i] for i in sorted(keep)]


def simplify_loop(points, tolerance):
    """시작과 끝이 같은 닫힌 선은 시작점에서 가장 먼 점으로 둘로 나눠 간소화한다."""
    origin = points[0]
    split = max(range(1, len(points) - 1), key=lambda i: (points[i][0] - origin[0]) ** 2 + (points[i][1] - origin[1]) ** 2)
    first = douglas_peucker(points[: split + 1], tolerance)
    second = douglas_peucker(points[split:], tolerance)
    return first + second[1:]


def canonical(segment):
    reverse = segment[::-1]
    return (tuple(segment), False) if tuple(segment) <= tuple(reverse) else (tuple(reverse), True)


def simplify_rings(regions, rings, tolerance):
    junctions = find_junctions(rings)
    arcs = {}
    arc_sides = defaultdict(list)
    region_rings = defaultdict(list)
    for region_index, ring in rings:
        rebuilt = []
        for segment in split_ring(ring, junctions):
            key, reversed_ = canonical(segment)
            if key not in arcs:
                points = list(key)
                arcs[key] = simplify_loop(points, tolerance) if points[0] == points[-1] else douglas_peucker(points, tolerance)
            arc_sides[key].append(region_index)
            simplified = arcs[key][::-1] if reversed_ else arcs[key]
            rebuilt.extend(simplified if not rebuilt else simplified[1:])
        if rebuilt[0] == rebuilt[-1]:
            rebuilt.pop()
        region_rings[region_index].append(rebuilt)

    borders = []
    for key, sides in arc_sides.items():
        owners = {regions[index]["owner"] for index in sides}
        if len(sides) == 1:
            kind = BORDER_COAST
        elif len(owners) > 1:
            kind = BORDER_COUNTRY
        else:
            kind = BORDER_REGION
        if kind == BORDER_REGION and len(set(sides)) == 1:
            continue
        borders.append((kind, arcs[key], tuple(sorted(owners))))
    borders.sort(key=lambda border: (border[0], border[1]))
    return region_rings, borders


def assign_colors(borders, owners):
    neighbors = defaultdict(set)
    for kind, _, sides in borders:
        if kind == BORDER_COUNTRY:
            for a in sides:
                for b in sides:
                    if a != b:
                        neighbors[a].add(b)
    usage = [0] * PALETTE_SIZE
    colors = {}
    for owner in sorted(owners, key=lambda code: (-len(neighbors[code]), code)):
        taken = {colors[n] for n in neighbors[owner] if n in colors}
        free = [index for index in range(PALETTE_SIZE) if index not in taken]
        if not free:
            raise RuntimeError(f"Palette too small for {owner}")
        choice = min(free, key=lambda index: (usage[index], index))
        colors[owner] = choice
        usage[choice] += 1
    return colors


def write_utf(stream, text):
    encoded = text.encode("utf-8")
    stream.write(struct.pack(">H", len(encoded)))
    stream.write(encoded)


def write_points(stream, points):
    stream.write(struct.pack(">i", len(points)))
    stream.write(struct.pack(f">{len(points) * 2}f", *[value * QUANTUM for point in points for value in point]))


def write_geometry(regions, region_rings, borders, colors):
    GEOMETRY_OUT.parent.mkdir(parents=True, exist_ok=True)
    with GEOMETRY_OUT.open("wb") as stream:
        write_utf(stream, FORMAT_NAME)
        stream.write(struct.pack(">i", FORMAT_VERSION))
        stream.write(struct.pack(">i", len(colors)))
        for owner in sorted(colors):
            write_utf(stream, owner)
            stream.write(struct.pack(">b", colors[owner]))
        stream.write(struct.pack(">i", len(regions)))
        for index, region in enumerate(regions):
            write_utf(stream, region["id"])
            stream.write(struct.pack(">i", len(region_rings[index])))
            for ring in region_rings[index]:
                write_points(stream, ring)
        stream.write(struct.pack(">i", len(borders)))
        for kind, points, _ in borders:
            stream.write(struct.pack(">b", kind))
            write_points(stream, points)


def write_world(admin0, admin1, owners):
    admin0_by_code = {feature["properties"]["ADM0_A3"]: feature["properties"] for feature in admin0}
    used_owners = sorted({owners[feature["properties"]["adm0_a3"]] for feature in admin1})
    countries = [
        {
            "id": code,
            "name": country_name(admin0_by_code[code]),
            "kind": "UNCLAIMED" if code in UNCLAIMED else "STATE",
        }
        for code in used_owners
    ]
    territory_codes = sorted(
        {
            feature["properties"]["adm0_a3"]
            for feature in admin1
            if owners[feature["properties"]["adm0_a3"]] != feature["properties"]["adm0_a3"]
        },
    )
    territories = [
        {"id": code, "name": country_name(admin0_by_code[code]), "owner": owners[code]}
        for code in territory_codes
    ]
    regions = []
    for feature in sorted(admin1, key=lambda item: item["properties"]["adm1_code"]):
        properties = feature["properties"]
        region = {
            "id": properties["adm1_code"],
            "name": first_name(properties, "name_ko", "name", "name_en", "gn_name", "woe_name")
            or country_name(admin0_by_code[owners[properties["adm0_a3"]]]),
            "country": owners[properties["adm0_a3"]],
        }
        if properties["adm0_a3"] in territory_codes:
            region["territory"] = properties["adm0_a3"]
        regions.append(region)
    WORLD_OUT.parent.mkdir(parents=True, exist_ok=True)
    WORLD_OUT.write_text(
        json.dumps({"countries": countries, "territories": territories, "regions": regions}, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8",
    )
    return used_owners, countries, territories, regions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ne-dir", required=True, type=Path)
    parser.add_argument("--tolerance", type=float, default=0.03, help="간소화 허용 오차(도)")
    args = parser.parse_args()

    admin0 = json.loads((args.ne_dir / "ne_10m_admin_0_countries.geojson").read_text(encoding="utf-8"))["features"]
    admin1 = json.loads((args.ne_dir / "ne_10m_admin_1_states_provinces.geojson").read_text(encoding="utf-8"))["features"]
    owners = resolve_owners(admin0)

    regions, rings = load_rings(admin1, owners)
    region_rings, borders = simplify_rings(regions, rings, args.tolerance / QUANTUM)
    used_owners, countries, territories, world_regions = write_world(admin0, admin1, owners)
    colors = assign_colors(borders, used_owners)
    write_geometry(regions, region_rings, borders, colors)

    ring_points = sum(len(ring) for rings_of_region in region_rings.values() for ring in rings_of_region)
    border_points = sum(len(points) for _, points, _ in borders)
    print(f"countries={len(countries)} territories={len(territories)} regions={len(world_regions)}")
    print(f"ring points={ring_points} borders={len(borders)} border points={border_points}")
    print(f"world: {WORLD_OUT.relative_to(ROOT)}")
    print(f"geometry: {GEOMETRY_OUT.relative_to(ROOT)} ({GEOMETRY_OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
