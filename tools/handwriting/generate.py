#!/usr/bin/env python3
"""Generates the handwriting test corpus (stroke JSON + PNG previews).

  tools/handwriting/.venv/bin/python -I tools/handwriting/generate.py            # whole corpus
  tools/handwriting/.venv/bin/python -I tools/handwriting/generate.py --only arabic
  tools/handwriting/.venv/bin/python -I tools/handwriting/generate.py --text "Hello world" --out /tmp/x.json

Setup once: python3 -m venv tools/handwriting/.venv &&
  tools/handwriting/.venv/bin/pip install -r tools/handwriting/requirements.txt
Output is deterministic (fixed seeds), so regenerating gives identical files.
"""
import argparse
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # -I drops the script dir from sys.path

from hw import doc as hwdoc  # noqa: E402
from hw import dynamics, latin, shapes  # noqa: E402

CORPUS = os.path.join(HERE, "corpus")

LECTURE = ("Lecture 7 — Kinematics. Motion with constant acceleration… When a body speeds up at a "
           "steady rate, its velocity grows linearly with time. The displacement after a time t depends on "
           "the initial velocity u and on the acceleration a. Sketch the graph and label both axes before "
           "you calculate anything!")
PAGE_W = 180.0


def writer(seed, **kw):
    return dynamics.Writer(random.Random(seed), **kw)


def lecture_en():
    w = writer(7, xh=3.2, name="student")
    d = hwdoc.Document("lecture_en", "English lecture paragraph, joined cursive", PAGE_W, 82, w)
    d.expect = []
    y = latin.write_paragraph(d, w, LECTURE, 2, 9, 172, line_gap=3.6, lines=d.expect)
    d.height = y + 8
    return d


def formulas():
    w = writer(21, xh=3.6, name="student-print")
    d = hwdoc.Document("formulas", "Kinematics formulas, print style with stacked fractions", PAGE_W, 60, w)
    latin.write_formula(d, w, "v = u + at", 4, 10, first_gap="shape")
    latin.write_formula(d, w, "s = ut + {1/2}at^2", 4, 26)
    latin.write_formula(d, w, "{dy/dx} = {dy/du} · {du/dx}", 4, 46)
    return d


def arabic_text():
    from hw import arabic  # needs the venv (scikit-image, uharfbuzz)
    w = writer(11, xh=3.2, name="arabic-ruqaa")
    d = hwdoc.Document("arabic", "Arabic Ruqaa-style sentences, RTL, dots after each word", PAGE_W, 48, w)
    arabic.write_line(d, w, "قانون نيوتن الثاني",
                      PAGE_W - 6, 15, first_gap="shape")
    arabic.write_line(d, w, "التسارع يتناسب "
                            "طردياً مع القوة",
                      PAGE_W - 6, 36)
    d.expect = ["قانون نيوتن الثاني", "التسارع يتناسب طردياً مع القوة"]
    return d


def tiny_fast():
    w = writer(5, xh=1.5, vmax=170.0, vcurve=55.0, accel=3200.0, pressure=0.45, name="tiny-fast")
    d = hwdoc.Document("tiny_fast", "Tiny, fast margin notes (x-height 1.5 mm)", PAGE_W, 22, w)
    d.expect = []
    y = latin.write_paragraph(d, w, "note: check units first, then sign of a. if v = 0 at top, the time to rise is "
                                    "u/g. exam tip: write every step, it is quick.", 2, 4, 120, line_gap=3.4,
                              lines=d.expect)
    d.height = y + 4
    return d


def slow_large():
    w = writer(9, xh=9.0, vmax=48.0, vcurve=17.0, accel=700.0, pressure=0.62, tilt_deg=40.0, name="slow-large")
    d = hwdoc.Document("slow_large", "Slow, large headline writing (x-height 9 mm)", PAGE_W, 62, w)
    latin.write_paragraph(d, w, "Newton's law", 4, 22, 176)
    latin.write_paragraph(d, w, "F = ma", 10, 54, 170, first_gap="line")
    d.expect = ["Newton's law", "F = ma"]
    return d


def drawings():
    w = writer(33, xh=3.4, vmax=120.0, name="diagram")
    d = hwdoc.Document("drawings", "Hand-drawn circle, arrow, box and graph axes with a curve", PAGE_W, 80, w)
    shapes.circle(d, w, (22, 22), 14)
    shapes.arrow(d, w, (42, 24), (72, 18))
    shapes.box(d, w, 76, 8, 40, 22)
    pen = latin.Pen(w, 82, 22)
    latin.write_word(d, pen, "Force", "cursive", first_gap="shape")
    shapes.axes(d, w, 130, 70, 44, 58)
    latin.write_formula(d, w, "s", 120, 14, first_gap="letter")
    latin.write_formula(d, w, "t", 176, 76, first_gap="letter")
    shapes.arrow(d, w, (30, 44), (24, 66))
    shapes.circle(d, w, (24, 72), 5.5)
    return d


