package app.bestnote.ai.vision.providers

import android.content.Context
import android.content.SharedPreferences
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ProviderCredentials
import app.bestnote.ai.vision.gemini.KeystoreCipher
import app.bestnote.ai.vision.gemini.KeystoreGeminiSettings
import app.bestnote.ai.vision.gemini.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun model(kind: AiProviderKind): String =
        if (kind == AiProviderKind.Gemini) gemini.model()
        else withContext(Dispatchers.IO) { prefs.getString(k(kind, MODEL), null)?.takeIf { it.isNotBlank() } } ?: AiProviderDefaults.model(kind)

    /** Blank resets to the provider's default. */
    suspend fun setModel(kind: AiProviderKind, model: String) {
        if (kind == AiProviderKind.Gemini) return gemini.setModel(model)
        edit { putString(k(kind, MODEL), model.trim()) }
    }

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
    }
}
