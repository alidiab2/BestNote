package app.bestnote.engine.layout.qa

import app.bestnote.core.model.RectPt
import kotlin.math.max
import kotlin.math.min

/** What a piece of ink is: a run of text (glyph ink box), a piece of a pen stroke, or page furniture (dashed rules). */
enum class InkKind { Text, Stroke, Rule }

/**
 * One piece of ink on a laid-out page with its REAL extent (glyph ink, not the font's line box; a pen
 * stroke is cut into short pieces so a box outline does not "cover" what it encloses).
 *
 * @property owner the element it belongs to (null: page furniture such as the dashed column rules).
 * @property part the row of the element it was drawn by: ink of one row is one unit (a fraction's bar and
 *   its numerator touch on purpose); ink of different rows or elements must never touch.
 * @property frame the column box the element was given: ink may not leave it sideways.
 * @property target the element this ink points at (a margin note's arrow): reaching it is the point.
 */
data class InkBox(
    val owner: String?,
    val part: Int,
    val kind: InkKind,
    val rect: RectPt,
    val sizePt: Float = 0f,
    val label: String = "",
    val frame: RectPt? = null,
    val target: String? = null,
)

/** A layout defect found by [OverlapDetector]. */
data class LayoutIssue(val type: Type, val page: Int, val a: InkBox, val b: InkBox? = null, val amount: Float = 0f) {
    enum class Type {
        /** Ink of two unrelated rows or elements overlaps. */
        Overlap,

        /** Ink leaves its column sideways. */
        Overflow,

        /** Ink leaves the page (clipped on screen and in exports). */
        Clipped,

        /** Text too small to read at arm's length on a tablet. */
        Tiny,
    }

    override fun toString(): String = buildString {
        append("p").append(page).append(' ').append(type).append(" by ").append("%.1f".format(amount)).append("pt: ")
        append(describe(a))
        if (b != null) append("  <->  ").append(describe(b))
    }

    private fun describe(i: InkBox) = "${i.owner ?: "rules"}#${i.part} ${i.kind} \"${i.label.take(28)}\" " +
        "[%.1f,%.1f %.1f,%.1f]".format(i.rect.left, i.rect.top, i.rect.right, i.rect.bottom)
}

/**
 * Finds what a reader would see as broken on a laid-out page: overlapping writing, writing that leaves
 * its column or the page, unreadably small text. Pure geometry over [InkBox]es, so it checks any
 * composer's output (font writer, synthetic handwriting) and any template.
 *
 * @param tolerance ink may touch by this much (pt) before it counts: anti-aliasing, a pen's round cap.
 * @param sideTolerance text may leave its column by this much (pt): a hand font's swash past the first
 *   letter's advance, into the gutter or margin. More is a line that was measured too narrow.
 * @param strokeSideTolerance pen strokes may leave it by this much: underlines, box corners and arrow
 *   tips hang a little past the writing on purpose (HANG); real collisions with a neighbour column are
 *   still [LayoutIssue.Type.Overlap]s.
 * @param minTextPt the smallest readable text (pt); sub/superscripts may be [minScriptPt].
 */
