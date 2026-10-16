package app.bestnote.core.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Separate class so its manifest entry does not clash with other modules' FileProviders. */
class UpdateFileProvider : FileProvider()

sealed interface InstallResult {
    data object Launched : InstallResult

    /** The user must allow installs from BestNote first; the settings screen was opened. */
    data object NeedsPermission : InstallResult
}

interface ApkInstaller {
    fun canInstall(): Boolean
    fun install(apk: File): InstallResult
}

@Singleton
class SystemApkInstaller @Inject constructor(@ApplicationContext private val context: Context) : ApkInstaller {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun install(apk: File): InstallResult {
        if (!canInstall()) {
            // "Install unknown apps" for BestNote; on return the user taps Update again.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallResult.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        // Same package and signing key: the system installs it as an update and keeps the data.
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return InstallResult.Launched
    }
}
