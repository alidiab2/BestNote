package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import app.bestnote.core.model.Page

/**
 * Supplies the raster printed under a page's ink when the page has a background that is not paper
 * (an imported PDF page, PDF-01). Implementations render off the main thread and cache.
 */
interface PageBackgrounds {
    /**
     * The best bitmap available now for [page] at about [widthPx] wide (a lower-resolution one is
     * fine while a sharper one renders), or null if none yet. When something better becomes
     * available, [onReady] is invoked on the main thread; it may be called with the same instance
     * again and again, so implementations should not retain it past the render.
     * Called from `onDraw`: must be cheap and must not block. The bitmap is not recycled while drawn.
     */
    fun background(page: Page, widthPx: Int, onReady: Runnable): Bitmap?

    /** Renders [page]'s background [widthPx] wide now, for thumbnails; blocks, so call off the main thread. */
    fun thumbnail(page: Page, widthPx: Int): Bitmap? = null
}
