package app.bestnote.feature.smartboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import app.bestnote.ai.scan.Quad
import app.bestnote.ai.scan.Raster
import app.bestnote.ai.scan.figure.PhotoPixels

/**
 * REQ-001: the photo as the camera or gallery gave it (still encoded, a few MB), so each drawing can be
 * redrawn from its full resolution: [width] × [height] is the upright photo, [rotation] how far the stored
 * pixels turn to be upright (EXIF). Kept only for this screen, in memory.
 */
class PhotoOriginal(val bytes: ByteArray, val width: Int, val height: Int, val rotation: Int)

internal object PhotoRegions {
    /**
     * Opens [original] for region reads and runs [use] with its [PhotoPixels] for the board inside
     * [quad]; null when the photo is no finer than [board] (nothing to gain) or cannot be opened. Only
     * the rectangles a drawing needs are ever decoded, each shrunk to what its crop needs.
     */
    fun <T> withPixels(original: PhotoOriginal, quad: Quad, enhance: Boolean, board: Raster, use: (PhotoPixels?) -> T): T {
        val decoder = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(original.bytes, 0, original.bytes.size)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(original.bytes, 0, original.bytes.size, false)
        }.getOrNull() ?: return use(null)
        try {
            val pixels = PhotoPixels(original.width, original.height, quad, enhance) { x0, y0, x1, y1, sample -> region(decoder, original, x0, y0, x1, y1, sample) }
            return use(pixels.takeIf { it.gainOver(board) > MIN_GAIN })
        } finally {
            decoder.recycle()
        }
    }

    /** The upright rectangle [x0, x1) × [y0, y1), read from the stored (maybe turned) pixels, shrunk by [sample]. */
    private fun region(decoder: BitmapRegionDecoder, o: PhotoOriginal, x0: Int, y0: Int, x1: Int, y1: Int, sample: Int): Raster? {
        val rw = decoder.width
        val rh = decoder.height
        val rect = PhotoPixels.storedRect(o.rotation, rw, rh, x0, y0, x1, y1).let { Rect(it[0], it[1], it[2], it[3]) }
        if (!rect.intersect(0, 0, rw, rh) || rect.isEmpty) return null
        val raw = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val up = if (o.rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(o.rotation.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
        return try {
            BoardScan.raster(up)
        } finally {
            up.recycle()
        }
    }

    /** The photo must show the board at least this much finer than the copy the model read. */
    private const val MIN_GAIN = 1.15f
}
