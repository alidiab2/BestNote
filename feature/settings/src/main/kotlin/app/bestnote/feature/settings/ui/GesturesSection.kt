package app.bestnote.feature.settings.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.bestnote.core.datastore.GesturePreferences
import app.bestnote.feature.settings.R

@Composable
internal fun GesturesSection(gestures: GesturePreferences, update: ((GesturePreferences) -> GesturePreferences) -> Unit) {
    DetailHeader(stringResource(R.string.settings_cat_gestures), stringResource(R.string.settings_gestures_desc))

    SettingsGroup(stringResource(R.string.settings_gestures_touch)) {
        row {
            SwitchRow(
                stringResource(R.string.settings_gesture_undo),
                gestures.twoFingerTapUndo,
                { v -> update { it.copy(twoFingerTapUndo = v) } },
                subtitle = stringResource(R.string.settings_gesture_undo_sub),
                icon = Icons.AutoMirrored.Outlined.Undo,
            )
        }
        row {
            SwitchRow(
                stringResource(R.string.settings_gesture_redo),
                gestures.threeFingerTapRedo,
                { v -> update { it.copy(threeFingerTapRedo = v) } },
                subtitle = stringResource(R.string.settings_gesture_redo_sub),
                icon = Icons.AutoMirrored.Outlined.Redo,
            )
        }
        row {
            SwitchRow(
                stringResource(R.string.settings_gesture_pinch),
                gestures.pinchToZoom,
                { v -> update { it.copy(pinchToZoom = v) } },
                subtitle = stringResource(R.string.settings_gesture_pinch_sub),
                icon = Icons.Outlined.ZoomIn,
            )
        }
        row {
            SwitchRow(
                stringResource(R.string.settings_gesture_scroll),
                gestures.fingerScroll,
                { v -> update { it.copy(fingerScroll = v) } },
                subtitle = stringResource(R.string.settings_gesture_scroll_sub),
                icon = Icons.Outlined.PanTool,
            )
        }
    }

    SettingsGroup(stringResource(R.string.settings_gestures_pen)) {
        row {
            SwitchRow(
                stringResource(R.string.settings_gesture_pen_double),
                gestures.penDoubleTapLastTool,
                { v -> update { it.copy(penDoubleTapLastTool = v) } },
                subtitle = stringResource(R.string.settings_gesture_pen_double_sub),
                icon = Icons.Outlined.SwapHoriz,
            )
        }
    }
}
