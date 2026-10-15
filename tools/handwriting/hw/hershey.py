"""Hershey .jhf single-stroke font loader (stdlib only).

Each glyph is a list of polylines in font units, with x relative to the glyph's left edge and
y growing downward. For the script fonts the baseline is y = 9 and the x-height top is y = 0.
"""
import os

FONT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fonts", "hershey")
BASELINE = 9.0
X_HEIGHT = 9.0


class Glyph:
    __slots__ = ("char", "strokes", "width")

    def __init__(self, char, strokes, width):
        self.char = char
        self.strokes = strokes  # list[list[(x, y)]]
        self.width = width


def _parse_line(line):
    count = int(line[5:8])
    data = line[8:]
    pairs = [data[i:i + 2] for i in range(0, count * 2, 2)]
    left = ord(pairs[0][0]) - ord("R")
    right = ord(pairs[0][1]) - ord("R")
    strokes, cur = [], []
    for p in pairs[1:]:
        if p == " R":
            if cur:
                strokes.append(cur)
            cur = []
            continue
        cur.append((float(ord(p[0]) - ord("R") - left), float(ord(p[1]) - ord("R"))))
    if cur:
        strokes.append(cur)
    return strokes, float(right - left)


def load(name):
    """Load fonts/hershey/<name>.jhf, mapping lines in order to ASCII 32..126."""
    path = os.path.join(FONT_DIR, name + ".jhf")
    with open(path, encoding="ascii") as f:
        raw = [l.rstrip("\n") for l in f]
    # Some .jhf files wrap long glyphs onto several physical lines; re-join by declared count.
    lines, buf = [], ""
    for l in raw:
        buf += l
        if len(buf) >= 8 and len(buf) - 8 >= int(buf[5:8]) * 2:
            lines.append(buf)
            buf = ""
    glyphs = {}
    for i, line in enumerate(lines):
        ch = chr(32 + i)
        strokes, width = _parse_line(line)
        glyphs[ch] = Glyph(ch, strokes, width)
    return glyphs
