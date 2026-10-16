package app.bestnote.feature.settings.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import app.bestnote.ai.vision.Redactor
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.core.common.CrashLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Build facts the app module knows (BuildConfig); bound in `:app`. */
interface DebugInfo {
    val versionName: String
    val versionCode: Long
    val commit: String
    val buildType: String

    /** Where the app's uncaught-exception handler writes crashes. */
    val crashDir: File
}

/** Separate class so its manifest entry does not clash with other modules' FileProviders. */
class DebugLogFileProvider : FileProvider()

/** Collects, redacts and exports the debug log. */
interface DebugLogCollector {
    /** The whole log as text, secrets redacted. */
    suspend fun collect(): String

    /** Writes [text] to a shareable file (zipped when large) and returns its content URI. */
    suspend fun export(text: String): Uri

    var verboseAiLogging: Boolean
}

/**
 * One text report for bug reports from a real device: app/build/device, the app's own logcat
 * (`logcat -d --pid`, last [LOGCAT_LINES] lines; no READ_LOGS needed), the last crashes and the
 * last Smart Board AI calls with drop reasons and (with verbose logging) raw responses. Every
 * section goes through [Redactor] with the stored API keys as extra secrets.
 */
class AndroidDebugLogCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val info: DebugInfo,
    private val providers: AiProviders,
) : DebugLogCollector {

    override var verboseAiLogging: Boolean
        get() = providers.settings.verboseLogging
        set(value) {
            providers.settings.verboseLogging = value
            VisionDiagnostics.captureRaw = value || info.buildType == "debug"
        }

    override suspend fun collect(): String = withContext(Dispatchers.IO) {
        val secrets = providers.settings.allKeys()
        val text = buildString {
            appendLine("# BestNote debug log")
            appendLine("generated: ${stamp("yyyy-MM-dd HH:mm:ss Z")}")
            appendLine("app: ${info.versionName} (${info.versionCode}) ${info.buildType} commit ${info.commit}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("smart board provider: ${providers.selected.id}, verbose AI logging: $verboseAiLogging")
            appendLine()
            appendLine("## Smart Board AI (last ${VisionDiagnostics.recent().size} calls)")
            VisionDiagnostics.recent().asReversed().forEach { appendLine(VisionDiagnostics.format(it)) }
            appendLine()
            val crashes = CrashLog(info.crashDir).recent()
            appendLine("## Crashes (last ${crashes.size})")
            crashes.forEach { appendLine("--- ${it.name}").appendLine(it.text) }
            appendLine()
            appendLine("## Logcat (this app, last $LOGCAT_LINES lines)")
            append(logcat())
        }
        Redactor.redact(text, secrets)
    }

    override suspend fun export(text: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "debug").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest export
        val base = "bestnote-debug-${stamp("yyyyMMdd-HHmmss")}"
        val bytes = text.toByteArray()
        val file = if (bytes.size <= ZIP_ABOVE_BYTES) {
            File(dir, "$base.txt").apply { writeBytes(bytes) }
        } else {
            File(dir, "$base.zip").apply {
                ZipOutputStream(outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("$base.txt"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.debuglog", file)
    }

    private fun logcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString(), "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(LOGCAT_TIMEOUT_S, TimeUnit.SECONDS)
        out
    }.getOrElse { "(logcat unavailable: ${it.javaClass.simpleName})\n" }

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).format(Date())

    private companion object {
        const val LOGCAT_LINES = 5000
        const val LOGCAT_TIMEOUT_S = 5L
        const val ZIP_ABOVE_BYTES = 1_500_000
    }
}
