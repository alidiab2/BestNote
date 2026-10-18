package app.bestnote.ai.vision.providers.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** One HTTP exchange, so the OAuth logic can be tested with fakes. */
fun interface AuthHttp {
    /** [body] null = GET. Throws [IOException] when offline. */
    suspend fun send(url: String, headers: Map<String, String>, body: String?): AuthReply
}

data class AuthReply(val code: Int, val body: String)

/** [AuthHttp] over OkHttp. */
class OkAuthHttp(private val client: OkHttpClient) : AuthHttp {
    override suspend fun send(url: String, headers: Map<String, String>, body: String?): AuthReply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) post(body.toRequestBody("application/json".toMediaType()))
        }.build()
        client.newCall(request).execute().use { AuthReply(it.code, it.body.string()) }
    }
}

/** Why a sign-in or status call failed. */
sealed class AccountError(message: String) : Exception(message) {
    /** The user closed the page, denied access, or took too long. */
    class Cancelled(why: String = "cancelled") : AccountError(why)

    /** The callback's `state` did not match ours (CSRF or a stale tab). */
    class StateMismatch : AccountError("state mismatch")

    /** OpenRouter refused the code or verifier (expired, reused, wrong verifier). */
    class Rejected(val status: Int, detail: String) : AccountError("rejected $status: $detail")

    /** The key no longer works (deleted or revoked on openrouter.ai). */
    class KeyRevoked : AccountError("key revoked")

    class Offline(cause: IOException) : AccountError("offline: ${cause.message}")

    class BadResponse(detail: String) : AccountError("bad response: $detail")
}

/** What OpenRouter's `GET /api/v1/key` says about the signed-in key. Amounts are US dollars (credits). */
data class AccountStatus(
    val label: String? = null,
    /** The key's own spending cap; null = no cap (spends the account's credits). */
    val limit: Double? = null,
    /** What is left under [limit]; null when uncapped. */
    val limitRemaining: Double? = null,
    val usage: Double = 0.0,
    val usageMonthly: Double? = null,
    /** True while the account has never bought credits (free models only, daily request cap). */
    val freeTier: Boolean = false,
)

/** Outcome of reading the redirect OpenRouter sends the browser to. */
sealed interface CallbackResult {
    data class Code(val code: String) : CallbackResult
    data class Error(val reason: String) : CallbackResult
    data object StateMismatch : CallbackResult

    /** Not the callback (e.g. `/favicon.ico`): keep waiting. */
    data object NotCallback : CallbackResult
}

/**
 * "Sign in with OpenRouter": OAuth PKCE that ends in a user-controlled API key
 * (https://openrouter.ai/docs/guides/overview/auth/oauth). There is no client id or secret: the
 * app is identified by its callback URL and PKCE proves the exchange comes from the app that
 * started it. The key is then used exactly like a pasted one.
 */
class OpenRouterAuth(
    private val http: AuthHttp,
    private val siteUrl: String = SITE,
    private val apiUrl: String = API,
) {
    /** The page to open in a Custom Tab. [state] comes back unchanged on the callback. */
    fun authUrl(callbackUrl: String, pkce: PkcePair, state: String, keyLabel: String? = KEY_LABEL): String = buildString {
        append(siteUrl.trimEnd('/')).append("/auth")
        append("?callback_url=").append(enc(callbackUrl))
        append("&code_challenge=").append(enc(pkce.challenge))
        append("&code_challenge_method=").append(Pkce.METHOD)
        append("&state=").append(enc(state))
        if (!keyLabel.isNullOrBlank()) append("&key_label=").append(enc(keyLabel))
    }

    /**
     * Reads a redirect target such as `/callback?code=…&state=…` (or a full URL) and checks it
     * belongs to this sign-in. [path] is the callback path we registered.
     */
    fun parseCallback(target: String, expectedState: String, path: String = CALLBACK_PATH): CallbackResult {
        val uri = runCatching { URI(target.trim()) }.getOrNull() ?: return CallbackResult.NotCallback
        if ((uri.rawPath ?: "").trimEnd('/') != path.trimEnd('/')) return CallbackResult.NotCallback
        val query = query(uri.rawQuery)
        query["error"]?.let { return CallbackResult.Error(query["error_description"] ?: it) }
        if (query["state"] != expectedState) return CallbackResult.StateMismatch
        val code = query["code"]?.takeIf { it.isNotBlank() } ?: return CallbackResult.Error("no code")
        return CallbackResult.Code(code)
    }

    /** Trades the one-time [code] (valid 10 minutes) for the user's key. Throws [AccountError]. */
    suspend fun exchange(code: String, verifier: String): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", Pkce.METHOD)
        }.toString()
        val reply = call("${apiUrl.trimEnd('/')}/auth/keys", emptyMap(), body)
        if (reply.code !in 200..299) throw AccountError.Rejected(reply.code, errorMessage(reply.body))
        return parseKey(reply.body)
    }

    /** The key's label, cap and usage. Throws [AccountError.KeyRevoked] when the key was deleted. */
    suspend fun status(key: String): AccountStatus {
        val reply = call("${apiUrl.trimEnd('/')}/key", mapOf("Authorization" to "Bearer $key"), null)
        if (reply.code == 401 || reply.code == 403) throw AccountError.KeyRevoked()
        if (reply.code !in 200..299) throw AccountError.Rejected(reply.code, errorMessage(reply.body))
        return parseStatus(reply.body)
    }

    private suspend fun call(url: String, headers: Map<String, String>, body: String?): AuthReply = try {
        http.send(url, headers, body)
    } catch (e: IOException) {
        throw AccountError.Offline(e)
    }

    companion object {
        const val SITE = "https://openrouter.ai"
        const val API = "https://openrouter.ai/api/v1"
        const val CALLBACK_PATH = "/callback"
        const val KEY_LABEL = "BestNote"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `{"key":"sk-or-…"}` → the key. */
        fun parseKey(body: String): String {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw AccountError.BadResponse("not JSON")
            return root.str("key")?.trim()?.takeIf { it.isNotEmpty() } ?: throw AccountError.BadResponse("no key")
        }

        /** `{"data":{"label","limit","limit_remaining","usage","usage_monthly","is_free_tier"}}`. */
        fun parseStatus(body: String): AccountStatus {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw AccountError.BadResponse("not JSON")
            val data = root["data"] as? JsonObject ?: root
            return AccountStatus(
                label = data.str("label"),
                limit = data.num("limit"),
                limitRemaining = data.num("limit_remaining"),
                usage = data.num("usage") ?: 0.0,
                usageMonthly = data.num("usage_monthly"),
                freeTier = (data["is_free_tier"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        /** Lowercase hex SHA-256 of the key: OpenRouter's link to manage it (`/keys/<hash>`), shown only to its owner. */
        fun keyHash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun errorMessage(body: String): String = runCatching {
            val root = json.parseToJsonElement(body) as JsonObject
            ((root["error"] as? JsonObject)?.str("message")) ?: root.str("error") ?: root.str("message")
        }.getOrNull() ?: body.take(200)

        private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.num(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
