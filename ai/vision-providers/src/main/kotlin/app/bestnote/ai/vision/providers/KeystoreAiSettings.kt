package app.bestnote.ai.vision.providers

import android.content.Context
import android.content.SharedPreferences
import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.ai.vision.ProviderCredentials
import app.bestnote.ai.vision.gemini.KeystoreCipher
import app.bestnote.ai.vision.gemini.KeystoreGeminiSettings
import app.bestnote.ai.vision.gemini.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Settings for every Smart Board provider. Keys are stored only as AES-GCM ciphertext with a
 * non-exportable Android Keystore key ([KeystoreCipher]), like [KeystoreGeminiSettings], which
 * still holds the Gemini key so keys saved before multi-provider support keep working. Only the
 * last 4 characters of a key are kept in clear, as a UI hint.
 */
class KeystoreAiSettings(
    private val prefs: SharedPreferences,
    val gemini: KeystoreGeminiSettings,
    private val cipher: SecretCipher = KeystoreCipher(),
) {
    constructor(context: Context, gemini: KeystoreGeminiSettings = KeystoreGeminiSettings(context)) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), gemini)

    val provider: AiProviderKind get() = AiProviderKind.of(prefs.getString(KEY_PROVIDER, null))

    suspend fun setProvider(kind: AiProviderKind) = edit { putString(KEY_PROVIDER, kind.id) }

    fun hasKey(kind: AiProviderKind): Boolean =
        if (kind == AiProviderKind.Gemini) gemini.hasApiKey else prefs.contains(k(kind, CIPHERTEXT))

    fun keyHint(kind: AiProviderKind): String? =
        if (kind == AiProviderKind.Gemini) gemini.apiKeyHint else prefs.getString(k(kind, HINT), null)?.let { "…$it" }

    suspend fun apiKey(kind: AiProviderKind): String? =
        if (kind == AiProviderKind.Gemini) gemini.apiKey() else withContext(Dispatchers.IO) { prefs.getString(k(kind, CIPHERTEXT), null)?.let(cipher::decrypt) }

    /** Saves (or with null/blank, removes) the key for [kind]. */
    suspend fun setApiKey(kind: AiProviderKind, key: String?) {
        if (kind == AiProviderKind.Gemini) return gemini.setApiKey(key)
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
        edit {
            if (trimmed == null) {
                remove(k(kind, CIPHERTEXT))
                remove(k(kind, HINT))
            } else {
                putString(k(kind, CIPHERTEXT), cipher.encrypt(trimmed))
                putString(k(kind, HINT), trimmed.takeLast(4))
            }
        }
    }

    /**
     * The model as the user chose it: an id, "" for automatic (newest pro-class model from the live
     * list) or [ModelCatalog.AUTO_FAST].
     */
    suspend fun storedModel(kind: AiProviderKind): String =
        if (kind == AiProviderKind.Gemini) gemini.storedModel()
        else withContext(Dispatchers.IO) { prefs.getString(k(kind, MODEL), null)?.trim().orEmpty() }

    /** The model id to call: [storedModel] resolved against the cached live list, else the newest-known fallback. */
    suspend fun model(kind: AiProviderKind): String = ModelCatalog.resolve(kind, storedModel(kind), cachedModels(kind))

    /** Blank = automatic. A concrete id is also added to the recent models. */
    suspend fun setModel(kind: AiProviderKind, model: String) {
        val m = model.trim()
        if (kind == AiProviderKind.Gemini) gemini.setModel(m) else edit { putString(k(kind, MODEL), m) }
        if (!ModelCatalog.isAuto(m)) addRecent(kind, m)
    }

    /** The provider's model list as last fetched (empty before the first fetch). */
    suspend fun cachedModels(kind: AiProviderKind): List<AiModel> = withContext(Dispatchers.IO) {
        prefs.getString(k(kind, MODELS), null)?.let { runCatching { json.decodeFromString(modelList, it) }.getOrNull() }.orEmpty()
    }

    /** When [cachedModels] was fetched, epoch millis; 0 = never. */
    fun modelsFetchedAt(kind: AiProviderKind): Long = prefs.getLong(k(kind, MODELS_AT), 0L)

    suspend fun setCachedModels(kind: AiProviderKind, models: List<AiModel>, now: Long = System.currentTimeMillis()) = edit {
        putString(k(kind, MODELS), json.encodeToString(modelList, models))
        putLong(k(kind, MODELS_AT), now)
    }

    /** Last [RECENT_MAX] concrete models used, newest first. */
    fun recentModels(kind: AiProviderKind): List<String> = list(k(kind, RECENT))

    fun favoriteModels(kind: AiProviderKind): List<String> = list(k(kind, FAVORITES))

    suspend fun toggleFavorite(kind: AiProviderKind, id: String) {
        val now = favoriteModels(kind)
        edit { putString(k(kind, FAVORITES), (if (id in now) now - id else now + id).joinToString("\n")) }
    }

    private suspend fun addRecent(kind: AiProviderKind, id: String) {
        val list = (listOf(id) + recentModels(kind).filter { it != id }).take(RECENT_MAX)
        edit { putString(k(kind, RECENT), list.joinToString("\n")) }
    }

    private fun list(key: String) = prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** Only meaningful for [AiProviderKind.OpenAiCompatible]. */
    suspend fun baseUrl(kind: AiProviderKind): String? =
        withContext(Dispatchers.IO) { prefs.getString(k(kind, BASE_URL), null)?.takeIf { it.isNotBlank() } }

    suspend fun setBaseUrl(kind: AiProviderKind, url: String) = edit { putString(k(kind, BASE_URL), url.trim()) }

    /** "Verbose AI logging": keep raw AI responses for the debug log (always on in debug builds). */
    var verboseLogging: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VERBOSE, value).apply()
        }

    /** What a provider reads per request; the key is decrypted only then. */
    fun credentials(kind: AiProviderKind): ProviderCredentials = object : ProviderCredentials {
        override suspend fun apiKey() = this@KeystoreAiSettings.apiKey(kind)
        override suspend fun model() = this@KeystoreAiSettings.model(kind)
        override suspend fun baseUrl() = if (kind == AiProviderKind.OpenAiCompatible) this@KeystoreAiSettings.baseUrl(kind) else null
    }

    /** Every stored key in clear, for redacting the debug log. Never shown, logged or shared. */
    suspend fun allKeys(): List<String> = AiProviderKind.entries.mapNotNull { runCatching { apiKey(it) }.getOrNull() }

    private suspend fun edit(block: SharedPreferences.Editor.() -> Unit) = withContext(Dispatchers.IO) {
        prefs.edit().apply(block).commit()
        Unit
    }

    private fun k(kind: AiProviderKind, field: String) = "${kind.id}.$field"

    private companion object {
        const val PREFS = "bestnote.ai.providers"
        const val KEY_PROVIDER = "provider"
        const val KEY_VERBOSE = "verbose_logging"
        const val CIPHERTEXT = "api_key_ciphertext"
        const val HINT = "api_key_hint"
        const val MODEL = "model"
        const val BASE_URL = "base_url"
        const val MODELS = "models_json"
        const val MODELS_AT = "models_fetched_at"
        const val RECENT = "recent_models"
        const val FAVORITES = "favorite_models"
        const val RECENT_MAX = 5
        val json = Json { ignoreUnknownKeys = true }
        val modelList = ListSerializer(AiModel.serializer())
    }
}
