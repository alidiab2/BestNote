package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** Supplies the pictures of image elements (MEDIA-01). Called from `onDraw`: must be cheap and never block. */
interface ElementImages {
    /**
     * A bitmap of [asset] at least about [widthPx] wide (or the whole picture if it is smaller), or null while
     * it decodes. [onReady] runs on the main thread once a bitmap is available or has become sharper.
     */
    fun bitmap(asset: String, widthPx: Int, onReady: Runnable): Bitmap?
}

/**
 * Decodes stored pictures on one background thread and keeps the most recent ones, sized to what is
 * drawn: a page at fit-to-width needs far fewer pixels than the 2048 px stored, so memory stays flat
 * however many images a notebook has. [bytesOf] returns an asset's file bytes (any thread), or null.
 */
class AssetImageCache(
    private val bytesOf: (String) -> ByteArray?,
    maxBytes: Int = DEFAULT_MAX_BYTES,
) : ElementImages {
    private class Entry(val bitmap: Bitmap, val sampleSize: Int)

    private val cache = object : LruCache<String, Entry>(maxBytes) {
        override fun sizeOf(key: String, value: Entry) = value.bitmap.allocationByteCount
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "element-images").apply { isDaemon = true } }
    private val pending = HashSet<String>()
    private val waiting = LinkedHashSet<Runnable>()

    override fun bitmap(asset: String, widthPx: Int, onReady: Runnable): Bitmap? {
        val have = cache.get(asset)
        val sharp = have != null && (have.sampleSize == 1 || have.bitmap.width >= widthPx)
        if (!sharp && pending.add(asset)) {
            waiting += onReady
            worker.execute { decode(asset, widthPx) }
        } else if (!sharp) {
            waiting += onReady
        }
        return have?.bitmap
    }

    /** Drops every cached bitmap (e.g. when the notebook closes). */
    fun clear() {
        cache.evictAll()
    }

    private fun decode(asset: String, widthPx: Int) {
        try {
            val bytes = bytesOf(asset) ?: return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx.coerceAtLeast(MIN_DECODE_PX)) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return
            cache.put(asset, Entry(bmp, sample))
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
        } finally {
            main.post {
                pending.remove(asset)
                val run = ArrayList(waiting)
                waiting.clear()
                run.forEach { it.run() }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 48 * 1024 * 1024
        private const val MIN_DECODE_PX = 256
    }
}
