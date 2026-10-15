"""Arabic words as single-line handwriting, by skeletonizing a shaped OFL font (Aref Ruqaa).

Pipeline per word:
1. Shape with HarfBuzz (joined forms, ligatures, RTL) and fill the glyph outlines into a bitmap
   with the non-zero winding rule.
2. Split the bitmap into connected components. Small ones are dots and diacritics; big ones are the
   letter bodies (a word breaks into several bodies after non-joining letters like ا د ر و).
3. Skeletonize each body, turn the skeleton into a graph, prune spurs, then walk it from its right end:
   short dead-end branches (teeth, ascenders) are drawn up and back down (a retrace, like a real pen),
   otherwise the walk prefers continuing leftward with the least turning. A lift happens only when the
   walk is stuck.
4. Bodies are written right to left; then dots and diacritics, right to left, as short separate strokes.

Needs the venv (numpy, scikit-image, uharfbuzz, fontTools).
"""
import math
import os

import numpy as np
import uharfbuzz as hb
from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont
from skimage.draw import polygon as fill_polygon
from skimage.measure import label, regionprops
from scipy.ndimage import distance_transform_edt
from skimage.feature import peak_local_max
from skimage.morphology import skeletonize, binary_closing, disk

from . import geom

FONT_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "fonts", "arefruqaa", "ArefRuqaa-Regular.ttf")
PX_PER_EM = 220.0


class _FlatPen(BasePen):
    def __init__(self, glyphset, steps=10):
        super().__init__(glyphset)
        self.contours, self.cur, self.steps = [], [], steps

    def _moveTo(self, p):
        self.cur = [p]

    def _lineTo(self, p):
        self.cur.append(p)

    def _curveToOne(self, p1, p2, p3):
        p0 = self.cur[-1]
        for i in range(1, self.steps + 1):
            t = i / self.steps
            mt = 1 - t
            self.cur.append((mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
                             mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1]))

    def _closePath(self):
        if len(self.cur) > 2:
            self.contours.append(self.cur)
        self.cur = []

    _endPath = _closePath


class ArabicFont:
    def __init__(self, path=FONT_PATH):
        self.tt = TTFont(path)
        self.upem = self.tt["head"].unitsPerEm
        self.glyphset = self.tt.getGlyphSet()
        self.order = self.tt.getGlyphOrder()
        blob = hb.Blob.from_file_path(path)
        self.hbfont = hb.Font(hb.Face(blob))

    def shape(self, text):
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(self.hbfont, buf, {"liga": True, "calt": True})
        return buf.glyph_infos, buf.glyph_positions

    def word_contours(self, text):
        """Outline contours of the shaped word in font units, x to the right, y up."""
        infos, pos = self.shape(text)
        x = 0
        contours = []
        for info, p in zip(infos, pos):
            name = self.order[info.codepoint]
            pen = _FlatPen(self.glyphset)
            self.glyphset[name].draw(pen)
            ox, oy = x + p.x_offset, p.y_offset
            for c in pen.contours:
                contours.append([(ox + a, oy + b) for a, b in c])
            x += p.x_advance
        return contours, x


def _rasterize(contours, scale):
    xs = [p[0] for c in contours for p in c]
    ys = [p[1] for c in contours for p in c]
    pad = 6
    minx, maxy = min(xs), max(ys)
    W = int((max(xs) - minx) * scale) + 2 * pad
    H = int((maxy - min(ys)) * scale) + 2 * pad
    wind = np.zeros((H, W), dtype=np.int32)
    for c in contours:
        cols = np.array([(p[0] - minx) * scale + pad for p in c])
        rows = np.array([(maxy - p[1]) * scale + pad for p in c])
        area = 0.5 * np.sum(cols * np.roll(rows, -1) - np.roll(cols, -1) * rows)
        rr, cc = fill_polygon(rows, cols, (H, W))
        wind[rr, cc] += 1 if area > 0 else -1
    mask = wind != 0
    mask = binary_closing(mask, disk(1))
    return mask, (minx, maxy, pad)


# ---------------------------------------------------------------- skeleton graph

_N8 = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


def _neighbors(sk, r, c):
    H, W = sk.shape
    for dr, dc in _N8:
        rr, cc = r + dr, c + dc
        if 0 <= rr < H and 0 <= cc < W and sk[rr, cc]:
            yield rr, cc


