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

enum class RequestPriority { Low, Normal, High }

/** A file attached to a request: a screenshot or any other file (log, PDF, video, zip...). */
@Serializable
data class Attachment(
    /** Name inside the store's `files/` folder, unique per request. */
    val file: String,
    /** The name the user knows it by. */
    val name: String,
    val mime: String,
    val size: Long,
    /** Where it lives in the GitHub repo once uploaded; null until then. */
    val url: String? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** A file ready to be stored: images already shrunk, other files as they are. */
class PendingAttachment(val name: String, val mime: String, val bytes: ByteArray)

/** One thing the user wants from the app. Saved on the device first; sent to GitHub only if a token is set. */
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
    val priority: RequestPriority = RequestPriority.Normal,
    val attachments: List<Attachment> = emptyList(),
    /** Last edit; 0 when never edited. */
    val updatedAt: Long = 0,
    /** The GitHub issue this was sent as; null until it has been sent. */
    val issueNumber: Int? = null,
    val issueUrl: String? = null,
    /** Edited here after it was sent, so the issue still shows the old text. */
    val unsynced: Boolean = false,
    /** Only read from files written by the first version, then moved into [attachments]. */
    val images: List<String> = emptyList(),
) {
    /** Stable, short id used in the list, the Markdown file and GitHub issue titles. */
    val ref: String get() = "REQ-%03d".format(number)
}

/** What the form holds; also saved so a half-written request survives leaving the screen. */
@Serializable
data class DraftText(
    val kind: RequestKind = RequestKind.Feature,
    val priority: RequestPriority = RequestPriority.Normal,
    val title: String = "",
    val details: String = "",
) {
    val isBlank: Boolean get() = title.isBlank() && details.isBlank()
}

/** New text and attachment changes for an existing request. */
class RequestEdit(
    val kind: RequestKind,
    val priority: RequestPriority,
    val title: String,
    val details: String,
    val add: List<PendingAttachment> = emptyList(),
    val remove: Set<String> = emptySet(),
)

interface FeatureRequestStore {
    val requests: StateFlow<List<FeatureRequest>>

    /** Returns the saved request, or null when [title] is blank. */
    suspend fun add(
        kind: RequestKind,
        title: String,
        details: String,
        priority: RequestPriority = RequestPriority.Normal,
        attachments: List<PendingAttachment> = emptyList(),
    ): FeatureRequest?

    /** Returns the updated request, or null when it is gone or the new title is blank. */
    suspend fun update(number: Int, edit: RequestEdit): FeatureRequest?

    suspend fun setStatus(number: Int, status: RequestStatus)

    /**
     * Remembers that [number] was sent as GitHub issue [issueNumber], and where its files now live. [at] is the
     * request's `updatedAt` when the send began: if it was edited meanwhile, the edit is still to be pushed.
     */
    suspend fun recordSent(number: Int, issueNumber: Int, issueUrl: String, urls: Map<String, String>, at: Long)

    /** An edit reached the issue; [urls] are newly uploaded files and [at] as in [recordSent]. */
    suspend fun recordSynced(number: Int, urls: Map<String, String>, at: Long)

    /** GitHub is the truth for sent requests: closed issues become Done, reopened ones Open. */
    suspend fun applyRemote(closedByIssue: Map<Int, Boolean>)

    suspend fun delete(number: Int)

    /** Removes every finished request and returns how many were removed. */
    suspend fun deleteDone(): List<FeatureRequest>

    /** The stored file for [name] (it may not exist any more). */
    fun file(name: String): File

    /** The whole list (or just [only]) as Markdown, the same text that is kept in `requests.md`. */
    fun markdown(only: Int? = null): String

    fun readDraft(): DraftText?

    fun writeDraft(draft: DraftText?)
}

/** Hands files to the share sheet and to other apps. */
interface FeatureRequestSharer {
    /** `requests.md` for everything, or for a single request. */
    suspend fun export(only: Int? = null): Uri

    /** A content URI other apps may read for one stored attachment. */
    fun uriFor(attachment: Attachment): Uri
}

/** Build/device facts stamped on each request; supplied by the app module (and faked in tests). */
fun interface RequestContextProvider {
    fun stamp(): Pair<String, String>
}

/**
 * Requests live in `<external files>/requests/` as `requests.json` (source of truth), `requests.md`
 * (a readable copy rewritten on every change) and `files/` (attachments). The external app folder is readable
 * over adb without root. Plain JVM: [dir] and [now] are injected.
 */
