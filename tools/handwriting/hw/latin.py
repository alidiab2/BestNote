"""Latin text and simple math as handwriting, from Hershey single-stroke fonts with human jitter.

Styles:
- "cursive": Hershey script simplex. Lowercase letters in a word are joined into one pen-down stroke;
  i/j dots, t crossbars and the second stroke of x are written after the word, like people do.
- "print": Hershey Roman simplex (futural), every glyph lifted, as in handwritten formulas.
"""
import math

from . import geom, hershey

_FONTS = {}
FONT_FOR_STYLE = {"cursive": "scripts", "print": "futural"}
XH_UNITS = {"scripts": 9.0, "futural": 14.0}
# (char -> indices of strokes written after the word) for the script font.
DEFERRED = {"i": [0], "j": [0], "t": [2], "x": [1]}


def font(name):
    if name not in _FONTS:
        _FONTS[name] = hershey.load(name)
    return _FONTS[name]


class Pen:
    """Layout cursor plus per-line baseline drift."""

    def __init__(self, w, x, baseline):
        self.w, self.x, self.base0 = w, x, baseline
        rng = w.rng
        self.drift = geom.SmoothNoise(rng, 60.0, 0.12 * w.xh * w.jitter, octaves=2)
        self.slope = math.radians(rng.gauss(0, 0.5 * w.jitter))
        self.line_start = x
        self.extra_slope = 0.0  # radians added to every line (uphill writing is negative: y grows down)

    def baseline(self, x):
        return self.base0 + self.drift(x) + (x - self.line_start) * math.tan(self.slope + self.extra_slope)

    def newline(self, x, baseline):
        self.x, self.base0, self.line_start = x, baseline, x
        rng = self.w.rng
        self.drift = geom.SmoothNoise(rng, 60.0, 0.12 * self.w.xh * self.w.jitter, octaves=2)
        self.slope = math.radians(rng.gauss(0, 0.5 * self.w.jitter))


# ---------------------------------------------------------------- glyph -> local mm strokes

def _special(ch, xh):
    """Characters the Hershey sets lack. Returns (strokes, dots, advance) in local mm (y up negative)."""
    if ch == "—":  # em dash
        return [[(0.15 * xh, -0.5 * xh), (1.9 * xh, -0.52 * xh)]], [], 2.2 * xh
    if ch == "…":  # ellipsis
        return [], [(0.25 * xh, -0.05 * xh), (0.75 * xh, -0.05 * xh), (1.25 * xh, -0.05 * xh)], 1.7 * xh
    if ch == "·":  # middle dot
        return [], [(0.45 * xh, -0.5 * xh)], 0.9 * xh
    return None


