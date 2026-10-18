package app.bestnote.engine.ink

import android.graphics.Bitmap
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.TextureBitmapStore
import kotlin.random.Random

/**
 * Textures BestNote's brushes use, generated in code (nothing to ship or license). Pass this store
 * to every androidx.ink renderer (wet `InProgressStrokesView.textureBitmapStore` and
 * `CanvasStrokeRenderer.create(InkTextures)`), or textured pens draw untextured.
 */
object InkTextures : TextureBitmapStore {
    /** The first pencil's grain ([InkFamily.PencilV1]); kept so its strokes look as drawn. */
    const val PENCIL_GRAIN = "bestnote:pencil-grain"

    /** INK-02 pencil grain: twice the resolution over the same tile, so it stays crisp when zoomed in. */
    const val PENCIL_GRAIN_V2 = "bestnote:pencil-grain.v2"

    private val bitmaps = HashMap<String, Bitmap>()

    @Synchronized
    override fun get(clientTextureId: String): Bitmap? = when (clientTextureId) {
        PENCIL_GRAIN -> bitmaps.getOrPut(clientTextureId) { grainBitmap(BrushTuning.Pencil.GRAIN_TEXTURE_PX_V1, BrushTuning.Pencil.GRAIN_STRENGTH) }
        PENCIL_GRAIN_V2 -> bitmaps.getOrPut(clientTextureId) { grainBitmap(BrushTuning.Pencil.GRAIN_TEXTURE_PX, BrushTuning.Pencil.GRAIN_STRENGTH) }
        else -> null
    }

    private val tinted = LinkedHashMap<Int, Bitmap>(16, 0.75f, true)

    /**
     * The pencil grain as an opaque-color tile: [colorArgb]'s RGB with the grain as alpha. For renderers
     * that can't use androidx.ink textures (the PDF/PNG export draws it as a repeating bitmap shader, tiled
     * [BrushTuning.Pencil.GRAIN_TILE] page points wide, so it matches the live grain). Shared; don't recycle.
     */
    @Synchronized
    fun grainTile(colorArgb: Int): Bitmap {
        val rgb = colorArgb and 0x00FFFFFF
        tinted[rgb]?.let { return it }
        val size = BrushTuning.Pencil.GRAIN_TEXTURE_PX
        val alpha = grainAlpha(size, BrushTuning.Pencil.GRAIN_STRENGTH)
        val bitmap = Bitmap.createBitmap(IntArray(size * size) { (alpha[it] shl 24) or rgb }, size, size, Bitmap.Config.ARGB_8888)
        tinted[rgb] = bitmap
        if (tinted.size > MAX_TINTED) tinted.remove(tinted.keys.first())
        return bitmap
    }

    private const val MAX_TINTED = 16

    /** Paper-grain layer for the pencil: tiled in page (stroke) coordinates so it never swims. */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    internal fun pencilGrainLayer(textureId: String = PENCIL_GRAIN_V2): BrushPaint.TextureLayer = BrushPaint.TilingTexture.builder()
        .setClientTextureId(textureId)
        .setSizeX(BrushTuning.Pencil.GRAIN_TILE)
        .setSizeY(BrushTuning.Pencil.GRAIN_TILE)
        .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES)
        .setOrigin(BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN)
        .setWrapX(BrushPaint.TextureLayer.Wrap.REPEAT)
        .setWrapY(BrushPaint.TextureLayer.Wrap.REPEAT)
        .setBlendMode(BrushPaint.TextureLayer.BlendMode.MODULATE)
        .build()

    private fun grainBitmap(size: Int, strength: Float): Bitmap {
        val alpha = grainAlpha(size, strength)
        val pixels = IntArray(size * size) { (alpha[it] shl 24) or 0x00FFFFFF }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Seamless (wrap-around) paper grain as per-pixel alpha 0..255: fine noise softened by a small
     * blur, so graphite catches the "peaks" of the paper. Mean alpha stays high (a pencil line
     * reads as a line, not dust); [strength] 0..1 sets how deep the gaps go. Pure for tests.
     */
    fun grainAlpha(size: Int, strength: Float, seed: Int = 7): IntArray {
        val rnd = Random(seed)
        val raw = FloatArray(size * size) { rnd.nextFloat() }
        val blurred = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val w = if (dx == 0 && dy == 0) 4f else if (dx == 0 || dy == 0) 2f else 1f
                sum += w * raw[((y + dy + size) % size) * size + (x + dx + size) % size]
            }
            blurred[y * size + x] = sum / 16f
        }
        val lo = blurred.min()
        val hi = blurred.max()
        val s = strength.coerceIn(0f, 1f)
        return IntArray(size * size) { i ->
            val n = (blurred[i] - lo) / (hi - lo) // 0..1, ~bell-shaped around 0.5
            // Most of the paper takes graphite; only the low valleys show through.
            val coverage = 1f - s * (1f - n) * (1f - n) * 1.6f
            (coverage.coerceIn(0f, 1f) * 255f).toInt()
        }
    }
}
