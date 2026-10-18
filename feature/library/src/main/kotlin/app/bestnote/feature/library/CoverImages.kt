package app.bestnote.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.bestnote.core.model.NotebookId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * LIB-10: custom cover images. A picked photo is centre-cropped to the cover's ratio, downscaled and
 * stored as a small JPEG in app storage; [NotebookMeta.coverImage] holds its file name. A locked
 * notebook never draws it (see [buildLibraryState]).
 */
interface CoverImages {
    /** Crops and stores the image at [uri] as a cover for [id]. @return the stored file name, or null if it cannot be read. */
    suspend fun import(id: NotebookId, uri: String): String?

    /** Copies the stored cover [name] for another notebook (a duplicate). @return the new file name. */
    suspend fun copy(name: String, to: NotebookId): String?

    /** Absolute path of the stored cover [name]; a pure lookup (the file may be gone, then the cover falls back to its color). */
    fun pathOf(name: String?): String?

    suspend fun delete(name: String)

    object None : CoverImages {
        override suspend fun import(id: NotebookId, uri: String): String? = null
        override suspend fun copy(name: String, to: NotebookId): String? = null
        override fun pathOf(name: String?): String? = null
        override suspend fun delete(name: String) = Unit
    }
}

/** The part of a [srcW] x [srcH] image that fills a [ratio] (width / height) box, centred: left, top, width, height. */
internal fun centerCrop(srcW: Int, srcH: Int, ratio: Float): IntArray {
    val srcRatio = srcW.toFloat() / srcH
    return if (srcRatio > ratio) {
        val w = (srcH * ratio).roundToInt().coerceIn(1, srcW)
        intArrayOf((srcW - w) / 2, 0, w, srcH)
    } else {
        val h = (srcW / ratio).roundToInt().coerceIn(1, srcH)
        intArrayOf(0, (srcH - h) / 2, srcW, h)
    }
}

/** Covers in `filesDir/library/covers`, JPEG, [COVER_W] x [COVER_H] at most (2x the shelf cover). */
class FileCoverImages @Inject constructor(@ApplicationContext private val context: Context) : CoverImages {
    private val dir get() = File(context.filesDir, DIR)

    override suspend fun import(id: NotebookId, uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = max(COVER_W.toFloat() / info.size.width, COVER_H.toFloat() / info.size.height).coerceAtMost(1f)
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
            val c = centerCrop(bitmap.width, bitmap.height, COVER_W.toFloat() / COVER_H)
            val cropped = Bitmap.createBitmap(bitmap, c[0], c[1], c[2], c[3])
            dir.mkdirs()
            val name = "${id.value}-${System.currentTimeMillis()}.jpg"
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) }
            if (!tmp.renameTo(File(dir, name))) throw IOException("Could not write cover")
            name
        } catch (@Suppress("SwallowedException") e: IOException) {
            null
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            null
        } catch (@Suppress("SwallowedException") e: IllegalStateException) {
            null
        }
    }

    override suspend fun copy(name: String, to: NotebookId): String? = withContext(Dispatchers.IO) {
        val from = File(dir, safe(name))
        if (!from.isFile) return@withContext null
        val copy = "${to.value}-${System.currentTimeMillis()}.jpg"
        runCatching { from.copyTo(File(dir, copy), overwrite = true) }.map { copy }.getOrNull()
    }

    override fun pathOf(name: String?): String? = name?.let { File(dir, safe(it)).path }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { File(dir, safe(name)).delete() }
    }

    private fun safe(name: String) = name.substringAfterLast('/')

    private companion object {
        const val DIR = "library/covers"
        const val COVER_W = 372
        const val COVER_H = 504
        const val QUALITY = 85
    }
}
