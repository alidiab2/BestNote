package app.bestnote.core.fonts

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches bytes. Replaced in tests. */
fun interface FontFetcher {
    @Throws(IOException::class)
    fun fetch(url: String, maxBytes: Int): ByteArray
}

/**
 * Downloads only from the trusted `google/fonts` repository over HTTPS (raw files under `ofl/`), with a size
 * cap, no redirects off that host, and nothing sent but the request itself (no key, no identifiers).
 */
object TrustedFontFetcher : FontFetcher {
    override fun fetch(url: String, maxBytes: Int): ByteArray {
        val u = URL(url)
        if (u.protocol != "https" || u.host != CatalogueFont.HOST || !u.path.startsWith("/google/fonts/")) throw IOException("untrusted font source")
        val c = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("font larger than $maxBytes bytes")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }
}

/** Installs a catalogue font: download the file and its OFL text, validate, store. */
class FontInstaller(private val library: FontLibrary, private val fetcher: FontFetcher = TrustedFontFetcher) {
    @Throws(IOException::class, FontRejected::class)
    fun install(font: CatalogueFont): AppFont {
        val bytes = fetcher.fetch(font.fontUrl, FontCatalogue.limitFor(font))
        val license = runCatching { String(fetcher.fetch(font.licenseUrl, 64 * 1024), Charsets.UTF_8) }.getOrNull()
        return library.installDownloaded(font, bytes, license)
    }
}
