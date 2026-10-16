package app.bestnote.ai.vision.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP transport for the Gemini REST API: key header, timeouts, cancellable calls.
 * The key is sent only in the [KEY_HEADER] header, never in the URL (URLs end up in logs).
 * Callers that add an HTTP logging interceptor must redact [KEY_HEADER].
 */
class GeminiHttp(http: OkHttpClient, private val config: GeminiConfig) {
    class Result(val code: Int, val body: String, val retryAfter: String?)

    private val client = http.newBuilder().callTimeout(config.timeoutSeconds, TimeUnit.SECONDS).build()

    suspend fun post(path: String, key: String, json: String): Result =
        call(Request.Builder().url(url(path)).header(KEY_HEADER, key).post(json.toRequestBody(JSON)).build())

    suspend fun get(path: String, key: String, query: Map<String, String> = emptyMap()): Result =
        call(Request.Builder().url(url(path, query)).header(KEY_HEADER, key).get().build())

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        config.baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend fun call(request: Request): Result = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { Result(it.code, it.body.string(), it.header("Retry-After")) } }
                        result.fold({ cont.resume(it) }, { if (cont.isActive) cont.resumeWithException(it) })
                    }
                },
            )
        }
    }

    companion object {
        const val KEY_HEADER = "x-goog-api-key"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
