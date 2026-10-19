package app.bestnote.feature.settings.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class RequestKind(val label: String) { Feature("Feature"), Improvement("Improvement"), Bug("Bug") }

enum class RequestStatus { Open, Done }

/** One thing the user wants from the app. Saved on the device; no account, nothing is sent anywhere. */
@Serializable
data class FeatureRequest(
    val number: Int,
    val createdAt: Long,
    val kind: RequestKind,
    val title: String,
    val details: String = "",
    val status: RequestStatus = RequestStatus.Open,
    /** "1.4.0 (14) debug abc1234" */
    val app: String = "",
    /** "samsung SM-X810, Android 14" */
    val device: String = "",
    /** File names in the store's images folder, e.g. `REQ-007-1.jpg`. */
    val images: List<String> = emptyList(),
    /** The GitHub issue this was sent as; null until it has been sent. */
    val issueNumber: Int? = null,
    val issueUrl: String? = null,
) {
    /** Stable, short id used in the list, the Markdown file and GitHub issue titles. */
    val ref: String get() = "REQ-%03d".format(number)
}

interface FeatureRequestStore {
    val requests: StateFlow<List<FeatureRequest>>

    /** Returns the saved request, or null when [title] is blank. [images] are ready-to-store JPEG bytes. */
    suspend fun add(kind: RequestKind, title: String, details: String, images: List<ByteArray> = emptyList()): FeatureRequest?

    suspend fun setStatus(number: Int, status: RequestStatus)

    /** Remembers that [number] was sent as GitHub issue [issueNumber]. */
    suspend fun markSent(number: Int, issueNumber: Int, issueUrl: String)

    /** GitHub is the truth for sent requests: closed issues become Done, reopened ones Open. */
    suspend fun applyRemote(closedByIssue: Map<Int, Boolean>)

    /** The stored image file for [name] (it may not exist any more). */
    fun imageFile(name: String): File

    suspend fun delete(number: Int)

    /** The whole list as Markdown, the same text that is kept in `requests.md`. */
    fun markdown(): String
}

/** Hands `requests.md` to the share sheet. */
interface FeatureRequestSharer {
    suspend fun export(): Uri
}

/** Build/device facts stamped on each request; supplied by the app module (and faked in tests). */
fun interface RequestContextProvider {
    fun stamp(): Pair<String, String>
}

/**
 * Requests live in `<external files>/requests/` as `requests.json` (source of truth) and `requests.md`
 * (a readable copy rewritten on every change). The external app folder is readable over adb without
 * root, so `scripts/pull-requests.sh` can copy both into `docs/requests/` and a coding agent can read
 * and fix them. Plain JVM: [dir] and [now] are injected.
 */
class FileFeatureRequestStore(
    private val dir: File,
    private val context: RequestContextProvider,
    private val now: () -> Long = System::currentTimeMillis,
) : FeatureRequestStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonFile get() = File(dir, JSON_NAME)
    private val markdownFile get() = File(dir, MARKDOWN_NAME)
    private val imagesDir get() = File(dir, "images")
    private val lock = Mutex()
    private val _requests = MutableStateFlow(load())
    override val requests: StateFlow<List<FeatureRequest>> = _requests.asStateFlow()

    override suspend fun add(kind: RequestKind, title: String, details: String, images: List<ByteArray>): FeatureRequest? {
        val cleanTitle = title.trim().replace('\n', ' ')
        if (cleanTitle.isEmpty()) return null
        return mutate { list ->
            val (app, device) = context.stamp()
            val number = (list.maxOfOrNull { it.number } ?: 0) + 1
            val names = images.take(MAX_IMAGES).mapIndexed { i, bytes ->
                val name = "REQ-%03d-%d.jpg".format(number, i + 1)
                imagesDir.mkdirs()
                File(imagesDir, name).writeBytes(bytes)
                name
            }
            val request = FeatureRequest(
                number = number,
                createdAt = now(),
                kind = kind,
                title = cleanTitle.take(MAX_TITLE),
                details = details.trim().take(MAX_DETAILS),
                app = app,
                device = device,
                images = names,
            )
            list + request to request
        }
    }

    override suspend fun markSent(number: Int, issueNumber: Int, issueUrl: String) {
        mutate { list ->
            list.map { if (it.number == number) it.copy(issueNumber = issueNumber, issueUrl = issueUrl) else it } to Unit
        }
    }

    override suspend fun applyRemote(closedByIssue: Map<Int, Boolean>) {
        mutate { list ->
            list.map { r ->
                val closed = r.issueNumber?.let(closedByIssue::get) ?: return@map r
                r.copy(status = if (closed) RequestStatus.Done else RequestStatus.Open)
            } to Unit
        }
    }

    override fun imageFile(name: String): File = File(imagesDir, File(name).name)

    override suspend fun setStatus(number: Int, status: RequestStatus) {
        mutate { list -> list.map { if (it.number == number) it.copy(status = status) else it } to Unit }
    }

    override suspend fun delete(number: Int) {
        mutate { list ->
            list.firstOrNull { it.number == number }?.images?.forEach { File(imagesDir, it).delete() }
            list.filterNot { it.number == number } to Unit
        }
    }

    override fun markdown(): String = FeatureRequestMarkdown.render(_requests.value)

    private suspend fun <T> mutate(change: (List<FeatureRequest>) -> Pair<List<FeatureRequest>, T>): T =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val (next, result) = change(_requests.value)
                if (next != _requests.value) {
                    persist(next)
                    _requests.value = next
                }
                result
            }
        }

    private fun persist(list: List<FeatureRequest>) {
        dir.mkdirs()
        writeAtomically(jsonFile, json.encodeToString(Wire.serializer(), Wire(list)))
        writeAtomically(markdownFile, FeatureRequestMarkdown.render(list))
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun load(): List<FeatureRequest> =
        runCatching { json.decodeFromString(Wire.serializer(), jsonFile.readText()).requests }.getOrDefault(emptyList())

    @Serializable
    private data class Wire(val requests: List<FeatureRequest>, val version: Int = 1)

    internal companion object {
        const val JSON_NAME = "requests.json"
        const val MARKDOWN_NAME = "requests.md"
        const val MAX_TITLE = 120
        const val MAX_DETAILS = 4000
        const val MAX_IMAGES = 4
    }
}

