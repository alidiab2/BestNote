#!/usr/bin/env python3
"""Turn text into handwriting-like stylus strokes for the debug WRITE hook (feature/editor src/debug).

Usage:
  scripts/agent/handwrite.py "Kinematics@60,90,22" "motion with constant acceleration@60,154,13" > ink.txt
  adb push ink.txt /sdcard/Android/data/app.bestnote.debug/files/ink.txt
  adb shell am broadcast -a app.bestnote.debug.WRITE --es file ink.txt

Or replay real handwriting: handwrite.py --corpus lecture_en.json X,Y,DP_PER_MM

Each argument is TEXT@X,BASELINE_Y,X_HEIGHT in dp from the page's top-left. Output: one stroke per
line, "x,y,pressure" points in dp. A small single-stroke script font with slant, jitter and a
pressure swell, smoothed with Catmull-Rom, so ink looks written rather than ruled.
"""
import math
import random
import sys

# Glyphs: (advance width, [strokes]); points are (x, y) with baseline y=0, x-height y=1, y up.
G = {
    "a": (1.0, [[(.8, .75), (.5, 1), (.1, .75), (.05, .3), (.35, 0), (.7, .2), (.8, .6), (.8, 1), (.8, .2), (.95, 0)]]),
    "b": (.9, [[(.1, 1.8), (.1, 0), (.1, .5), (.4, .95), (.75, .8), (.8, .3), (.45, 0), (.1, .15)]]),
    "c": (.85, [[(.8, .8), (.5, 1), (.1, .7), (.1, .25), (.45, 0), (.8, .2)]]),
    "d": (1.0, [[(.8, .75), (.5, 1), (.1, .7), (.1, .25), (.4, 0), (.8, .3), (.8, 1.8), (.8, .1), (.95, 0)]]),
    "e": (.85, [[(.1, .5), (.75, .6), (.6, .95), (.3, .95), (.05, .55), (.15, .15), (.5, 0), (.8, .2)]]),
    "f": (.7, [[(.8, 1.7), (.55, 1.85), (.35, 1.6), (.35, 0)], [(.1, 1), (.7, 1)]]),
    "g": (.95, [[(.8, .75), (.5, 1), (.1, .7), (.15, .3), (.45, .1), (.8, .4), (.8, 1), (.8, -.5), (.5, -.8), (.15, -.6)]]),
    "h": (.9, [[(.1, 1.8), (.1, 0), (.1, .5), (.4, .95), (.7, .85), (.75, .5), (.75, 0)]]),
    "i": (.45, [[(.2, 1), (.2, .1), (.35, 0)], [(.2, 1.35), (.23, 1.38)]]),
    "j": (.55, [[(.4, 1), (.4, -.6), (.2, -.85), (0, -.7)], [(.4, 1.35), (.43, 1.38)]]),
    "k": (.8, [[(.1, 1.8), (.1, 0)], [(.7, 1), (.1, .45), (.75, 0)]]),
    "l": (.4, [[(.15, 1.8), (.15, .15), (.3, 0)]]),
    "m": (1.2, [[(.05, 1), (.05, 0), (.05, .6), (.3, 1), (.5, .8), (.55, 0), (.55, .6), (.8, 1), (1, .8), (1.05, 0)]]),
    "n": (.85, [[(.05, 1), (.05, 0), (.05, .6), (.35, 1), (.65, .85), (.7, 0)]]),
    "o": (.9, [[(.5, 1), (.1, .7), (.1, .25), (.45, 0), (.8, .3), (.8, .7), (.5, 1), (.4, .97)]]),
    "p": (.9, [[(.1, 1), (.1, -.8)], [(.1, .6), (.4, 1), (.75, .8), (.8, .3), (.45, 0), (.1, .2)]]),
    "q": (.95, [[(.8, .75), (.5, 1), (.1, .7), (.15, .25), (.45, 0), (.8, .35), (.8, 1), (.8, -.8), (.95, -.6)]]),
    "r": (.7, [[(.1, 1), (.1, 0), (.1, .55), (.35, .95), (.65, .95)]]),
    "s": (.8, [[(.75, .85), (.45, 1), (.1, .8), (.25, .55), (.6, .45), (.75, .2), (.45, 0), (.05, .15)]]),
    "t": (.7, [[(.35, 1.6), (.35, .15), (.55, 0), (.7, .1)], [(.05, 1), (.7, 1)]]),
    "u": (.95, [[(.05, 1), (.05, .3), (.3, 0), (.65, .2), (.75, .55), (.75, 1), (.75, .1), (.9, 0)]]),
    "v": (.85, [[(0, 1), (.4, 0), (.8, 1)]]),
    "w": (1.15, [[(0, 1), (.3, 0), (.55, .75), (.8, 0), (1.1, 1)]]),
    "x": (.8, [[(0, 1), (.75, 0)], [(.75, 1), (0, 0)]]),
    "y": (.85, [[(0, 1), (.4, .1)], [(.8, 1), (.3, -.6), (.05, -.8)]]),
    "z": (.85, [[(.05, 1), (.75, 1), (.05, 0), (.8, 0)]]),
    "K": (1.0, [[(.1, 1.8), (.1, 0)], [(.9, 1.8), (.15, .8), (.95, 0)]]),
    "T": (1.0, [[(.5, 1.8), (.5, 0)], [(0, 1.8), (1, 1.8)]]),
    "=": (.85, [[(.05, .7), (.75, .7)], [(.05, .3), (.75, .3)]]),
    "+": (.85, [[(.4, .95), (.4, .05)], [(0, .5), (.8, .5)]]),
    "-": (.7, [[(.05, .5), (.6, .5)]]),
    "2": (.8, [[(.1, 1.4), (.4, 1.8), (.75, 1.5), (.6, 1), (.05, 0), (.8, 0)]]),
    ".": (.3, [[(.1, 0), (.13, .03)]]),
}
SLANT = 0.18


