/**
 * :core:security — locked notebooks (LIB-11) on Android: Keystore key wrapping, unlocking with the device
 * biometric / screen lock or the app PIN, the optional recovery password, the relock session and the shared lock
 * dialogs. The file encryption itself is pure JVM in :core:storage ([SealCrypto], [FileNotebookStore]).
 */
package app.bestnote.core.security

import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.AppPin
import app.bestnote.core.storage.Base64Url
import app.bestnote.core.storage.KeyWrap
import app.bestnote.core.storage.NotebookKeyring
import app.bestnote.core.storage.NotebookSealing
import app.bestnote.core.storage.PasswordKeys
import app.bestnote.core.storage.PinRecord
import app.bestnote.core.storage.SealCrypto
import app.bestnote.core.storage.SealHeader
import app.bestnote.core.storage.WrongKeyException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The Keystore operations [LockManager] needs (Android: [AndroidKeyVault]; tests fake it). */
interface KeyVault {
    /** True when the device has a secure screen lock (PIN, pattern, password), so auth-bound keys can exist. */
    val deviceSecure: Boolean

    /** [key] encrypted with the public half of Keystore key [alias] (created if needed). Never needs the user. */
    fun wrap(alias: String, requireAuth: Boolean, key: ByteArray): ByteArray

    /**
     * [blob] decrypted with Keystore key [alias].
     * @throws AuthRequiredException when the user must confirm the device credential or a biometric first.
     * @throws KeyInvalidatedException when the key is gone (screen lock removed, Keystore reset).
     */
    fun unwrap(alias: String, blob: ByteArray): ByteArray
}

class AuthRequiredException(cause: Throwable? = null) : Exception("User authentication required", cause)

class KeyInvalidatedException(cause: Throwable? = null) : Exception("Keystore key no longer usable", cause)

/** Where the app PIN's salted hash lives. */
interface PinStore {
    fun load(): PinRecord?
    fun save(record: PinRecord)
}

/** App-side work around locking: plain derivatives to delete, saves to wait for. */
interface LockHooks {
    /** Waits for saves of [id] still running after its editor closed. */
    suspend fun awaitSaves(id: NotebookId) = Unit

    /** Deletes thumbnails and search index rows made from [id] before it was locked. */
    suspend fun forgetDerived(id: NotebookId) = Unit

    /** [id] was just sealed with [dataKey]: encrypt its working files outside the package (LIB-11b). */
    suspend fun sealedWith(id: NotebookId, dataKey: ByteArray) = Unit

    /** The lock of [id] (key [dataKey]) was removed: its encrypted working files become plain again. */
    suspend fun unsealed(id: NotebookId, dataKey: ByteArray) = Unit
}

/** How long the unlocked state survives the app being in the background (Settings → Storage → Locked notebooks). */
enum class RelockAfter(val millis: Long?) {
    Immediately(0L),
    OneMinute(60_000L),
    FiveMinutes(300_000L),

    /** Only when the app process ends. */
    UntilAppCloses(null),
    ;

    companion object {
        val Default = OneMinute

        /** The stored setting (seconds, -1 = until the app closes). */
        fun fromSeconds(seconds: Int): RelockAfter = when {
            seconds < 0 -> UntilAppCloses
            seconds == 0 -> Immediately
            seconds <= 60 -> OneMinute
            else -> FiveMinutes
        }
    }
}

/** Where the relock setting comes from. */
fun interface RelockSetting {
    fun current(): RelockAfter
}

/**
 * When to relock: pure timing, so it is unit-tested without Android. [onBackground] starts the clock;
 * [dueAt] says when relocking is due; [onForeground] says whether it already is.
 */
class RelockSchedule {
    private var backgroundAt: Long? = null

    fun onBackground(now: Long) {
        if (backgroundAt == null) backgroundAt = now
    }