class FileFeatureRequestStore(
    private val dir: File,
    private val context: RequestContextProvider,
    private val now: () -> Long = System::currentTimeMillis,
) : FeatureRequestStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonFile get() = File(dir, JSON_NAME)
    private val markdownFile get() = File(dir, MARKDOWN_NAME)
    private val draftFile get() = File(dir, DRAFT_NAME)
    private val filesDir get() = File(dir, FILES)
    private val lock = Mutex()
    private val _requests = MutableStateFlow(load())
    override val requests: StateFlow<List<FeatureRequest>> = _requests.asStateFlow()

    override suspend fun add(
        kind: RequestKind,
        title: String,
        details: String,
        priority: RequestPriority,
        attachments: List<PendingAttachment>,
    ): FeatureRequest? {
        val cleanTitle = cleanTitle(title) ?: return null
        return mutate { list ->
            val (app, device) = context.stamp()
            val number = (list.maxOfOrNull { it.number } ?: 0) + 1
            val stored = store(number, emptyList(), attachments)
            val request = FeatureRequest(
                number = number,
                createdAt = now(),
                kind = kind,
                title = cleanTitle,
                details = cleanDetails(details),
                priority = priority,
                app = app,
                device = device,
                attachments = stored,
            )
            list + request to request
        }
    }

    override suspend fun update(number: Int, edit: RequestEdit): FeatureRequest? {
        val cleanTitle = cleanTitle(edit.title) ?: return null
        return mutate { list ->
            val old = list.firstOrNull { it.number == number } ?: return@mutate list to null
            val kept = old.attachments.filter { it.file !in edit.remove }
            old.attachments.filter { it.file in edit.remove }.forEach { File(filesDir, it.file).delete() }
            val attachments = store(number, kept, edit.add)
            val textChanged = old.kind != edit.kind || old.priority != edit.priority || old.title != cleanTitle ||
                old.details != cleanDetails(edit.details) || attachments != old.attachments
            if (!textChanged) return@mutate list to old
            val updated = old.copy(
                kind = edit.kind,
                priority = edit.priority,
                title = cleanTitle,
                details = cleanDetails(edit.details),
                attachments = attachments,
                updatedAt = now(),
                unsynced = old.issueNumber != null,
            )
            list.map { if (it.number == number) updated else it } to updated
        }
    }

    override suspend fun setStatus(number: Int, status: RequestStatus) {
        mutate { list -> list.map { if (it.number == number) it.copy(status = status) else it } to Unit }
    }

    override suspend fun recordSent(number: Int, issueNumber: Int, issueUrl: String, urls: Map<String, String>, at: Long) {
        mutate { list ->
            list.map {
                if (it.number == number) {
                    it.copy(issueNumber = issueNumber, issueUrl = issueUrl, unsynced = it.updatedAt != at, attachments = it.withUrls(urls))
                } else {
                    it
                }
            } to Unit
        }
    }

    override suspend fun recordSynced(number: Int, urls: Map<String, String>, at: Long) {
        mutate { list ->
            list.map { if (it.number == number) it.copy(unsynced = it.updatedAt != at, attachments = it.withUrls(urls)) else it } to Unit
        }
    }

    private fun FeatureRequest.withUrls(urls: Map<String, String>) = attachments.map { a -> urls[a.file]?.let { a.copy(url = it) } ?: a }

    override suspend fun applyRemote(closedByIssue: Map<Int, Boolean>) {
        mutate { list ->
            list.map { r ->
                val closed = r.issueNumber?.let(closedByIssue::get) ?: return@map r
                r.copy(status = if (closed) RequestStatus.Done else RequestStatus.Open)
            } to Unit
        }
    }

    override suspend fun delete(number: Int) {
        mutate { list ->
            list.firstOrNull { it.number == number }?.attachments?.forEach { File(filesDir, it.file).delete() }
            list.filterNot { it.number == number } to Unit
        }
    }

    override suspend fun deleteDone(): List<FeatureRequest> = mutate { list ->
        val (gone, kept) = list.partition { it.status == RequestStatus.Done }
        gone.flatMap { it.attachments }.forEach { File(filesDir, it.file).delete() }
        kept to gone
    }

    override fun file(name: String): File = File(filesDir, File(name).name)

    override fun markdown(only: Int?): String =
        FeatureRequestMarkdown.render(_requests.value.let { all -> if (only == null) all else all.filter { it.number == only } })

    override fun readDraft(): DraftText? =
        runCatching { json.decodeFromString(DraftText.serializer(), draftFile.readText()) }.getOrNull()

    override fun writeDraft(draft: DraftText?) {
        runCatching {
            if (draft == null || draft.isBlank) {
                draftFile.delete()
            } else {
                dir.mkdirs()
                writeAtomically(draftFile, json.encodeToString(DraftText.serializer(), draft))
            }
        }
    }

    private fun cleanTitle(title: String) = title.trim().replace('\n', ' ').take(MAX_TITLE).ifEmpty { null }

    private fun cleanDetails(details: String) = details.trim().take(MAX_DETAILS)

    /** Writes new files next to the kept ones, up to [MAX_FILES] in all, skipping any that are too big. */
    private fun store(number: Int, kept: List<Attachment>, add: List<PendingAttachment>): List<Attachment> {
        val result = kept.toMutableList()
        var seq = (kept.maxOfOrNull { seqOf(it.file) } ?: 0)
        for (p in add) {
            if (result.size >= MAX_FILES) break
            if (p.bytes.size > MAX_FILE_BYTES) continue
            seq++
            val name = "REQ-%03d-%d-%s".format(number, seq, safeName(p.name))
            filesDir.mkdirs()
            File(filesDir, name).writeBytes(p.bytes)
            result += Attachment(file = name, name = p.name, mime = p.mime, size = p.bytes.size.toLong())
        }
        return result
    }

    private fun seqOf(file: String) = SEQ.find(file)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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

    private fun load(): List<FeatureRequest> {
        val list = runCatching { json.decodeFromString(Wire.serializer(), jsonFile.readText()).requests }.getOrDefault(emptyList())
        if (list.none { it.images.isNotEmpty() }) return list
        // First-version files kept screenshots in `images/`; they now live with every other attachment.
        val old = File(dir, "images")
        val migrated = list.map { r ->
            if (r.images.isEmpty()) return@map r
            filesDir.mkdirs()
            val moved = r.images.map { name ->
                val from = File(old, name)
                val to = File(filesDir, name)
                val size = from.length()
                if (from.exists()) from.renameTo(to)
                Attachment(file = name, name = name, mime = "image/jpeg", size = size)
            }
            r.copy(attachments = r.attachments + moved, images = emptyList())
        }
        runCatching { persist(migrated) }
        return migrated
    }

    @Serializable
    private data class Wire(val requests: List<FeatureRequest>, val version: Int = 2)

    internal companion object {
        const val JSON_NAME = "requests.json"
        const val MARKDOWN_NAME = "requests.md"
        const val DRAFT_NAME = "draft.json"
        const val FILES = "files"
        const val MAX_TITLE = 120
        const val MAX_DETAILS = 4000
        const val MAX_FILES = 8
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        private val SEQ = Regex("""^REQ-\d+-(\d+)[-.]""")

        /** Keeps the extension and plain characters so the name is safe on every file system. */
        fun safeName(name: String): String {
            val base = name.substringAfterLast('/')
            val ext = base.substringAfterLast('.', "").filter { it.isLetterOrDigit() && it.code < 128 }.take(8)
            val stem = (if ('.' in base) base.substringBeforeLast('.') else base)
                .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').take(40).ifEmpty { "file" }
            return if (ext.isEmpty()) stem else "$stem.$ext"
        }
    }
}

