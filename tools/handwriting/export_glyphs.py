#!/usr/bin/env python3
"""Exports the Arabic handwriting glyph set that the app ships (Smart Board, AI-05).

  tools/handwriting/.venv/bin/python -I tools/handwriting/export_glyphs.py

Every Arabic letter is shaped in each of its contextual forms (isolated, initial, medial, final) with
HarfBuzz and the OFL Aref Ruqaa font, using zero-width joiners for context, then skeletonised into
single-line pen paths with the same pipeline as hw/arabic.py (bodies walked from their right end,
dots and marks as separate short strokes). The Kotlin side (`:engine:handwriting`) joins the forms of a
word, adds per-word jitter and turns the paths into timed ink.

Output: engine/handwriting/src/main/resources/handwriting/arabic-ruqaa.json, deterministic. Units are
em; x is measured from the glyph's RIGHT edge (so x <= 0), y grows downward from the baseline.
The font is SIL OFL 1.1, so the derived data is too (see the OFL.txt copied next to it).
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # -I drops the script dir from sys.path

import numpy as np  # noqa: E402
from scipy.ndimage import distance_transform_edt  # noqa: E402
from skimage.measure import label, regionprops  # noqa: E402
from skimage.morphology import skeletonize  # noqa: E402

from hw import arabic, geom  # noqa: E402

OUT_DIR = os.path.join(HERE, "..", "..", "engine", "handwriting", "src", "main", "resources", "handwriting")
ZWJ = "‍"

# Dual-joining letters take all four forms; right-joining ones only isolated and final.
DUAL = "بتثجحخسشصضطظعغفقكلمنهيئى"
RIGHT = "اأإآدذرزوؤة"
NONE = "ء"
LIGATURES = ["لا", "لأ", "لإ", "لآ"]  # right-joining units
DIGITS = "٠١٢٣٤٥٦٧٨٩"
PUNCT = "،؛؟"


def unit_contours(f, text, unit_len):
    """Contours of `text` (the unit) shaped in context; ZWJ glyphs are skipped. Returns (contours, adv)."""
    infos, pos = f.shape(text)
    lead = 1 if text.startswith(ZWJ) else 0
    unit = set(range(lead, lead + unit_len))
    x = 0
    items = []
    for info, p in zip(infos, pos):
        if info.cluster in unit:
            items.append((x, info, p))
        x += p.x_advance
    if not items:
        return [], 0
    left = min(i[0] for i in items)
    right = max(i[0] + i[2].x_advance for i in items)
    contours = []
    for x0, info, p in items:
        name = f.order[info.codepoint]
        pen = arabic._FlatPen(f.glyphset)
        f.glyphset[name].draw(pen)
        ox, oy = x0 - left + p.x_offset, p.y_offset
        for c in pen.contours:
            contours.append([(ox + a, oy + b) for a, b in c])
    return contours, right - left


def skeleton(f, contours, adv):
    """Same pipeline as arabic.word_strokes, on given contours. Returns (bodies, marks) in em, x from right."""
    scale = arabic.PX_PER_EM / f.upem
    mask, (minx, maxy, pad) = arabic._rasterize(contours, scale)
    width_em = adv / f.upem

    def to_em(r, c):
        x_units = (c - pad) / scale + minx
        y_units = maxy - (r - pad) / scale
        return (x_units / f.upem - width_em, -y_units / f.upem)

    lab = label(mask, connectivity=2)
    full_sk = skeletonize(mask)
    stroke_w = max(2.0, 2.0 * mask.sum() / max(1, full_sk.sum()))
    half = float(np.median(distance_transform_edt(mask)[full_sk > 0])) if full_sk.any() else 4.0
    bodies, marks = [], []
    for rp in regionprops(lab):
        r0, c0, r1, c1 = rp.bbox
        ext = max(r1 - r0, c1 - c0) / arabic.PX_PER_EM
        comp = lab[r0:r1, c0:c1] == rp.label
        area_em = rp.area / arabic.PX_PER_EM ** 2
        is_mark = ext < 0.3 and area_em < 0.035
        if is_mark and area_em < 0.016:
            cy, cx = rp.centroid
            marks.append((c1, [[to_em(cy, cx)]]))
            continue
        padded = np.pad(comp, 1)
        sk = skeletonize(padded)
        nodes, edges = arabic._graph(sk)
        edges = arabic._prune(nodes, edges, 1.3 * stroke_w)
        paths = arabic._walk(nodes, edges, spur_len=0.45 * arabic.PX_PER_EM)
        paths = arabic._add_loops(paths, padded, sk, half)
        polys = []
        for p in paths:
            if len(p) < 2:
                continue
            pts = [(r + r0 - 1, c + c0 - 1) for r, c in p]
            pts = geom.moving_average(pts, 7)
            pts = geom.rdp(pts, 0.6)
            polys.append([to_em(r, c) for r, c in pts])
        if polys:
            (marks if is_mark else bodies).append((c1, polys))
    bodies.sort(key=lambda b: -b[0])
    marks.sort(key=lambda m: -m[0])
    flat = lambda polys: [[round(v, 4) for pt in poly for v in pt] for poly in polys]  # noqa: E731
    return [flat(p) for _k, p in bodies], [flat(p) for _k, p in marks]


def export_form(f, unit, form):
    ctx = {"isol": unit, "init": unit + ZWJ, "medi": ZWJ + unit + ZWJ, "fina": ZWJ + unit}[form]
    contours, adv = unit_contours(f, ctx, len(unit))
    if not contours:
        return None
    bodies, marks = skeleton(f, contours, adv)
    return {"adv": round(adv / f.upem, 4),
            "bodies": [s for b in bodies for s in b],
            "marks": [s for m in marks for s in m]}


def main():
    f = arabic.font()
    glyphs = {}
    units = [(c, ("isol", "init", "medi", "fina")) for c in DUAL] + \
            [(c, ("isol", "fina")) for c in RIGHT] + [(c, ("fina", "isol")) for c in LIGATURES] + \
            [(c, ("isol",)) for c in NONE + DIGITS + PUNCT]
    for unit, forms in units:
        out = {}
        for form in forms:
            g = export_form(f, unit, form)
            if g is not None:
                out[form] = g
        glyphs[unit] = out
        print(unit, sorted(out), file=sys.stderr)
    data = {
        "format": "bestnote-glyphs", "version": 1, "font": "Aref Ruqaa (skeletonised)", "license": "OFL-1.1",
        "units": "em", "origin": "right edge, baseline; y down",
        "joining": {"dual": DUAL, "right": RIGHT + "".join(LIGATURES), "none": NONE + DIGITS + PUNCT},
        "glyphs": glyphs,
    }
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "arabic-ruqaa.json"), "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    shutil.copy(os.path.join(HERE, "fonts", "arefruqaa", "OFL.txt"), os.path.join(OUT_DIR, "OFL-ArefRuqaa.txt"))
    for name in ("futural.jhf", "scripts.jhf", "LICENSE-HERSHEY.txt"):
        shutil.copy(os.path.join(HERE, "fonts", "hershey", name), os.path.join(OUT_DIR, name))


if __name__ == "__main__":
    main()