    /** Back in the foreground at [now]: true if the app was away for [after] or longer. Resets the clock. */
    fun onForeground(now: Long, after: RelockAfter): Boolean {
        val since = backgroundAt ?: return false
        backgroundAt = null
        val limit = after.millis ?: return false
        return now - since >= limit
    }

    /** Milliseconds after going to the background when relocking is due, or null for never. */
    fun delayFor(after: RelockAfter): Long? = after.millis

    val inBackground: Boolean get() = backgroundAt != null
}

/**
 * Locks, unlocks and relocks notebooks. State: [sealed] (locked on disk) and [unlocked] (key held this session,
 * in the shared [NotebookKeyring]). A notebook is shown locked when it is sealed and not unlocked.
 *
 * Keys: each notebook has a random 256-bit data key, stored only wrapped: by an RSA-OAEP Android Keystore key that
 * needs the device credential or a strong biometric ([KeyWrap.KIND_DEVICE]); on devices with no screen lock by a
 * Keystore key plus the app PIN ([KeyWrap.KIND_PIN]); and optionally by a recovery password
 * ([KeyWrap.KIND_PASSWORD]) that survives the Keystore key being invalidated.
 */
@Singleton
class LockManager @Inject constructor(
    private val keyring: NotebookKeyring,
    private val sealing: NotebookSealing,
    private val vault: KeyVault,
    private val pins: PinStore,
    private val hooks: LockHooks,
    private val setting: RelockSetting,
) {
    /** PBKDF2 rounds for new PINs and recovery passwords (lowered in tests). */
    internal var iterations = PasswordKeys.DEFAULT_ITERATIONS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sealed = MutableStateFlow<Set<NotebookId>>(emptySet())
    private val _ready = MutableStateFlow(false)

    /** Notebooks locked on disk (after the first [refresh]). */
    val sealed: StateFlow<Set<NotebookId>> = _sealed.asStateFlow()

    /** Notebooks whose key is held this session. */
    val unlocked: StateFlow<Set<NotebookId>> = keyring.unlocked

    /** False until [sealed] has been read once. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Wrapping key opened by the app PIN, for this session only. */
    @Volatile private var pinKey: ByteArray? = null

    /** Open editors: flush before their key is dropped. */
    private val openSessions = ConcurrentHashMap<NotebookId, suspend () -> Unit>()

    private val schedule = RelockSchedule()
    private var relockJob: Job? = null

    /** While a prompt is up, the app going "to the background" (the system credential screen) is not real. */
    @Volatile private var authenticating = 0

    fun isLocked(id: NotebookId): Boolean = id in _sealed.value && id !in keyring

    /** Locking uses the app PIN when the device has no screen lock. */
    val usesPin: Boolean get() = !vault.deviceSecure

    val hasPin: Boolean get() = pins.load() != null

    val pinOpen: Boolean get() = pinKey != null

    suspend fun refresh(): Set<NotebookId> {
        val ids = sealing.sealedIds()
        _sealed.value = ids
        _ready.value = true
        return ids
    }

    // region Unlock

    /** What [tryUnlock] still needs from the user. */
    enum class Need { Nothing, Authentication, Pin, RecoveryPassword, Lost }

    /**
     * Unlocks every locked notebook in [ids] that can be unlocked without asking (a recent device unlock, an open
     * PIN). @return what is still needed for the rest; [Need.Nothing] when all are unlocked.
     */
    suspend fun tryUnlock(ids: Collection<NotebookId>): Need = withContext(Dispatchers.Default) {
        var need = Need.Nothing
        for (id in ids) {
            if (id in keyring) continue
            val header = sealing.sealHeader(id) ?: continue
            val result = unwrapWithoutUser(header)
            when {
                result.key != null -> keyring.put(id, result.key, header)
                // Report the most actionable need: a prompt before a PIN before the recovery password.
                need == Need.Nothing || result.need.ordinal < need.ordinal -> need = result.need
            }
        }
        need
    }

    private class Attempt(val key: ByteArray?, val need: Need)

    private fun unwrapWithoutUser(header: SealHeader): Attempt {
        val password = header.wrap(KeyWrap.KIND_PASSWORD) != null
        header.wrap(KeyWrap.KIND_DEVICE)?.let { w ->
            return try {
                Attempt(vault.unwrap(w.alias ?: DEVICE_ALIAS, Base64Url.decode(w.blob)), Need.Nothing)
            } catch (_: AuthRequiredException) {
                Attempt(null, Need.Authentication)
            } catch (_: KeyInvalidatedException) {
                Attempt(null, if (password) Need.RecoveryPassword else Need.Lost)
            }
        }
        header.wrap(KeyWrap.KIND_PIN)?.let { w ->
            val pin = pinKey ?: return Attempt(null, Need.Pin)
            return try {
                Attempt(PasswordKeys.unwrap(pin, vault.unwrap(w.alias ?: PIN_ALIAS, Base64Url.decode(w.blob))), Need.Nothing)
            } catch (_: KeyInvalidatedException) {
                Attempt(null, if (password) Need.RecoveryPassword else Need.Lost)
            } catch (_: AuthRequiredException) {
                Attempt(null, Need.Authentication)
            } catch (_: WrongKeyException) {
                Attempt(null, Need.Pin)
            }
        }
        return Attempt(null, if (password) Need.RecoveryPassword else Need.Lost)
    }

    /** Checks [pin] against the stored hash and keeps its wrapping key for the session. @return false if wrong. */
    suspend fun openPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val record = pins.load() ?: return@withContext false
        val key = AppPin.open(pin, record) ?: return@withContext false
        pinKey = key
        true
    }

    /** Sets the app PIN (once: only when there is none yet). Only its salted hash is stored. */
    suspend fun createPin(pin: String) = withContext(Dispatchers.Default) {
        check(pins.load() == null) { "The app PIN is already set" }
        val (record, key) = AppPin.create(pin, iterations)
        pins.save(record)
        pinKey = key
    }

    /** Unlocks [ids] with the recovery password. @return false if it is wrong for any of them. */
    suspend fun unlockWithRecovery(ids: Collection<NotebookId>, password: String): Boolean = withContext(Dispatchers.Default) {
        val derived = HashMap<String, ByteArray>() // one PBKDF2 per salt: a folder locked at once shares one
        var ok = true
        for (id in ids) {
            if (id in keyring) continue
            val header = sealing.sealHeader(id) ?: continue
            val w = header.wrap(KeyWrap.KIND_PASSWORD) ?: continue
            val salt = w.salt ?: continue
            val kek = derived.getOrPut(salt) { PasswordKeys.derive(password.toCharArray(), Base64Url.decode(salt), w.iterations) }
            try {
                keyring.put(id, PasswordKeys.unwrap(kek, Base64Url.decode(w.blob)), header)
            } catch (_: WrongKeyException) {
                ok = false
            }
        }
        ok
    }

    // endregion

    // region Lock

    /**
     * Locks [ids]: each gets a fresh data key, wrapped for the device lock (or the app PIN, which must be open) and,
     * when [recoveryPassword] is given, for that password too; then it is re-written encrypted and its plaintext
     * thumbnails and search rows are deleted. [keepUnlocked] keeps it open for this session (locking from the editor).
     * @return the ids that were locked.
     */
    suspend fun lock(ids: Collection<NotebookId>, recoveryPassword: String? = null, keepUnlocked: Boolean = false): Set<NotebookId> = withContext(Dispatchers.Default) {
        val usePin = usesPin
        val pin = pinKey
        check(!usePin || pin != null) { "Open the app PIN first" }
        val salt = recoveryPassword?.let { PasswordKeys.newSalt() }
        val recoveryKek = recoveryPassword?.let { PasswordKeys.derive(it.toCharArray(), salt!!, iterations) }
        val done = HashSet<NotebookId>()
        for (id in ids.distinct()) {
            if (sealing.sealHeader(id) != null) continue
            hooks.awaitSaves(id) // the last plain save of a just-closed editor lands before sealing
            val dataKey = SealCrypto.newDataKey()
            val wraps = ArrayList<KeyWrap>(2)
            wraps += if (usePin) {
                KeyWrap(KeyWrap.KIND_PIN, Base64Url.encode(vault.wrap(PIN_ALIAS, false, PasswordKeys.wrap(pin!!, dataKey))), alias = PIN_ALIAS)
            } else {
                KeyWrap(KeyWrap.KIND_DEVICE, Base64Url.encode(vault.wrap(DEVICE_ALIAS, true, dataKey)), alias = DEVICE_ALIAS)
            }
            if (recoveryKek != null) {
                wraps += KeyWrap(KeyWrap.KIND_PASSWORD, Base64Url.encode(PasswordKeys.wrap(recoveryKek, dataKey)), salt = Base64Url.encode(salt!!), iterations = iterations)
            }
            val header = SealHeader(keyCheck = SealCrypto.keyCheck(dataKey), wraps = wraps)
            if (sealing.seal(id, dataKey, header)) {
                done += id
                if (keepUnlocked) keyring.put(id, dataKey, header)
                hooks.forgetDerived(id)
                hooks.sealedWith(id, dataKey)
            }
        }
        refresh()
        done
    }

    /** "Remove lock": writes [ids] back as plain files. They must be unlocked. @return the ids now plain. */
    suspend fun removeLock(ids: Collection<NotebookId>): Set<NotebookId> = withContext(Dispatchers.Default) {
        val done = HashSet<NotebookId>()
        for (id in ids) {
            val key = keyring[id]?.dataKey ?: continue
            if (sealing.unseal(id)) {
                done += id
                hooks.unsealed(id, key)
            }
        }
        refresh()
        done
    }

    // endregion

    // region Session

    /** An editor has [id] open: [flush] is called before its key is dropped. Returns the unregister action. */
    fun registerOpen(id: NotebookId, flush: suspend () -> Unit): () -> Unit {
        openSessions[id] = flush
        return { openSessions.remove(id, flush) }
    }

    /** Locks [ids] again now (their editors save first). */
    suspend fun relock(ids: Collection<NotebookId>) {
        for (id in ids) {
            openSessions[id]?.let { flush -> withTimeoutOrNull(FLUSH_TIMEOUT_MS) { runCatching { flush() } } }
            hooks.awaitSaves(id)
            keyring.remove(id)
        }
    }

    suspend fun relockAll() {
        relock(keyring.unlocked.value)
        pinKey = null
    }

    /** Drops every key at once without waiting for saves (pending saves retry after the next unlock). */
    private fun relockNowBlocking() {
        keyring.clear()
        pinKey = null
    }

    /** A biometric / credential prompt is opening (its screen briefly sends the app to the background). */
    fun beginAuthentication() {
        authenticating++
    }

    fun endAuthentication() {
        scope.launch {
            delay(AUTH_SETTLE_MS) // the activity restarts just after the result arrives
            authenticating = (authenticating - 1).coerceAtLeast(0)
        }
    }

    /** The app's last activity stopped. */
    fun onBackground(now: Long) {
        if (authenticating > 0) return
        schedule.onBackground(now)
        val delayMs = schedule.delayFor(setting.current()) ?: return
        relockJob?.cancel()
        relockJob = scope.launch {
            delay(delayMs)
            relockAll()
        }
    }

    /** An activity of the app started again. */
    fun onForeground(now: Long) {
        relockJob?.cancel()
        relockJob = null
        // Doze can hold the timer back; relock before anything is drawn if the time is up.
        if (schedule.onForeground(now, setting.current())) relockNowBlocking()
    }

    // endregion

    companion object {
        const val DEVICE_ALIAS = "bestnote.lock.device.v1"
        const val PIN_ALIAS = "bestnote.lock.pin.v1"
        private const val FLUSH_TIMEOUT_MS = 5_000L
        private const val AUTH_SETTLE_MS = 1_500L
    }
}
