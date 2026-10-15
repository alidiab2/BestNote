package app.bestnote.engine.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * REC-09: recognizes lines, arrows, circles, ellipses, rectangles, triangles and polygons in a
 * hand-drawn stroke by plain geometric fitting (no ML). Pure Kotlin, allocation-light: a few
 * hundred samples take well under a millisecond, so it can run per stroke off the main thread.
 *
 * How it decides:
 *  1. The still part at the start and end (a hold-to-snap dwell, the pen settling) is dropped.
 *  2. Nearly straight → [RecognizedShape.Line] (snapped to 0/45/90° when close).
 *  3. Ends meet (or overshoot) → a closed loop: corners are found from the turning angle; with 3+
 *     corners a polygon is fitted (each side a least-squares line, vertices where sides meet),
 *     otherwise / if better an ellipse (algebraic fit in the loop's principal axes). Four right
 *     angles make a rectangle, near-equal radii a circle.
 *  4. Open with a long straight start and short barbs that point back at it → an arrow; other
 *     open strokes made of straight segments → an open polygon.
 *
 * Confidence is 1 at a perfect fit and 0.5 at the acceptance limit; anything below
 * [Config.minConfidence] is reported as null.
 */
class GeometricShapeRecognizer(private val config: Config = Config()) : ShapeRecognizer {

    data class Config(
        val minConfidence: Float = 0.5f,
        /** Lines, arrow shafts and rectangles within this many degrees of 0/45/90° snap onto it. */
        val angleSnapDeg: Float = 6f,
        /** Strokes whose bounds' diagonal is shorter than this (in sample units) are never shapes. */
        val minSize: Float = 6f,
        /** Open polylines (zigzags, angles) are recognized too; off, only the listed shapes are. */
        val openPolylines: Boolean = true,
    )

    override suspend fun recognize(stroke: InkStrokeSamples): ShapeMatch? = match(stroke)

    override suspend fun recognizeArrow(shaft: InkStrokeSamples, head: InkStrokeSamples): ShapeMatch? = matchArrow(shaft, head)

    /** Blocking [recognize]. */
    fun match(stroke: InkStrokeSamples): ShapeMatch? =
        match(FloatArray(stroke.size) { stroke[it].x }, FloatArray(stroke.size) { stroke[it].y })

    /** [recognize] on bare coordinates. */
    fun match(xs: FloatArray, ys: FloatArray): ShapeMatch? {
        val pts = prepare(xs, ys) ?: return null
        val result = line(pts) ?: closed(pts) ?: open(pts)
        return result?.takeIf { it.confidence >= config.minConfidence }
    }

    /** Blocking [recognizeArrow]. */
    fun matchArrow(shaft: InkStrokeSamples, head: InkStrokeSamples): ShapeMatch? {
        val s = prepare(FloatArray(shaft.size) { shaft[it].x }, FloatArray(shaft.size) { shaft[it].y }) ?: return null
        val h = prepare(FloatArray(head.size) { head[it].x }, FloatArray(head.size) { head[it].y }, minSize = 0.0) ?: return null
        return arrowFromParts(s, h)?.takeIf { it.confidence >= config.minConfidence }
    }

    // ------------------------------------------------------------------ preparation

    /** Deduplicated points with the still start/end dropped, or null when too small to be a shape. */
    private fun prepare(xs: FloatArray, ys: FloatArray, minSize: Double = config.minSize.toDouble()): Pts? {
        val n = min(xs.size, ys.size)
        if (n < 2) return null
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        var m = 0
        for (i in 0 until n) {
            val x = xs[i].toDouble()
            val y = ys[i].toDouble()
            if (m > 0 && x == px[m - 1] && y == py[m - 1]) continue
            px[m] = x
            py[m] = y
            m++
        }
        if (m < 2) return null
        val all = Pts(px.copyOf(m), py.copyOf(m))
        val d = all.diagonal()
        if (d < minSize || d <= 0.0) return null
        // A dwell: the pen stays inside a small circle. Relative to the shape, with an absolute floor.
        val r = max(DWELL_FRACTION * d, DWELL_MIN)
        var end = m - 1
        while (end > 0 && dist(all, end - 1, m - 1) <= r) end--
        var start = 0
        while (start < end && dist(all, start + 1, 0) <= r) start++
        if (end - start < 1) return null
        return Pts(all.x.copyOfRange(start, end + 1), all.y.copyOfRange(start, end + 1))
    }

    // ------------------------------------------------------------------ line

    private fun line(p: Pts): ShapeMatch? {
        val r = p.resample(LINE_POINTS)
        val chord = dist(r, 0, r.size - 1)
        val len = p.length()
        if (chord <= 0.0 || chord / len < LINE_MIN_CHORD) return null
        val fit = LineFit.of(r, 0, r.size - 1)
        val dev = fit.maxDistance(r, 0, r.size - 1) / chord
        if (dev > LINE_MAX_DEV) return null
        var (x0, y0) = fit.project(r.x[0], r.y[0])
        var (x1, y1) = fit.project(r.x[r.size - 1], r.y[r.size - 1])
        snapSegment(x0, y0, x1, y1).let { (a, b, c, e) -> x0 = a; y0 = b; x1 = c; y1 = e }
        return ShapeMatch(RecognizedShape.Line(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()), confidence(dev, LINE_MAX_DEV))
    }

    /** Rotates the segment about its middle onto the nearest multiple of 45° when within [Config.angleSnapDeg]. */
    private fun snapSegment(x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray {
        val a = atan2(y1 - y0, x1 - x0)
        val snapped = snapAngle(a, QUARTER / 2)
        if (snapped == a) return doubleArrayOf(x0, y0, x1, y1)
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val h = hypot(x1 - x0, y1 - y0) / 2
        return doubleArrayOf(cx - cos(snapped) * h, cy - sin(snapped) * h, cx + cos(snapped) * h, cy + sin(snapped) * h)
    }

    /** [a] moved onto the nearest multiple of [step] if it is within the snap tolerance, else [a]. */
    private fun snapAngle(a: Double, step: Double): Double {
        val k = (a / step).roundToInt()
        val target = k * step
        return if (abs(a - target) <= Math.toRadians(config.angleSnapDeg.toDouble())) target else a
    }

    // ------------------------------------------------------------------ closed shapes

    private fun closed(p: Pts): ShapeMatch? {
        val r = p.resample(CLOSED_POINTS)
        val n = r.size
        val d = r.diagonal()
        // Where the stroke closes: the nearest pair between its first and last quarter (handles gaps and overshoot).
        var bi = 0
        var bj = n - 1
        var best = Double.MAX_VALUE
        for (i in 0 until n / 4) for (j in n - n / 4 until n) {
            val dd = dist(r, i, j)
            if (dd < best) { best = dd; bi = i; bj = j }
        }
        if (best > CLOSE_GAP * d) return null
        val loopLen = r.length(bi, bj)
        if (loopLen < 0.6 * r.length()) return null
        val loop = r.slice(bi, bj).resampleClosed(LOOP_POINTS)
        val ell = fitEllipse(loop)
        val poly = fitPolygon(loop)
        val ellOk = ell != null && ell.second <= ELLIPSE_MAX_ERR
        val polyOk = poly != null && poly.second <= POLYGON_MAX_ERR
        return when {
            polyOk && (!ellOk || poly!!.second < ell!!.second * POLYGON_PREFERENCE) -> ShapeMatch(poly!!.first, confidence(poly.second, POLYGON_MAX_ERR))
            ellOk -> ShapeMatch(ell!!.first, confidence(ell.second, ELLIPSE_MAX_ERR))
            else -> null
        }
    }

    /** Ellipse fit of a closed loop and its mean distance error relative to the loop's diagonal. */
    private fun fitEllipse(loop: Pts): Pair<RecognizedShape.Ellipse, Double>? {
        val n = loop.size
        val d = loop.diagonal()
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += loop.x[i]; my += loop.y[i] }
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val dx = loop.x[i] - mx; val dy = loop.y[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val phi = 0.5 * atan2(2 * sxy, sxx - syy)
        val c = cos(phi); val s = sin(phi)
        val u = DoubleArray(n); val v = DoubleArray(n)
        for (i in 0 until n) {
            val dx = loop.x[i] - mx; val dy = loop.y[i] - my
            u[i] = dx * c + dy * s
            v[i] = -dx * s + dy * c
        }
        // Axis-aligned conic in the principal frame: A u² + B v² + C u + D v = 1 (least squares).
        val m = Array(4) { DoubleArray(4) }
        val rhs = DoubleArray(4)
        for (i in 0 until n) {
            val row = doubleArrayOf(u[i] * u[i], v[i] * v[i], u[i], v[i])
            for (a in 0 until 4) {
                rhs[a] += row[a]
                for (b in 0 until 4) m[a][b] += row[a] * row[b]
            }
        }
        val sol = solve4(m, rhs)
        val cu: Double; val cv: Double; val rx: Double; val ry: Double
        if (sol != null && sol[0] > 0 && sol[1] > 0) {
            cu = -sol[2] / (2 * sol[0]); cv = -sol[3] / (2 * sol[1])
            val f = 1 + sol[0] * cu * cu + sol[1] * cv * cv
            rx = sqrt(f / sol[0]); ry = sqrt(f / sol[1])
        } else {
            cu = (u.max() + u.min()) / 2; cv = (v.max() + v.min()) / 2
            rx = (u.max() - u.min()) / 2; ry = (v.max() - v.min()) / 2
        }
        if (!rx.isFinite() || !ry.isFinite() || rx <= 0 || ry <= 0 || rx > d || ry > d) return null
        var err = 0.0
        for (i in 0 until n) {
            val du = u[i] - cu; val dv = v[i] - cv
            val q = sqrt((du / rx) * (du / rx) + (dv / ry) * (dv / ry))
            err += if (q > 0) abs(q - 1) * hypot(du, dv) / q else min(rx, ry)
        }
        err /= n * d
        val cx = mx + cu * c - cv * s
        val cy = my + cu * s + cv * c
        // The start (where the hand began) and direction, so the clean loop is drawn the same way.
        val su = loop.x[0] - cx; val sv = loop.y[0] - cy
        val clockwise = loop.signedArea() > 0
        val shape = if (min(rx, ry) / max(rx, ry) >= CIRCLE_RATIO) {
            var rr = 0.0
            for (i in 0 until n) rr += hypot(loop.x[i] - cx, loop.y[i] - cy)
            rr /= n
            RecognizedShape.Ellipse(cx.toFloat(), cy.toFloat(), rr.toFloat(), rr.toFloat(), 0f, atan2(sv, su).toFloat(), clockwise)
        } else {
            var rot = phi
            var a = rx; var b = ry
            // Upright when close: rotation onto 0 (or swap axes for 90°).
            val snapped = snapAngle(rot, QUARTER)
            if (snapped != rot) {
                val k = (snapped / QUARTER).roundToInt()
                rot = 0.0
                if (k % 2 != 0) { val t = a; a = b; b = t }
            }
            val cr = cos(rot); val sr = sin(rot)
            val lu = su * cr + sv * sr; val lv = -su * sr + sv * cr
            RecognizedShape.Ellipse(cx.toFloat(), cy.toFloat(), a.toFloat(), b.toFloat(), rot.toFloat(), atan2(lv / b, lu / a).toFloat(), clockwise)
        }
        return shape to err
    }

    /** Polygon fit of a closed loop (3..[MAX_VERTICES] corners) and its mean distance error relative to the diagonal. */
    private fun fitPolygon(loop: Pts): Pair<RecognizedShape.Polygon, Double>? {
        val corners = corners(loop, closed = true)
        if (corners.size < 3 || corners.size > MAX_VERTICES) return null
        val n = loop.size
        val m = corners.size
        val fits = Array(m) { k ->
            val a = corners[k]
            var b = corners[(k + 1) % m]
            if (b <= a) b += n
            val trim = ((b - a) * SIDE_TRIM).toInt()
            LineFit.ofCircular(loop, a + trim, b - trim)
        }
        val vx = DoubleArray(m); val vy = DoubleArray(m)
        val d = loop.diagonal()
        for (k in 0 until m) {
            val c = corners[k]
            val hit = fits[(k + m - 1) % m].intersect(fits[k])
            if (hit != null && hypot(hit[0] - loop.x[c], hit[1] - loop.y[c]) < VERTEX_DRIFT * d) {
                vx[k] = hit[0]; vy[k] = hit[1]
            } else {
                vx[k] = loop.x[c]; vy[k] = loop.y[c]
            }
        }
        var verts = Pts(vx, vy)
        verts = dropStraightVertices(verts)
        if (verts.size < 3) return null
        val err = meanDistanceToPolygon(loop, verts) / d
        val kind = when (verts.size) {
            3 -> ShapeKind.Triangle
            4 -> if (isRectangular(verts)) ShapeKind.Rectangle else ShapeKind.Polygon
            else -> ShapeKind.Polygon
        }
        if (kind == ShapeKind.Rectangle) verts = regularRectangle(verts)
        val points = (0 until verts.size).map { verts.x[it].toFloat() to verts.y[it].toFloat() }
        return RecognizedShape.Polygon(points, closed = true, kind = kind) to err
    }

    private fun dropStraightVertices(v: Pts): Pts {
        val keep = ArrayList<Int>()
        val m = v.size
        for (k in 0 until m) {
            val p = (k + m - 1) % m; val q = (k + 1) % m
            if (turn(v.x[p], v.y[p], v.x[k], v.y[k], v.x[q], v.y[q]) >= MIN_VERTEX_TURN) keep += k
        }
        return Pts(DoubleArray(keep.size) { v.x[keep[it]] }, DoubleArray(keep.size) { v.y[keep[it]] })
    }

    private fun isRectangular(v: Pts): Boolean = (0 until 4).all { k ->
        val p = (k + 3) % 4; val q = (k + 1) % 4
        abs(turn(v.x[p], v.y[p], v.x[k], v.y[k], v.x[q], v.y[q]) - QUARTER) <= RIGHT_ANGLE_TOLERANCE
    }

    /** The rectangle closest to quadrilateral [v], snapped upright (or to 45°) when close; corners in [v]'s order. */
    private fun regularRectangle(v: Pts): Pts {
        // Mean side direction modulo 90°: average the angles ×4 as unit vectors.
        var sx = 0.0; var sy = 0.0
        for (k in 0 until 4) {
            val q = (k + 1) % 4
            val a = atan2(v.y[q] - v.y[k], v.x[q] - v.x[k])
            val w = hypot(v.y[q] - v.y[k], v.x[q] - v.x[k])
            sx += cos(4 * a) * w; sy += sin(4 * a) * w
        }
        val theta = snapAngle(atan2(sy, sx) / 4, QUARTER / 2)
        val c = cos(theta); val s = sin(theta)
        val u = DoubleArray(4) { v.x[it] * c + v.y[it] * s }
        val w = DoubleArray(4) { -v.x[it] * s + v.y[it] * c }
        val us = u.sorted(); val ws = w.sorted()
        val left = (us[0] + us[1]) / 2; val right = (us[2] + us[3]) / 2
        val top = (ws[0] + ws[1]) / 2; val bottom = (ws[2] + ws[3]) / 2
        val ox = DoubleArray(4); val oy = DoubleArray(4)
        for (k in 0 until 4) {
            val cu = if (u[k] < (left + right) / 2) left else right
            val cw = if (w[k] < (top + bottom) / 2) top else bottom
            ox[k] = cu * c - cw * s
            oy[k] = cu * s + cw * c
        }
        return Pts(ox, oy)
    }

    // ------------------------------------------------------------------ open shapes

    private fun open(p: Pts): ShapeMatch? {
        val r = p.resample(OPEN_POINTS)
        val cs = corners(r, closed = false)
        if (cs.isEmpty()) return null
        return arrow(r, cs) ?: polyline(r, cs)
    }

    /** One-stroke arrow: a long straight shaft, then barb(s) near its end pointing back along it. */
    private fun arrow(r: Pts, cs: List<Int>): ShapeMatch? {
        val tip = cs[0]
        val shaftLen = dist(r, 0, tip)
        if (shaftLen <= 0 || r.length(0, tip) < ARROW_SHAFT_SHARE * r.length()) return null
        val fit = LineFit.of(r, 0, tip)
        val dev = fit.maxDistance(r, 0, tip) / shaftLen
        if (dev > LINE_MAX_DEV * 1.4) return null
        val tx = r.x[tip]; val ty = r.y[tip]
        val vertices = cs.drop(1) + (r.size - 1)
        var maxD = 0.0
        for (k in vertices) {
            val dd = hypot(r.x[k] - tx, r.y[k] - ty)
            if (dd > ARROW_HEAD_MAX * shaftLen) return null
            maxD = max(maxD, dd)
        }
        // Points after the tip must stay near the head.
        for (i in tip until r.size) if (hypot(r.x[i] - tx, r.y[i] - ty) > ARROW_HEAD_MAX * shaftLen) return null
        val backX = r.x[0] - tx; val backY = r.y[0] - ty
        val barbs = vertices.filter { k ->
            val bx = r.x[k] - tx; val by = r.y[k] - ty
            val bd = hypot(bx, by)
            bd >= 0.6 * maxD && bd >= ARROW_BARB_MIN * shaftLen && angleBetween(bx, by, backX, backY) <= ARROW_BARB_MAX_ANGLE
        }
        if (barbs.isEmpty()) return null
        val start = fit.project(r.x[0], r.y[0])
        val end = fit.project(tx, ty)
        val shape = cleanArrow(start[0], start[1], end[0], end[1], barbs.map { r.x[it] to r.y[it] })
        return ShapeMatch(shape, confidence(dev, LINE_MAX_DEV * 1.4))
    }

    /** Shaft [x0],[y0] → tip [x1],[y1] (snapped), with a symmetric head sized and opened like the hand-drawn [barbs]. */
    private fun cleanArrow(x0: Double, y0: Double, x1: Double, y1: Double, barbs: List<Pair<Double, Double>>): RecognizedShape.Arrow {
        val s = snapSegment(x0, y0, x1, y1)
        val ang = atan2(s[3] - s[1], s[2] - s[0])
        var len = 0.0; var spread = 0.0
        for ((bx, by) in barbs) {
            val dx = bx - x1; val dy = by - y1
            len += hypot(dx, dy)
            spread += angleBetween(dx, dy, x0 - x1, y0 - y1)
        }
        len /= barbs.size
        spread = (spread / barbs.size).coerceIn(ARROW_MIN_SPREAD, ARROW_MAX_SPREAD)
        val back = ang + PI
        val b1 = (s[2] + cos(back - spread) * len).toFloat() to (s[3] + sin(back - spread) * len).toFloat()
        val b2 = (s[2] + cos(back + spread) * len).toFloat() to (s[3] + sin(back + spread) * len).toFloat()
        return RecognizedShape.Arrow(s[0].toFloat(), s[1].toFloat(), s[2].toFloat(), s[3].toFloat(), listOf(b1, b2))
    }

    /** An open stroke of straight segments (an angle, a zigzag). */
    private fun polyline(r: Pts, cs: List<Int>): ShapeMatch? {
        if (!config.openPolylines || cs.size > MAX_VERTICES - 2) return null
        val idx = listOf(0) + cs + (r.size - 1)
        val total = r.length()
        var worst = 0.0
        val fits = ArrayList<LineFit>()
        for (k in 0 until idx.size - 1) {
            val a = idx[k]; val b = idx[k + 1]
            val seg = dist(r, a, b)
            if (seg < POLYLINE_MIN_SEGMENT * total) return null
            val trim = ((b - a) * SIDE_TRIM).toInt()
            val fit = LineFit.of(r, a + trim, b - trim)
            worst = max(worst, fit.maxDistance(r, a + trim, b - trim) / seg)
            fits += fit
        }
        if (worst > POLYLINE_MAX_DEV) return null
        val pts = ArrayList<Pair<Float, Float>>()
        pts += fits.first().project(r.x[0], r.y[0]).let { it[0].toFloat() to it[1].toFloat() }
        for (k in 1 until fits.size) {
            val c = idx[k]
            val hit = fits[k - 1].intersect(fits[k])
            pts += if (hit != null && hypot(hit[0] - r.x[c], hit[1] - r.y[c]) < VERTEX_DRIFT * r.diagonal()) {
                hit[0].toFloat() to hit[1].toFloat()
            } else {
                r.x[c].toFloat() to r.y[c].toFloat()
            }
        }
        pts += fits.last().project(r.x[r.size - 1], r.y[r.size - 1]).let { it[0].toFloat() to it[1].toFloat() }
        return ShapeMatch(RecognizedShape.Polygon(pts, closed = false), confidence(worst, POLYLINE_MAX_DEV))
    }

    /** Two-stroke arrow: [shaft] must be a line and [head] a "V" whose point sits on one of its ends. */
    private fun arrowFromParts(shaft: Pts, head: Pts): ShapeMatch? {
        val line = line(shaft)?.shape as? RecognizedShape.Line ?: return null
        val shaftLen = hypot((line.x1 - line.x0).toDouble(), (line.y1 - line.y0).toDouble())
        val h = head.resample(OPEN_POINTS)
        val cs = corners(h, closed = false)
        if (cs.isEmpty()) return null
        val tip = cs.maxBy { turnAt(h, it, cornerWindow(h.size)) }
        val tx = h.x[tip]; val ty = h.y[tip]
        // Which end of the shaft the head is on.
        val d0 = hypot(tx - line.x0, ty - line.y0)
        val d1 = hypot(tx - line.x1, ty - line.y1)
        val (ex, ey, fx, fy) = if (d1 <= d0) {
            doubleArrayOf(line.x1.toDouble(), line.y1.toDouble(), line.x0.toDouble(), line.y0.toDouble())
        } else {
            doubleArrayOf(line.x0.toDouble(), line.y0.toDouble(), line.x1.toDouble(), line.y1.toDouble())
        }
        val armA = hypot(h.x[0] - tx, h.y[0] - ty)
        val armB = hypot(h.x[h.size - 1] - tx, h.y[h.size - 1] - ty)
        val arm = (armA + armB) / 2
        if (arm > ARROW_HEAD_MAX * shaftLen || min(armA, armB) < 0.3 * max(armA, armB)) return null
        if (min(d0, d1) > 0.6 * arm + 0.05 * shaftLen) return null
        val backX = fx - ex; val backY = fy - ey
        val a = angleBetween(h.x[0] - tx, h.y[0] - ty, backX, backY)
        val b = angleBetween(h.x[h.size - 1] - tx, h.y[h.size - 1] - ty, backX, backY)
        if (a > ARROW_BARB_MAX_ANGLE || b > ARROW_BARB_MAX_ANGLE) return null
        // Barbs on opposite sides of the shaft.
        val sa = cross(backX, backY, h.x[0] - tx, h.y[0] - ty)
        val sb = cross(backX, backY, h.x[h.size - 1] - tx, h.y[h.size - 1] - ty)
        if (sa * sb > 0) return null
        val barbs = listOf(ex + (h.x[0] - tx) to ey + (h.y[0] - ty), ex + (h.x[h.size - 1] - tx) to ey + (h.y[h.size - 1] - ty))
        val conf = line(shaft)!!.confidence
        return ShapeMatch(cleanArrow(fx, fy, ex, ey, barbs), conf)
    }

    // ------------------------------------------------------------------ corners

    private fun cornerWindow(n: Int) = max(2, n / CORNER_WINDOW_DIV)

    /** Turning angle at [i] over ±[k] points (0 = straight, PI = reversal). Indices clamp at the ends. */
    private fun turnAt(p: Pts, i: Int, k: Int, closed: Boolean = false): Double {
        val n = p.size
        val a = if (closed) (i - k + n) % n else max(0, i - k)
        val b = if (closed) (i + k) % n else min(n - 1, i + k)
        return turn(p.x[a], p.y[a], p.x[i], p.y[i], p.x[b], p.y[b])
    }

    /** Indices of sharp corners (local maxima of the turning angle above [CORNER_ANGLE]), in order. */
    private fun corners(p: Pts, closed: Boolean): List<Int> {
        val n = p.size
        val k = cornerWindow(n)
        val t = DoubleArray(n) { i -> if (!closed && (i < k || i > n - 1 - k)) 0.0 else turnAt(p, i, k, closed) }
        val out = ArrayList<Int>()
        for (i in 0 until n) {
            if (t[i] < CORNER_ANGLE) continue
            var isMax = true
            for (j in -k..k) {
                if (j == 0) continue
                val q = if (closed) (i + j + n) % n else i + j
                if (q !in 0 until n) continue
                if (t[q] > t[i] || (t[q] == t[i] && j < 0)) { isMax = false; break }
            }
            if (isMax) out += i
        }
        return out
    }

    // ------------------------------------------------------------------ geometry helpers

    private class Pts(val x: DoubleArray, val y: DoubleArray) {
        val size get() = x.size

        fun diagonal(): Double = hypot(x.max() - x.min(), y.max() - y.min())

        fun length(from: Int = 0, to: Int = size - 1): Double {
            var l = 0.0
            for (i in from + 1..to) l += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            return l
        }

        fun slice(from: Int, to: Int) = Pts(x.copyOfRange(from, to + 1), y.copyOfRange(from, to + 1))

        /** [n] points evenly spaced along the polyline, ends included. */
        fun resample(n: Int): Pts = resampleImpl(n, closed = false)

        /** [n] points evenly spaced around the loop (the closing segment back to the start included). */
        fun resampleClosed(n: Int): Pts = resampleImpl(n, closed = true)

        private fun resampleImpl(n: Int, closed: Boolean): Pts {
            val m = size
            val cx = if (closed) x + x[0] else x
            val cy = if (closed) y + y[0] else y
            val cum = DoubleArray(cx.size)
            for (i in 1 until cx.size) cum[i] = cum[i - 1] + hypot(cx[i] - cx[i - 1], cy[i] - cy[i - 1])
            val total = cum.last()
            val ox = DoubleArray(n); val oy = DoubleArray(n)
            if (total <= 0.0 || m < 2) {
                ox.fill(x[0]); oy.fill(y[0]); return Pts(ox, oy)
            }
            val steps = if (closed) n else n - 1
            var j = 0
            for (i in 0 until n) {
                val s = total * i / steps
                while (j < cum.size - 2 && cum[j + 1] < s) j++
                val seg = cum[j + 1] - cum[j]
                val u = if (seg > 0) ((s - cum[j]) / seg).coerceIn(0.0, 1.0) else 0.0
                ox[i] = cx[j] + (cx[j + 1] - cx[j]) * u
                oy[i] = cy[j] + (cy[j + 1] - cy[j]) * u
            }
            return Pts(ox, oy)
        }

        /** Shoelace area; positive = clockwise on a y-down screen. */
        fun signedArea(): Double {
            var a = 0.0
            for (i in 0 until size) {
                val j = (i + 1) % size
                a += x[i] * y[j] - x[j] * y[i]
            }
            return a / 2
        }
    }

    /** A least-squares line: a point on it and a unit direction. */
    private class LineFit(val px: Double, val py: Double, val dx: Double, val dy: Double) {
        fun distance(x: Double, y: Double) = abs((x - px) * dy - (y - py) * dx)

        fun maxDistance(p: Pts, from: Int, to: Int): Double {
            var m = 0.0
            for (i in from..to) m = max(m, distance(p.x[i], p.y[i]))
            return m
        }

        fun project(x: Double, y: Double): DoubleArray {
            val t = (x - px) * dx + (y - py) * dy
            return doubleArrayOf(px + dx * t, py + dy * t)
        }

        fun intersect(o: LineFit): DoubleArray? {
            val den = dx * o.dy - dy * o.dx
            if (abs(den) < PARALLEL) return null
            val t = ((o.px - px) * o.dy - (o.py - py) * o.dx) / den
            return doubleArrayOf(px + dx * t, py + dy * t)
        }

        companion object {
            fun of(p: Pts, from: Int, to: Int): LineFit = fit(to - from + 1) { p.x[from + it] to p.y[from + it] }

            /** Indices may run past the end of a closed loop ([to] up to 2 × size). */
            fun ofCircular(p: Pts, from: Int, to: Int): LineFit {
                val n = p.size
                val count = max(2, to - from + 1)
                return fit(count) { val i = (from + it) % n; p.x[i] to p.y[i] }
            }

            private inline fun fit(count: Int, at: (Int) -> Pair<Double, Double>): LineFit {
                var mx = 0.0; var my = 0.0
                for (i in 0 until count) { val (x, y) = at(i); mx += x; my += y }
                mx /= count; my /= count
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for (i in 0 until count) {
                    val (x, y) = at(i)
                    sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my); sxy += (x - mx) * (y - my)
                }
                var phi = 0.5 * atan2(2 * sxy, sxx - syy)
                if (sxx == 0.0 && syy == 0.0) {
                    val (x0, y0) = at(0); val (x1, y1) = at(count - 1)
                    phi = atan2(y1 - y0, x1 - x0)
                }
                // Point the direction the way the points run.
                val (x0, y0) = at(0); val (x1, y1) = at(count - 1)
                var dx = cos(phi); var dy = sin(phi)
                if ((x1 - x0) * dx + (y1 - y0) * dy < 0) { dx = -dx; dy = -dy }
                return LineFit(mx, my, dx, dy)
            }
        }
    }

    private companion object {
        const val QUARTER = PI / 2
        const val DWELL_FRACTION = 0.015
        const val DWELL_MIN = 0.75
        const val LINE_POINTS = 64
        const val CLOSED_POINTS = 160
        const val LOOP_POINTS = 128
        const val OPEN_POINTS = 160
        const val LINE_MIN_CHORD = 0.9
        const val LINE_MAX_DEV = 0.07
        const val CLOSE_GAP = 0.2
        const val ELLIPSE_MAX_ERR = 0.035
        const val POLYGON_MAX_ERR = 0.03
        const val POLYGON_PREFERENCE = 0.85
        const val CIRCLE_RATIO = 0.82
        const val MAX_VERTICES = 8
        const val SIDE_TRIM = 0.18
        const val VERTEX_DRIFT = 0.15
        const val CORNER_WINDOW_DIV = 40
        val CORNER_ANGLE = Math.toRadians(42.0)
        val MIN_VERTEX_TURN = Math.toRadians(22.0)
        val RIGHT_ANGLE_TOLERANCE = Math.toRadians(20.0)
        const val ARROW_SHAFT_SHARE = 0.5
        const val ARROW_HEAD_MAX = 0.5
        const val ARROW_BARB_MIN = 0.04
        val ARROW_BARB_MAX_ANGLE = Math.toRadians(70.0)
        val ARROW_MIN_SPREAD = Math.toRadians(18.0)
        val ARROW_MAX_SPREAD = Math.toRadians(45.0)
        const val POLYLINE_MIN_SEGMENT = 0.08
        const val POLYLINE_MAX_DEV = 0.06
        const val PARALLEL = 0.1

        private fun dist(p: Pts, i: Int, j: Int) = hypot(p.x[i] - p.x[j], p.y[i] - p.y[j])

        /** 1 at zero error, 0.5 at the acceptance limit. */
        fun confidence(err: Double, limit: Double) = (1.0 - 0.5 * err / limit).coerceIn(0.0, 1.0).toFloat()

        fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx

        fun angleBetween(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la == 0.0 || lb == 0.0) return PI
            return acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0))
        }

        /** Direction change at b going a → b → c: 0 straight on, PI a full reversal. */
        fun turn(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
            angleBetween(bx - ax, by - ay, cx - bx, cy - by)

        fun meanDistanceToPolygon(p: Pts, v: Pts): Double {
            var sum = 0.0
            val m = v.size
            for (i in 0 until p.size) {
                var best = Double.MAX_VALUE
                for (k in 0 until m) {
                    val q = (k + 1) % m
                    best = min(best, segmentDistance(p.x[i], p.y[i], v.x[k], v.y[k], v.x[q], v.y[q]))
                }
                sum += best
            }
            return sum / p.size
        }

        fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            return hypot(px - ax - dx * t, py - ay - dy * t)
        }

        /** Gaussian elimination with partial pivoting; null when singular. */
        fun solve4(m: Array<DoubleArray>, r: DoubleArray): DoubleArray? {
            val a = Array(4) { i -> DoubleArray(5) { j -> if (j < 4) m[i][j] else r[i] } }
            for (c in 0 until 4) {
                var piv = c
                for (i in c + 1 until 4) if (abs(a[i][c]) > abs(a[piv][c])) piv = i
                if (abs(a[piv][c]) < 1e-12) return null
                val t = a[c]; a[c] = a[piv]; a[piv] = t
                for (i in 0 until 4) {
                    if (i == c) continue
                    val f = a[i][c] / a[c][c]
                    for (j in c until 5) a[i][j] -= f * a[c][j]
                }
            }
            return DoubleArray(4) { a[it][4] / a[it][it] }
        }
    }
}
