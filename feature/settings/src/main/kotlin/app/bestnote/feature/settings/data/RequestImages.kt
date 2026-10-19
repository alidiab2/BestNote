package app.bestnote.feature.settings.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Turns a picked image into small JPEG bytes that are cheap to store and upload. */
interface ImageCompressor {
    /** Null when the image can't be read or decoded. */
    suspend fun compress(uri: Uri): ByteArray?
}

class AndroidImageCompressor @Inject constructor(@ApplicationContext private val context: Context) : ImageCompressor {
    override suspend fun compress(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                decoded
            }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
        }.getOrNull()
    }

    /** Power of two that leaves the long edge at or above [MAX_EDGE]. */
    private fun sampleSize(longEdge: Int): Int {
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    private companion object {
        const val MAX_EDGE = 1600
        const val QUALITY = 80
    }
}
