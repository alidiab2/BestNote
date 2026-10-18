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


def circle(doc, w, c, r, gap="shape", hold_ms=0.0):
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
    doc.add(geom.wobble(pts, rng, 0.04, 8.0), gap=gap, hold_ms=hold_ms)


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


def box(doc, w, x, y, bw, bh, gap="shape", hold_ms=0.0):
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
    doc.add(pts, gap=gap, hold_ms=hold_ms)


def line(doc, w, a, b, gap="shape", hold_ms=0.0):
    doc.add(_line(a, b, w, bow=0.02), gap=gap, hold_ms=hold_ms)


def ellipse(doc, w, c, rx, ry, rot_deg=0.0, gap="shape", hold_ms=0.0):
    """Like circle(), stretched and turned: one loop with a small overlap at the close."""
    rng = w.rng
    start = math.radians(rng.uniform(-150, -100))
    sweep = 2 * math.pi + math.radians(rng.uniform(6, 22))
    rn = geom.SmoothNoise(rng, 2 * math.pi, 0.03)
    ca, sa = math.cos(math.radians(rot_deg)), math.sin(math.radians(rot_deg))
    n = int(max(rx, ry) * sweep / 0.15)
    pts = []
    for i in range(n + 1):
        a = start - sweep * i / n
        k = 1.0 + rn(a) - 0.04 * (i / n)
        u, v = math.cos(a) * rx * k, math.sin(a) * ry * k
        pts.append((c[0] + u * ca - v * sa, c[1] + u * sa + v * ca))
    doc.add(geom.wobble(pts, rng, 0.04, 8.0), gap=gap, hold_ms=hold_ms)


def polygon(doc, w, corners, gap="shape", hold_ms=0.0, overshoot=True):
    """One closed stroke through corners (mm), each corner jittered, closing back on the first one."""
    rng = w.rng
    j = lambda: rng.gauss(0, 0.5)
    c = [(x + j(), y + j()) for x, y in corners]
    pts = []
    for i in range(len(c)):
        seg = _line(c[i], c[(i + 1) % len(c)], w)
        pts += seg if not pts else seg[1:]
    if overshoot:
        a, b = c[0], c[1]
        d = geom.dist(a, b) or 1.0
        k = rng.uniform(0.04, 0.12)
        pts += _line(a, (a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k), w)[1:]
    doc.add(pts, gap=gap, hold_ms=hold_ms)


def regular_polygon(doc, w, c, r, n, rot_deg=-90.0, gap="shape", hold_ms=0.0):
    corners = [(c[0] + r * math.cos(math.radians(rot_deg) + 2 * math.pi * i / n),
                c[1] + r * math.sin(math.radians(rot_deg) + 2 * math.pi * i / n)) for i in range(n)]
    polygon(doc, w, corners, gap=gap, hold_ms=hold_ms)


def arrow_one_stroke(doc, w, a, b, size=3.6, gap="shape", hold_ms=0.0):
    """Shaft, then one barb, back to the tip and the other barb, without lifting the pen."""
    rng = w.rng
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    spread = math.radians(rng.uniform(24, 34))
    l1, l2 = size * rng.uniform(0.9, 1.1), size * rng.uniform(0.9, 1.1)
    p1 = (b[0] - math.cos(ang - spread) * l1, b[1] - math.sin(ang - spread) * l1)
    p2 = (b[0] - math.cos(ang + spread) * l2, b[1] - math.sin(ang + spread) * l2)
    pts = _line(a, b, w, bow=0.02)
    pts += _line(b, p1, w, bow=0.0)[1:]
    pts += _line(p1, b, w, bow=0.0)[1:]
    pts += _line(b, p2, w, bow=0.0)[1:]
    doc.add(pts, gap=gap, hold_ms=hold_ms)


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


def scribble(doc, w, x, y, sw, sh, spacing=1.1, angle_deg=40.0, gap="shape", tilt_deg=None, pressure=1.1):
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
    doc.add(geom.wobble(dense, rng, 0.12, 6.0), gap=gap, speed=1.8, pressure=pressure, tilt_deg=tilt_deg)


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


# ---------------------------------------------------------------- CANVAS-17: sloppier shapes for the accuracy corpus

