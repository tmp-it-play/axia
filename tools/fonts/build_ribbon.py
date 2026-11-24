"""Build the two OFL Axia Ribbon typewriter faces with fontTools.

Pass a directory containing the upstream TTFs to build offline, or run without
arguments to fetch the pinned sources from google/fonts.
"""

import hashlib
import math
import random
import subprocess
import sys
import zlib
from io import BytesIO
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import GlyphCoordinates

UPSTREAM = (
    "https://raw.githubusercontent.com/google/fonts/"
    "90abd17b4f97671435798b6147b698aa9087612f/ofl/nanumgothiccoding"
)
SOURCES = {
    "Regular": "787effd7efed2abca88ade231faa8191f4e9fcf85b1805a13ee1dc3724b72089",
    "Bold": "77a6de97c176b76ef9a683b565a1e6f4ce40b499c72a2972c4b5bdf7c6b8e7a0",
}
DEST = Path(__file__).resolve().parents[2] / "desktopApp/src/main/composeResources/font"


def wear_edge(start, end, rng, depth):
    """Cut a shallow asymmetric nick in long strokes. TrueType ink is on the right."""
    x, y = start
    dx, dy = end[0] - x, end[1] - y
    length = math.hypot(dx, dy)
    if length < 105 or rng.random() > min(0.90, length / 260):
        return []
    tangent_x, tangent_y = dx / length, dy / length
    half_width = min(length * 0.20, rng.uniform(15, 31))
    center = rng.uniform(half_width + 6, length - half_width - 6)
    bite = rng.uniform(depth * 0.4, depth)
    profile = [
        (center - half_width, 0),
        (center - half_width * 0.12, bite),
        (center + half_width * 0.42, bite * 0.47),
        (center + half_width, 0),
    ]
    return [
        (x + tangent_x * along + tangent_y * inset, y + tangent_y * along - tangent_x * inset)
        for along, inset in profile
    ]


def imprint(glyph, rng, advance, depth):
    # One stable mechanical strike per metal sort. Bend both sides of a stroke
    # together so the counters in dense Hangul stay open at small UI sizes.
    tilt = rng.uniform(-0.025, 0.025)
    lift = rng.uniform(-22, 22)
    drift = rng.uniform(-9, 9)
    phase_x = rng.uniform(0, math.tau)
    phase_y = rng.uniform(0, math.tau)

    def print_point(point):
        x, y = point
        # Taper the shift at the limits of the printable cell, including the
        # native 800/-200 vertical metrics used by small single-line controls.
        shift_x = drift + tilt * (y - 325) + 5 * math.sin(y / 101 + phase_x)
        shift_y = lift - tilt * (x - advance / 2) + 4 * math.sin(x / 97 + phase_y)
        edge_x = max(0, min(1, x / 60, (advance - x) / 60))
        edge_y = max(0, min(1, (y + 200) / 60, (800 - y) / 60))
        return round(x + edge_x * shift_x), round(y + edge_y * shift_y)

    coordinates, flags, ends = [], bytearray(), []
    first = 0
    for last in glyph.endPtsOfContours:
        for i in range(first, last + 1):
            point = glyph.coordinates[i]
            coordinates.append(print_point(point))
            flags.append(glyph.flags[i])
            next_i = i + 1 if i < last else first
            if glyph.flags[i] & glyph.flags[next_i] & 1:
                for nick in wear_edge(point, glyph.coordinates[next_i], rng, depth):
                    coordinates.append(print_point(nick))
                    flags.append(1)
        first = last + 1
        ends.append(len(coordinates) - 1)
    glyph.coordinates = GlyphCoordinates(coordinates)
    glyph.flags = flags
    glyph.endPtsOfContours = ends


def build(source, style):
    if hashlib.sha256(source).hexdigest() != SOURCES[style]:
        raise ValueError(f"Unexpected source for {style}")
    font = TTFont(BytesIO(source), recalcTimestamp=False)
    outlines = font["glyf"]
    metrics = font["hmtx"].metrics
    ruling = {name for char, name in font.getBestCmap().items() if 0x2500 <= char <= 0x259F}
    for name in font.getGlyphOrder():
        glyph = outlines[name]
        # The source bytecode references point indices in the old outlines.
        glyph.removeHinting()
        if glyph.numberOfContours > 0 and name not in ruling:
            rng = random.Random(zlib.crc32(name.encode("ascii")))
            imprint(glyph, rng, metrics[name][0], 36 if style == "Bold" else 28)
    for name in font.getGlyphOrder():
        glyph = outlines[name]
        if glyph.numberOfContours:
            glyph.recalcBounds(outlines)
            metrics[name] = (metrics[name][0], glyph.xMin)
    for table in ("fpgm", "prep", "cvt "):
        del font[table]
    maxp = font["maxp"]
    maxp.maxZones = 1
    maxp.maxTwilightPoints = maxp.maxStorage = maxp.maxStackElements = 0
    maxp.maxFunctionDefs = maxp.maxInstructionDefs = maxp.maxSizeOfInstructions = 0
    font["gasp"].gaspRange = {65535: 10}  # grayscale and symmetric smoothing at all sizes
    font["head"].flags &= ~0x18  # no grid fitting or instructed advance widths
    font["head"].fontRevision = 1.0
    names = {
        1: "Axia Ribbon", 2: style, 3: f"Axia Ribbon {style} 1.000",
        4: f"Axia Ribbon {style}", 5: "Version 1.000", 6: f"AxiaRibbon-{style}",
    }
    for record in font["name"].names:
        if record.nameID in names:
            record.string = names[record.nameID].encode(record.getEncoding())
    font["name"].setName("Typewriter adaptation of NanumGothicCoding for Axia.", 10, 3, 1, 0x409)
    font.save(DEST / f"axia_ribbon_{style.lower()}.ttf")


if __name__ == "__main__":
    for style in SOURCES:
        filename = f"NanumGothicCoding-{style}.ttf"
        if len(sys.argv) > 1:
            source = (Path(sys.argv[1]) / filename).read_bytes()
        else:
            source = subprocess.check_output(["curl", "-fsSL", f"{UPSTREAM}/{filename}"])
        build(source, style)
