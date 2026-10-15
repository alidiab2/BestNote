package app.bestnote.engine.export

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ExportEntryPoint {
    fun runner(): ExportRunner
    fun backup(): BackupController
}

/** The app-wide [ExportRunner], for screens that have no ViewModel of their own to inject it into. */
@Composable
fun rememberExportRunner(): ExportRunner {
    val context = LocalContext.current.applicationContext
    return remember(context) { entryPoint(context).runner() }
}

/** The app-wide [BackupController] (Settings → Backup & restore). */
@Composable
fun rememberBackupController(): BackupController {
    val context = LocalContext.current.applicationContext
    return remember(context) { entryPoint(context).backup() }
}

private fun entryPoint(context: Context) = EntryPointAccessors.fromApplication(context, ExportEntryPoint::class.java)