class OverlapDetector(
    private val tolerance: Float = 0.6f,
    private val sideTolerance: Float = 3.5f,
    private val strokeSideTolerance: Float = 8f,
    private val minTextPt: Float = 5.5f,
    private val minScriptPt: Float = 4.0f,
) {
    /**
     * @param refine the real overlap (pt) of two colliding boxes, asked only when their rects meet: e.g.
     *   from glyph outlines, so a descender that passes beside the ascender of the line below is not counted.
     */
    fun check(page: Int, items: List<InkBox>, pageRect: RectPt, refine: ((InkBox, InkBox) -> Float)? = null): List<LayoutIssue> {
        val out = ArrayList<LayoutIssue>()
        for (i in items) {
            val r = i.rect
            val off = max(max(pageRect.left - r.left, r.right - pageRect.right), max(pageRect.top - r.top, r.bottom - pageRect.bottom))
            if (off > tolerance) out += LayoutIssue(LayoutIssue.Type.Clipped, page, i, amount = off)
            val f = i.frame
            if (f != null && i.kind != InkKind.Rule) {
                val side = max(f.left - r.left, r.right - f.right)
                if (side > (if (i.kind == InkKind.Text) sideTolerance else strokeSideTolerance)) out += LayoutIssue(LayoutIssue.Type.Overflow, page, i, amount = side)
            }
            if (i.kind == InkKind.Text && i.sizePt > 0f && i.sizePt < minScriptPt) out += LayoutIssue(LayoutIssue.Type.Tiny, page, i, amount = i.sizePt)
        }
        // Small scripts are fine, whole small text runs are not: flag a run below minTextPt only when its owner has no bigger text.
        val biggest = items.filter { it.kind == InkKind.Text }.groupBy { it.owner }.mapValues { (_, v) -> v.maxOf { it.sizePt } }
        for ((owner, size) in biggest) if (size in 0.01f..minTextPt) {
            val i = items.first { it.owner == owner && it.kind == InkKind.Text }
            out += LayoutIssue(LayoutIssue.Type.Tiny, page, i, amount = size)
        }
        out += overlaps(page, items, refine)
        return out
    }

    /** Sweep down the page: pairs of unrelated ink whose boxes overlap by more than [tolerance] both ways. */
    private fun overlaps(page: Int, items: List<InkBox>, refine: ((InkBox, InkBox) -> Float)?): List<LayoutIssue> {
        val sorted = items.sortedBy { it.rect.top }
        val active = ArrayList<InkBox>()
        // One report per pair of (owner, part) units: the worst overlap.
        val worst = LinkedHashMap<String, LayoutIssue>()
        for (b in sorted) {
            active.removeAll { it.rect.bottom - tolerance <= b.rect.top }
            for (a in active) {
                if (related(a, b)) continue
                val dx = min(a.rect.right, b.rect.right) - max(a.rect.left, b.rect.left)
                val dy = min(a.rect.bottom, b.rect.bottom) - max(a.rect.top, b.rect.top)
                if (dx <= tolerance || dy <= tolerance) continue
                val amount = if (refine == null) min(dx, dy) else refine(a, b)
                if (amount <= tolerance) continue
                val ka = "${a.owner}#${a.part}"
                val kb = "${b.owner}#${b.part}"
                val key = if (ka < kb) "$ka|$kb" else "$kb|$ka"
                val prev = worst[key]
                if (prev == null || prev.amount < amount) worst[key] = LayoutIssue(LayoutIssue.Type.Overlap, page, a, b, amount)
            }
            active += b
        }
        return worst.values.toList()
    }

    private fun related(a: InkBox, b: InkBox): Boolean {
        if (a.kind == InkKind.Rule && b.kind == InkKind.Rule) return true
        if (a.target != null && a.target == b.owner || b.target != null && b.target == a.owner) return true
        return a.owner == b.owner && a.part == b.part
    }

    companion object {
        /**
         * Cuts a pen path into pieces no longer than [piece] pt, each boxed with half the pen [width]
         * around it, so the outline of a box or a long underline is measured as the thin ink it is.
         */
        fun strokeBoxes(xs: FloatArray, ys: FloatArray, width: Float, piece: Float = 4f): List<RectPt> {
            if (xs.isEmpty()) return emptyList()
            val half = width / 2f
            val out = ArrayList<RectPt>()
            var l = xs[0]
            var t = ys[0]
            var r = xs[0]
            var b = ys[0]
            for (k in 1 until xs.size) {
                l = min(l, xs[k]); r = max(r, xs[k]); t = min(t, ys[k]); b = max(b, ys[k])
                if (r - l > piece || b - t > piece || k == xs.size - 1) {
                    out += RectPt(l - half, t - half, r + half, b + half)
                    l = xs[k]; r = xs[k]; t = ys[k]; b = ys[k]
                }
            }
            if (xs.size == 1) out += RectPt(l - half, t - half, r + half, b + half)
            return out
        }
    }
}
