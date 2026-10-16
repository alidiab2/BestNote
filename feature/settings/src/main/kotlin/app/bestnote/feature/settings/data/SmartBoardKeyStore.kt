package app.bestnote.feature.settings.data

import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.providers.AiProviderDefaults
import app.bestnote.ai.vision.providers.AiProviders
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** What Settings may know about a provider's stored key: never the key itself, only whether it exists and its last 4 chars. */
data class SmartBoardKeyState(
    val hasKey: Boolean = false,
    val hint: String? = null,
    /** As chosen: an id, "" = automatic best, [ModelCatalog.AUTO_FAST] = automatic fast. */
    val model: String = "",
    /** Only for [AiProviderKind.OpenAiCompatible]. */
    val baseUrl: String = "",
    /** What [model] resolves to right now. */
    val resolvedModel: String = AiProviderDefaults.model(AiProviderKind.Gemini),
    /** The provider's live list as last fetched, picker order. */
    val models: List<AiModel> = emptyList(),
    val recents: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    /** Epoch millis of the last list fetch; 0 = never. */
    val modelsFetchedAt: Long = 0L,
) {
    /** What auto-best / auto-fast resolve to with the cached list. */
    fun autoModel(kind: AiProviderKind, preference: ModelCatalog.Preference): String =
        ModelCatalog.pick(models, preference)?.id ?: ModelCatalog.fallback(kind, preference)

    fun displayName(id: String): String = models.firstOrNull { it.id == id }?.displayName ?: id
}

/** Outcome of "Test connection". */
sealed interface KeyTestResult {
    data object Ok : KeyTestResult
    data object NoKey : KeyTestResult
    data object Invalid : KeyTestResult
    data object ModelUnavailable : KeyTestResult
    data object RateLimited : KeyTestResult
    data object Offline : KeyTestResult
    data object NoBaseUrl : KeyTestResult
    data object Failed : KeyTestResult

    companion object {
        fun from(error: Throwable): KeyTestResult = when (error) {
            is VisionError.MissingKey -> NoKey
            is VisionError.InvalidKey -> Invalid
            is VisionError.RateLimited -> RateLimited
            is VisionError.Network -> Offline
            is VisionError.ModelUnavailable -> ModelUnavailable
            is VisionError.BadResponse -> when {
                "not available" in (error.message ?: "") -> ModelUnavailable
                "base URL" in (error.message ?: "") -> NoBaseUrl
                else -> Failed
            }
            else -> Failed
        }
    }
}

/**
 * Settings' view of the Smart Board providers. The plain key only passes through [saveKey] and
 * [test]; it is never returned, logged or kept in UI state after saving.
 */
interface SmartBoardKeyStore {
    suspend fun selected(): AiProviderKind
    suspend fun select(kind: AiProviderKind)
    suspend fun load(kind: AiProviderKind): SmartBoardKeyState
    suspend fun saveKey(kind: AiProviderKind, key: String?)
    suspend fun saveModel(kind: AiProviderKind, model: String)
    suspend fun saveBaseUrl(kind: AiProviderKind, url: String)

    /** Fetches the live model list (with [draftKey] or the stored key) into the cache; [KeyTestResult.Ok] on success. */
    suspend fun refreshModels(kind: AiProviderKind, draftKey: String?, baseUrl: String?): KeyTestResult

    suspend fun toggleFavorite(kind: AiProviderKind, id: String)

    /** Tests [draftKey] when given, else the stored key, against [model] (and [baseUrl] for a compatible server). */
    suspend fun test(kind: AiProviderKind, draftKey: String?, model: String, baseUrl: String?): KeyTestResult
}

/** Backed by [AiProviders]: Keystore-encrypted keys per provider and each provider's own checker. */
class ProvidersKeyStore @Inject constructor(private val providers: AiProviders) : SmartBoardKeyStore {
    private val settings get() = providers.settings

    override suspend fun selected() = providers.selected

    override suspend fun select(kind: AiProviderKind) = settings.setProvider(kind)

    override suspend fun load(kind: AiProviderKind) = SmartBoardKeyState(
        hasKey = settings.hasKey(kind),
        hint = settings.keyHint(kind),
        model = settings.storedModel(kind),
        baseUrl = settings.baseUrl(kind).orEmpty(),
        resolvedModel = settings.model(kind),
        models = ModelCatalog.sorted(settings.cachedModels(kind)),
        recents = settings.recentModels(kind),
        favorites = settings.favoriteModels(kind),
        modelsFetchedAt = settings.modelsFetchedAt(kind),
    )

    override suspend fun refreshModels(kind: AiProviderKind, draftKey: String?, baseUrl: String?): KeyTestResult = try {
        providers.refreshModels(kind, draftKey, baseUrl)
        KeyTestResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        KeyTestResult.from(e)
    }

    override suspend fun toggleFavorite(kind: AiProviderKind, id: String) = settings.toggleFavorite(kind, id)

    override suspend fun saveKey(kind: AiProviderKind, key: String?) = settings.setApiKey(kind, key)

    override suspend fun saveModel(kind: AiProviderKind, model: String) = settings.setModel(kind, model)

    override suspend fun saveBaseUrl(kind: AiProviderKind, url: String) = settings.setBaseUrl(kind, url)

    override suspend fun test(kind: AiProviderKind, draftKey: String?, model: String, baseUrl: String?): KeyTestResult = try {
        providers.test(kind, draftKey, model, baseUrl)
        KeyTestResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        KeyTestResult.from(e)
    }
}
