package app.bestnote.feature.settings.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.inject.Inject

sealed interface ReadResult {
    data class Ok(val attachment: PendingAttachment) : ReadResult
    data object TooLarge : ReadResult
    data object Unreadable : ReadResult
}

/** Turns something the user picked into bytes to store: photos are shrunk, other files are kept as they are. */
interface AttachmentReader {
    suspend fun read(uri: Uri): ReadResult
}

class AndroidAttachmentReader @Inject constructor(@ApplicationContext private val context: Context) : AttachmentReader {
    override suspend fun read(uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) -1L else c.getLong(1)
                }
            }
            val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: guessMime(fileName)
            if (isShrinkable(mime)) {
                shrink(uri)?.let { return@runCatching ReadResult.Ok(PendingAttachment(asJpegName(fileName), "image/jpeg", it)) }
                // Not decodable as a photo: fall through and keep it as a plain file.
            }
            if (size > MAX_BYTES) return@runCatching ReadResult.TooLarge
            val bytes = resolver.openInputStream(uri)?.use { readUpTo(it, MAX_BYTES + 1) } ?: return@runCatching ReadResult.Unreadable
            if (bytes.size > MAX_BYTES) ReadResult.TooLarge else ReadResult.Ok(PendingAttachment(fileName, mime, bytes))
        }.getOrDefault(ReadResult.Unreadable)
    }

    /** Reads at most [limit] bytes, so a huge file is noticed without being loaded whole. */
    private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun shrink(uri: Uri): ByteArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight)) })
        } ?: return null
        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        // JPEG has no transparency: put transparent screenshots on white instead of black.
        val flat = if (scaled.hasAlpha()) {
            Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(-1); drawBitmap(scaled, 0f, 0f, null) } }
        } else {
            scaled
        }
        return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
    }

    /** Power of two that leaves the long edge at or above [MAX_EDGE]. */
    private fun sampleSize(longEdge: Int): Int {
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    private fun guessMime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"

    private companion object {
        const val MAX_EDGE = 1600
        const val QUALITY = 80
        const val MAX_BYTES = FileFeatureRequestStore.MAX_FILE_BYTES

        /** GIFs and SVGs would lose their meaning as a JPEG, so they stay as picked. */
        fun isShrinkable(mime: String) = mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml"

        fun asJpegName(name: String) = name.substringBeforeLast('.', name).ifBlank { "image" } + ".jpg"
    }
}