/** The file everyone reads: open requests first, high priority then newest, each with enough context to act on it. */
object FeatureRequestMarkdown {
    fun render(requests: List<FeatureRequest>): String = buildString {
        val order = compareByDescending<FeatureRequest> { it.priority.ordinal }.thenByDescending { it.number }
        val open = requests.filter { it.status == RequestStatus.Open }.sortedWith(order)
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
            if (r.priority != RequestPriority.Normal) appendLine("- priority: ${r.priority.name.lowercase()}")
            appendLine("- status: ${r.status.name.lowercase()}")
            appendLine("- created: ${formatDate(r.createdAt)}")
            if (r.updatedAt > 0) appendLine("- edited: ${formatDate(r.updatedAt)}")
            if (r.app.isNotBlank()) appendLine("- app: ${r.app}")
            if (r.device.isNotBlank()) appendLine("- device: ${r.device}")
            r.issueUrl?.let { appendLine("- issue: $it") }
            if (r.attachments.isNotEmpty()) {
                appendLine("- attachments: " + r.attachments.joinToString(", ") { "files/${it.file}" + if (it.name != it.file) " (${it.name})" else "" })
            }
            if (r.details.isNotBlank()) {
                appendLine()
                appendLine(r.details)
            }
        }
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))
}

/** Android wrapper: builds the folder under the app's external files dir and shares files through a FileProvider. */
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
    override suspend fun add(kind: RequestKind, title: String, details: String, priority: RequestPriority, attachments: List<PendingAttachment>) =
        store.add(kind, title, details, priority, attachments)
    override suspend fun update(number: Int, edit: RequestEdit) = store.update(number, edit)
    override suspend fun setStatus(number: Int, status: RequestStatus) = store.setStatus(number, status)
    override suspend fun recordSent(number: Int, issueNumber: Int, issueUrl: String, urls: Map<String, String>, at: Long) =
        store.recordSent(number, issueNumber, issueUrl, urls, at)
    override suspend fun recordSynced(number: Int, urls: Map<String, String>, at: Long) = store.recordSynced(number, urls, at)
    override suspend fun applyRemote(closedByIssue: Map<Int, Boolean>) = store.applyRemote(closedByIssue)
    override fun file(name: String) = store.file(name)
    override suspend fun delete(number: Int) = store.delete(number)
    override suspend fun deleteDone() = store.deleteDone()
    override fun markdown(only: Int?) = store.markdown(only)
    override fun readDraft() = store.readDraft()
    override fun writeDraft(draft: DraftText?) = store.writeDraft(draft)

    override suspend fun export(only: Int?): Uri = withContext(Dispatchers.IO) {
        // The share sheet reads from the cache folder the provider exposes; the live copy stays in [dir].
        val shared = File(appContext.cacheDir, "debug").apply { mkdirs() }
        val file = File(shared, FileFeatureRequestStore.MARKDOWN_NAME)
        file.writeText(store.markdown(only))
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.debuglog", file)
    }

    override fun uriFor(attachment: Attachment): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.debuglog", store.file(attachment.file))
}