def _graph(sk):
    """Returns (nodes: id->(r,c), edges: list of [a, b, pixel_path])."""
    pix = set(zip(*np.nonzero(sk)))
    deg = {p: sum(1 for _ in _neighbors(sk, *p)) for p in pix}
    special = {p for p, d in deg.items() if d != 2}
    # merge clusters of adjacent junction pixels into one node
    node_of, nodes = {}, {}
    for p in special:
        if p in node_of:
            continue
        nid = len(nodes)
        stack, members = [p], []
        node_of[p] = nid
        while stack:
            q = stack.pop()
            members.append(q)
            for n in _neighbors(sk, *q):
                if n in special and n not in node_of and deg[n] > 2 and deg[q] > 2:
                    node_of[n] = nid
                    stack.append(n)
        nodes[nid] = (sum(m[0] for m in members) / len(members), sum(m[1] for m in members) / len(members))
    edges, seen = [], set()
    for p in special:
        for n in _neighbors(sk, *p):
            if n in node_of and node_of[n] == node_of[p]:
                continue
            if (p, n) in seen:
                continue
            path, prev, cur = [p], p, n
            while cur not in special:
                path.append(cur)
                nxt = [q for q in _neighbors(sk, *cur) if q != prev and q not in path[-3:]]
                if not nxt:
                    break
                prev, cur = cur, nxt[0]
            path.append(cur)
            if cur in special:
                seen.add((cur, path[-2]))
                seen.add((p, n))
                edges.append([node_of[p], node_of[cur], path])
    if not nodes and pix:  # a pure loop, e.g. a closed letter head drawn alone
        start = min(pix, key=lambda q: (q[1], q[0]))
        nodes[0] = start
        path, prev, cur = [start], None, start
        while True:
            nxt = [q for q in _neighbors(sk, *cur) if q != prev and q not in path[1:]]
            if not nxt:
                break
            prev, cur = cur, nxt[0]
            path.append(cur)
            if cur == start:
                break
        path.append(start)
        edges.append([0, 0, path])
    # drop duplicate edges between the same nodes with the same pixels (from both directions)
    uniq, keys = [], set()
    for a, b, path in edges:
        key = (min(a, b), max(a, b), len(path), min(path[0], path[-1]))
        if key in keys:
            continue
        keys.add(key)
        uniq.append([a, b, path])
    return nodes, uniq


def _plen(path):
    return sum(math.hypot(path[i + 1][0] - path[i][0], path[i + 1][1] - path[i][1]) for i in range(len(path) - 1))


def _prune(nodes, edges, min_len):
    for _ in range(3):
        deg = {}
        for a, b, _p in edges:
            deg[a] = deg.get(a, 0) + 1
            deg[b] = deg.get(b, 0) + 1
        keep = []
        for e in edges:
            a, b, path = e
            spur = (deg[a] == 1) != (deg[b] == 1)
            if spur and _plen(path) < min_len and len(edges) > 1:
                continue
            keep.append(e)
        if len(keep) == len(edges):
            break
        edges = keep
        # merge degree-2 nodes
        changed = True
        while changed:
            changed = False
            deg = {}
            for a, b, _p in edges:
                deg[a] = deg.get(a, 0) + 1
                deg[b] = deg.get(b, 0) + 1
            for nid, d in deg.items():
                if d != 2:
                    continue
                es = [e for e in edges if e[0] == nid or e[1] == nid]
                if len(es) != 2 or es[0] is es[1] or es[0][0] == es[0][1]:
                    continue
                e1, e2 = es
                p1 = e1[2] if e1[1] == nid else e1[2][::-1]
                o1 = e1[0] if e1[1] == nid else e1[1]
                p2 = e2[2] if e2[0] == nid else e2[2][::-1]
                o2 = e2[1] if e2[0] == nid else e2[0]
                edges = [e for e in edges if e is not e1 and e is not e2] + [[o1, o2, p1 + p2[1:]]]
                changed = True
                break
    return edges