def shapes_hold():
    """Every snap shape, each held still ~650 ms at the end (INK-08 hold-to-snap)."""
    w = writer(55, xh=3.4, vmax=110.0, name="diagram")
    d = hwdoc.Document("shapes_hold", "Hold-to-snap shapes: line, arrow, circle, ellipse, box, triangle, pentagon",
                       PAGE_W, 120, w)
    h = 650.0
    shapes.line(d, w, (8, 12), (62, 14.5), hold_ms=h)
    shapes.arrow_one_stroke(d, w, (78, 22), (120, 8), hold_ms=h)
    shapes.circle(d, w, (150, 18), 13, hold_ms=h)
    shapes.ellipse(d, w, (34, 50), 26, 12, rot_deg=8, hold_ms=h)
    shapes.box(d, w, 74, 38, 44, 26, hold_ms=h)
    shapes.polygon(d, w, [(150, 36), (172, 70), (128, 68)], hold_ms=h)
    shapes.regular_polygon(d, w, (32, 96), 16, 5, hold_ms=h)
    shapes.line(d, w, (66, 84), (110, 112), hold_ms=h)
    shapes.circle(d, w, (146, 98), 8, hold_ms=0.0)  # no hold: stays hand-drawn
    return d


def scribble():
    w = writer(44, xh=3.2, vmax=200.0, vcurve=70.0, accel=4000.0, pressure=0.6, name="shader")
    d = hwdoc.Document("scribble", "Scribble shading: fast back-and-forth hatching in two boxes", PAGE_W, 50, w)
    shapes.box(d, w, 6, 6, 50, 34)
    shapes.scribble(d, w, 8, 8, 46, 30, spacing=1.2)
    shapes.circle(d, w, (90, 24), 17)
    shapes.scribble(d, w, 78, 12, 24, 24, spacing=0.9, angle_deg=-35)
    return d


def highlight_lecture():
    """Highlighter strokes over lines 1-3 of lecture_en (same coordinates). Replay after lecture_en."""
    w = writer(55, xh=3.2, vmax=140.0, pressure=0.5, name="highlighter")
    d = hwdoc.Document("highlight_lecture", "Highlighter passes over the first three lines of lecture_en",
                       PAGE_W, 82, w)
    for i, (x0, x1) in enumerate([(2, 120), (10, 168), (2, 90)]):
        yb = 9 + i * 3.6 * 3.2 - 1.4
        pts = shapes._line((x0, yb + w.rng.gauss(0, 0.4)), (x1, yb + w.rng.gauss(0, 0.6)), w, bow=0.004)
        d.add(pts, gap="shape", speed=1.0)
    return d


def hover_erase():
    """Writes two words with hover between strokes, then erases the second word with the side button held."""
    w = writer(66, xh=4.0, name="hover-eraser")
    d = hwdoc.Document("hover_erase", "Hover between strokes, then a side-button erase scribble over 'wrong'",
                       PAGE_W, 40, w, hover=True)
    pen = latin.Pen(w, 6, 18)
    latin.write_word(d, pen, "right", "cursive", first_gap="shape")
    pen.x += 6
    x_wrong = pen.x
    latin.write_word(d, pen, "wrong", "cursive", first_gap=1800.0)  # long hover: pen cursor visible
    x_end = pen.x
    pts = []
    for i in range(7):
        pts.append((x_wrong + (x_end - x_wrong) * (i % 2), 18 - 5 + i * 1.3))
    from hw import geom
    d.add(geom.catmull_rom(pts, 0.1), gap=2500.0, speed=0.6, button=True)
    return d


# ---------------------------------------------------------------- REC-10: harder, more realistic convert-to-text ink

NOTES = ("Friction always opposes the motion of a sliding block. The normal force is equal to the weight on a flat "
         "table, so the friction force is the coefficient times the weight. On a ramp the normal force gets smaller "
         "and the block starts to slide when the angle is steep enough.")


def para_tight():
    """Six lines of notes on tight ruling (line pitch 2.5 x-heights): ascenders and descenders nearly touch."""
    w = writer(71, xh=3.0, name="student-tight")
    d = hwdoc.Document("para_tight", "English notes, tight line spacing (2.5 x-heights)", PAGE_W, 60, w)
    d.expect = []
    y = latin.write_paragraph(d, w, NOTES, 3, 8, 150, line_gap=2.5, lines=d.expect)
    d.height = y + 6
    return d


def slanted():
    """Right-leaning letters on lines that climb ~4 degrees, as on a tablet held at an angle."""
    w = writer(72, xh=3.4, slant_deg=14.0, name="student-slanted")
    d = hwdoc.Document("slanted", "English, slanted letters, lines climbing 4 degrees", PAGE_W, 70, w)
    d.expect = []
    y = latin.write_paragraph(d, w, "Remember to bring the lab report on Monday and check every unit in your answers "
                                    "before the quiz starts", 4, 16, 120, line_gap=3.6, lines=d.expect, slope_deg=4.0)
    d.height = y + 8
    return d


