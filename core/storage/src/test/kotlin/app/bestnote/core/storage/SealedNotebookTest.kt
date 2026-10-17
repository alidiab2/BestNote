package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.TextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** LIB-11: sealed (locked) notebooks — crypto, store behaviour, migration, crash recovery and old readers. */
class SealedNotebookTest {
    @get:Rule val tmp = TemporaryFolder()

    private val secret = "Quantum tunnelling notes"
    private val assetBytes = ByteArray(4096) { (it * 7).toByte() }

    private fun pkg(id: String = "nb-1", updated: Long = 5): NotebookPackage {
        val nid = NotebookId(id)
        return NotebookPackage(
            Notebook(nid, "Diary", 1_700_000_000_000, updated, PaperTemplate.Grid, pageCount = 2),
            List(2) { i ->
                PageContent(
                    Page(nid, i, PaperTemplate.Ruled, 612f, 792f),
                    InkChunkCodec.encode(randomStrokes(40, seed = i + 3)),
                    id = "page-secret-$i",
                    elements = listOf(TextElement("t$i", 100f, 100f, 200f, 40f, "$secret $i")),
                )
            },
            assets = mapOf("assets/img-private.png" to assetBytes),
            extras = mapOf("smartboard.json" to "{\"glossary\":\"$secret\"}".encodeToByteArray()),
        )
    }

