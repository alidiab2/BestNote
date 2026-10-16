package app.bestnote.core.model

import kotlin.math.cos
import kotlin.math.sin

/** Paragraph alignment of a [TextElement]. [Start] follows the text's direction: right for Arabic, left for English. */
enum class TextAlignment { Start, Center, End, Justify }

/** The visible part of an image, as fractions (0..1) of the stored picture. */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom) { "Bad crop $this" }
    }

    val isFull: Boolean get() = left == 0f && top == 0f && right == 1f && bottom == 1f

    companion object {
        /** Smallest fraction of the picture a crop can keep. */
        const val MIN_SPAN = 0.05f

        /** A crop from possibly stored-out-of-range numbers: clamped, or the full picture when degenerate. */
        fun sanitized(left: Float, top: Float, right: Float, bottom: Float): CropRect {
            val l = left.coerceIn(0f, 1f)
            val t = top.coerceIn(0f, 1f)
            val r = right.coerceIn(0f, 1f)
            val b = bottom.coerceIn(0f, 1f)
            return if (l < r && t < b) CropRect(l, t, r, b) else CropRect()
        }
    }
}

/**
 * Something placed on a page besides ink (TEXT-01, MEDIA-01). Elements are added to a page, never to
 * its strokes, so older readers just see the ink. The frame is a [width] x [height] rectangle in page
 * points centered on ([cx], [cy]) and turned by [rotation] radians (clockwise, y down).
 */
sealed interface PageElement {
    val id: String
    val cx: Float
    val cy: Float
    val width: Float
    val height: Float
    val rotation: Float

    /** A locked element is shown but cannot be moved, resized or rotated until it is unlocked. */
    val locked: Boolean

    fun placed(cx: Float = this.cx, cy: Float = this.cy, width: Float = this.width, height: Float = this.height, rotation: Float = this.rotation): PageElement

    fun withLocked(locked: Boolean): PageElement

    /** Id shared by everything grouped together on the page (strokes and elements), or null when ungrouped (CANVAS-14). */
    val group: String?

    fun withGroup(group: String?): PageElement

    /** The same element under a new id (duplicate, paste). */
    fun withId(id: String): PageElement

    companion object {
        fun newId(): String = "el-" + java.util.UUID.randomUUID().toString().take(ID_LENGTH)
        private const val ID_LENGTH = 12
    }
}

/**
 * A picture. [asset] is the archive path of the stored (already downsampled) file, shared by every
 * element that shows the same picture. [srcWidth] x [srcHeight] are its pixels; [crop] is the part shown,
 * stretched over the frame.
 */
data class ImageElement(
    override val id: String,
    override val cx: Float,
    override val cy: Float,
    override val width: Float,
    override val height: Float,
    val asset: String,
    val srcWidth: Int,
    val srcHeight: Int,
    val crop: CropRect = CropRect(),
    override val rotation: Float = 0f,
    override val locked: Boolean = false,
    override val group: String? = null,
) : PageElement {
    init {
        require(width > 0f && height > 0f) { "Image frame must be positive" }
        require(srcWidth > 0 && srcHeight > 0) { "Image size must be positive" }
    }

    override fun placed(cx: Float, cy: Float, width: Float, height: Float, rotation: Float) =
        copy(cx = cx, cy = cy, width = width, height = height, rotation = rotation)

    override fun withLocked(locked: Boolean) = copy(locked = locked)
    override fun withGroup(group: String?) = copy(group = group)
    override fun withId(id: String) = copy(id = id)

    /**
     * This picture showing [next] instead of [crop], with the kept part staying exactly where it is on the
     * page (same scale, same rotation): cropping trims the frame, it never moves or stretches what remains.
     */
    fun cropped(next: CropRect): ImageElement {
        val ptPerCropW = width / (crop.right - crop.left)
        val ptPerCropH = height / (crop.bottom - crop.top)
        val dx = ((next.left + next.right) / 2f - (crop.left + crop.right) / 2f) * ptPerCropW
        val dy = ((next.top + next.bottom) / 2f - (crop.top + crop.bottom) / 2f) * ptPerCropH
        val c = cos(rotation)
        val s = sin(rotation)
        return copy(
            cx = cx + dx * c - dy * s,
            cy = cy + dx * s + dy * c,
            width = (next.right - next.left) * ptPerCropW,
            height = (next.bottom - next.top) * ptPerCropH,
            crop = next,
        )
    }

    /** This picture turned a quarter turn about its own center (the frame turns with it). */
    fun turnedQuarter(): ImageElement = copy(rotation = normalizeAngle(rotation + (Math.PI / 2).toFloat()))
}

/** Typed text in a box [width] wide; [height] is the laid-out height of [text] (the box grows with it). */
data class TextElement(
    override val id: String,
    override val cx: Float,
    override val cy: Float,
    override val width: Float,
    override val height: Float,
    val text: String,
    val sizePt: Float = DEFAULT_SIZE_PT,
    val colorArgb: Int = DEFAULT_COLOR,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlignment = TextAlignment.Start,
    override val rotation: Float = 0f,
    override val locked: Boolean = false,
    /** Typeface family: null = the default sans; [FONT_HANDWRITING] = the bundled handwriting fonts (Smart Board). */
    val font: String? = null,
    /** Underline / strikethrough of the whole box (TEXT-02); [runs] add more on top. */
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Extra styling of parts of [text], additive to the box style (TEXT-02). */
    val runs: List<TextRun> = emptyList(),
    /** Every paragraph (line break separated) is a bullet or a numbered item. */
    val list: ListStyle = ListStyle.None,
    val numerals: Numerals = Numerals.Latin,
    /** Line height as a multiple of the font size's natural line. */
    val lineSpacing: Float = DEFAULT_LINE_SPACING,
    override val group: String? = null,
) : PageElement {
    init {
        require(width > 0f && height > 0f) { "Text frame must be positive" }
        require(sizePt > 0f) { "Text size must be positive" }
    }

    override fun placed(cx: Float, cy: Float, width: Float, height: Float, rotation: Float) =
        copy(cx = cx, cy = cy, width = width, height = height, rotation = rotation)

    override fun withLocked(locked: Boolean) = copy(locked = locked)
    override fun withGroup(group: String?) = copy(group = group)
    override fun withId(id: String) = copy(id = id)

    companion object {
        const val DEFAULT_SIZE_PT = 20f
        const val MIN_SIZE_PT = 6f
        const val MAX_SIZE_PT = 200f
        const val DEFAULT_COLOR = 0xFF1F1F1F.toInt()
        const val DEFAULT_LINE_SPACING = 1.12f
        const val MIN_LINE_SPACING = 0.8f
        const val MAX_LINE_SPACING = 3f

        /** Handwriting font, Latin first (Caveat, then Aref Ruqaa for Arabic letters). */
        const val FONT_HANDWRITING = "hand"

        /** Handwriting font, Arabic first (Aref Ruqaa, then Caveat for Latin letters). */
        const val FONT_HANDWRITING_ARABIC = "hand-ar"
    }
}

/** [radians] wrapped into (-PI, PI]. */
fun normalizeAngle(radians: Float): Float {
    val twoPi = (2 * Math.PI).toFloat()
    var a = radians % twoPi
    if (a > Math.PI) a -= twoPi
    if (a <= -Math.PI) a += twoPi
    return a
}
