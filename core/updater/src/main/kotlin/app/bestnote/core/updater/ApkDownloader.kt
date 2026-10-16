package app.bestnote.core.updater

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface ApkDownloader {
    /** Downloads [release]'s APK into the app cache and returns it once its size checks out. */
    suspend fun download(release: ReleaseInfo, onProgress: (done: Long, total: Long) -> Unit): File
}

@Singleton
class OkHttpApkDownloader @Inject constructor(@ApplicationContext private val context: Context) : ApkDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        // Old downloads are useless once a newer one starts.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "bestnote-${release.versionCode}.apk")
        val part = File(dir, target.name + ".part")
        try {
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val total = release.apkSize.takeIf { it > 0 } ?: body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            currentCoroutineContext().ensureActive()
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (!SizeCheck.matches(release.apkSize, part.length())) {
                throw IOException("Size mismatch: expected ${release.apkSize}, got ${part.length()}")
            }
            check(part.renameTo(target)) { "Cannot move download into place" }
            target
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    private companion object {
        const val UPDATES_DIR = "updates"
        const val BUFFER = 64 * 1024
    }
}

object SizeCheck {
    /** An unknown (0) expected size cannot be verified; a known one must match exactly and be non-empty. */
    fun matches(expected: Long, actual: Long): Boolean = actual > 0 && (expected <= 0 || expected == actual)
}