    private fun header(dataKey: ByteArray) = SealHeader(keyCheck = SealCrypto.keyCheck(dataKey), wraps = listOf(KeyWrap(KeyWrap.KIND_DEVICE, "AAAA", alias = "test")))

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName} but got $e", e)
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }

    private fun entries(p: NotebookPackage) = BnoteFormat.toEntries(p)

    private fun assertSameEntries(expected: NotebookPackage, actual: NotebookPackage) {
        val a = entries(expected)
        val b = entries(actual)
        assertEquals(a.keys, b.keys)
        a.forEach { (k, v) -> assertArrayEquals("entry $k", v, b.getValue(k)) }
    }

    /** Every file under [dir], as bytes. */
    private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }

    private fun ByteArray.contains(needle: ByteArray): Boolean = (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    // region Crypto

    @Test fun encryptDecryptRoundTrip() {
        val keys = SealCrypto.Keys(SealCrypto.newDataKey())
        val plain = "hello ink".encodeToByteArray()
        val aad = SealCrypto.aad(NotebookId("n"), "pages/a.ink")
        val sealed = SealCrypto.encrypt(keys, aad, plain)
        assertFalse(sealed.contains(plain))
        assertArrayEquals(plain, SealCrypto.decrypt(keys, aad, sealed))
        // Same plaintext encrypts differently every time (fresh nonce).
        assertFalse(sealed.contentEquals(SealCrypto.encrypt(keys, aad, plain)))
    }

    @Test fun wrongKeyFailsCleanly() = runTest {
        val aad = SealCrypto.aad(NotebookId("n"), "manifest.json")
        val sealed = SealCrypto.encrypt(SealCrypto.Keys(SealCrypto.newDataKey()), aad, ByteArray(100))
        expect<WrongKeyException> { SealCrypto.decrypt(SealCrypto.Keys(SealCrypto.newDataKey()), aad, sealed) }
    }

    @Test fun swappedOrAlteredFilesAreRejected() = runTest {
        val keys = SealCrypto.Keys(SealCrypto.newDataKey())
        val sealed = SealCrypto.encrypt(keys, SealCrypto.aad(NotebookId("n"), "pages/a.ink"), ByteArray(64) { 1 })
        expect<WrongKeyException> { SealCrypto.decrypt(keys, SealCrypto.aad(NotebookId("n"), "pages/b.ink"), sealed) }
        expect<WrongKeyException> { SealCrypto.decrypt(keys, SealCrypto.aad(NotebookId("other"), "pages/a.ink"), sealed) }
        val flipped = sealed.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        expect<WrongKeyException> { SealCrypto.decrypt(keys, SealCrypto.aad(NotebookId("n"), "pages/a.ink"), flipped) }
        expect<CorruptChunkException> { SealCrypto.decrypt(keys, ByteArray(0), "not sealed at all".encodeToByteArray()) }
    }

    @Test fun fileNamesHideEntryPaths() {
        val keys = SealCrypto.Keys(SealCrypto.newDataKey())
        val name = keys.fileName("pages/page-secret-0.ink")
        assertTrue(name.matches(Regex("data/[0-9a-f]{32}\\.bin")))
        assertEquals(name, keys.fileName("pages/page-secret-0.ink"))
        assertFalse(name == SealCrypto.Keys(SealCrypto.newDataKey()).fileName("pages/page-secret-0.ink"))
    }

    @Test fun recoveryPasswordWrapsTheKey() = runTest {
        val dataKey = SealCrypto.newDataKey()
        val salt = PasswordKeys.newSalt()
        val wrapped = PasswordKeys.wrap(PasswordKeys.derive("correct horse".toCharArray(), salt, 1_000), dataKey)
        assertArrayEquals(dataKey, PasswordKeys.unwrap(PasswordKeys.derive("correct horse".toCharArray(), salt, 1_000), wrapped))
        expect<WrongKeyException> { PasswordKeys.unwrap(PasswordKeys.derive("wrong horse".toCharArray(), salt, 1_000), wrapped) }
    }

    @Test fun pinIsStoredOnlyAsSaltedHash() {
        val (record, wrapKey) = AppPin.create("482913", iterations = 1_000)
        val json = BnoteFormat.json.encodeToString(PinRecord.serializer(), record)
        assertFalse(json.contains("482913"))
        assertEquals(1_000, record.iterations)
        assertArrayEquals(wrapKey, AppPin.open("482913", record))
        assertNull(AppPin.open("482914", record))
        assertNull(AppPin.open("12", record))
        // Same PIN, new salt: a different hash (no rainbow tables).
        assertFalse(record.verifier == AppPin.create("482913", iterations = 1_000).first.verifier)
        assertFalse(AppPin.isValid("12a4"))
        assertTrue(AppPin.isValid("0000"))
    }

    @Test fun keyringRejectsAKeyThatDoesNotMatchTheHeader() = runTest {
        val ring = NotebookKeyring()
        expect<WrongKeyException> { ring.put(NotebookId("n"), SealCrypto.newDataKey(), header(SealCrypto.newDataKey())) }
        val key = SealCrypto.newDataKey()
        ring.put(NotebookId("n"), key, header(key))
        assertEquals(setOf(NotebookId("n")), ring.unlocked.value)
        ring.clear()
        assertTrue(ring.unlocked.value.isEmpty())
    }

    // endregion

    // region Store

    @Test fun sealedNotebookOnDiskHasNoPlaintext() = runTest {
        val ring = NotebookKeyring()
        val store = FileNotebookStore(tmp.root, keyring = ring)
        val original = pkg()
        store.write(original)
        val key = SealCrypto.newDataKey()
        assertTrue(store.seal(original.notebook.id, key, header(key)))
        val onDisk = files(File(tmp.root, "nb-1"))
        assertTrue(onDisk.keys.all { it == BnoteFormat.MANIFEST || it.matches(Regex("data/[0-9a-f]{32}\\.bin")) })
        val needles = listOf(secret, "page-secret", "img-private", "BNIK", "glossary").map { it.encodeToByteArray() } + listOf(assetBytes.copyOf(64))
        for ((name, bytes) in onDisk) for (n in needles) assertFalse("$name leaks ${n.decodeToString()}", bytes.contains(n))
        // The stub keeps only what the locked cover needs.
        val (cover, h) = BnoteFormat.readSealedStub(onDisk.getValue(BnoteFormat.MANIFEST))
        assertEquals(original.notebook, cover)
        assertEquals(SealCrypto.keyCheck(key), h.keyCheck)
        assertEquals(listOf(original.notebook), store.list())
        assertEquals(setOf(original.notebook.id), store.sealedIds())
    }

    @Test fun lockedNotebookCannotBeReadOrWrittenWithoutItsKey() = runTest {
        val ring = NotebookKeyring()
        val store = FileNotebookStore(tmp.root, keyring = ring)
        val original = pkg()
        store.write(original)
        val key = SealCrypto.newDataKey()
        store.seal(original.notebook.id, key, header(key))
        val before = files(File(tmp.root, "nb-1"))
        expect<NotebookLockedException> { store.read(original.notebook.id) }
        expect<NotebookLockedException> { store.readExtra(original.notebook.id, "smartboard.json") }
        expect<NotebookLockedException> { store.write(pkg(updated = 99)) }
        assertNull(store.appendJournal(original.notebook.id, "page-secret-0", 1, ByteArray(10)))
        val after = files(File(tmp.root, "nb-1"))
        assertEquals(before.keys, after.keys)
        before.forEach { (k, v) -> assertArrayEquals(v, after.getValue(k)) }
        // A store with no keyring at all (or another key) fails just as cleanly.
        expect<NotebookLockedException> { FileNotebookStore(tmp.root).read(original.notebook.id) }
        val other = SealCrypto.newDataKey()
        ring.put(original.notebook.id, other, header(other))
        expect<WrongKeyException> { store.read(original.notebook.id) }
    }

    @Test fun lockUnlockRemoveLockKeepsEveryByte() = runTest {
        val ring = NotebookKeyring()
        val store = FileNotebookStore(tmp.root, keyring = ring)
        val original = pkg()
        store.write(original)
        val plainFiles = files(File(tmp.root, "nb-1"))
        val key = SealCrypto.newDataKey()
        assertTrue(store.seal(original.notebook.id, key, header(key)))
        assertFalse(store.seal(original.notebook.id, key, header(key))) // already sealed
        ring.put(original.notebook.id, key, header(key))
        assertSameEntries(original, store.read(original.notebook.id)!!)
        assertArrayEquals(original.extras.getValue("smartboard.json"), store.readExtra(original.notebook.id, "smartboard.json"))
        assertTrue(store.unseal(original.notebook.id))
        assertFalse(original.notebook.id in ring)
        assertTrue(store.sealedIds().isEmpty())
        assertSameEntries(original, store.read(original.notebook.id)!!)
        val back = files(File(tmp.root, "nb-1"))
        assertEquals(plainFiles.keys, back.keys)
        plainFiles.forEach { (k, v) -> assertArrayEquals("file $k", v, back.getValue(k)) }
        assertTrue(tmp.root.listFiles()!!.none { it.name.startsWith(".") })
    }

    @Test fun unlockedNotebookSavesEncryptedAndOnlyChangedPages() = runTest {
        val ring = NotebookKeyring()
        val store = FileNotebookStore(tmp.root, keyring = ring)
        val original = pkg()
        store.write(original)
        val key = SealCrypto.newDataKey()
        store.seal(original.notebook.id, key, header(key))
        ring.put(original.notebook.id, key, header(key))
        val keys = SealCrypto.Keys(key)
        val page1 = File(tmp.root, "nb-1/" + keys.fileName("pages/page-secret-1.ink"))
        val page1Before = page1.readBytes()
        val edited = PageContent(original.pages[0].page, InkChunkCodec.encode(randomStrokes(5, seed = 99)), original.pages[0].id, original.pages[0].elements)
        val next = NotebookPackage(original.notebook.copy(updatedAtMillis = 50), listOf(edited, original.pages[1]), original.assets, original.recordings, original.extras)
        store.writePages(next, setOf(edited.id))
        assertArrayEquals(page1Before, page1.readBytes())
        assertSameEntries(next, store.read(original.notebook.id)!!)
        assertEquals(50L, store.list().single().updatedAtMillis)
        assertTrue(File(tmp.root, "nb-1").walkTopDown().none { it.name.endsWith(PageJournal.EXT) || it.name.endsWith(".ink") })
    }

    @Test fun autosaverSavesWholeEncryptedPagesForASealedNotebook() = runTest {
        val ring = NotebookKeyring()
        val store = FileNotebookStore(tmp.root, keyring = ring)
        val original = pkg()
        store.write(original)
        val key = SealCrypto.newDataKey()
        store.seal(original.notebook.id, key, header(key))
        ring.put(original.notebook.id, key, header(key))
        val opened = store.read(original.notebook.id)!!
        val saver = Autosaver(opened, store, backgroundScope, cpu = Dispatchers.Default, idleCompactMillis = 0)
        val strokes = InkChunkCodec.decode(opened.pages[0].strokes)
        saver.adoptBaseline(opened.pages[0].id, strokes)
        val more = strokes + randomStrokes(1, seed = 7)
        saver.updatePage(opened.pages[0].id, more)
        saver.flush()
        assertEquals(more, InkChunkCodec.decode(store.read(original.notebook.id)!!.pages[0].strokes))
        val dir = File(tmp.root, "nb-1")
        assertTrue(dir.walkTopDown().none { it.name.endsWith(PageJournal.EXT) })
        assertTrue(dir.walkTopDown().filter { it.isFile && it.name != BnoteFormat.MANIFEST }.all { SealCrypto.isSealedFile(it.readBytes()) })
        saver.close()
    }

    @Test fun trashOrDuplicateWhileUnlockedStaysSealed() = runTest {
        val ring = NotebookKeyring()
        val trash = FileNotebookStore(tmp.newFolder("trash"), keyring = ring)
        val key = SealCrypto.newDataKey()
        ring.put(NotebookId("nb-1"), key, header(key))
        trash.write(pkg())
        assertEquals(setOf(NotebookId("nb-1")), trash.sealedIds())
        ring.remove(NotebookId("nb-1"))
        expect<NotebookLockedException> { trash.read(NotebookId("nb-1")) }
        assertEquals("Diary", trash.list().single().title)
    }

    @Test fun interruptedSwapsAreRolledBackOrForward() = runTest {
        val plain = FileNotebookStore(tmp.root)
        plain.write(pkg("a"))
        plain.write(pkg("b"))
        // Crash after "a → .old-a" but before the sealed copy moved in: roll back.
        File(tmp.root, "a").renameTo(File(tmp.root, ".old-a"))
        File(tmp.root, ".seal-a").mkdirs()
        // Crash after the sealed copy of "b" moved in, before the old one was deleted: roll forward.
        File(tmp.root, ".old-b").mkdirs()
        val reopened = FileNotebookStore(tmp.root)
        assertEquals(setOf("a", "b"), reopened.list().map { it.id.value }.toSet())
        assertSameEntries(pkg("a"), reopened.read(NotebookId("a"))!!)
        assertTrue(tmp.root.listFiles()!!.none { it.name.startsWith(".") })
    }

    // endregion

    // region Old readers

    @Test fun olderReadersRefuseASealedNotebookGracefully() = runTest {
        val store = FileNotebookStore(tmp.root, keyring = NotebookKeyring())
        store.write(pkg())
        val key = SealCrypto.newDataKey()
        store.seal(NotebookId("nb-1"), key, header(key))
        val dir = File(tmp.root, "nb-1")
        val stub = File(dir, BnoteFormat.MANIFEST).readBytes()
        // A v8 app checks the version first: format 9 is newer, so it says "update the app" and never reads further.
        val format = FormatMigrations.formatOf(BnoteFormat.json.parseToJsonElement(stub.decodeToString()).jsonObject)
        assertEquals(BnoteFormat.SEALED_FORMAT, format)
        assertTrue(format > 8)
        assertTrue(BnoteFormat.SEALED_FORMAT > BnoteFormat.VERSION)
        // Any plain reading path of this app (zip import, raw directory) refuses it with a typed, catchable error.
        expect<NotebookLockedException> { BnoteFormat.fromEntries { File(dir, it).takeIf { f -> f.isFile }?.readBytes() } }
        // Sealed files never look like ink chunks or JSON to an old reader.
        dir.walkTopDown().filter { it.isFile && it.name != BnoteFormat.MANIFEST }.forEach { f ->
            assertFalse(PageJournal.isInkChunk(f.readBytes()))
            try {
                InkChunkCodec.decode(f.readBytes())
                fail("decoded a sealed file as ink")
            } catch (_: CorruptChunkException) {
                // expected
            }
        }
    }

    @Test fun backupSkipsLockedNotebooksButKeepsTheirEarlierCopy() = runTest {
        val ring = NotebookKeyring()
        val notebooks = FileNotebookStore(tmp.newFolder("nb"), keyring = ring)
        val trash = FileNotebookStore(tmp.newFolder("trash"), keyring = ring)
        val target = DirectoryBackupTarget(tmp.newFolder("backup"))
        notebooks.write(pkg("x"))
        notebooks.write(pkg("y"))
        val backup = LibraryBackup(notebooks, trash)
        val first = backup.backup(target)
        assertEquals(2, first.notebooks)
        val key = SealCrypto.newDataKey()
        notebooks.seal(NotebookId("x"), key, header(key))
        // Unchanged since the last backup: its earlier copy is reused without reading it.
        assertEquals(0, backup.backup(target).locked)
        // Edited while unlocked, then locked again: not readable now, so the earlier copy stays.
        ring.put(NotebookId("x"), key, header(key))
        notebooks.write(pkg("x", updated = 77))
        ring.remove(NotebookId("x"))
        val second = backup.backup(target)
        assertEquals(1, second.locked)
        assertEquals(2, second.notebooks)
        assertEquals(5L, second.manifest.notebooks.first { it.id == "x" }.updatedAtMillis)
        assertNotNull(target.read(second.manifest.notebooks.first { it.id == "x" }.file) { BnoteArchive.import(it) })
    }

    // endregion
}
