package app.bestnote.core.common

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The last few uncaught crashes, one text file each in [dir] (the app uses `filesDir/crashes`).
 * Written synchronously from the uncaught-exception handler, so it does no I/O beyond one small
 * file write, and never throws.
 */
class CrashLog(private val dir: File, private val keep: Int = 5, private val clock: () -> Long = System::currentTimeMillis) {
    class Entry(val name: String, val text: String)

    /** Records [error] on [thread] with a [header] (app version, device). Returns the file, or null on failure. */
    fun record(thread: Thread, error: Throwable, header: String): File? = runCatching {
        dir.mkdirs()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val file = File(dir, "crash-${clock()}.txt")
        file.writeText("$header\nthread: ${thread.name}\n\n$trace")
        prune()
        file
    }.getOrNull()

    /** Newest first, at most [keep]. */
    fun recent(): List<Entry> = files().take(keep).mapNotNull { f -> runCatching { Entry(f.name, f.readText()) }.getOrNull() }

    fun clear() {
        files().forEach { it.delete() }
    }

    private fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L }

    private fun prune() {
        files().drop(keep).forEach { it.delete() }
    }

    companion object {
        /**
         * Installs a default handler that records the crash and then hands it to the previous
         * handler (so the system still shows the crash and kills the process).
         */
        fun install(log: CrashLog, header: () -> String) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                log.record(thread, error, runCatching(header).getOrDefault(""))
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
