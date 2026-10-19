package app.bestnote.feature.library

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing

private const val SHIMMER_LOW = 0.45f
private const val SHIMMER_HIGH = 0.9f
private const val PLACEHOLDER_COVERS = 6

/** A pulsing placeholder block (a still one under "Remove animations"), from theme tokens only. */
@Composable
internal fun Modifier.shimmer(shape: Shape): Modifier {
    val alpha = if (BestNoteTheme.reducedMotion) {
        SHIMMER_HIGH
    } else {
        val pulse = rememberInfiniteTransition(label = "shimmer")
        val a by pulse.animateFloat(SHIMMER_LOW, SHIMMER_HIGH, Motion.Shimmer.pulse(), label = "shimmer-alpha")
        a
    }
    return clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha))
}

/** The library while the first load runs: a shelf of placeholder covers instead of a false "0 notebooks" or an empty state. */
@Composable
internal fun LibraryLoadingShelf(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.library_loading)
    Row(
        modifier.fillMaxWidth().clipToBounds().semantics { contentDescription = loading },
        horizontalArrangement = Arrangement.spacedBy(Spacing.XL),
    ) {
        repeat(PLACEHOLDER_COVERS) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Box(Modifier.size(CoverWidth, CoverHeight).shimmer(BestNoteShapes.NotebookCover))
                Box(Modifier.padding(top = Spacing.XS).width(CoverWidth * 0.8f).height(12.dp).shimmer(BestNoteShapes.Control))
            }
        }
    }
}
