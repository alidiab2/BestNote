package app.bestnote.core.security

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookKeyring
import app.bestnote.core.storage.NotebookLockedException
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.PageContent
import app.bestnote.core.storage.PasswordKeys
import app.bestnote.core.storage.PinRecord
import app.bestnote.core.storage.SealCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stands in for the Android Keystore: AES keys per alias, an "authenticated recently" switch, invalidation. */
private class FakeVault(override var deviceSecure: Boolean = true) : KeyVault {
    private val keys = HashMap<String, Pair<SecretKey, Boolean>>()
    var authenticated = false
    val invalidated = HashSet<String>()

    override fun wrap(alias: String, requireAuth: Boolean, key: ByteArray): ByteArray {
        val k = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() to requireAuth }.first
        val nonce = ByteArray(12) { it.toByte() }
        return nonce + Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce)) }.doFinal(key)
    }

    override fun unwrap(alias: String, blob: ByteArray): ByteArray {
        if (alias in invalidated) throw KeyInvalidatedException()
        val (k, auth) = keys[alias] ?: throw KeyInvalidatedException()
        if (auth && !authenticated) throw AuthRequiredException()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, 12)) }.doFinal(blob, 12, blob.size - 12)
    }
}

private class MemoryPins : PinStore {
    var record: PinRecord? = null
    override fun load() = record
    override fun save(record: PinRecord) {
        this.record = record
    }
}

class LockManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ring = NotebookKeyring()
    private val vault = FakeVault()
    private val pins = MemoryPins()
    private var relockAfter = RelockAfter.OneMinute
    private val forgotten = ArrayList<NotebookId>()
    private val sealedKeys = HashMap<NotebookId, ByteArray>()
    private val unsealedKeys = HashMap<NotebookId, ByteArray>()
    private val store by lazy { FileNotebookStore(tmp.root, keyring = ring) }
    private val locks by lazy {
        LockManager(
            ring,
            store,
            vault,
            pins,
            object : LockHooks {
                override suspend fun forgetDerived(id: NotebookId) {
                    forgotten += id
                }

                override suspend fun sealedWith(id: NotebookId, dataKey: ByteArray) {
                    sealedKeys[id] = dataKey
                }

                override suspend fun unsealed(id: NotebookId, dataKey: ByteArray) {
                    unsealedKeys[id] = dataKey
                }
            },
        ) { relockAfter }.also { it.iterations = 1_000 }
    }

    private suspend fun notebook(id: String): NotebookId {
        val nid = NotebookId(id)
        store.write(
            NotebookPackage(
                Notebook(nid, "Title $id", 1, 2, PaperTemplate.Ruled),
                listOf(PageContent(Page(nid, 0), InkChunkCodec.encode(emptyList()), "p0")),
            ),
        )
        return nid
    }

    private suspend fun assertLocked(id: NotebookId) {
        assertTrue(locks.isLocked(id))
        try {
            store.read(id)
            fail("read a locked notebook")
        } catch (_: NotebookLockedException) {
            // expected
        }
    }

    @Test fun deviceLockNeedsAuthenticationToUnlock() = runTest {
        val a = notebook("a")
        assertEquals(setOf(a), locks.lock(listOf(a)))
        assertEquals(listOf(a), forgotten)
        assertLocked(a)
        assertEquals(LockManager.Need.Authentication, locks.tryUnlock(listOf(a)))
        vault.authenticated = true
        assertEquals(LockManager.Need.Nothing, locks.tryUnlock(listOf(a)))
        assertFalse(locks.isLocked(a))
        assertEquals("Title a", store.read(a)!!.notebook.title)
        locks.relock(listOf(a))
        assertLocked(a)
    }

    @Test fun lockFromTheEditorKeepsItOpen() = runTest {
        val a = notebook("a")
        locks.lock(listOf(a), keepUnlocked = true)
        assertTrue(a in locks.sealed.value)
        assertFalse(locks.isLocked(a))
    }

    @Test fun appPinWhenTheDeviceHasNoScreenLock() = runTest {
        vault.deviceSecure = false
        val a = notebook("a")
        assertTrue(locks.usesPin)
        locks.createPin("2468")
        assertTrue(pins.record != null && !pins.record.toString().contains("2468"))
        locks.lock(listOf(a))
        locks.relockAll() // also forgets the opened PIN
        assertEquals(LockManager.Need.Pin, locks.tryUnlock(listOf(a)))
        assertFalse(locks.openPin("1357"))
        assertEquals(LockManager.Need.Pin, locks.tryUnlock(listOf(a)))
        assertTrue(locks.openPin("2468"))
        assertEquals(LockManager.Need.Nothing, locks.tryUnlock(listOf(a)))
    }

    @Test fun recoveryPasswordOpensNotebooksWhoseDeviceKeyIsGone() = runTest {
        val a = notebook("a")
        val b = notebook("b")
        val c = notebook("c")
        locks.lock(listOf(a, b), recoveryPassword = "long enough secret")
        locks.lock(listOf(c))
        vault.invalidated += LockManager.DEVICE_ALIAS // e.g. the screen lock was removed
        assertEquals(LockManager.Need.RecoveryPassword, locks.tryUnlock(listOf(a, b)))
        assertEquals(LockManager.Need.Lost, locks.tryUnlock(listOf(c)))
        assertFalse(locks.unlockWithRecovery(listOf(a, b), "wrong password"))
        assertTrue(locks.isLocked(a))
        assertTrue(locks.unlockWithRecovery(listOf(a, b), "long enough secret"))
        assertFalse(locks.isLocked(a))
        assertFalse(locks.isLocked(b))
        assertTrue(locks.isLocked(c))
    }

    @Test fun removeLockWritesItBackPlain() = runTest {
        val a = notebook("a")
        locks.lock(listOf(a))
        assertTrue(locks.removeLock(listOf(a)).isEmpty()) // still locked: nothing happens
        vault.authenticated = true
        locks.tryUnlock(listOf(a))
        assertEquals(setOf(a), locks.removeLock(listOf(a)))
        assertTrue(locks.sealed.value.isEmpty())
        assertFalse(a in ring)
        assertEquals("Title a", FileNotebookStore(tmp.root).read(a)!!.notebook.title)
    }

    @Test fun workingFilesFollowTheLockWithTheNotebookKey() = runTest {
        val a = notebook("a")
        locks.lock(listOf(a))
        val key = sealedKeys.getValue(a) // recordings outside the package get encrypted with the same key
        assertEquals(store.sealHeader(a)!!.keyCheck, SealCrypto.keyCheck(key))
        vault.authenticated = true
        locks.tryUnlock(listOf(a))
        locks.removeLock(listOf(a))
        assertArrayEquals(key, unsealedKeys.getValue(a))
    }

    @Test fun relocksAfterTheChosenTimeInTheBackground() = runTest {
        val a = notebook("a")
        locks.lock(listOf(a), keepUnlocked = true)
        locks.onBackground(now = 1_000)
        locks.onForeground(now = 31_000) // 30 s away: still open
        assertFalse(locks.isLocked(a))
        locks.onBackground(now = 40_000)
        locks.onForeground(now = 100_001) // > 1 min away: locked before anything is drawn
        assertTrue(locks.isLocked(a))
    }

    @Test fun untilAppClosesNeverRelocksAndPromptsDoNotCount() = runTest {
        val a = notebook("a")
        locks.lock(listOf(a), keepUnlocked = true)
        relockAfter = RelockAfter.UntilAppCloses
        locks.onBackground(now = 0)
        locks.onForeground(now = 10_000_000)
        assertFalse(locks.isLocked(a))
        relockAfter = RelockAfter.Immediately
        locks.beginAuthentication() // the system credential screen sends the app "to the background"
        locks.onBackground(now = 20_000_000)
        locks.onForeground(now = 20_000_500)
        assertFalse(locks.isLocked(a))
    }

    @Test fun relockScheduleTiming() {
        val s = RelockSchedule()
        assertFalse(s.onForeground(5, RelockAfter.Immediately)) // never went away
        s.onBackground(100)
        s.onBackground(200) // the first stop counts
        assertTrue(s.onForeground(100, RelockAfter.Immediately))
        s.onBackground(0)
        assertFalse(s.onForeground(59_999, RelockAfter.OneMinute))
        s.onBackground(0)
        assertTrue(s.onForeground(60_000, RelockAfter.OneMinute))
        s.onBackground(0)
        assertFalse(s.onForeground(299_999, RelockAfter.FiveMinutes))
        s.onBackground(0)
        assertFalse(s.onForeground(Long.MAX_VALUE, RelockAfter.UntilAppCloses))
        assertEquals(0L, s.delayFor(RelockAfter.Immediately))
        assertEquals(null, s.delayFor(RelockAfter.UntilAppCloses))
        assertEquals(RelockAfter.UntilAppCloses, RelockAfter.fromSeconds(-1))
        assertEquals(RelockAfter.Immediately, RelockAfter.fromSeconds(0))
        assertEquals(RelockAfter.OneMinute, RelockAfter.fromSeconds(60))
        assertEquals(RelockAfter.FiveMinutes, RelockAfter.fromSeconds(300))
    }

    @Test fun passwordDerivationIsDeterministicPerSalt() {
        val salt = PasswordKeys.newSalt()
        assertTrue(PasswordKeys.derive("x".toCharArray(), salt, 1_000).contentEquals(PasswordKeys.derive("x".toCharArray(), salt, 1_000)))
        assertFalse(PasswordKeys.derive("x".toCharArray(), salt, 1_000).contentEquals(PasswordKeys.derive("x".toCharArray(), PasswordKeys.newSalt(), 1_000)))
    }
}
