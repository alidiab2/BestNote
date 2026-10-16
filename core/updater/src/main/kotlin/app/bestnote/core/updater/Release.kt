package app.bestnote.core.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the running build knows about itself. */
data class BuildInfo(val versionName: String, val versionCode: Long, val commit: String)

/** A published, installable BestNote build. */
data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val prerelease: Boolean,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
)

@Serializable
internal data class GhAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
internal data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

/**
 * Parses the GitHub releases API (`GET /repos/{owner}/{repo}/releases`).
 *
 * Convention (docs/release.md): the release body has a line `versionCode: N`. Without it the
 * tag is read: `v0.1.N` or `v0.1.N-sha` means N. Releases without an `.apk` asset, drafts, and
 * releases with no readable versionCode are ignored. The newest is the highest versionCode.
 */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val bodyCode = Regex("""(?im)^\s*[*_`>-]*\s*version[ _-]?code\s*[:=][\s`*_]*(\d{1,12})""")
    private val tagCode = Regex("""^v?\d+\.\d+\.(\d+)(?:[-+].*)?$""")

    fun parse(text: String): List<ReleaseInfo> {
        val raw = runCatching { json.decodeFromString<List<GhRelease>>(text) }.getOrElse { return emptyList() }
        return raw.mapNotNull(::toRelease)
    }

    fun newest(releases: List<ReleaseInfo>): ReleaseInfo? = releases.maxByOrNull { it.versionCode }

    fun versionCodeOf(tag: String, body: String?): Long? =
        body?.let { bodyCode.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: tagCode.find(tag.trim())?.groupValues?.get(1)?.toLongOrNull()

    private fun toRelease(r: GhRelease): ReleaseInfo? {
        if (r.draft) return null
        val apk = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && it.url.startsWith("https://") } ?: return null
        val code = versionCodeOf(r.tag, r.body) ?: return null
        val name = r.tag.trim().removePrefix("v").ifEmpty { code.toString() }
        return ReleaseInfo(
            tag = r.tag,
            title = r.name?.takeIf { it.isNotBlank() } ?: r.tag,
            notes = cleanNotes(r.body.orEmpty()),
            prerelease = r.prerelease,
            versionCode = code,
            versionName = name,
            apkUrl = apk.url,
            apkSize = apk.size,
        )
    }

    /** Drops the machine line `versionCode: N` from what the user reads. */
    private fun cleanNotes(body: String): String =
        body.replace("\r\n", "\n").lines().filterNot { bodyCode.containsMatchIn(it) }.joinToString("\n").trim()
}
