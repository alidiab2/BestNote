package app.bestnote.feature.editor

import android.content.Context
import app.bestnote.engine.canvas.PageCanvasView

/** Release builds: no synthetic input hooks (see the debug source set). */
internal object DebugStylusHooks {
    @Suppress("UNUSED_PARAMETER")
    fun attach(context: Context, view: PageCanvasView): () -> Unit = {}
}