/** The file everyone reads: open requests first, newest first, each with enough context to act on it. */
object FeatureRequestMarkdown {
    fun render(requests: List<FeatureRequest>): String = buildString {
        val open = requests.filter { it.status == RequestStatus.Open }.sortedByDescending { it.number }
        val done = requests.filter { it.status == RequestStatus.Done }.sortedByDescending { it.number }
        appendLine("# BestNote requests")
        appendLine()
        appendLine("${open.size} open, ${done.size} done. Written by the app (Settings, Requests).")
        section("Open", open)
        section("Done", done)
    }

    private fun StringBuilder.section(title: String, list: List<FeatureRequest>) {
        appendLine()
        appendLine("## $title")
        if (list.isEmpty()) {
            appendLine()
            appendLine("_None._")
            return
        }
        list.forEach { r ->
            appendLine()
            appendLine("### ${r.ref}: ${r.title}")
            appendLine()
            appendLine("- type: ${r.kind.label.lowercase()}")
            appendLine("- status: ${r.status.name.lowercase()}")
            appendLine("- created: ${formatDate(r.createdAt)}")
            if (r.app.isNotBlank()) appendLine("- app: ${r.app}")
            if (r.device.isNotBlank()) appendLine("- device: ${r.device}")
            r.issueUrl?.let { appendLine("- issue: $it") }
            if (r.images.isNotEmpty()) appendLine("- screenshots: " + r.images.joinToString(", ") { "images/$it" })
            if (r.details.isNotBlank()) {
                appendLine()
                appendLine(r.details)
            }
        }
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))
}

/** Android wrapper: builds the folder under the app's external files dir and shares `requests.md`. */
@Singleton
class AndroidFeatureRequestStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
    info: DebugInfo,
) : FeatureRequestStore, FeatureRequestSharer {
    private val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "requests")
    private val store = FileFeatureRequestStore(
        dir,
        RequestContextProvider {
            "${info.versionName} (${info.versionCode}) ${info.buildType} ${info.commit}".trim() to
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        },
    )

    override val requests get() = store.requests
    override suspend fun add(kind: RequestKind, title: String, details: String, images: List<ByteArray>) =
        store.add(kind, title, details, images)
    override suspend fun setStatus(number: Int, status: RequestStatus) = store.setStatus(number, status)
    override suspend fun markSent(number: Int, issueNumber: Int, issueUrl: String) = store.markSent(number, issueNumber, issueUrl)
    override suspend fun applyRemote(closedByIssue: Map<Int, Boolean>) = store.applyRemote(closedByIssue)
    override fun imageFile(name: String) = store.imageFile(name)
    override suspend fun delete(number: Int) = store.delete(number)
    override fun markdown() = store.markdown()

    override suspend fun export(): Uri = withContext(Dispatchers.IO) {
        // The share sheet reads from the cache folder the provider exposes; the live copy stays in [dir].
        val shared = File(appContext.cacheDir, "debug").apply { mkdirs() }
        val file = File(shared, FileFeatureRequestStore.MARKDOWN_NAME)
        file.writeText(store.markdown())
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.debuglog", file)
    }
}
