package app.bestnote.feature.settings

import androidx.datastore.core.DataStore
import app.bestnote.feature.settings.data.AppLanguage
import app.bestnote.feature.settings.data.AppLocaleController
import app.bestnote.feature.settings.data.KeyTestResult
import app.bestnote.feature.settings.data.SmartBoardKeyState
import app.bestnote.feature.settings.data.SmartBoardKeyStore
import app.bestnote.feature.settings.data.StorageInspector
import app.bestnote.feature.settings.data.StorageUsage
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.ModelCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<T> = state
    val value: T get() = state.value
    override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
}

class FakeLocale(override val supported: Boolean = true) : AppLocaleController {
    var language = AppLanguage.System
    override fun current() = language
    override fun set(language: AppLanguage) {
        this.language = language
    }
}

class FakeStorage : StorageInspector {
    var usage = StorageUsage(notebooksBytes = 1_000, thumbnailsBytes = 200, cacheBytes = 50, notebooksPath = "/data/notebooks")
    var cleared = 0
    override suspend fun usage() = usage
    override suspend fun clearThumbnails() {
        cleared++
        usage = usage.copy(thumbnailsBytes = 0, cacheBytes = 0)
    }
}

/** Records keys only to assert on them; a real store never exposes the key. */
/** In-memory [SmartBoardKeyStore]; [key]/[model] are Gemini's, other providers live in [states]. */
class FakeKeyStore(var key: String? = null, var model: String = "") : SmartBoardKeyStore {
    val tested = mutableListOf<Pair<String?, String>>()
    val testedBaseUrls = mutableListOf<String?>()
    var nextResult: KeyTestResult = KeyTestResult.Ok
    var listResult: KeyTestResult = KeyTestResult.Ok
    var gate: CompletableDeferred<Unit>? = null
    var selected = AiProviderKind.Gemini
    val states = mutableMapOf<AiProviderKind, SmartBoardKeyState>()
    private val keys = mutableMapOf<AiProviderKind, String?>()
    var listed: List<AiModel> = emptyList()
    val listCalls = mutableListOf<AiProviderKind>()

    private fun state(kind: AiProviderKind): SmartBoardKeyState {
        val base = states[kind] ?: SmartBoardKeyState()
        val k = keyOf(kind)
        val m = if (kind == AiProviderKind.Gemini) model else base.model
        return base.copy(hasKey = k != null, hint = k?.takeLast(4)?.let { "…$it" }, model = m, resolvedModel = ModelCatalog.resolve(kind, m, base.models))
    }

    fun keyOf(kind: AiProviderKind) = if (kind == AiProviderKind.Gemini) key else keys[kind]

    override suspend fun selected() = selected
    override suspend fun select(kind: AiProviderKind) {
        selected = kind
    }

    override suspend fun load(kind: AiProviderKind) = state(kind)

    override suspend fun saveKey(kind: AiProviderKind, key: String?) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        if (kind == AiProviderKind.Gemini) this.key = k else keys[kind] = k
    }

    override suspend fun saveModel(kind: AiProviderKind, model: String) {
        if (kind == AiProviderKind.Gemini) this.model = model
        val s = states[kind] ?: SmartBoardKeyState()
        val recents = if (ModelCatalog.isAuto(model)) s.recents else (listOf(model) + s.recents).distinct()
        states[kind] = s.copy(model = model, recents = recents)
    }

    override suspend fun saveBaseUrl(kind: AiProviderKind, url: String) {
        states[kind] = (states[kind] ?: SmartBoardKeyState()).copy(baseUrl = url)
    }

    override suspend fun refreshModels(kind: AiProviderKind, draftKey: String?, baseUrl: String?): KeyTestResult {
        listCalls += kind
        if (listResult == KeyTestResult.Ok) states[kind] = (states[kind] ?: SmartBoardKeyState()).copy(models = ModelCatalog.sorted(listed), modelsFetchedAt = 1L)
        return listResult
    }

    override suspend fun toggleFavorite(kind: AiProviderKind, id: String) {
        val s = states[kind] ?: SmartBoardKeyState()
        states[kind] = s.copy(favorites = if (id in s.favorites) s.favorites - id else s.favorites + id)
    }

    override suspend fun test(kind: AiProviderKind, draftKey: String?, model: String, baseUrl: String?): KeyTestResult {
        tested += draftKey to model
        testedBaseUrls += baseUrl
        gate?.await()
        return if (draftKey == null && keyOf(kind) == null && kind != AiProviderKind.OpenAiCompatible) KeyTestResult.NoKey else nextResult
    }
}