def glyph_mm(ch, style, xh, rng, jitter, scale=1.0):
    """Jittered strokes for one character. Returns (strokes, deferred, dots, advance)."""
    sp = _special(ch, xh * scale)
    if sp is not None:
        s, d, adv = sp
        return s, [], d, adv
    fname = FONT_FOR_STYLE[style]
    f = font(fname)
    g = f.get(ch)
    if g is None:
        f = font("futural")
        fname = "futural"
        g = f.get(ch) or f["?"]
    u = xh * scale / XH_UNITS[fname]
    sx = u * (1 + rng.gauss(0, 0.045 * jitter))
    sy = u * (1 + rng.gauss(0, 0.05 * jitter))
    dy = rng.gauss(0, 0.04 * xh * jitter)
    # per-glyph rotation/shear: print hands vary more than joined cursive
    rot = math.radians(rng.gauss(0, (3.0 if style == "print" else 1.5) * jitter))
    cr, sr = math.cos(rot), math.sin(rot)
    strokes, deferred = [], []
    dfi = DEFERRED.get(ch, []) if style == "cursive" else []
    for i, s in enumerate(g.strokes):
        pts = [(x * sx, (y - hershey.BASELINE) * sy + dy) for x, y in s]
        pts = [(x * cr - y * sr, x * sr + y * cr) for x, y in pts]
        if i in dfi:
            # dots and crossbars drift a little: people place them carelessly
            ox, oy = rng.gauss(0.05 * xh, 0.08 * xh * jitter), rng.gauss(0, 0.06 * xh * jitter)
            pts = [(x + ox, y + oy) for x, y in pts]
            if geom.length(pts) < 0.4 * xh * scale:
                c = (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
                deferred.append(("dot", [c]))
            else:
                deferred.append(("stroke", pts))
        else:
            strokes.append(pts)
    adv = g.width * sx
    if style == "print":
        adv *= 0.9
    return strokes, deferred, [], adv


def _shear(pts, base, tan_slant):
    # positive slant leans letters to the right
    return [(x - (y - base) * tan_slant, y) for x, y in pts]


def _finish(pts, w, scale=1.0, style="cursive"):
    """Polyline in page mm -> smooth, wobbly dense path."""
    dense = geom.catmull_rom(pts, step=0.06)
    amp = (0.02 if style == "print" else 0.014) * w.xh * scale * w.jitter + 0.01
    return geom.wobble(dense, w.rng, amp, 1.6 * w.xh * scale)


# ---------------------------------------------------------------- words and text

def write_word(doc, pen, word, style="cursive", first_gap="word", scale=1.0, speed=1.0):
    """Writes one word at pen.x on the current baseline. Advances pen.x past the word."""
    w = pen.w
    rng = w.rng
    xh = w.xh
    slant = math.tan(math.radians(w.slant_deg + rng.gauss(0, 2.5 * w.jitter)))
    chain, chain_char = None, None
    out = []  # (kind, pts, gap)
    later = []
    gap = first_gap
    for ch in word:
        x0 = pen.x
        base = pen.baseline(x0)
        if ch == "½":
            if chain is not None:
                out.append(("stroke", chain, gap))
                gap = "letter"
            chain = None
            pen.x = write_fraction(doc, pen, "1", "2", "print", small=True, gap=gap,
                                   flush=lambda: _flush(doc, out, w))
            gap = "letter"
            continue
        sup = ch == "²"
        cch = "2" if sup else ch
        sc = scale * (0.62 if sup else 1.0)
        strokes, deferred, dots, adv = glyph_mm(cch, style, xh, rng, w.jitter, sc)
        lift = -0.95 * xh if sup else 0.0
        place = lambda pts: _shear([(x0 + x, base + y + lift) for x, y in pts], base, slant)
        joinable = style == "cursive" and ch.isalpha()
        for si, s in enumerate(strokes):
            pts = place(s)
            if chain is not None:
                d = geom.dist(chain[-1], pts[0])
                same_glyph = si > 0
                lower = ch.islower() and (chain_char or "").isalpha()
                if (same_glyph and (ch.islower() or d < 0.15 * xh)) or \
                        (not same_glyph and joinable and lower and d < 1.3 * xh):
                    chain = chain + pts[1:] if d < 0.05 * xh else chain + pts
                    chain_char = ch
                    continue
                out.append(("stroke", chain, gap))
                gap = "letter"
            chain, chain_char = pts, ch
            if not joinable:
                out.append(("stroke", chain, gap))
                gap = "letter"
                chain = None
        for kind, pts in deferred:
            later.append((kind, place(pts)))
        for d in dots:
            if chain is not None:
                out.append(("stroke", chain, gap))
                gap = "letter"
                chain = None
            out.append(("dot", [place([d])[0]], gap))
            gap = "letter"
        jitter_adv = rng.gauss(0, 0.05 * xh * w.jitter)
        pen.x = x0 + adv + (jitter_adv if not joinable else 0.4 * jitter_adv)
    if chain is not None:
        out.append(("stroke", chain, gap))
    for kind, pts in later:
        out.append((kind, pts, "deferred"))
    for kind, pts, g in out:
        if kind == "dot":
            doc.add_dot(pts[0], gap=g)
        else:
            doc.add(_finish(pts, w, scale, style), gap=g, speed=speed)


def _flush(doc, out, w):
    for kind, pts, g in out:
        if kind == "dot":
            doc.add_dot(pts[0], gap=g)
        else:
            doc.add(_finish(pts, w), gap=g)
    out.clear()


def measure(word, style, xh, scale=1.0):
    f = font(FONT_FOR_STYLE[style])
    u = xh * scale / XH_UNITS[FONT_FOR_STYLE[style]]
    total = 0.0
    for ch in word:
        sp = _special(ch, xh * scale)
        if sp is not None:
            total += sp[2]
        elif ch == "½":
            total += 1.2 * xh * scale
        else:
            g = f.get(ch) or font("futural").get(ch) or f["?"]
            total += g.width * u * (0.62 if ch == "²" else 1.0) * (0.9 if style == "print" else 1.0)
    return total


def write_paragraph(doc, w, text, x, y, width, style="cursive", line_gap=3.4, speed=1.0, first_gap="shape",
                    lines=None, spans=None, slope_deg=0.0):
    """Greedy word wrap. y is the first baseline. Returns the baseline of the last line.
    lines: if given, the written lines are appended (as text). spans: (first, end) item indices of each word.
    slope_deg: every line runs uphill (positive) or downhill by this angle."""
    pen = Pen(w, x, y)
    pen.extra_slope = -math.radians(slope_deg)
    space = 1.0 * w.xh
    first = True
    for word in text.split():
        ww = measure(word, style, w.xh)
        gap = first_gap if first else "word"
        if not first and pen.x + ww > x + width:
            y += line_gap * w.xh * w.rng.uniform(0.97, 1.03)
            pen.newline(x + w.rng.gauss(0, 0.25 * w.xh), y)
            gap = "line"
            if lines is not None:
                lines.append("")
        if lines is not None:
            if not lines:
                lines.append("")
            lines[-1] = (lines[-1] + " " + word).strip()
        start = len(doc.items)
        write_word(doc, pen, word, style, first_gap=gap, speed=speed)
        if spans is not None:
            spans.append((start, len(doc.items)))
        pen.x += space * w.rng.uniform(0.85, 1.3)
        first = False
    return y


# ---------------------------------------------------------------- formulas

def write_fraction(doc, pen, num, den, style, small=False, gap="word", flush=None):
    """Stacked fraction: numerator, bar, denominator. Returns the x after it."""
    w = pen.w
    if flush:
        flush()
    sc = 0.6 if small else 0.85
    xh = w.xh
    nw, dw = measure(num, style, xh, sc), measure(den, style, xh, sc)
    bw = max(nw, dw) + (0.25 if small else 0.5) * xh
    x0 = pen.x + 0.15 * xh
    base = pen.baseline(x0)
    bar_y = base - 0.5 * xh
    sub = Pen(w, x0 + (bw - nw) / 2, bar_y - (0.3 if small else 0.38) * xh)
    sub.drift = lambda s: 0.0
    for i, word in enumerate(num.split()):
        write_word(doc, sub, word, style, first_gap=gap if i == 0 else "word", scale=sc)
    bar = [(x0 + w.rng.gauss(0, 0.05 * xh), bar_y + w.rng.gauss(0, 0.04 * xh)),
           (x0 + bw / 2, bar_y + w.rng.gauss(0, 0.05 * xh)),
           (x0 + bw + w.rng.gauss(0, 0.06 * xh), bar_y + w.rng.gauss(0, 0.05 * xh))]
    doc.add(_finish(bar, w), gap="letter")
    top_den = (1.0 if small else 1.0) * xh * sc * (21.0 / 14.0)  # digit/cap height in print font
    sub = Pen(w, x0 + (bw - dw) / 2, bar_y + 0.3 * xh + top_den)
    sub.drift = lambda s: 0.0
    for word in den.split():
        write_word(doc, sub, word, style, first_gap="letter", scale=sc)
    return x0 + bw + 0.15 * xh


def write_formula(doc, w, expr, x, y, style="print", first_gap="line"):
    """Mini-markup: {a/b} is a stacked fraction, ^c a superscript character, spaces separate words."""
    pen = Pen(w, x, y)
    i, word, gap = 0, "", first_gap

    def emit():
        nonlocal word, gap
        if word:
            write_word(doc, pen, word, style, first_gap=gap)
            gap = "word"
            word = ""

    while i < len(expr):
        c = expr[i]
        if c == "{":
            emit()
            j = expr.index("}", i)
            num, den = expr[i + 1:j].split("/")
            pen.x = write_fraction(doc, pen, num, den, style, gap=gap)
            gap = "word"
            i = j + 1
            continue
        if c == "^":
            word += "²" if expr[i + 1] == "2" else expr[i + 1]
            i += 2
            continue
        if c == " ":
            emit()
            pen.x += 0.8 * w.xh * w.rng.uniform(0.8, 1.3)
            i += 1
            continue
        word += c
        i += 1
    emit()
    return pen.x
