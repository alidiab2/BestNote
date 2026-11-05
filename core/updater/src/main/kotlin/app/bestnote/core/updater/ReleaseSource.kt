package app.bestnote.core.updater

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where releases come from. */
fun interface ReleaseSource {
    /** All published releases, prereleases included. Throws on network or HTTP errors. */
    suspend fun fetch(): List<ReleaseInfo>
}

/**
 * The public APK-only repo. The source repo is private; nothing here needs a token.
 * Unauthenticated GitHub API calls are limited to 60/h per IP, far above one check a day.
 */
@Singleton
class GitHubReleaseSource @Inject constructor(@ApplicationContext private val context: Context) : ReleaseSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): List<ReleaseInfo> = fetchFrom(releasesUrl(), client)

    /**
     * Debuggable builds only: `files/releases-url-override.txt` points the check at another server
     * (the emulator's host, `http://10.0.2.2:PORT/...`) so the whole flow can be tested without publishing.
     */
    private fun releasesUrl(): String {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return RELEASES_URL
        return runCatching { java.io.File(context.filesDir, "releases-url-override.txt").readText().trim() }
            .getOrNull()?.takeIf { it.startsWith("http") } ?: RELEASES_URL
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/alidiab2/BestNote/releases?per_page=30"

        internal suspend fun fetchFrom(url: String, client: OkHttpClient): List<ReleaseInfo> = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                ReleaseParser.parse(response.body.string())
            }
        }
    }
}
