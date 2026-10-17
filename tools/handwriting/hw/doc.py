"""A stroke document: geometry in writing order, then timed into a .strokes.json file.

File format (version 1), units in millimetres from the page's top-left corner, y down:
{
  "format": "bestnote-strokes", "version": 1, "units": "mm", "sampleRateHz": 240,
  "name": ..., "description": ..., "width": mm, "height": mm, "writer": {...},
  "strokes": [ {"type": "ink" | "hover", "button": false,
                "samples": [ {"x", "y", "t_ms", "pressure", "tiltRad", "orientationRad"}, ... ]}, ... ]
}
"ink" strokes are pen-down contact (DOWN/MOVE/UP). "hover" strokes are the pen in range but not touching
(HOVER_ENTER/MOVE/EXIT, pressure 0). "button": true means the S Pen side button is held for the stroke.
t_ms is absolute from the start of the file and strictly increasing across all strokes.
"""
import json

from . import dynamics, geom

# Air time between strokes (ms): (min, max) + travel distance / speed (mm/s)
GAPS = {
    "letter": ((50, 120), 260.0),
    "deferred": ((90, 190), 220.0),
    "word": ((150, 330), 260.0),
    "line": ((380, 720), 320.0),
    "shape": ((280, 600), 240.0),
    "retrace": ((25, 60), 300.0),
}


class Item:
    __slots__ = ("pts", "kind", "gap", "speed", "pressure", "button", "hold_ms")

    def __init__(self, pts, kind, gap, speed, pressure, button, hold_ms=0.0):
        self.pts, self.kind, self.gap, self.speed, self.pressure, self.button = pts, kind, gap, speed, pressure, button
        self.hold_ms = hold_ms


class Document:
    def __init__(self, name, description, width, height, writer, hover=False):
        self.name, self.description = name, description
        self.width, self.height = width, height
        self.writer = writer
        self.hover = hover
        self.items = []
        self.expect = None  # the written text, one entry per line (saved as <name>.expect.txt)

    def move_to_end(self, spans):
        """Moves the items of [spans] (first, end) to the end, in span order: what the page's stroke list (z-order)
        looks like after those words were moved with the lasso, or erased and brought back with undo."""
        moved = set()
        tail = []
        for a, b in spans:
            for i in range(a, b):
                if i not in moved:
                    moved.add(i)
                    tail.append(self.items[i])
        if tail:
            tail[0].gap = "shape"
        self.items = [it for i, it in enumerate(self.items) if i not in moved] + tail
        self.labels = None  # optional: what each ink stroke is (accuracy tests)

    def add(self, pts, gap="letter", kind="stroke", speed=1.0, pressure=1.0, button=False, hold_ms=0.0):
        """hold_ms: the pen stays down and still that long at the end (hold-to-snap)."""
        if not pts:
            return
        self.items.append(Item(list(pts), kind, gap, speed, pressure, button, hold_ms))

    def add_dot(self, center, gap="deferred", pressure=1.0):
        self.items.append(Item([center], "dot", gap, 1.0, pressure, False))

    def build(self, start_ms=300.0):
        w = self.writer
        rng = w.rng
        strokes = []
        t = start_ms
        last = None
        for it in self.items:
            first = it.pts[0]
            if last is not None:
                if isinstance(it.gap, (int, float)):
                    gap = float(it.gap)  # explicit air time in ms
                else:
                    (lo, hi), travel = GAPS.get(it.gap, GAPS["letter"])
                    gap = rng.uniform(lo, hi) + 1000.0 * geom.dist(last, first) / travel
                if self.hover:
                    hp = dynamics.hover_path(last, first, w, t, t + gap)
                    if hp:
                        # the side button is already held while the pen approaches for a button stroke
                        strokes.append({"type": "hover", "button": bool(it.button), "samples": hp})
                t += gap
            if it.kind == "dot":
                samples, t = dynamics.tap(first, w, t, heavy=it.pressure)
            else:
                samples, t = dynamics.stroke(it.pts, w, t, speed=it.speed, pressure_scale=it.pressure, hold_ms=it.hold_ms)
            strokes.append({"type": "ink", "button": bool(it.button), "samples": samples})
            last = (samples[-1]["x"], samples[-1]["y"])
        return {
            "format": "bestnote-strokes", "version": 1, "units": "mm",
            "sampleRateHz": int(dynamics.SAMPLE_HZ),
            "name": self.name, "description": self.description,
            "width": round(self.width, 2), "height": round(self.height, 2),
            "writer": w.as_dict(),
            "durationMs": round(t, 1),
            "strokes": strokes,
            **({"labels": list(self.labels)} if self.labels else {}),
        }


def save(data, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
