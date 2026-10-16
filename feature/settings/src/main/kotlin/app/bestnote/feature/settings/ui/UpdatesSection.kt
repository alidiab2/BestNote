package app.bestnote.feature.settings.ui

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.core.updater.UpdateStatus
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.UpdatesViewModel

/** Version line, "Check for updates" with its result, and the auto-check switch. */
@Composable
internal fun UpdatesGroup(viewModel: UpdatesViewModel) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val build = viewModel.build

    SettingsGroup(stringResource(R.string.settings_updates_group)) {
        row {
            SettingsRow(
                title = stringResource(R.string.settings_updates_version),
                subtitle = stringResource(R.string.settings_updates_version_value, build.versionName, build.versionCode, build.commit),
                icon = Icons.Outlined.Info,
            )
        }
        row {
            val subtitle = when (val s = status) {
                UpdateStatus.Checking -> stringResource(R.string.settings_updates_checking)
                UpdateStatus.UpToDate -> stringResource(R.string.settings_updates_up_to_date)
                is UpdateStatus.Available -> stringResource(R.string.settings_updates_available, s.release.versionName)
                is UpdateStatus.Downloading -> stringResource(R.string.settings_updates_downloading)
                is UpdateStatus.NeedsPermission -> stringResource(R.string.settings_updates_needs_permission)
                is UpdateStatus.Failed -> stringResource(R.string.settings_updates_failed, s.message)
                UpdateStatus.Idle -> if (settings.lastCheckMillis > 0) {
                    stringResource(R.string.settings_updates_last, DateUtils.getRelativeTimeSpanString(settings.lastCheckMillis).toString())
                } else {
                    stringResource(R.string.settings_updates_never)
                }
            }
            SettingsRow(
                title = stringResource(R.string.settings_updates_check),
                subtitle = subtitle,
                icon = Icons.Outlined.Sync,
            ) {
                RowAction(
                    text = stringResource(
                        if (status is UpdateStatus.Available) R.string.settings_updates_update else R.string.settings_updates_check_action,
                    ),
                    enabled = status !is UpdateStatus.Checking && status !is UpdateStatus.Downloading,
                    onClick = viewModel::checkOrUpdate,
                )
            }
        }
        row {
            SwitchRow(
                title = stringResource(R.string.settings_updates_auto),
                subtitle = stringResource(R.string.settings_updates_auto_sub),
                checked = settings.autoCheck,
                onCheckedChange = viewModel::setAuto,
                icon = Icons.Outlined.SystemUpdate,
            )
        }
    }
}
