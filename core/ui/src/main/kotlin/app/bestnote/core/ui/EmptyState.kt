/**
 * :core:ui — reusable, feature-agnostic composables built on :core:designsystem tokens.
 *
 * Anything here must be stateless and know nothing about notebooks, ink or navigation.
 */
package app.bestnote.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion

/** Centered title + message shown when a list has nothing to display, with an optional action slot. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = true,
        enter = Motion.Enter.content(reducedMotion()),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.S, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    BestNoteTheme {
        EmptyState(title = "No notebooks yet", message = "Tap New notebook to start writing.")
    }
}