def numbers():
    """Print-style digits and punctuation: times, ranges, a phone number, a question."""
    w = writer(73, xh=3.4, name="student-print")
    d = hwdoc.Document("numbers", "Numbers and punctuation, print style", PAGE_W, 50, w)
    d.expect = []
    lines = ["Room 204 at 9:30, pages 12-15.", "Call 555-0142 by Friday!", "Is 3 x 7 = 21?"]
    for i, text in enumerate(lines):
        written = []
        latin.write_paragraph(d, w, text, 4, 10 + i * 13, 170, "print", first_gap="line" if i else "shape",
                              lines=written)
        d.expect += written
    return d


ARABIC_PARA = ["درس اليوم عن الطاقة الحركية", "الطاقة تساوي نصف الكتلة", "في مربع السرعة دائما",
               "راجع الأمثلة في الكتاب"]


def _arabic_para(name, description, seed, pitch, spans=None):
    from hw import arabic
    w = writer(seed, xh=3.0, name="arabic-ruqaa")
    d = hwdoc.Document(name, description, PAGE_W, 20 + pitch * len(ARABIC_PARA), w)
    for i, text in enumerate(ARABIC_PARA):
        arabic.write_line(d, w, text, PAGE_W - 6, 16 + i * pitch, first_gap="shape" if i == 0 else "line", spans=spans)
    d.expect = list(ARABIC_PARA)
    return d


def arabic_para():
    """Four Arabic lines on ~12 mm ruling (dots and marks after each word, as people write them)."""
    return _arabic_para("arabic_para", "Arabic paragraph, 4 lines, 12 mm line pitch", 74, 12.0)


def mixed():
    """Arabic lines with English words and numbers in them, each written in its own direction."""
    from hw import arabic
    w = writer(75, xh=3.0, name="arabic-mixed")
    d = hwdoc.Document("mixed", "Arabic lines with English words and numbers inside", PAGE_W, 60, w)
    d.expect = ["قانون Newton الثاني", "السرعة تساوي 20 متر", "راجع chapter 3 في البيت"]
    for i, text in enumerate(d.expect):
        arabic.write_line(d, w, text, PAGE_W - 6, 16 + i * 15, first_gap="shape" if i == 0 else "line")
    return d


def para_moved():
    """para_tight after editing: every fourth word was moved with the lasso (or erased and undone), so its strokes
    sit at the end of the page's stroke list, out of writing order. A lasso returns strokes in that order."""
    w = writer(71, xh=3.0, name="student-tight")
    d = hwdoc.Document("para_moved", "para_tight with every 4th word's strokes at the end (z-order after edits)",
                       PAGE_W, 60, w)
    d.expect = []
    spans = []
    y = latin.write_paragraph(d, w, NOTES, 3, 8, 150, line_gap=2.5, lines=d.expect, spans=spans)
    d.move_to_end(spans[1::4])
    d.height = y + 6
    return d


def arabic_moved():
    """arabic_para with every third word's strokes moved to the end of the stroke list (z-order after edits)."""
    spans = []
    d = _arabic_para("arabic_moved", "arabic_para with every 3rd word's strokes at the end (z-order after edits)",
                     74, 12.0, spans)
    d.move_to_end(spans[1::3])
    return d


REC = [para_tight, slanted, numbers, arabic_para, mixed, para_moved, arabic_moved]

ALL = [lecture_en, formulas, arabic_text, tiny_fast, slow_large, drawings, scribble, highlight_lecture, hover_erase,
       shapes_hold] + REC
NAMES = {"arabic": arabic_text}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    ap.add_argument("--text")
    ap.add_argument("--style", default="cursive", choices=["cursive", "print"])
    ap.add_argument("--xh", type=float, default=3.2)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--out")
    ap.add_argument("--no-preview", action="store_true")
    a = ap.parse_args()
    if a.text:
        w = writer(a.seed, xh=a.xh)
        d = hwdoc.Document("custom", a.text[:60], PAGE_W, 100, w)
        y = latin.write_paragraph(d, w, a.text, 2, a.xh * 2.5, PAGE_W - 8, a.style)
        d.height = y + 2 * a.xh
        docs = [(a.out or os.path.join(CORPUS, "custom.json"), d)]
    else:
        fns = ALL
        if a.only:
            fns = [NAMES.get(a.only) or next(f for f in ALL if f.__name__ == a.only)]
        docs = []
        for f in fns:
            d = f()
            docs.append((os.path.join(CORPUS, d.name + ".json"), d))
    for path, d in docs:
        data = d.build()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        hwdoc.save(data, path)
        if d.expect:
            with open(path[:-len(".json")] + ".expect.txt", "w", encoding="utf-8") as f:
                f.write("|".join(d.expect) + "\n")
        n = sum(1 for s in data["strokes"] if s["type"] == "ink")
        print("%-22s %4d strokes %6.1f s  %s" % (d.name, n, data["durationMs"] / 1000, path))
        if not a.no_preview:
            from hw import render
            pv = os.path.join(os.path.dirname(path), "previews")
            os.makedirs(pv, exist_ok=True)
            render.render(data, os.path.join(pv, d.name + ".png"))


if __name__ == "__main__":
    main()
