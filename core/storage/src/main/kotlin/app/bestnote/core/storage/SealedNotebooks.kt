package app.bestnote.core.storage

import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Locked notebooks (LIB-11). A locked ("sealed") notebook keeps its unpacked `.bnote` layout, but every entry
 * is AES-256-GCM encrypted under a random per-notebook data key and stored under a name that reveals nothing.
 * Only a small plaintext stub manifest (what the locked cover shows, plus the wrapped data keys) stays readable.
 * See docs/architecture/locked-notebooks.md.
 */

/** The notebook is locked and its key is not unlocked in this session. Nothing was read or written. */
class NotebookLockedException(val id: NotebookId?, message: String = "Notebook ${id?.value.orEmpty()} is locked") : IOException(message)

/** A sealed file did not decrypt with the key given: a wrong key, a wrong password, or an altered file. */
class WrongKeyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One way to recover a notebook's data key. [kind] is [KIND_DEVICE] (Android Keystore key that needs the device
 * screen lock), [KIND_PIN] (Keystore key + the app PIN, for devices without a screen lock) or [KIND_PASSWORD]
 * (the optional recovery password). [blob] is the wrapped key (Base64); [salt]/[iterations] are the PBKDF2
 * parameters of password-derived wraps; [alias] names the Keystore key.
 */
@Serializable
data class KeyWrap(
    val kind: String,
    val blob: String,
    val alias: String? = null,
    val salt: String? = null,
    val iterations: Int = 0,
) {
    companion object {
        const val KIND_DEVICE = "device"
        const val KIND_PIN = "pin"
        const val KIND_PASSWORD = "password"
    }
}

/**
 * Plaintext header of a sealed notebook, stored in its stub manifest. [keyCheck] identifies the data key
 * (a keyed hash, useless for guessing it) so a wrong unwrap is caught before any file is touched.
 */
@Serializable
data class SealHeader(
    val version: Int = VERSION,
    val cipher: String = CIPHER,
    val keyCheck: String,
    val wraps: List<KeyWrap>,
) {
    fun wrap(kind: String): KeyWrap? = wraps.firstOrNull { it.kind == kind }

    companion object {
        const val VERSION = 1
        const val CIPHER = "AES-256-GCM"
    }
}

/**
 * Data keys of the sealed notebooks unlocked in this session (memory only, never written). A notebook with an
 * entry here is written sealed by [FileNotebookStore], whatever is on disk; one without an entry cannot be read
 * or written while it is sealed. The Android lock manager fills and clears it.
 */
class NotebookKeyring {
    class Unlocked(val dataKey: ByteArray, val header: SealHeader)

    private val keys = ConcurrentHashMap<NotebookId, Unlocked>()
    private val _unlocked = MutableStateFlow<Set<NotebookId>>(emptySet())

    /** Ids whose key is held right now. */
    val unlocked: StateFlow<Set<NotebookId>> = _unlocked.asStateFlow()

    /** @throws WrongKeyException if [dataKey] is not the key [header] describes. */
    fun put(id: NotebookId, dataKey: ByteArray, header: SealHeader) {
        if (SealCrypto.keyCheck(dataKey) != header.keyCheck) throw WrongKeyException("Key does not match notebook ${id.value}")
        keys[id] = Unlocked(dataKey.copyOf(), header)
        _unlocked.update { it + id }
    }

    operator fun get(id: NotebookId): Unlocked? = keys[id]

    operator fun contains(id: NotebookId): Boolean = keys.containsKey(id)

    /** Forgets [id]'s key (the bytes are dropped, not zeroed: a save in flight may still be using them). */
    fun remove(id: NotebookId) {
        keys.remove(id)
        _unlocked.update { it - id }
    }

    fun clear() {
        keys.clear()
        _unlocked.value = emptySet()
    }
}

/** Seals and unseals notebooks in place (LIB-11). Implemented by [FileNotebookStore]. */
interface NotebookSealing {
    /** Ids of sealed notebooks, readable or not. */
    suspend fun sealedIds(): Set<NotebookId>

    /** The header (wrapped keys) of sealed notebook [id], or null when it is not sealed. */
    suspend fun sealHeader(id: NotebookId): SealHeader?

    /**
     * Re-writes plain notebook [id] encrypted under [dataKey] (temp directory + rename, so a crash leaves it
     * either fully plain or fully sealed). Does not unlock it in the keyring. @return false if absent or already sealed.
     */
    suspend fun seal(id: NotebookId, dataKey: ByteArray, header: SealHeader): Boolean

    /** Writes sealed notebook [id] back as plain files ("Remove lock"); its key must be unlocked. @return false if not sealed. */
    suspend fun unseal(id: NotebookId): Boolean
}

