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
    /** Uploads the screenshots, opens an issue and returns its number and URL. */
    suspend fun submit(request: FeatureRequest, images: List<Pair<String, ByteArray>>): SentIssue

    /** Issue number to "is closed" for every issue the app has opened. */
    suspend fun closedStates(): Map<Int, Boolean>

    suspend fun setClosed(issueNumber: Int, closed: Boolean)

    /** Throws [RequestSyncException] when the token or repo can't be used. */
    suspend fun check()
}

data class SentIssue(val number: Int, val url: String)

/** GitHub REST: screenshots are committed under `attachments/`, the issue embeds them. Needs Issues + Contents write on one repo. */
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

    override suspend fun submit(request: FeatureRequest, images: List<Pair<String, ByteArray>>): SentIssue {
        val (repo, token) = target()
        val embedded = images.mapIndexed { i, (_, bytes) ->
            val path = "attachments/${request.createdAt}-${request.ref}/${i + 1}.jpg"
            val body = buildJsonObject {
                put("message", "Screenshot ${i + 1} for ${request.ref}")
                put("content", Base64.getEncoder().encodeToString(bytes))
            }
            val url = try {
                val reply = call(token, Request.Builder().url("$baseUrl/repos/$repo/contents/$path").put(body.toBody()))
                reply.obj["content"]?.jsonObject?.get("html_url")?.jsonPrimitive?.contentOrNull
            } catch (e: RequestSyncException) {
                // A retry after a half-finished send finds the file already there (422): reuse it.
                if (e.problem != SyncProblem.Other || !e.message.orEmpty().startsWith("HTTP 422")) throw e
                null
            } ?: "https://github.com/$repo/blob/HEAD/$path"
            "![screenshot ${i + 1}]($url?raw=true)"
        }
        val body = buildJsonObject {
            put("title", "[${request.ref}] ${request.title}")
            put("body", issueBody(request, embedded))
            put("labels", buildJsonArray { add(JsonPrimitive(LABEL)); add(JsonPrimitive(request.kind.name.lowercase())) })
        }
        val reply = call(token, Request.Builder().url("$baseUrl/repos/$repo/issues").post(body.toBody()))
        val number = reply.obj["number"]?.jsonPrimitive?.intOrNull
        val url = reply.obj["html_url"]?.jsonPrimitive?.contentOrNull
        if (number == null || url == null) throw RequestSyncException(SyncProblem.Other, "Unexpected reply")
        return SentIssue(number, url)
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

        fun issueBody(r: FeatureRequest, screenshots: List<String>): String = buildString {
            if (r.details.isNotBlank()) appendLine(r.details).appendLine()
            if (screenshots.isNotEmpty()) {
                screenshots.forEach { appendLine(it).appendLine() }
            }
            appendLine("---")
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Type | ${r.kind.label.lowercase()} |")
            appendLine("| Request | ${r.ref} |")
            if (r.app.isNotBlank()) appendLine("| App | ${r.app} |")
            if (r.device.isNotBlank()) appendLine("| Device | ${r.device} |")
        }
    }
}

/** Sends everything not sent yet and pulls open/closed state back. Pure logic over the store and a sink. */
class RequestSync(private val store: FeatureRequestStore, private val sink: RequestSink) {
    /** Result of one pass: how many went out, and the first problem if it stopped early. */
    data class Result(val sent: Int, val problem: SyncProblem? = null)

    suspend fun run(): Result {
        var sent = 0
        try {
            for (r in store.requests.value.filter { it.issueNumber == null }.sortedBy { it.number }) {
                val images = r.images.mapNotNull { name -> store.imageFile(name).takeIf(File::exists)?.let { name to it.readBytes() } }
                val issue = sink.submit(r, images)
                store.markSent(r.number, issue.number, issue.url)
                sent++
            }
            store.applyRemote(sink.closedStates())
        } catch (e: RequestSyncException) {
            return Result(sent, e.problem)
        }
        return Result(sent)
    }
}
