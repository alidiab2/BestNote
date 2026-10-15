"""PNG previews of stroke files (needs Pillow from the venv).

Ink width follows pressure like a simple fountain pen, so ramps, fades and dots are visible.
mode="order" colours strokes by writing order (blue -> red) and draws hover paths in grey.
"""
import colorsys

from PIL import Image, ImageDraw


def render(data, path, px_per_mm=9.0, mode="ink", nib_mm=0.5, margin_mm=4.0):
    W = int((data["width"] + 2 * margin_mm) * px_per_mm)
    H = int((data["height"] + 2 * margin_mm) * px_per_mm)
    ss = 2  # supersample
    img = Image.new("RGB", (W * ss, H * ss), (252, 251, 247))
    d = ImageDraw.Draw(img)
    k = px_per_mm * ss
    ink_strokes = [s for s in data["strokes"] if s["type"] == "ink"]
    n = max(1, len(ink_strokes))
    idx = 0

    def P(s):
        return ((s["x"] + margin_mm) * k, (s["y"] + margin_mm) * k)

    for st in data["strokes"]:
        sm = st["samples"]
        if st["type"] == "hover":
            if mode == "order":
                for a, b in zip(sm, sm[1:]):
                    d.line([P(a), P(b)], fill=(200, 200, 210), width=max(1, ss))
            continue
        if mode == "order":
            h = 0.66 * (1 - idx / n)
            col = tuple(int(c * 255) for c in colorsys.hsv_to_rgb(h, 0.85, 0.75))
        else:
            col = (20, 30, 60) if not st.get("button") else (230, 80, 80)
        idx += 1
        for i, s in enumerate(sm):
            r = max(0.5, nib_mm * (0.25 + 0.95 * s["pressure"]) * k / 2)
            x, y = P(s)
            if i:
                px, py = P(sm[i - 1])
                d.line([(px, py), (x, y)], fill=col, width=int(2 * r))
            d.ellipse([x - r, y - r, x + r, y + r], fill=col)
    img = img.resize((W, H), Image.LANCZOS)
    img.save(path)
    return path