def _rounded(corners, w, r_mm, closed=True, bow=0.025):
    """Straight-ish sides through corners whose corners are cut by a small hand-drawn curve of about r_mm."""
    rng = w.rng
    pts = []
    m = len(corners)
    n = m if closed else m - 1
    for i in range(n):
        a, b = corners[i], corners[(i + 1) % m]
        L = geom.dist(a, b) or 1.0
        ra = min(r_mm * rng.uniform(0.6, 1.4), 0.3 * L) if (closed or i > 0) else 0.0
        rb = min(r_mm * rng.uniform(0.6, 1.4), 0.3 * L) if (closed or i < n - 1) else 0.0
        ux, uy = (b[0] - a[0]) / L, (b[1] - a[1]) / L
        s = (a[0] + ux * ra, a[1] + uy * ra)
        e = (b[0] - ux * rb, b[1] - uy * rb)
        seg = _line(s, e, w, bow=bow)
        if pts:
            # the corner: a short curve from the previous side's end through near the corner
            c = (a[0] + rng.gauss(0, 0.15), a[1] + rng.gauss(0, 0.15))
            curve = geom.catmull_rom([pts[-1], ((pts[-1][0] + c[0]) / 2, (pts[-1][1] + c[1]) / 2),
                                      ((c[0] + s[0]) / 2, (c[1] + s[1]) / 2), s], step=0.08)
            pts += curve[1:] + seg[1:]
        else:
            pts += seg
    return pts


def sloppy_box(doc, w, cx, cy, bw, bh, rot_deg=0.0, corner_mm=1.5, close="overshoot", hold_ms=0.0, gap="shape"):
    """A quickly drawn box: rounded corners, uneven sides, slightly turned; closes with an overshoot or a gap."""
    rng = w.rng
    j = lambda: rng.gauss(0, 0.04 * min(bw, bh))
    ca, sa = math.cos(math.radians(rot_deg)), math.sin(math.radians(rot_deg))
    raw = [(-bw / 2, -bh / 2), (bw / 2, -bh / 2), (bw / 2, bh / 2), (-bw / 2, bh / 2)]
    c = [(cx + (u + j()) * ca - (v + j()) * sa, cy + (u + j()) * sa + (v + j()) * ca) for u, v in raw]
    pts = _rounded(c, w, corner_mm)
    a, b = c[0], c[1]
    if close == "overshoot":
        k = rng.uniform(0.05, 0.15)
        pts += _line(pts[-1], (a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k), w, bow=0.0)[1:]
    elif close == "gap":
        # stops short of the first corner by a few percent of the side
        L = geom.dist(c[3], a)
        cut = rng.uniform(0.06, 0.14) * L
        while len(pts) > 2 and geom.dist(pts[-1], a) < cut:
            pts.pop()
    doc.add(pts, gap=gap, hold_ms=hold_ms)


def open_circle(doc, w, c, r, gap_deg=25.0, squash=1.0, hold_ms=0.0, gap="shape", clockwise=False):
    """A circle that doesn't quite close (the pen lifts gap_deg short of the start), radius drifting a little."""
    rng = w.rng
    start = math.radians(rng.uniform(-150, -30))
    sweep = 2 * math.pi - math.radians(gap_deg)
    rn = geom.SmoothNoise(rng, 2 * math.pi, 0.05 * r)
    n = int(r * sweep / 0.15)
    sgn = 1 if clockwise else -1
    pts = []
    for i in range(n + 1):
        a = start + sgn * sweep * i / n
        rr = r + rn(a) - 0.06 * r * (i / n)
        pts.append((c[0] + math.cos(a) * rr, c[1] + math.sin(a) * rr * squash))
    doc.add(geom.wobble(pts, rng, 0.05, 8.0), gap=gap, hold_ms=hold_ms)


def arrow_closed_head(doc, w, a, b, size=4.0, hold_ms=0.0, gap="shape"):
    """Shaft, then a closed triangular head drawn without lifting: tip, barb, other barb, back to the tip."""
    rng = w.rng
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    spread = math.radians(rng.uniform(22, 32))
    l = size * rng.uniform(0.9, 1.1)
    p1 = (b[0] - math.cos(ang - spread) * l, b[1] - math.sin(ang - spread) * l)
    p2 = (b[0] - math.cos(ang + spread) * l, b[1] - math.sin(ang + spread) * l)
    pts = _line(a, b, w, bow=0.02)
    pts += _line(b, p1, w, bow=0.0)[1:]
    pts += _line(p1, p2, w, bow=0.0)[1:]
    pts += _line(p2, b, w, bow=0.0)[1:]
    doc.add(pts, gap=gap, hold_ms=hold_ms)