def catmull(points, steps=7):
    if len(points) < 3:
        out = []
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            for i in range(steps):
                t = i / steps
                out.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
        return out + [points[-1]]
    p = [points[0]] + points + [points[-1]]
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for s in range(steps):
            t = s / steps
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2 + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3) for k in (0, 1)))
    return out + [points[-1]]


def write(text, x0, base, size, rnd):
    strokes = []
    x = x0
    for ch in text:
        if ch == " ":
            x += size * 0.55
            continue
        glyph = G.get(ch) or G.get(ch.lower())
        if glyph is None:
            x += size * 0.6
            continue
        adv, glyph_strokes = glyph
        wob = rnd.uniform(-0.04, 0.04)
        for stroke in glyph_strokes:
            pts = [(px + rnd.uniform(-.025, .025), py + wob + rnd.uniform(-.025, .025)) for px, py in stroke]
            smooth = catmull(pts)
            n = len(smooth)
            out = []
            for i, (px, py) in enumerate(smooth):
                t = i / max(1, n - 1)
                edge = min(t, 1 - t) / 0.18
                pressure = (0.35 + 0.45 * min(1, edge)) * (0.85 + 0.15 * math.sin(t * 9 + x))
                sx = x + (px + py * SLANT) * size
                sy = base - py * size
                out.append(f"{sx:.2f},{sy:.2f},{min(1, pressure):.3f}")
            strokes.append(" ".join(out))
        x += (adv + 0.16 + rnd.uniform(-0.03, 0.05)) * size
    return strokes


def corpus(path, x0, y0, dp_per_mm, every=2):
    """Real handwriting from a tools/handwriting corpus file (format bestnote-strokes, mm)."""
    import json
    with open(path) as f:
        data = json.load(f)
    for stroke in data["strokes"]:
        if stroke.get("type", "ink") != "ink":
            continue
        samples = stroke["samples"]
        picked = samples[::every] + ([samples[-1]] if len(samples) % every != 1 else [])
        yield " ".join(f"{x0 + s['x'] * dp_per_mm:.2f},{y0 + s['y'] * dp_per_mm:.2f},{s['pressure']:.3f}" for s in picked)


def main(args):
    rnd = random.Random(7)
    if args and args[0] == "--corpus":
        # --corpus FILE X,Y,DP_PER_MM
        x, y, scale = (float(v) for v in args[2].split(","))
        for line in corpus(args[1], x, y, scale):
            print(line)
        return
    for arg in args:
        text, spec = arg.rsplit("@", 1)
        x, base, size = (float(v) for v in spec.split(","))
        for line in write(text, x, base, size, rnd):
            print(line)


if __name__ == "__main__":
    main(sys.argv[1:])
