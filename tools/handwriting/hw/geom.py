"""Small polyline helpers (stdlib only). Points are (x, y) tuples in millimetres, y down."""
import math


def dist(a, b):
    return math.hypot(b[0] - a[0], b[1] - a[1])


def length(pts):
    return sum(dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))


def dedupe(pts, eps=1e-6):
    out = [pts[0]]
    for p in pts[1:]:
        if dist(out[-1], p) > eps:
            out.append(p)
    return out


def catmull_rom(pts, step=0.08, alpha=0.5):
    """Centripetal Catmull-Rom through pts, densified to about `step` mm. No overshoot loops."""
    pts = dedupe(pts)
    if len(pts) < 2:
        return list(pts)
    if len(pts) == 2:
        return resample(pts, step)
    ext = [(2 * pts[0][0] - pts[1][0], 2 * pts[0][1] - pts[1][1])] + pts + \
          [(2 * pts[-1][0] - pts[-2][0], 2 * pts[-1][1] - pts[-2][1])]
    out = [pts[0]]
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]

        def tj(ti, a, b):
            return ti + max(dist(a, b), 1e-4) ** alpha

        t0 = 0.0
        t1 = tj(t0, p0, p1)
        t2 = tj(t1, p1, p2)
        t3 = tj(t2, p2, p3)
        n = max(2, int(math.ceil(dist(p1, p2) / step)))
        for k in range(1, n + 1):
            t = t1 + (t2 - t1) * k / n

            def lerp(a, b, ta, tb):
                if tb - ta < 1e-9:
                    return a
                w = (t - ta) / (tb - ta)
                return (a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w)

            a1 = lerp(p0, p1, t0, t1)
            a2 = lerp(p1, p2, t1, t2)
            a3 = lerp(p2, p3, t2, t3)
            b1 = lerp(a1, a2, t0, t2)
            b2 = lerp(a2, a3, t1, t3)
            out.append(lerp(b1, b2, t1, t2))
    return out


def resample(pts, step):
    """Uniform arc-length resampling."""
    pts = dedupe(pts)
    if len(pts) < 2:
        return list(pts)
    out = [pts[0]]
    carry = 0.0
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        seg = dist(a, b)
        d = step - carry
        while d <= seg:
            w = d / seg
            out.append((a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w))
            d += step
        carry = seg - (d - step)
    if dist(out[-1], pts[-1]) > step * 0.25:
        out.append(pts[-1])
    return out


def rdp(pts, eps):
    if len(pts) < 3:
        return list(pts)
    a, b = pts[0], pts[-1]
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy)
    best, idx = -1.0, 0
    for i in range(1, len(pts) - 1):
        p = pts[i]
        if n < 1e-9:
            d = dist(a, p)
        else:
            d = abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / n
        if d > best:
            best, idx = d, i
    if best <= eps:
        return [a, b]
    return rdp(pts[:idx + 1], eps)[:-1] + rdp(pts[idx:], eps)


def moving_average(pts, w):
    if w <= 1 or len(pts) < 3:
        return list(pts)
    h = w // 2
    out = []
    for i in range(len(pts)):
        lo, hi = max(0, i - h), min(len(pts), i + h + 1)
        sx = sum(p[0] for p in pts[lo:hi])
        sy = sum(p[1] for p in pts[lo:hi])
        out.append((sx / (hi - lo), sy / (hi - lo)))
    out[0], out[-1] = pts[0], pts[-1]
    return out


class SmoothNoise:
    """1-D band-limited noise: sum of a few random sinusoids. Deterministic per rng."""

    def __init__(self, rng, wavelength, amplitude, octaves=3):
        self.parts = []
        for o in range(octaves):
            wl = wavelength / (1.9 ** o) * rng.uniform(0.8, 1.25)
            self.parts.append((2 * math.pi / wl, rng.uniform(0, 2 * math.pi), amplitude / (1.8 ** o)))

    def __call__(self, s):
        return sum(a * math.sin(k * s + ph) for k, ph, a in self.parts)


def wobble(pts, rng, amp, wavelength):
    """Displace points by smooth 2-D noise as a function of arc length (hand tremor / imprecision)."""
    nx = SmoothNoise(rng, wavelength, amp)
    ny = SmoothNoise(rng, wavelength, amp)
    out, s = [], 0.0
    for i, p in enumerate(pts):
        if i:
            s += dist(pts[i - 1], p)
        out.append((p[0] + nx(s), p[1] + ny(s)))
    return out
