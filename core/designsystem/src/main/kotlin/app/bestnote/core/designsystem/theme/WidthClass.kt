package app.bestnote.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window width buckets of the Material window size classes (DS-05): compact < 600 dp (phone,
 * Fold outer screen), medium 600 to 840 dp (small tablet, Fold inner screen, split screen),
 * expanded >= 840 dp (tablet, DeX). Decide layouts from the width of the space a screen actually
 * has (`BoxWithConstraints.maxWidth`), so free-form windows, split screen and fold/unfold all
 * resize the layout live without recreating the activity.
 */
enum class WidthClass {
    Compact,
    Medium,
    Expanded,
    ;

    companion object {
        val MediumFrom: Dp = 600.dp
        val ExpandedFrom: Dp = 840.dp

        fun of(width: Dp): WidthClass = when {
            width < MediumFrom -> Compact
            width < ExpandedFrom -> Medium
            else -> Expanded
        }
    }
}
