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
import heapq
import json
import math
import statistics
import struct
from collections import defaultdict
from pathlib import Path

FORMAT_NAME = "AXIA-MAP"
FORMAT_VERSION = 3
PALETTE_SIZE = 8
QUANTUM = 1e-6
LABEL_PRECISION = 0.02
LABEL_CANDIDATES = 6
CURVE_GRID = 44
CURVE_BINS = 20
CURVE_MIN_SAMPLES = 8
CURVE_MAX_ANGLE = math.radians(40)
CURVE_MAX_SAG = 0.12
CURVE_END_TRIM = 0.08

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
        owner = owners[properties["adm0_a3"]]
        regions.append({"id": properties["adm1_code"], "owner": owner, "core": owner == properties["adm0_a3"]})
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
    adjacency = set()
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
        if kind == BORDER_REGION:
            adjacency.add(tuple(sorted(set(sides))))
    borders.sort(key=lambda border: (border[0], border[1]))
    return region_rings, borders, adjacency


def to_degrees(ring):
    return [(x * QUANTUM, y * QUANTUM) for x, y in ring]


def area_and_centroid(rings):
    """방향이 맞춰진 고리들(외곽 +, 구멍 -)의 평면 면적과 무게중심."""
    area = cx = cy = 0.0
    for ring in rings:
        for i, (x1, y1) in enumerate(ring):
            x2, y2 = ring[(i + 1) % len(ring)]
            cross = x1 * y2 - x2 * y1
            area += cross
            cx += (x1 + x2) * cross
            cy += (y1 + y2) * cross
    area /= 2.0
    if area == 0:
        x, y = rings[0][0]
        return 0.0, (x, y)
    return area, (cx / (6.0 * area), cy / (6.0 * area))


