package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import android.graphics.Rect
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

    /**
     * PDF-02: renders only the pixel rect [region] of [page] laid out [widthPx] x [heightPx] (any zoom,
     * so text stays sharp at 400 %+), as a bitmap the size of [region]; null if this source can't.
     * Blocks: called from the dry-ink tile workers (CANVAS-06), never the main thread.
     */
    fun region(page: Page, widthPx: Int, heightPx: Int, region: Rect): Bitmap? = null
}
