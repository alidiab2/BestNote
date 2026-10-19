package app.bestnote.feature.settings.data

import android.content.Context
import android.content.SharedPreferences
import app.bestnote.ai.vision.gemini.KeystoreCipher
import app.bestnote.ai.vision.gemini.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where requests are sent: a GitHub repo and a fine-grained token for it. The token is kept only as
 * Keystore-sealed ciphertext (same scheme as the Smart Board keys) and is never logged or shown again.
 */
interface GitHubRequestSettings {
    /** `owner/name`. */
    suspend fun repo(): String
    suspend fun token(): String?
    suspend fun save(token: String, repo: String)
    suspend fun clear()

    /** e.g. "…a1b2"; null when no token is stored. */
    val tokenHint: String?
}

@Singleton
class KeystoreGitHubRequestSettings(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher,
) : GitHubRequestSettings {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KeystoreCipher())

    override suspend fun repo() = withContext(Dispatchers.IO) { prefs.getString(KEY_REPO, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO }

    override suspend fun token(): String? = withContext(Dispatchers.IO) { prefs.getString(KEY_TOKEN, null)?.let(cipher::decrypt) }

    override val tokenHint: String? get() = prefs.getString(KEY_HINT, null)?.let { "…$it" }

    override suspend fun save(token: String, repo: String) {
        withContext(Dispatchers.IO) {
            val t = token.trim()
            prefs.edit()
                .putString(KEY_TOKEN, cipher.encrypt(t))
                .putString(KEY_HINT, t.takeLast(4))
                .putString(KEY_REPO, repo.trim().ifBlank { DEFAULT_REPO })
                .commit()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { prefs.edit().remove(KEY_TOKEN).remove(KEY_HINT).commit() }
    }

    companion object {
        const val DEFAULT_REPO = "alidiab2/BestNote-requests"
        private const val PREFS = "bestnote.requests.github"
        private const val KEY_TOKEN = "token_ciphertext"
        private const val KEY_HINT = "token_hint"
        private const val KEY_REPO = "repo"
    }
}

/** Why a send or a check failed, in terms the screen can explain. */
enum class SyncProblem { NoToken, BadToken, NoRepo, Offline, Other }

class RequestSyncException(val problem: SyncProblem, message: String) : Exception(message)

/** Where a request goes. [GitHubRequestSink] in the app; a fake in tests. */
interface RequestSink {
    /** Uploads [files] (the attachments with no `url` yet), opens an issue and returns its number and URL. */
    suspend fun submit(request: FeatureRequest, files: List<Pair<Attachment, ByteArray>>): SentIssue

    /** Uploads new [files] and rewrites the issue's title, text and labels. Returns the new files' URLs. */
    suspend fun update(request: FeatureRequest, files: List<Pair<Attachment, ByteArray>>): Map<String, String>

    /** Issue number to "is closed" for every issue the app has opened. */
    suspend fun closedStates(): Map<Int, Boolean>

    suspend fun setClosed(issueNumber: Int, closed: Boolean)

    /** The request was deleted in the app: leave a note on the issue and close it as not planned. */
    suspend fun discard(issueNumber: Int)

    /** Replies on the issue, oldest first. */
    suspend fun comments(issueNumber: Int): List<IssueComment>

    /** Throws [RequestSyncException] when the token or repo can't be used. */
    suspend fun check()
}

data class SentIssue(val number: Int, val url: String, val urls: Map<String, String> = emptyMap())

data class IssueComment(val author: String, val body: String, val createdAt: String)

/** GitHub REST: attachments are committed under `attachments/`, the issue links them. Needs Issues + Contents write on one repo. */
class GitHubRequestSink(
    private val http: OkHttpClient,
    private val settings: GitHubRequestSettings,
    private val baseUrl: String = "https://api.github.com",
) : RequestSink {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check() {
        val (repo, token) = target()
        call(token, Request.Builder().url("$baseUrl/repos/$repo").get())
    }

    override suspend fun submit(request: FeatureRequest, files: List<Pair<Attachment, ByteArray>>): SentIssue {
        val (repo, token) = target()
        val urls = upload(repo, token, request, files)
        val body = buildJsonObject {
            put("title", "[${request.ref}] ${request.title}")
            put("body", issueBody(request, urls))
            put("labels", labelsFor(request, emptyList()))
        }
        val reply = call(token, Request.Builder().url("$baseUrl/repos/$repo/issues").post(body.toBody()))
        val number = reply.obj["number"]?.jsonPrimitive?.intOrNull
        val url = reply.obj["html_url"]?.jsonPrimitive?.contentOrNull
        if (number == null || url == null) throw RequestSyncException(SyncProblem.Other, "Unexpected reply")
        return SentIssue(number, url, urls.filterKeys { k -> files.any { it.first.file == k } })
    }

    override suspend fun update(request: FeatureRequest, files: List<Pair<Attachment, ByteArray>>): Map<String, String> {
        val (repo, token) = target()
        val issue = request.issueNumber ?: throw RequestSyncException(SyncProblem.Other, "Not sent yet")
        val urls = upload(repo, token, request, files)
        // Labels are replaced as one list, so keep the ones added on GitHub (wontfix, a milestone tag...).
        val current = call(token, Request.Builder().url("$baseUrl/repos/$repo/issues/$issue").get())
        val existing = current.obj["labels"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty()
        val body = buildJsonObject {
            put("title", "[${request.ref}] ${request.title}")
            put("body", issueBody(request, urls))
            put("labels", labelsFor(request, existing))
        }
        call(token, Request.Builder().url("$baseUrl/repos/$repo/issues/$issue").patch(body.toBody()))
        return urls.filterKeys { k -> files.any { it.first.file == k } }
    }

    /** Commits every new file and returns the URL of every attachment of [request] (old ones keep theirs). */
    private suspend fun upload(repo: String, token: String, request: FeatureRequest, files: List<Pair<Attachment, ByteArray>>): Map<String, String> {
        val urls = request.attachments.mapNotNull { a -> a.url?.let { a.file to it } }.toMap().toMutableMap()
        for ((attachment, bytes) in files) {
            val path = "attachments/${request.createdAt}-${request.ref}/${attachment.file}"
            val body = buildJsonObject {
                put("message", "Attachment ${attachment.file} for ${request.ref}")
                put("content", Base64.getEncoder().encodeToString(bytes))
            }
            urls[attachment.file] = try {
                val reply = call(token, Request.Builder().url("$baseUrl/repos/$repo/contents/$path").put(body.toBody()))
                reply.obj["content"]?.jsonObject?.get("html_url")?.jsonPrimitive?.contentOrNull
            } catch (e: RequestSyncException) {
                // A retry after a half-finished send finds the file already there (422): reuse it.
                if (e.problem != SyncProblem.Other || !e.message.orEmpty().startsWith("HTTP 422")) throw e
                null
            } ?: "https://github.com/$repo/blob/HEAD/$path"
        }
        return urls
    }

    override suspend fun closedStates(): Map<Int, Boolean> {
        val (repo, token) = target()
        val reply = call(token, Request.Builder().url("$baseUrl/repos/$repo/issues?labels=$LABEL&state=all&per_page=100").get())
        val array = runCatching { json.parseToJsonElement(reply.text) as JsonArray }.getOrNull() ?: return emptyMap()
        return array.map { it.jsonObject }
            .filter { "pull_request" !in it }
            .mapNotNull { o ->
                val n = o["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                n to (o["state"]?.jsonPrimitive?.contentOrNull == "closed")
            }.toMap()
    }

    override suspend fun setClosed(issueNumber: Int, closed: Boolean) {
        val (repo, token) = target()
        val body = buildJsonObject { put("state", if (closed) "closed" else "open") }
        call(token, Request.Builder().url("$baseUrl/repos/$repo/issues/$issueNumber").patch(body.toBody()))
    }

    override suspend fun discard(issueNumber: Int) {
        val (repo, token) = target()
        val note = buildJsonObject { put("body", "Deleted in the app by the user. Closing as not planned.") }
        call(token, Request.Builder().url("$baseUrl/repos/$repo/issues/$issueNumber/comments").post(note.toBody()))
        val close = buildJsonObject {
            put("state", "closed")
            put("state_reason", "not_planned")
        }
        call(token, Request.Builder().url("$baseUrl/repos/$repo/issues/$issueNumber").patch(close.toBody()))
    }

    override suspend fun comments(issueNumber: Int): List<IssueComment> {
        val (repo, token) = target()
        val reply = call(token, Request.Builder().url("$baseUrl/repos/$repo/issues/$issueNumber/comments?per_page=50").get())
        val array = runCatching { json.parseToJsonElement(reply.text) as JsonArray }.getOrNull() ?: return emptyList()
        return array.map { it.jsonObject }.map { o ->
            IssueComment(
                author = o["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull.orEmpty(),
                body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    private suspend fun target(): Pair<String, String> {
        val token = settings.token() ?: throw RequestSyncException(SyncProblem.NoToken, "No token")
        return settings.repo() to token
    }

    private fun JsonObject.toBody() = toString().toRequestBody("application/json".toMediaType())

    private class Reply(val text: String, val obj: JsonObject)

    private suspend fun call(token: String, builder: Request.Builder): Reply = withContext(Dispatchers.IO) {
        val request = builder
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.isSuccessful -> Reply(text, runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap())))
                    response.code == 401 -> throw RequestSyncException(SyncProblem.BadToken, "HTTP 401")
                    response.code == 403 -> throw RequestSyncException(SyncProblem.BadToken, "HTTP 403")
                    response.code == 404 -> throw RequestSyncException(SyncProblem.NoRepo, "HTTP 404")
                    else -> throw RequestSyncException(SyncProblem.Other, "HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            throw RequestSyncException(SyncProblem.Offline, e.message ?: "offline")
        }
    }

    internal companion object {
        const val LABEL = "app-request"
        private val MANAGED = setOf("feature", "improvement", "bug", "priority:high", "priority:low")

        /** Labels the app owns (type, priority) on top of whatever [existing] labels were added on GitHub. */
        fun labelsFor(r: FeatureRequest, existing: List<String>): JsonArray = buildJsonArray {
            val mine = buildList {
                add(LABEL)
                add(r.kind.name.lowercase())
                when (r.priority) {
                    RequestPriority.High -> add("priority:high")
                    RequestPriority.Low -> add("priority:low")
                    RequestPriority.Normal -> Unit
                }
            }
            (existing.filter { it !in MANAGED && it != LABEL } + mine).distinct().forEach { add(JsonPrimitive(it)) }
        }

        fun issueBody(r: FeatureRequest, urls: Map<String, String>): String = buildString {
            if (r.details.isNotBlank()) appendLine(r.details).appendLine()
            val images = r.attachments.filter { it.isImage }
            val others = r.attachments.filterNot { it.isImage }
            images.forEachIndexed { i, a -> urls[a.file]?.let { appendLine("![${a.name.ifBlank { "screenshot ${i + 1}" }}]($it?raw=true)").appendLine() } }
            if (others.isNotEmpty()) {
                appendLine("**Files**")
                others.forEach { a -> urls[a.file]?.let { appendLine("- [${a.name}]($it?raw=true) (${size(a.size)})") } }
                appendLine()
            }
            appendLine("---")
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Type | ${r.kind.label.lowercase()} |")
            if (r.priority != RequestPriority.Normal) appendLine("| Priority | ${r.priority.name.lowercase()} |")
            appendLine("| Request | ${r.ref} |")
            if (r.app.isNotBlank()) appendLine("| App | ${r.app} |")
            if (r.device.isNotBlank()) appendLine("| Device | ${r.device} |")
        }

        private fun size(bytes: Long) = when {
            bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }
    }
}

/** Sends everything not sent yet, pushes edits, and pulls open/closed state back. Pure logic over the store and a sink. */
class RequestSync(private val store: FeatureRequestStore, private val sink: RequestSink) {
    /** Result of one pass: how many went out, and the first problem if it stopped early. */
    data class Result(val sent: Int, val problem: SyncProblem? = null)

    suspend fun run(): Result {
        var sent = 0
        try {
            for (r in store.requests.value.sortedBy { it.number }) {
                val pending = r.attachments.filter { it.url == null }
                    .mapNotNull { a -> store.file(a.file).takeIf(File::exists)?.let { a to it.readBytes() } }
                when {
                    r.issueNumber == null -> {
                        val issue = sink.submit(r, pending)
                        store.recordSent(r.number, issue.number, issue.url, issue.urls, r.updatedAt)
                        sent++
                    }
                    r.unsynced -> store.recordSynced(r.number, sink.update(r, pending), r.updatedAt)
                }
            }
            store.applyRemote(sink.closedStates())
        } catch (e: RequestSyncException) {
            return Result(sent, e.problem)
        }
        return Result(sent)
    }
}
