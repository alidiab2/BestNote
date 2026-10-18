package app.bestnote.ai.vision.providers.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.SocketException

/**
 * Receives the OAuth redirect on `http://localhost:<port>/callback` (RFC 8252 §7.3 loopback
 * redirect; OpenRouter accepts localhost callbacks on any port). Listens on 127.0.0.1 only, so
 * nothing off the device can reach it; PKCE makes an intercepted code useless anyway.
 *
 * Pure JVM: tests drive it with a plain socket.
 */
class LoopbackReceiver private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    /** The URL to give the provider as `callback_url`. */
    fun callbackUrl(path: String = OpenRouterAuth.CALLBACK_PATH) = "http://localhost:$port$path"

    /**
     * Waits for browser requests until [accept] returns a non-null answer for one of them (other
     * requests, like `/favicon.ico`, get a 404). [accept] receives the request target (path +
     * query) and returns the result plus the HTML page the browser shows. Null on timeout.
     */
    suspend fun <T : Any> await(timeoutMs: Long, accept: (target: String) -> Pair<T, String>?): T? = withTimeoutOrNull(timeoutMs) {
        try {
            runInterruptible(Dispatchers.IO) {
                server.soTimeout = POLL_MS // accept() ignores interrupts; poll so a timeout or cancel ends the wait
                var result: T? = null
                while (result == null) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketTimeoutException) {
                        if (Thread.interrupted()) throw InterruptedException()
                        continue
                    }
                    socket.use { s -> result = answer(s, accept) }
                }
                result
            }
        } catch (e: SocketException) {
            null
        } finally {
            close()
        }
    }

    private fun <T : Any> answer(s: Socket, accept: (String) -> Pair<T, String>?): T? {
        s.soTimeout = READ_TIMEOUT_MS
        val target = runCatching { readTarget(s.getInputStream()) }.getOrNull()
        val answer = target?.let(accept)
        val (status, html) = if (answer != null) "200 OK" to answer.second else "404 Not Found" to NOT_FOUND
        val bytes = html.toByteArray(Charsets.UTF_8)
        runCatching {
            s.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                write(bytes)
                flush()
            }
        }
        return answer?.first
    }

    override fun close() = runCatching { server.close() }.let { }

    companion object {
        /** Opens a listener on a free port of 127.0.0.1. Throws [IOException] when that fails. */
        fun open(): LoopbackReceiver = LoopbackReceiver(ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1")))

        /** `GET /callback?code=… HTTP/1.1` → `/callback?code=…`. */
        internal fun readTarget(input: java.io.InputStream): String? {
            val parts = readLine(input).split(' ')
            // Read the headers too: closing with unread bytes makes TCP reset the connection and the browser shows an error.
            var headers = 0
            while (headers++ < MAX_HEADERS && readLine(input).isNotEmpty()) Unit
            return if (parts.size >= 2 && parts[0] == "GET") parts[1] else null
        }

        private fun readLine(input: java.io.InputStream): String {
            val line = StringBuilder()
            while (line.length < MAX_LINE) {
                val c = input.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            return line.toString()
        }

        private const val BACKLOG = 4
        private const val POLL_MS = 300
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_LINE = 8_192
        private const val MAX_HEADERS = 100
        private const val NOT_FOUND = "<!doctype html><title>BestNote</title>"
    }
}