def _walk(nodes, edges, spur_len):
    """Orders the skeleton edges into pen paths. Returns list of pixel paths (row, col)."""
    if not edges:
        return []
    adj = {}
    for i, (a, b, _p) in enumerate(edges):
        adj.setdefault(a, []).append(i)
        adj.setdefault(b, []).append(i)
    deg = {n: len(v) for n, v in adj.items()}
    used = [False] * len(edges)

    def oriented(i, frm):
        a, b, p = edges[i]
        return (p, b) if a == frm else (p[::-1], a)

    def is_spur(i, frm):
        p, to = oriented(i, frm)
        return deg.get(to, 0) == 1 and _plen(p) < spur_len and to != frm

    def direction(p, k=8):
        q = p[min(len(p) - 1, k)]
        return math.atan2(-(q[0] - p[0][0]), q[1] - p[0][1])  # y up

    ends = [n for n, d in deg.items() if d == 1] or list(adj)
    # right end first; among near-ties prefer the higher one (Arabic letters often start at the top)
    rmax = max(nodes[n][1] for n in ends)
    start = min((n for n in ends if nodes[n][1] > rmax - 12), key=lambda n: nodes[n][0])
    paths, cur_path, node, heading = [], [], start, math.pi
    while not all(used):
        cand = [i for i in adj.get(node, []) if not used[i]]
        if not cand:
            if cur_path:
                paths.append(cur_path)
            cur_path = []
            # lift: resume at the rightmost node that still has unused edges
            rest = [n for n in adj if any(not used[i] for i in adj[n])]
            node = max(rest, key=lambda n: (nodes[n][1], -nodes[n][0]))
            continue
        spurs = [i for i in cand if is_spur(i, node)]
        if spurs and len(cand) > 1:
            i = spurs[0]
            p, _to = oriented(i, node)
            used[i] = True
            cur_path = cur_path + p[1:] if cur_path else list(p)
            cur_path += p[::-1][1:]  # back down the same line
            continue

        def score(i):
            p, _to = oriented(i, node)
            d = direction(p)
            turn = abs((d - heading + math.pi) % (2 * math.pi) - math.pi)
            left = -math.cos(d)  # 1 when heading left
            return turn - 0.6 * left

        i = min(cand, key=score)
        p, to = oriented(i, node)
        used[i] = True
        cur_path = cur_path + p[1:] if cur_path else list(p)
        tail = p[-min(len(p), 9):]
        heading = math.atan2(-(tail[-1][0] - tail[0][0]), tail[-1][1] - tail[0][1])
        node = to
    if cur_path:
        paths.append(cur_path)
    return paths


def _add_loops(paths, comp, sk, half):
    """Ruqaa fills the small heads of و ق ف م ط ة as solid blobs, which skeletonize to nothing.
    A hand draws them as small loops, so put a loop back wherever the stroke is much thicker than usual."""
    dt = distance_transform_edt(comp)
    if not sk.any():
        return paths
    peaks = peak_local_max(dt, min_distance=max(3, int(2.5 * half)), threshold_abs=1.95 * half)
    out = [list(p) for p in paths]
    for pr, pc in peaks:
        rad = float(dt[pr, pc])
        best = None
        for pi, p in enumerate(out):
            for i, (r, c) in enumerate(p):
                d = math.hypot(r - pr, c - pc)
                if best is None or d < best[0]:
                    best = (d, pi, i)
        if best is None or best[0] > 1.5 * rad:
            continue
        _d, pi, i = best
        r0, c0 = out[pi][i]
        lr = max(1.5, rad - 0.7 * half)  # loop radius: the hole a pen leaves inside the blob
        # loop centre sits beyond the path point, towards the blob centre
        ang0 = math.atan2(r0 - pr, c0 - pc)
        cr, cc = r0 - math.sin(ang0) * lr, c0 - math.cos(ang0) * lr
        n = max(12, int(2 * math.pi * lr / 1.5))
        loop = [(cr + math.sin(ang0 - 2 * math.pi * k / n) * lr, cc + math.cos(ang0 - 2 * math.pi * k / n) * lr)
                for k in range(1, n + 1)]
        out[pi] = out[pi][:i + 1] + loop + out[pi][i:]
    return out


# ---------------------------------------------------------------- public API

_FONT = None


def font():
    global _FONT
    if _FONT is None:
        _FONT = ArabicFont()
    return _FONT


