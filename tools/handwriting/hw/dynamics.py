"""Turns a geometric path (mm) into timed digitizer samples: speed, pressure, tilt, orientation.

Model (kept simple, but grounded in how people write):
- Speed follows the two-thirds power law: v = k * curvature^(-1/3), capped at the writer's vmax, so the pen
  slows on curves and nearly stops at cusps, then accelerates on straights. Acceleration is limited, so
  every stroke starts and ends slowly.
- Pressure ramps up over the first ~20-35 ms after touch-down, is a little lighter when moving fast and a
  little heavier on slow curves, drifts with low-frequency noise and fades out over the last ~25-40 ms.
- Tilt (Android AXIS_TILT, radians from perpendicular) stays in 35-55 degrees and drifts slowly.
  Orientation (AXIS_ORIENTATION) drifts slowly around the writer's hand angle.
- Samples are taken on a continuous 240 Hz digitizer clock, like a Wacom EMR panel.
"""
import math

from . import geom

SAMPLE_HZ = 240.0
DT = 1000.0 / SAMPLE_HZ


class Writer:
    """Personal writing parameters. All lengths in mm, speeds in mm/s."""

    def __init__(self, rng, xh=3.2, vmax=95.0, vcurve=32.0, accel=1600.0, pressure=0.55,
                 tilt_deg=45.0, orientation_rad=2.35, slant_deg=0.0, jitter=1.0, name="default"):
        self.rng = rng
        self.xh = xh
        self.vmax = vmax
        self.vcurve = vcurve  # speed on a 1 mm radius curve
        self.accel = accel
        self.pressure = pressure
        self.tilt_deg = tilt_deg
        self.orientation_rad = orientation_rad
        self.slant_deg = slant_deg
        self.jitter = jitter
        self.name = name
        self._tilt_phase = (rng.uniform(0, 6.28), rng.uniform(0, 6.28), rng.uniform(0, 6.28))
        self._p_noise = geom.SmoothNoise(rng, 900.0, 0.07)  # over time in ms
        self._v_noise = geom.SmoothNoise(rng, 3 * xh, 0.12)  # over arc length

    def tilt(self, t_ms):
        a, b, _ = self._tilt_phase
        d = self.tilt_deg + 5.0 * math.sin(2 * math.pi * t_ms / 9000.0 + a) \
            + 1.8 * math.sin(2 * math.pi * t_ms / 2300.0 + b)
        return math.radians(min(55.0, max(35.0, d)))

    def orientation(self, t_ms):
        c = self._tilt_phase[2]
        return self.orientation_rad + 0.12 * math.sin(2 * math.pi * t_ms / 7000.0 + c) \
            + 0.04 * math.sin(2 * math.pi * t_ms / 1700.0 + 2 * c)

    def as_dict(self):
        return {"name": self.name, "xHeightMm": self.xh, "vmaxMmPerS": self.vmax, "pressure": self.pressure,
                "tiltDeg": self.tilt_deg, "orientationRad": self.orientation_rad}


