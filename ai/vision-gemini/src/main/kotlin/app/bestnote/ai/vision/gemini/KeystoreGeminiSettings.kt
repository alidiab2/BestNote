package app.bestnote.ai.vision.gemini

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets. [KeystoreCipher] in the app; a fake in tests. */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** Null when the ciphertext can't be opened (key wiped, other device): the user re-enters it. */
    fun decrypt(sealed: String): String?
}

/**
 * [GeminiSettings] backed by private SharedPreferences. The API key is stored only as AES-GCM
 * ciphertext whose key lives in the Android Keystore and never leaves it, so a copied or backed-up
 * prefs file is useless elsewhere. The plain key is never written or logged; only its last 4
 * characters are kept in clear as a UI hint.
 */
class KeystoreGeminiSettings(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher = KeystoreCipher(),
) : GeminiSettings {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override suspend fun apiKey(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_CIPHERTEXT, null)?.let(cipher::decrypt)
    }

    override suspend fun model(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: GeminiConfig.DEFAULT_MODEL
    }

    /** The stored model id as saved: "" means automatic (also for the old hard-wired default). */
    suspend fun storedModel(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_MODEL, null)?.trim()?.takeUnless { it == GeminiConfig.LEGACY_DEFAULT_MODEL }.orEmpty()
    }

    val hasApiKey: Boolean get() = prefs.contains(KEY_CIPHERTEXT)

    /** e.g. "…a1b2" for the settings screen. */
    val apiKeyHint: String? get() = prefs.getString(KEY_HINT, null)?.let { "…$it" }

    /** Saves (or with null/blank, removes) the user's key. */
    suspend fun setApiKey(key: String?) = withContext(Dispatchers.IO) {
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (trimmed == null) {
                remove(KEY_CIPHERTEXT)
                remove(KEY_HINT)
            } else {
                putString(KEY_CIPHERTEXT, cipher.encrypt(trimmed))
                putString(KEY_HINT, trimmed.takeLast(4))
            }
        }.commit()
        Unit
    }

    /** Blank resets to [GeminiConfig.DEFAULT_MODEL]. */
    suspend fun setModel(model: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_MODEL, model.trim()).commit()
        Unit
    }

    private companion object {
        const val PREFS = "bestnote.ai.gemini"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_HINT = "api_key_hint"
        const val KEY_MODEL = "model"
    }
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. */
class KeystoreCipher : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        return Base64.getEncoder().encodeToString(sealed)
    }

    override fun decrypt(sealed: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "bestnote.ai.gemini-key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