def segment_distance_squared(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx != 0 or dy != 0:
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        ax, ay = ax + dx * t, ay + dy * t
    return (px - ax) ** 2 + (py - ay) ** 2


def signed_distance(x, y, rings):
    inside = False
    nearest = math.inf
    for ring in rings:
        j = len(ring) - 1
        for i in range(len(ring)):
            ax, ay = ring[i]
            bx, by = ring[j]
            if (ay > y) != (by > y) and x < (bx - ax) * (y - ay) / (by - ay) + ax:
                inside = not inside
            nearest = min(nearest, segment_distance_squared(x, y, ax, ay, bx, by))
            j = i
    distance = math.sqrt(nearest)
    return distance if inside else -distance


def polylabel(rings, precision):
    """다각형 안에서 경계와 가장 먼 점(pole of inaccessibility)과 그 거리."""
    xs = [x for ring in rings for x, _ in ring]
    ys = [y for ring in rings for _, y in ring]
    min_x, min_y, max_x, max_y = min(xs), min(ys), max(xs), max(ys)
    cell_size = min(max_x - min_x, max_y - min_y)
    if cell_size == 0:
        return (min_x, min_y), 0.0

    def cell(x, y, half):
        distance = signed_distance(x, y, rings)
        return (-(distance + half * math.sqrt(2)), x, y, half, distance)

    _, (cx, cy) = area_and_centroid(rings)
    best = cell(cx, cy, 0)
    box_center = cell((min_x + max_x) / 2, (min_y + max_y) / 2, 0)
    if box_center[4] > best[4]:
        best = box_center
    queue = []
    half = cell_size / 2
    x = min_x
    while x < max_x:
        y = min_y
        while y < max_y:
            heapq.heappush(queue, cell(x + half, y + half, half))
            y += cell_size
        x += cell_size
    while queue:
        current = heapq.heappop(queue)
        if current[4] > best[4]:
            best = current
        if -current[0] - best[4] <= precision:
            continue
        half = current[3] / 2
        for dx in (-half, half):
            for dy in (-half, half):
                heapq.heappush(queue, cell(current[1] + dx, current[2] + dy, half))
    return (best[1], best[2]), best[4]


def crossings(arcs, axis, at):
    """국가 외곽 경계선들이 수평선(axis=1) 또는 수직선(axis=0)과 만나는 좌표들."""
    other = 1 - axis
    values = []
    for points in arcs:
        for (ax, ay), (bx, by) in zip(points, points[1:]):
            a, b = (ax, ay), (bx, by)
            if (a[axis] > at) != (b[axis] > at):
                values.append(a[other] + (at - a[axis]) * (b[other] - a[other]) / (b[axis] - a[axis]))
    return values


def span(values, center, fallback):
    lower = max((value for value in values if value <= center), default=center - fallback)
    upper = min((value for value in values if value >= center), default=center + fallback)
    return lower, upper


def compute_labels(regions, region_rings, adjacency, borders):
    """국가마다 본토(본국 지역 중 가장 넓은 연결 영역) 안쪽의 이름 표기 위치와 그 위치를 지나는 가로, 세로 폭을 구한다."""
    parent = list(range(len(regions)))

    def find(index):
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    for a, b in adjacency:
        parent[find(a)] = find(b)

    shapes = {}
    for index in range(len(regions)):
        rings = [to_degrees(ring) for ring in region_rings[index]]
        if not rings:
            continue
        area, centroid = area_and_centroid(rings)
        shapes[index] = {
            "rings": rings,
            "area": abs(area),
            "weighted": abs(area) * math.cos(math.radians(centroid[1])),
            "centroid": centroid,
        }

    components = defaultdict(list)
    for index in shapes:
        components[(regions[index]["owner"], find(index))].append(index)
    main_components = {}
    for (owner, _), members in components.items():
        core = any(regions[index]["core"] for index in members)
        weight = (core, sum(shapes[index]["weighted"] for index in members))
        if owner not in main_components or weight > main_components[owner][0]:
            main_components[owner] = (weight, members)

    outline = defaultdict(list)
    for kind, points, owners in borders:
        if kind != BORDER_REGION:
            for owner in owners:
                outline[owner].append(to_degrees(points))

    labels = {}
    for owner, (_, members) in main_components.items():
        total = sum(shapes[index]["area"] for index in members) or 1.0
        cx = sum(shapes[index]["centroid"][0] * shapes[index]["area"] for index in members) / total
        cy = sum(shapes[index]["centroid"][1] * shapes[index]["area"] for index in members) / total
        nearest = sorted(
            members,
            key=lambda index: (shapes[index]["centroid"][0] - cx) ** 2 + (shapes[index]["centroid"][1] - cy) ** 2,
        )[:LABEL_CANDIDATES]
        best = None
        for index in nearest:
            point, radius = polylabel(shapes[index]["rings"], LABEL_PRECISION)
            score = math.hypot(point[0] - cx, point[1] - cy) - 2 * radius
            if best is None or score < best[0]:
                best = (score, point)
        x, y = best[1]
        left, right = span(crossings(outline[owner], 1, y), x, 0.5)
        bottom, top = span(crossings(outline[owner], 0, x), y, 0.5)
        fallback = (
            (left, miller_y(y)),
            ((left + right) / 2, miller_y(y)),
            (right, miller_y(y)),
            miller_y(bottom) - miller_y(top),
        )
        labels[owner] = label_curve([shapes[index]["rings"] for index in members]) or fallback
    return labels


def miller_y(latitude):
    """desktopApp의 MapProjection.y와 같은 밀러 도법. 아래로 갈수록 커진다."""
    phi = math.radians(max(-89.9, min(89.9, latitude)))
    return -1.25 * math.log(math.tan(math.pi / 4 + 0.4 * phi)) * 180 / math.pi


def solve_quadratic_fit(points):
    """가중치가 있는 점 (t, s, w)에 s = a t^2 + b t + c를 최소제곱으로 맞춘다."""
    sums = [[0.0] * 4 for _ in range(3)]
    for t, value, weight in points:
        basis = (t * t, t, 1.0)
        for row in range(3):
            for column in range(3):
                sums[row][column] += weight * basis[row] * basis[column]
            sums[row][3] += weight * basis[row] * value
    for pivot in range(3):
        best = max(range(pivot, 3), key=lambda row: abs(sums[row][pivot]))
        sums[pivot], sums[best] = sums[best], sums[pivot]
        if abs(sums[pivot][pivot]) < 1e-12:
            return None
        for row in range(3):
            if row != pivot:
                factor = sums[row][pivot] / sums[pivot][pivot]
                sums[row] = [a - factor * b for a, b in zip(sums[row], sums[pivot])]
    return tuple(sums[row][3] / sums[row][row] for row in range(3))


def label_curve(region_ring_sets):
    """
    본토 안쪽을 밀러 좌표로 격자 샘플링해 장축을 찾고, 장축을 따라 나눈 구간별 중심선에 2차 곡선을 맞춘다.
    결과는 이차 베지어 제어점 3개와 국토 두께(밀러 좌표)다. 샘플이 너무 적으면 None.
    """
    projected = [[[(x, miller_y(y)) for x, y in ring] for ring in rings] for rings in region_ring_sets]
    boxes = []
    for rings in projected:
        xs = [x for ring in rings for x, _ in ring]
        ys = [y for ring in rings for _, y in ring]
        boxes.append((min(xs), min(ys), max(xs), max(ys)))
    min_x, min_y = min(b[0] for b in boxes), min(b[1] for b in boxes)
    max_x, max_y = max(b[2] for b in boxes), max(b[3] for b in boxes)
    step = max(max_x - min_x, max_y - min_y) / CURVE_GRID
    if step <= 0:
        return None

    samples = []
    y = min_y + step / 2
    while y < max_y:
        x = min_x + step / 2
        while x < max_x:
            for rings, box in zip(projected, boxes):
                if box[0] <= x <= box[2] and box[1] <= y <= box[3] and signed_distance(x, y, rings) > 0:
                    samples.append((x, y))
                    break
            x += step
        y += step
    if len(samples) < CURVE_MIN_SAMPLES:
        return None

    mean_x = sum(x for x, _ in samples) / len(samples)
    mean_y = sum(y for _, y in samples) / len(samples)
    cxx = sum((x - mean_x) ** 2 for x, _ in samples)
    cyy = sum((y - mean_y) ** 2 for _, y in samples)
    cxy = sum((x - mean_x) * (y - mean_y) for x, y in samples)
    angle = 0.5 * math.atan2(2 * cxy, cxx - cyy)
    if angle > math.pi / 2:
        angle -= math.pi
    elif angle < -math.pi / 2:
        angle += math.pi
    angle = max(-CURVE_MAX_ANGLE, min(CURVE_MAX_ANGLE, angle))
    cos_a, sin_a = math.cos(angle), math.sin(angle)
    rotated = [((x - mean_x) * cos_a + (y - mean_y) * sin_a, -(x - mean_x) * sin_a + (y - mean_y) * cos_a) for x, y in samples]

    t_min = min(t for t, _ in rotated)
    t_max = max(t for t, _ in rotated)
    width = (t_max - t_min) / CURVE_BINS
    if width <= 0:
        return None
    bins = [[] for _ in range(CURVE_BINS)]
    for t, value in rotated:
        bins[min(CURVE_BINS - 1, int((t - t_min) / width))].append(value)
    columns = []
    for index, values in enumerate(bins):
        if values:
            columns.append((t_min + (index + 0.5) * width, (min(values) + max(values)) / 2, max(values) - min(values) + step, index))
    thickest = max(column[2] for column in columns)
    run, best_run = [], []
    for column in columns:
        if column[2] >= 0.4 * thickest and (not run or column[3] == run[-1][3] + 1):
            run.append(column)
        else:
            run = [column] if column[2] >= 0.4 * thickest else []
        if len(run) > len(best_run):
            best_run = list(run)

    t0 = best_run[0][0] - width / 2
    t1 = best_run[-1][0] + width / 2
    length = t1 - t0
    t0 += length * CURVE_END_TRIM
    t1 -= length * CURVE_END_TRIM
    fit = solve_quadratic_fit([(t, center, thickness) for t, center, thickness, _ in best_run]) if len(best_run) >= 3 else None
    a, b, c = fit if fit else (0.0, 0.0, statistics.median(column[1] for column in best_run))
    half = (t1 - t0) / 2
    if half > 0 and abs(a) * half * half > CURVE_MAX_SAG * (t1 - t0):
        a = math.copysign(CURVE_MAX_SAG * (t1 - t0) / (half * half), a)

    def curve(t):
        return a * t * t + b * t + c

    tm = (t0 + t1) / 2
    control = 2 * curve(tm) - (curve(t0) + curve(t1)) / 2

    def back(t, value):
        return (mean_x + t * cos_a - value * sin_a, mean_y + t * sin_a + value * cos_a)

    thickness = statistics.median(column[2] for column in best_run) * 0.9
    return back(t0, curve(t0)), back(tm, control), back(t1, curve(t1)), thickness


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


def write_geometry(regions, region_rings, borders, colors, labels):
    GEOMETRY_OUT.parent.mkdir(parents=True, exist_ok=True)
    with GEOMETRY_OUT.open("wb") as stream:
        write_utf(stream, FORMAT_NAME)
        stream.write(struct.pack(">i", FORMAT_VERSION))
        stream.write(struct.pack(">i", len(colors)))
        for owner in sorted(colors):
            write_utf(stream, owner)
            stream.write(struct.pack(">b", colors[owner]))
            p0, p1, p2, thickness = labels[owner]
            stream.write(struct.pack(">7f", *p0, *p1, *p2, thickness))
        stream.write(struct.pack(">i", len(regions)))
        for index, region in enumerate(regions):
            write_utf(stream, region["id"])
            stream.write(struct.pack(">i", len(region_rings[index])))
            for ring in region_rings[index]:
                write_points(stream, ring)
        stream.write(struct.pack(">i", len(borders)))
        for kind, points, owners in borders:
            stream.write(struct.pack(">b", kind))
            if kind == BORDER_REGION:
                write_utf(stream, owners[0])
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
    region_rings, borders, adjacency = simplify_rings(regions, rings, args.tolerance / QUANTUM)
    used_owners, countries, territories, world_regions = write_world(admin0, admin1, owners)
    colors = assign_colors(borders, used_owners)
    labels = compute_labels(regions, region_rings, adjacency, borders)
    write_geometry(regions, region_rings, borders, colors, labels)

    ring_points = sum(len(ring) for rings_of_region in region_rings.values() for ring in rings_of_region)
    border_points = sum(len(points) for _, points, _ in borders)
    print(f"countries={len(countries)} territories={len(territories)} regions={len(world_regions)}")
    print(f"ring points={ring_points} borders={len(borders)} border points={border_points}")
    print(f"world: {WORLD_OUT.relative_to(ROOT)}")
    print(f"geometry: {GEOMETRY_OUT.relative_to(ROOT)} ({GEOMETRY_OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
