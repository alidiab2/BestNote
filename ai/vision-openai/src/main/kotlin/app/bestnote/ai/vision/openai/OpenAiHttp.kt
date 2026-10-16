package app.bestnote.ai.vision.openai

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
 * Cancellable JSON-over-HTTP transport. Headers (including the key) are only ever sent, never
 * logged; callers that add a logging interceptor must redact them.
 */
internal class OpenAiHttp(http: OkHttpClient, timeoutSeconds: Long) {
    class Result(val code: Int, val body: String, val retryAfter: String?)

    private val client = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()

    suspend fun post(url: HttpUrl, headers: Map<String, String>, json: String): Result =
        call(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(json.toRequestBody(JSON)).build())

    suspend fun get(url: HttpUrl, headers: Map<String, String>): Result =
        call(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())

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
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** [base] + [path], tolerating a missing or doubled slash in a user-typed base URL. */
        fun url(base: String, path: String): HttpUrl {
            val b = base.trim().let { if (it.endsWith('/')) it else "$it/" }
            return b.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).build()
        }
    }
}
