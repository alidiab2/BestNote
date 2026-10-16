package app.bestnote.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** A picture ready to store (MEDIA-01): [bytes] of a `jpg` or `png` no larger than [ImageImport.MAX_EDGE_PX], with its pixel size. */
internal class ImportedImage(val bytes: ByteArray, val ext: String, val width: Int, val height: Int)

/**
 * Turns a picked, photographed or pasted picture into the file stored in the notebook: upright (EXIF turn
 * applied), at most [MAX_EDGE_PX] on its long side so a 50 MP photo does not bloat the notebook or memory,
 * as JPEG (photos) or PNG (pictures with transparency).
 */
internal object ImageImport {
    const val MAX_EDGE_PX = 2048
    private const val JPEG_QUALITY = 88

    /** Decodes [uri] off the main thread; null if it is not a readable picture. */
    suspend fun load(context: Context, uri: Uri): ImportedImage? = withContext(Dispatchers.Default) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            var transparent = false
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val long = max(info.size.width, info.size.height)
                if (long > MAX_EDGE_PX) {
                    val k = MAX_EDGE_PX.toFloat() / long
                    decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                transparent = info.mimeType == "image/png" || info.mimeType == "image/webp" || info.mimeType == "image/gif"
            }
            try {
                encode(bitmap, transparent && bitmap.hasAlpha())
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun encode(bitmap: Bitmap, png: Boolean): ImportedImage {
        val out = ByteArrayOutputStream()
        if (png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return ImportedImage(out.toByteArray(), if (png) "png" else "jpg", bitmap.width, bitmap.height)
    }
}

/** Where a new picture or text box goes: the middle of page [page], a bit above its center. */
internal object ElementPlacement {
    /** A frame of at most [maxFraction] of the page, with the picture's proportions: (width, height). */
    fun pictureFrame(pageW: Float, pageH: Float, picW: Int, picH: Int, maxFraction: Float = 0.6f): Pair<Float, Float> {
        val k = minOf(pageW * maxFraction / picW, pageH * maxFraction / picH)
        return picW * k to picH * k
    }
}
