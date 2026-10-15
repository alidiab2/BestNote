"""Hand-drawn shapes: circle, arrow, box, graph axes, scribble shading. Lengths in mm."""
import math

from . import geom


def _line(a, b, w, bow=0.012):
    """A hand-drawn straight line: slight bow, tremor, sub-mm wobble."""
    rng = w.rng
    n = max(4, int(geom.dist(a, b) / 2.0))
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / L, dx / L
    b_amt = rng.gauss(0, bow) * L
    pts = []
    for i in range(n + 1):
        u = i / n
        off = b_amt * math.sin(math.pi * u)
        pts.append((a[0] + dx * u + nx * off, a[1] + dy * u + ny * off))
    dense = geom.catmull_rom(pts, step=0.08)
    return geom.wobble(dense, rng, 0.05, 9.0)


def circle(doc, w, c, r, gap="shape"):
    rng = w.rng
    start = math.radians(rng.uniform(-120, -60))  # near the top
    sweep = 2 * math.pi + math.radians(rng.uniform(8, 30))  # overlap at the close
    ex = rng.uniform(0.92, 1.06)
    rn = geom.SmoothNoise(rng, 2 * math.pi, 0.035 * r)
    n = int(r * sweep / 0.15)
    pts = []
    for i in range(n + 1):
        a = start - sweep * i / n  # counter-clockwise on screen, like most right-handers
        rr = r + rn(a) - 0.05 * r * (i / n)  # ends slightly inside
        pts.append((c[0] + math.cos(a) * rr * ex, c[1] + math.sin(a) * rr))
    doc.add(geom.wobble(pts, rng, 0.04, 8.0), gap=gap)


def arrow_head(doc, w, tip, ang, size, gap="letter"):
    rng = w.rng
    spread = math.radians(rng.uniform(24, 34))
    l = size * rng.uniform(0.9, 1.1)
    a = (tip[0] - math.cos(ang - spread) * l, tip[1] - math.sin(ang - spread) * l)
    b = (tip[0] - math.cos(ang + spread) * l * rng.uniform(0.9, 1.1),
         tip[1] - math.sin(ang + spread) * l * rng.uniform(0.9, 1.1))
    pts = geom.resample([a, tip], 0.3) + geom.resample([tip, b], 0.3)[1:]
    doc.add(geom.wobble(geom.catmull_rom(pts, 0.08), rng, 0.03, 6.0), gap=gap)


def arrow(doc, w, a, b, gap="shape"):
    doc.add(_line(a, b, w, bow=0.03), gap=gap)
    arrow_head(doc, w, b, math.atan2(b[1] - a[1], b[0] - a[0]), 3.2)


def box(doc, w, x, y, bw, bh, gap="shape"):
    """One stroke, clockwise from the top-left corner, with a small overshoot where it closes."""
    rng = w.rng
    j = lambda: rng.gauss(0, 0.6)
    c = [(x + j(), y + j()), (x + bw + j(), y + j()), (x + bw + j(), y + bh + j()), (x + j(), y + bh + j())]
    pts = []
    for i in range(4):
        seg = _line(c[i], c[(i + 1) % 4], w)
        pts += seg if not pts else seg[1:]
    # overshoot past the start corner
    pts += _line(c[0], (c[0][0] + rng.uniform(1.0, 3.0), c[0][1] + j() * 0.3), w)[1:]
    doc.add(pts, gap=gap)


def axes(doc, w, ox, oy, xl, yl, gap="shape"):
    """y axis drawn upward, x axis rightward, both with arrow heads, ticks, and a sketched curve."""
    rng = w.rng
    doc.add(_line((ox, oy + 2), (ox, oy - yl), w), gap=gap)
    arrow_head(doc, w, (ox, oy - yl), -math.pi / 2, 2.6)
    doc.add(_line((ox - 2, oy), (ox + xl, oy), w), gap="letter")
    arrow_head(doc, w, (ox + xl, oy), 0.0, 2.6)
    for i in range(1, 5):
        tx = ox + xl * i / 5.5
        doc.add(_line((tx, oy - 1.0), (tx, oy + 1.0), w), gap="letter")
    for i in range(1, 4):
        ty = oy - yl * i / 4.5
        doc.add(_line((ox - 1.0, ty), (ox + 1.0, ty), w), gap="letter")
    # a parabola-ish s(t) curve, fast and confident
    pts = []
    for i in range(41):
        u = i / 40
        pts.append((ox + 1 + u * xl * 0.8, oy - 1 - (0.25 * u + 0.75 * u * u) * yl * 0.82))
    doc.add(geom.wobble(geom.catmull_rom(pts, 0.08), rng, 0.08, 10.0), gap="shape", speed=1.3)


def scribble(doc, w, x, y, sw, sh, spacing=1.1, angle_deg=40.0, gap="shape"):
    """Back-and-forth hatching that fills a box: one long fast stroke with rounded turnarounds."""
    rng = w.rng
    ang = math.radians(angle_deg)
    ca, sa = math.cos(ang), math.sin(ang)
    diag = sw * abs(ca) + sh * abs(sa) + sw * abs(sa) + sh * abs(ca)
    pts = []
    k, s = 0, -diag / 2
    while s < diag / 2:
        # line perpendicular offset s, clipped to the box (approximate by long segment then clip)
        ends = []
        for t in (-diag, diag):
            px = x + sw / 2 + (-sa) * s + ca * t
            py = y + sh / 2 + ca * s + sa * t
            ends.append((px, py))
        seg = _clip(ends[0], ends[1], x, y, sw, sh)
        if seg:
            a, b = seg
            over = rng.uniform(-0.6, 0.6)
            a = (a[0] - ca * over, a[1] - sa * over)
            if k % 2:
                a, b = b, a
            pts += [a, b]
            k += 1
        s += spacing * rng.uniform(0.8, 1.25)
    dense = geom.catmull_rom(pts, 0.1)
    doc.add(geom.wobble(dense, rng, 0.12, 6.0), gap=gap, speed=1.8, pressure=1.1)


def _clip(a, b, x, y, sw, sh):
    t0, t1 = 0.0, 1.0
    dx, dy = b[0] - a[0], b[1] - a[1]
    for p, q in ((-dx, a[0] - x), (dx, x + sw - a[0]), (-dy, a[1] - y), (dy, y + sh - a[1])):
        if p == 0:
            if q < 0:
                return None
            continue
        r = q / p
        if p < 0:
            t0 = max(t0, r)
        else:
            t1 = min(t1, r)
    if t0 >= t1:
        return None
    return (a[0] + dx * t0, a[1] + dy * t0), (a[0] + dx * t1, a[1] + dy * t1)