def word_strokes(word, size_mm):
    """Strokes for one word, in writing order, in local mm (origin at the word's right end, on the
    baseline, x growing left-to-right as on the page, y down). size_mm is the em size.
    Returns (bodies, marks, width_mm): lists of polylines."""
    f = font()
    contours, adv = f.word_contours(word)
    scale = PX_PER_EM / f.upem
    mask, (minx, maxy, pad) = _rasterize(contours, scale)
    mm_per_px = size_mm / PX_PER_EM
    width_mm = adv * size_mm / f.upem

    def to_mm(r, c):
        x_units = (c - pad) / scale + minx
        y_units = maxy - (r - pad) / scale
        return (x_units * size_mm / f.upem - width_mm, -y_units * size_mm / f.upem)

    lab = label(mask, connectivity=2)
    props = regionprops(lab)
    full_sk = skeletonize(mask)
    stroke_w = max(2.0, 2.0 * mask.sum() / max(1, full_sk.sum()))
    half = float(np.median(distance_transform_edt(mask)[full_sk > 0])) if full_sk.any() else 4.0
    bodies, marks = [], []
    for rp in props:
        r0, c0, r1, c1 = rp.bbox
        ext = max(r1 - r0, c1 - c0) / PX_PER_EM
        comp = lab[r0:r1, c0:c1] == rp.label
        area_em = rp.area / PX_PER_EM ** 2
        is_mark = ext < 0.3 and area_em < 0.035
        if is_mark and area_em < 0.016:  # a single dot: a tap
            cy, cx = rp.centroid
            marks.append(((c1 + c0) / 2, [[to_mm(cy, cx)]], comp.shape))
            continue
        padded = np.pad(comp, 1)
        sk = skeletonize(padded)
        nodes, edges = _graph(sk)
        edges = _prune(nodes, edges, 1.3 * stroke_w)
        paths = _walk(nodes, edges, spur_len=0.45 * PX_PER_EM)
        paths = _add_loops(paths, padded, sk, half)
        polys = []
        for p in paths:
            if len(p) < 2:
                continue
            pts = [(r + r0 - 1, c + c0 - 1) for r, c in p]
            pts = geom.moving_average(pts, 7)
            pts = geom.rdp(pts, 0.6)
            polys.append([to_mm(r, c) for r, c in pts])
        if not polys:
            continue
        key = c1
        (marks if is_mark else bodies).append((key, polys, comp.shape))
    bodies.sort(key=lambda b: -b[0])
    marks.sort(key=lambda m: -m[0])
    out_b = [p for _k, polys, _s in bodies for p in polys]
    out_m = []
    for _k, polys, _s in marks:
        out_m.extend(polys)
    return out_b, out_m, width_mm


def write_line(doc, w, text, x_right, baseline, size_mm=None, first_gap="line", speed=1.0):
    """Writes Arabic words right to left starting at x_right. Returns the x where the line ended."""
    rng = w.rng
    size = size_mm or w.xh * 3.4
    x = x_right
    gap = first_gap
    drift = geom.SmoothNoise(rng, 70.0, 0.1 * w.xh * w.jitter, octaves=2)
    for word in text.split():
        bodies, marks, width = word_strokes(word, size * rng.uniform(0.96, 1.04))
        dy = drift(x) + rng.gauss(0, 0.05 * w.xh)
        slant = math.tan(math.radians(rng.gauss(0, 2.0 * w.jitter)))

        def place(pts):
            return [(x + px - py * slant, baseline + py + dy) for px, py in pts]

        for poly in bodies:
            pts = place(poly)
            dense = geom.catmull_rom(pts, step=0.06)
            doc.add(geom.wobble(dense, rng, 0.012 * w.xh + 0.01, 1.8 * w.xh), gap=gap, speed=speed)
            gap = "letter"
        for poly in marks:
            pts = place(poly)
            pts = [(px + rng.gauss(0, 0.04 * w.xh), py + rng.gauss(0, 0.04 * w.xh)) for px, py in pts]
            if len(pts) == 1 or geom.length(pts) < 0.5:
                doc.add_dot(pts[0], gap="deferred")
            else:
                dense = geom.catmull_rom(pts, step=0.05)
                doc.add(dense, gap="deferred", speed=speed)
        x -= width + 0.9 * w.xh * rng.uniform(0.85, 1.3)
        gap = "word"
    return x