def _smoothstep(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def _curvature(pts, ds, win=3):
    n = len(pts)
    k = [0.0] * n
    for i in range(n):
        a, b = max(0, i - win), min(n - 1, i + win)
        if b - a < 2:
            continue
        p0, p1, p2 = pts[a], pts[i], pts[b]
        a1 = math.atan2(p1[1] - p0[1], p1[0] - p0[0])
        a2 = math.atan2(p2[1] - p1[1], p2[0] - p1[0])
        da = abs((a2 - a1 + math.pi) % (2 * math.pi) - math.pi)
        k[i] = da / max(ds * (b - a), 1e-6)
    # light smoothing
    return [sum(k[max(0, i - 2):i + 3]) / len(k[max(0, i - 2):i + 3]) for i in range(n)]


def _sample(t0_abs, t_rel, pos, pressure, w):
    t = t0_abs + t_rel
    return {"x": round(pos[0], 3), "y": round(pos[1], 3), "t_ms": round(t, 2),
            "pressure": round(min(1.0, max(0.0, pressure)), 3),
            "tiltRad": round(w.tilt(t), 4), "orientationRad": round(w.orientation(t), 4)}


def next_tick(t):
    return math.ceil(t / DT - 1e-9) * DT


def tap(center, w, t_start, flick=None, heavy=1.0):
    """A dot: a short touch with a tiny flick in some direction. Returns (samples, t_end)."""
    rng = w.rng
    dur = rng.uniform(38.0, 75.0)
    ang = rng.uniform(-0.6, 0.6) + (flick if flick is not None else 0.9)  # mostly down-right
    flen = rng.uniform(0.12, 0.35) * min(1.6, w.xh / 3.2)
    t0 = next_tick(t_start)
    out, t = [], 0.0
    peak = w.pressure * rng.uniform(0.85, 1.1) * heavy
    while t <= dur:
        u = t / dur
        p = peak * math.sin(math.pi * min(1.0, 0.12 + 0.88 * u)) ** 0.8
        m = _smoothstep(u)
        pos = (center[0] + math.cos(ang) * flen * m, center[1] + math.sin(ang) * flen * m)
        out.append(_sample(t0, t, pos, p, w))
        t += DT
    return out, out[-1]["t_ms"]


def stroke(pts, w, t_start, speed=1.0, pressure_scale=1.0):
    """Timed samples along a dense polyline. Returns (samples, t_end_abs)."""
    rng = w.rng
    ds = 0.04
    path = geom.resample(pts, ds)
    if len(path) < 3 or geom.length(path) < 0.35:
        c = path[len(path) // 2]
        return tap(c, w, t_start)
    kappa = _curvature(path, ds)
    vmax = w.vmax * speed
    kc = w.vcurve * speed  # v = kc * kappa^(-1/3)
    v = []
    s_acc = 0.0
    for i, k in enumerate(kappa):
        vk = kc * (max(k, 1e-4) ** (-1.0 / 3.0))
        vk = min(vmax, vk) * (1.0 + w._v_noise(s_acc))
        v.append(max(4.0, vk))
        s_acc += ds
    a = w.accel * speed
    v[0] = min(v[0], rng.uniform(6, 16) * speed)
    for i in range(1, len(v)):
        v[i] = min(v[i], math.sqrt(v[i - 1] ** 2 + 2 * a * ds))
    v[-1] = min(v[-1], rng.uniform(8, 25) * speed)
    for i in range(len(v) - 2, -1, -1):
        v[i] = min(v[i], math.sqrt(v[i + 1] ** 2 + 2 * a * 1.4 * ds))
    # time at each path point (ms)
    tt = [0.0]
    for i in range(1, len(path)):
        tt.append(tt[-1] + 1000.0 * ds / (0.5 * (v[i] + v[i - 1])))
    dwell = rng.uniform(0.0, 10.0)
    total = tt[-1] + dwell
    ramp = rng.uniform(18.0, 34.0)
    fade = rng.uniform(22.0, 40.0)
    base = w.pressure * pressure_scale * rng.uniform(0.94, 1.06)
    t0 = next_tick(t_start)
    out = []
    j = 0
    t = 0.0
    while True:
        tm = t - dwell
        if tm <= 0:
            pos, vi, ki = path[0], v[0], kappa[0]
        else:
            while j < len(tt) - 2 and tt[j + 1] < tm:
                j += 1
            seg = tt[j + 1] - tt[j]
            u = 0.0 if seg <= 0 else min(1.0, (tm - tt[j]) / seg)
            p0, p1 = path[j], path[j + 1]
            pos = (p0[0] + (p1[0] - p0[0]) * u, p0[1] + (p1[1] - p0[1]) * u)
            vi, ki = v[j], kappa[j]
        env_in = 0.1 + 0.9 * _smoothstep(t / ramp)
        env_out = 0.18 + 0.82 * _smoothstep((total - t) / fade)
        speed_term = 1.0 - 0.24 * min(1.0, vi / max(vmax, 1.0))
        curve_term = 1.0 + 0.08 * min(1.0, ki * 0.6)
        p = base * env_in * env_out * speed_term * curve_term * (1.0 + w._p_noise(t0 + t))
        # sub-pixel digitizer noise
        pos = (pos[0] + rng.gauss(0, 0.008), pos[1] + rng.gauss(0, 0.008))
        out.append(_sample(t0, t, pos, p, w))
        if t >= total:
            break
        t += DT
    return out, out[-1]["t_ms"]


def hover_path(a, b, w, t_start, t_end):
    """Pen in the air between strokes: lifts off, travels in a shallow arc, comes back down."""
    out = []
    t0 = next_tick(t_start + 12.0)
    t1 = t_end - 10.0
    if t1 - t0 < 3 * DT:
        return out
    t = t0
    while t <= t1:
        u = _smoothstep((t - t0) / (t1 - t0))
        x = a[0] + (b[0] - a[0]) * u
        y = a[1] + (b[1] - a[1]) * u - 1.2 * math.sin(math.pi * u)
        out.append({"x": round(x, 3), "y": round(y, 3), "t_ms": round(t, 2), "pressure": 0.0,
                    "tiltRad": round(w.tilt(t), 4), "orientationRad": round(w.orientation(t), 4)})
        t += DT
    return out
