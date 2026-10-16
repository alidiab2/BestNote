package app.bestnote.feature.smartboard

import android.graphics.Bitmap
import app.bestnote.ai.scan.BoardDetector
import app.bestnote.ai.scan.Enhance
import app.bestnote.ai.scan.Perspective
import app.bestnote.ai.scan.Quad
import app.bestnote.ai.scan.Raster
import java.io.ByteArrayOutputStream

/** AI-04 on Android bitmaps: find the board, flatten it, clean it up (all pure Kotlin, no Play Services). */
internal object BoardScan {
    /** What the provider gets: board text stays legible, fewer image tiles = fewer tokens. */
    private const val OUT_SIDE = 1600
    private const val JPEG_QUALITY = 88

    fun raster(bitmap: Bitmap): Raster {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Raster(bitmap.width, bitmap.height, px)
    }

    fun bitmap(r: Raster): Bitmap = Bitmap.createBitmap(r.argb, r.width, r.height, Bitmap.Config.ARGB_8888)

    fun detect(bitmap: Bitmap): BoardDetector.Result? = runCatching { BoardDetector.detect(raster(bitmap)) }.getOrNull()

    /** The board inside [quad], upright, and enhanced when [enhance] is on. */
    fun flatten(bitmap: Bitmap, quad: Quad, enhance: Boolean, maxSide: Int = OUT_SIDE): Bitmap {
        val src = raster(bitmap)
        val q = if (quad.isConvex) quad else Quad.full(0f)
        val (w, h) = Perspective.outputSize(q, src.width, src.height, maxSide)
        val flat = Perspective.warp(src, q, w, h)
        return bitmap(if (enhance) Enhance.apply(flat) else flat)
    }

    fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.toByteArray()
    }
}
