package app.bestnote.core.security

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.core.content.edit
import app.bestnote.core.database.NotebookRepository
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.LockedScratch
import app.bestnote.core.storage.PinRecord
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [KeyVault] on the Android Keystore: RSA-2048 OAEP key pairs that never leave secure hardware. Wrapping uses the
 * public key in software (no prompt, so a notebook can be locked silently); unwrapping uses the private key, which
 * for [LockManager.DEVICE_ALIAS] is usable only for [AUTH_VALIDITY_SECONDS] after the user confirmed the device
 * credential or a strong biometric.
 */
class AndroidKeyVault @Inject constructor(@ApplicationContext private val context: Context) : KeyVault {
    override val deviceSecure: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun wrap(alias: String, requireAuth: Boolean, key: ByteArray): ByteArray {
        val store = keyStore()
        if (!store.containsAlias(alias)) generate(alias, requireAuth)
        val public = store.getCertificate(alias).publicKey
        // A software copy of the public key: the Keystore provider would apply the private key's auth rules to it.
        val plainPublic = KeyFactory.getInstance(public.algorithm).generatePublic(X509EncodedKeySpec(public.encoded))
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, plainPublic, OAEP) }.doFinal(key)
    }

    override fun unwrap(alias: String, blob: ByteArray): ByteArray {
        val private = try {
            keyStore().getKey(alias, null) as? PrivateKey
        } catch (e: GeneralSecurityException) {
            throw KeyInvalidatedException(e)
        } ?: throw KeyInvalidatedException()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, private, OAEP) }.doFinal(blob)
        } catch (e: UserNotAuthenticatedException) {
            throw AuthRequiredException(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyInvalidatedException(e)
        } catch (e: GeneralSecurityException) {
            throw KeyInvalidatedException(e)
        }
    }

    private fun generate(alias: String, requireAuth: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(RSA_BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
        if (requireAuth) {
            spec.setUserAuthenticationRequired(true)
            // A new fingerprint must not lock the user out of their notes: the key follows the screen lock only.
            spec.setInvalidatedByBiometricEnrollment(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                spec.setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply { initialize(spec.build()) }.generateKeyPair()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        const val RSA_BITS = 2048
        const val AUTH_VALIDITY_SECONDS = 30
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}

/** The app PIN's salted hash, in a private preferences file (never the PIN). */
class PrefsPinStore @Inject constructor(@ApplicationContext context: Context) : PinStore {
    private val prefs = context.getSharedPreferences("lock", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): PinRecord? = prefs.getString(KEY, null)?.let {
        try {
            json.decodeFromString(PinRecord.serializer(), it)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun save(record: PinRecord) {
        prefs.edit { putString(KEY, json.encodeToString(PinRecord.serializer(), record)) }
    }

    private companion object {
        const val KEY = "app_pin"
    }
}

class RepositoryLockHooks @Inject constructor(
    private val repository: NotebookRepository,
    private val scratch: LockedScratch,
) : LockHooks {
    override suspend fun awaitSaves(id: NotebookId) = repository.awaitSaves(id)
    override suspend fun forgetDerived(id: NotebookId) = repository.forgetDerived(id)
    override suspend fun sealedWith(id: NotebookId, dataKey: ByteArray) = withContext(Dispatchers.IO) { scratch.sealed(id, dataKey) }
    override suspend fun unsealed(id: NotebookId, dataKey: ByteArray) = withContext(Dispatchers.IO) { scratch.unsealed(id, dataKey) }
}

/** The relock setting, kept current from the user preferences. */
@Singleton
class PreferencesRelockSetting @Inject constructor(preferences: UserPreferencesRepository) : RelockSetting {
    @Volatile private var value = RelockAfter.Default

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            preferences.preferences.collect { value = RelockAfter.fromSeconds(it.storage.relockAfterSeconds) }
        }
    }

    override fun current(): RelockAfter = value
}

/**
 * Tells the [LockManager] when the app goes to the background and comes back (started-activity count; a
 * rotation is not a trip to the background). Call [install] once from `Application.onCreate`.
 */
object LockSession {
    fun install(app: Application, locks: LockManager) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var started = 0

                override fun onActivityStarted(activity: Activity) {
                    if (started++ == 0) locks.onForeground(SystemClock.elapsedRealtime())
                }

                override fun onActivityStopped(activity: Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    if (started == 0 && !activity.isChangingConfigurations) locks.onBackground(SystemClock.elapsedRealtime())
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface SecurityModule {
    @Binds fun vault(impl: AndroidKeyVault): KeyVault

    @Binds fun pins(impl: PrefsPinStore): PinStore

    @Binds fun hooks(impl: RepositoryLockHooks): LockHooks

    @Binds fun relockSetting(impl: PreferencesRelockSetting): RelockSetting
}
