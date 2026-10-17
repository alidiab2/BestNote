package app.bestnote.engine.canvas.shape

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.engine.canvas.select.Affine
import java.util.UUID

/**
 * CANVAS-17: how shape records follow selection edits. Moving (or resizing, turning) shapes moves their key points;
 * a moved connector keeps the ends glued to shapes that moved with it and re-glues the others where they land;
 * copies get new ids, with links between copied shapes pointing at the copies. Pure Kotlin.
 */
object ShapeEdits {
    private const val ID_LENGTH = 12

    fun newId(): String = "s-" + UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)

    /**
     * [brush] of a stroke moved by [m] together with the shapes [movedIds]; [others] are the shapes left in place,
     * which a moved connector's free ends glue to when within [tolerance] of a glue point or edge.
     */
    fun moved(brush: InkBrush, m: Affine, movedIds: Set<String>, others: List<ShapeInfo>, tolerance: Float): InkBrush {
        val info = brush.shape ?: return brush
        return brush.copy(shape = movedInfo(info, m, movedIds, others, tolerance))
    }

    fun movedInfo(info: ShapeInfo, m: Affine, movedIds: Set<String>, others: List<ShapeInfo>, tolerance: Float): ShapeInfo {
        val t = ShapeGeometry.transformed(info, m)
        if (!ShapeGeometry.isConnector(t)) return t
        val e = ShapeGeometry.ends(t)
        fun relink(link: ShapeLink?, x: Float, y: Float): ShapeLink? =
            if (link != null && link.targetId in movedIds) link else ShapeGlue.snapEnd(x, y, others, tolerance)?.link
        return t.copy(start = relink(t.start, e[0], e[1]), end = relink(t.end, e[2], e[3]))
    }

    /** Fresh ids for copies of the shapes [ids] (old id → new id). */
    fun renamer(ids: Collection<String>): Map<String, String> = ids.associateWith { newId() }

    /** [brush] of a copy: its shape under its new id (from [renamed]), links kept only between copies. */
    fun copied(brush: InkBrush, renamed: Map<String, String>, m: Affine = Affine.IDENTITY): InkBrush {
        val info = brush.shape ?: return brush
        val t = if (m.isIdentity) info else ShapeGeometry.transformed(info, m)
        fun remap(l: ShapeLink?) = l?.let { renamed[it.targetId]?.let { id -> it.copy(targetId = id) } }
        return brush.copy(shape = t.copy(id = renamed[info.id] ?: newId(), start = remap(info.start), end = remap(info.end)))
    }
}
