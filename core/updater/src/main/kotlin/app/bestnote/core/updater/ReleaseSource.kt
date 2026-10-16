package app.bestnote.core.updater

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
class GitHubReleaseSource @Inject constructor() : ReleaseSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): List<ReleaseInfo> = fetchFrom(RELEASES_URL, client)

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/alidiab2/BestNote-releases/releases?per_page=30"

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