object FakeSharer : app.bestnote.feature.settings.data.FeatureRequestSharer {
    override suspend fun export(only: Int?): android.net.Uri = error("not used")
    override fun uriFor(attachment: app.bestnote.feature.settings.data.Attachment): android.net.Uri = error("not used")
}

/** Reads every uri as a one-byte photo unless told otherwise. */
class FakeReader : app.bestnote.feature.settings.data.AttachmentReader {
    var result: app.bestnote.feature.settings.data.ReadResult =
        app.bestnote.feature.settings.data.ReadResult.Ok(app.bestnote.feature.settings.data.PendingAttachment("p.jpg", "image/jpeg", byteArrayOf(1)))

    override suspend fun read(uri: android.net.Uri) = result
}

class FakeGitHubSettings(private var token: String? = null, private var repo: String = "me/requests") :
    app.bestnote.feature.settings.data.GitHubRequestSettings {
    override suspend fun repo() = repo
    override suspend fun token() = token
    override val tokenHint: String? get() = token?.let { "…" + it.takeLast(4) }
    override suspend fun save(token: String, repo: String) {
        this.token = token
        this.repo = repo
    }
    override suspend fun clear() {
        token = null
    }
}

/** Pretends to be GitHub: numbers issues, remembers what was sent and which issues are closed. */
class FakeSink : app.bestnote.feature.settings.data.RequestSink {
    val submitted = mutableListOf<Pair<app.bestnote.feature.settings.data.FeatureRequest, List<String>>>()
    val updated = mutableListOf<Pair<app.bestnote.feature.settings.data.FeatureRequest, List<String>>>()
    val discarded = mutableListOf<Int>()
    val closed = mutableMapOf<Int, Boolean>()
    val replies = mutableMapOf<Int, List<app.bestnote.feature.settings.data.IssueComment>>()
    var failWith: app.bestnote.feature.settings.data.SyncProblem? = null

    private fun maybeFail() {
        failWith?.let { throw app.bestnote.feature.settings.data.RequestSyncException(it, "fake") }
    }

    override suspend fun submit(
        request: app.bestnote.feature.settings.data.FeatureRequest,
        files: List<Pair<app.bestnote.feature.settings.data.Attachment, ByteArray>>,
    ): app.bestnote.feature.settings.data.SentIssue {
        maybeFail()
        submitted += request to files.map { it.first.file }
        val n = submitted.size
        closed[n] = false
        return app.bestnote.feature.settings.data.SentIssue(n, "https://github.com/me/requests/issues/$n", files.associate { it.first.file to "https://x/${it.first.file}" })
    }

    override suspend fun update(
        request: app.bestnote.feature.settings.data.FeatureRequest,
        files: List<Pair<app.bestnote.feature.settings.data.Attachment, ByteArray>>,
    ): Map<String, String> {
        maybeFail()
        updated += request to files.map { it.first.file }
        return files.associate { it.first.file to "https://x/${it.first.file}" }
    }

    override suspend fun closedStates(): Map<Int, Boolean> = run { maybeFail(); closed.toMap() }
    override suspend fun setClosed(issueNumber: Int, closed: Boolean) {
        maybeFail()
        this.closed[issueNumber] = closed
    }
    override suspend fun discard(issueNumber: Int) {
        maybeFail()
        discarded += issueNumber
        closed[issueNumber] = true
    }
    override suspend fun comments(issueNumber: Int) = run { maybeFail(); replies[issueNumber].orEmpty() }
    override suspend fun check() = maybeFail()
}
