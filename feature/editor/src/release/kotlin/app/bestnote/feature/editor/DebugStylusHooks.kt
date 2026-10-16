package app.bestnote.feature.editor

import android.content.Context
import android.view.View

/** Release builds: no synthetic input hooks (see the debug source set). */
internal object DebugStylusHooks {
    @Suppress("UNUSED_PARAMETER")
    fun attach(context: Context, view: View, elements: ElementTools): () -> Unit = {}
}