def arrow_one_barb_first(doc, w, a, b, size=4.0, hold_ms=0.0, gap="shape"):
    """Shaft, then the head as one 'V' through the tip: barb, back to the tip, other barb (sloppier barbs)."""
    rng = w.rng
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    s1, s2 = math.radians(rng.uniform(20, 40)), math.radians(rng.uniform(20, 40))
    l1, l2 = size * rng.uniform(0.75, 1.25), size * rng.uniform(0.75, 1.25)
    p1 = (b[0] - math.cos(ang - s1) * l1, b[1] - math.sin(ang - s1) * l1)
    p2 = (b[0] - math.cos(ang + s2) * l2, b[1] - math.sin(ang + s2) * l2)
    pts = _line(a, b, w, bow=0.03)
    pts += geom.catmull_rom([b, p1], 0.08)[1:]
    pts += geom.catmull_rom([p1, (b[0] + rng.gauss(0, 0.2), b[1] + rng.gauss(0, 0.2))], 0.08)[1:]
    pts += geom.catmull_rom([pts[-1], p2], 0.08)[1:]
    doc.add(geom.wobble(pts, rng, 0.03, 8.0), gap=gap, hold_ms=hold_ms)


def diamond(doc, w, c, rw, rh, hold_ms=0.0, gap="shape"):
    """A rhombus: top, right, bottom, left."""
    corners = [(c[0], c[1] - rh), (c[0] + rw, c[1]), (c[0], c[1] + rh), (c[0] - rw, c[1])]
    rng = w.rng
    corners = [(x + rng.gauss(0, 0.4), y + rng.gauss(0, 0.4)) for x, y in corners]
    pts = _rounded(corners, w, 0.8)
    pts += _line(pts[-1], corners[0], w, bow=0.0)[1:]
    a, b = corners[0], corners[1]
    pts += _line(a, (a[0] + (b[0] - a[0]) * 0.08, a[1] + (b[1] - a[1]) * 0.08), w, bow=0.0)[1:]
    doc.add(pts, gap=gap, hold_ms=hold_ms)


def pentagram(doc, w, c, r, rot_deg=-90.0, hold_ms=0.0, gap="shape"):
    """The classic one-stroke star: five straight lines through every second point of a pentagon."""
    rng = w.rng
    pts5 = [(c[0] + r * math.cos(math.radians(rot_deg) + 2 * math.pi * i / 5) + rng.gauss(0, 0.04 * r),
             c[1] + r * math.sin(math.radians(rot_deg) + 2 * math.pi * i / 5) + rng.gauss(0, 0.04 * r)) for i in range(5)]
    order = [0, 2, 4, 1, 3, 0]
    pts = []
    for i in range(5):
        seg = _line(pts5[order[i]], pts5[order[i + 1]], w, bow=0.015)
        pts += seg if not pts else seg[1:]
    doc.add(pts, gap=gap, hold_ms=hold_ms)


def star_outline(doc, w, c, r_out, r_in, points=5, rot_deg=-90.0, hold_ms=0.0, gap="shape"):
    """A star drawn as its outline: alternating outer and inner corners."""
    rng = w.rng
    corners = []
    for i in range(2 * points):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(rot_deg) + math.pi * i / points
        corners.append((c[0] + r * math.cos(a) + rng.gauss(0, 0.03 * r_out), c[1] + r * math.sin(a) + rng.gauss(0, 0.03 * r_out)))
    polygon(doc, w, corners, hold_ms=hold_ms, gap=gap)


def zigzag(doc, w, a, step, n, amp, hold_ms=0.0, gap="shape"):
    """An open polyline: n segments going right, alternately up and down."""
    rng = w.rng
    corners = [a]
    for i in range(1, n + 1):
        corners.append((a[0] + step * i + rng.gauss(0, 0.3), a[1] + (amp if i % 2 else 0.0) + rng.gauss(0, 0.3)))
    pts = []
    for i in range(n):
        seg = _line(corners[i], corners[i + 1], w, bow=0.02)
        pts += seg if not pts else seg[1:]
    doc.add(pts, gap=gap, hold_ms=hold_ms)


def spiral(doc, w, c, r, turns=2.2, hold_ms=0.0, gap="shape"):
    rng = w.rng
    n = int(turns * 80)
    pts = [(c[0] + r * (0.25 + 0.75 * i / n) * math.cos(2 * math.pi * turns * i / n),
            c[1] + r * (0.25 + 0.75 * i / n) * math.sin(2 * math.pi * turns * i / n)) for i in range(n + 1)]
    doc.add(geom.wobble(pts, rng, 0.04, 8.0), gap=gap, hold_ms=hold_ms)


def wave(doc, w, a, length, amp, periods=2.0, hold_ms=0.0, gap="shape"):
    rng = w.rng
    n = int(length / 0.2)
    pts = [(a[0] + length * i / n, a[1] + amp * math.sin(2 * math.pi * periods * i / n)) for i in range(n + 1)]
    doc.add(geom.wobble(pts, rng, 0.05, 8.0), gap=gap, hold_ms=hold_ms)