/** The symmetric crypto of sealed notebooks: per-file AES-256-GCM and opaque file names. */
object SealCrypto {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val CHECK_BYTES = 16
    private const val NAME_HEX = 32
    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte())
    private val HEADER_BYTES = MAGIC.size + 1 + NONCE_BYTES

    /** Directory (inside a sealed notebook) that holds the encrypted entries. */
    const val DATA_DIR = "data/"

    private val random = SecureRandom()

    fun newDataKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** Public fingerprint of [dataKey] (a keyed hash; it reveals nothing about the key). */
    fun keyCheck(dataKey: ByteArray): String = hex(hmac(dataKey, "bestnote/lib-11/check")).take(CHECK_BYTES * 2)

    /** The subkeys derived from one data key. */
    class Keys(dataKey: ByteArray) {
        init {
            require(dataKey.size == KEY_BYTES) { "Data key must be $KEY_BYTES bytes" }
        }

        internal val enc = SecretKeySpec(hmac(dataKey, "bestnote/lib-11/enc"), "AES")
        private val names = hmac(dataKey, "bestnote/lib-11/names")

        /** Where package entry [path] is stored: `data/<32 hex>.bin`, a keyed hash of the path. */
        fun fileName(path: String): String = DATA_DIR + hex(hmac(names, path)).take(NAME_HEX) + ".bin"
    }

    /** Binds a file to its notebook and path, so sealed files cannot be swapped around unnoticed. */
    fun aad(id: NotebookId, path: String): ByteArray = "BestNote sealed v1\u0000${id.value}\u0000$path".encodeToByteArray()

    /** `"BNSE" | u8 version | 12-byte nonce | AES-GCM ciphertext + 16-byte tag`. */
    fun encrypt(keys: Keys, aad: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.enc, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(HEADER_BYTES + body.size).put(MAGIC).put(VERSION).put(nonce).put(body).array()
    }

    /**
     * @throws WrongKeyException if the key is wrong or the file was altered.
     * @throws CorruptChunkException if [sealed] is not a sealed file at all.
     */
    fun decrypt(keys: Keys, aad: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER_BYTES + TAG_BITS / 8 || !MAGIC.indices.all { sealed[it] == MAGIC[it] }) throw CorruptChunkException("Not a sealed file")
        if (sealed[MAGIC.size] != VERSION) throw CorruptChunkException("Unknown sealed file version ${sealed[MAGIC.size]}")
        val nonce = sealed.copyOfRange(MAGIC.size + 1, HEADER_BYTES)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keys.enc, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongKeyException("Sealed file does not decrypt with this key", e)
        } catch (e: GeneralSecurityException) {
            throw WrongKeyException("Sealed file does not decrypt", e)
        }
    }

    internal fun isSealedFile(bytes: ByteArray) = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    internal fun hmac(key: ByteArray, label: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(label.encodeToByteArray())

    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Password-derived keys (PBKDF2-HMAC-SHA256) and AES-GCM key wrapping, for the recovery password and the
 * app PIN. A wrapped key is `nonce | ciphertext + tag`.
 */
object PasswordKeys {
    /** About half a second on a mid-range tablet; stored with every wrap, so it can grow later. */
    const val DEFAULT_ITERATIONS = 120_000
    const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val WRAP_AAD = "BestNote key wrap v1".encodeToByteArray()

    fun newSalt(): ByteArray = SealCrypto.randomBytes(SALT_BYTES)

    fun derive(password: CharArray, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(iterations > 0) { "Need iterations" }
        val spec = PBEKeySpec(password, salt, iterations, SealCrypto.KEY_BYTES * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** [key] encrypted under [kek] (32 bytes). */
    fun wrap(kek: ByteArray, key: ByteArray): ByteArray {
        val nonce = SealCrypto.randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(WRAP_AAD)
        return nonce + cipher.doFinal(key)
    }

    /** @throws WrongKeyException if [kek] is not the key [wrapped] was made with. */
    fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray {
        if (wrapped.size <= NONCE_BYTES) throw WrongKeyException("Wrapped key too short")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES))
            cipher.updateAAD(WRAP_AAD)
            cipher.doFinal(wrapped, NONCE_BYTES, wrapped.size - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw WrongKeyException("Wrong password", e)
        }
    }
}

/**
 * The app PIN used to unlock notebooks on devices without a screen lock. Only a salted PBKDF2 hash is kept
 * ([verifier]); the PIN itself is never stored. The same derivation also yields a wrapping key, so the PIN
 * protects the notebook keys cryptographically and not only as a gate.
 */
@Serializable
data class PinRecord(val salt: String, val iterations: Int, val verifier: String)

object AppPin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    fun isValid(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** A new record for [pin] and the wrapping key it opens. */
    fun create(pin: String, iterations: Int = PasswordKeys.DEFAULT_ITERATIONS): Pair<PinRecord, ByteArray> {
        require(isValid(pin)) { "PIN must be $MIN_LENGTH-$MAX_LENGTH digits" }
        val salt = PasswordKeys.newSalt()
        val master = PasswordKeys.derive(pin.toCharArray(), salt, iterations)
        val record = PinRecord(Base64Url.encode(salt), iterations, Base64Url.encode(SealCrypto.hmac(master, VERIFY)))
        return record to SealCrypto.hmac(master, WRAP)
    }

    /** The wrapping key when [pin] is right, else null. Constant-time comparison. */
    fun open(pin: String, record: PinRecord): ByteArray? {
        if (!isValid(pin)) return null
        val master = PasswordKeys.derive(pin.toCharArray(), Base64Url.decode(record.salt), record.iterations)
        val ok = MessageDigest.isEqual(SealCrypto.hmac(master, VERIFY), Base64Url.decode(record.verifier))
        return if (ok) SealCrypto.hmac(master, WRAP) else null
    }

    private const val VERIFY = "bestnote/lib-11/pin-verify"
    private const val WRAP = "bestnote/lib-11/pin-wrap"
}

/** Base64 without line breaks, for keys and salts in JSON. */
object Base64Url {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /** @throws CorruptChunkException for text that is not Base64. */
    fun decode(text: String): ByteArray = try {
        java.util.Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw CorruptChunkException("Bad Base64", e)
    }
}
