package app.bestnote.core.storage

import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** LIB-11b: chunked encryption of recordings, crash recovery, scratch sweeps and no plaintext working files. */
class SealedStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = NotebookId("nb-locked")
    private val key = SealCrypto.newDataKey()
    private val keys = SealCrypto.Keys(key)
    private val aad = AudioWorkFiles.aad(id, "rec-1")

    /** Looks like the start of an ADTS / RIFF / MP4 / PDF file, so a plaintext leak is easy to spot. */
    private fun audio(size: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(size).also {
        "RIFFftyp%PDF".encodeToByteArray().copyInto(it, 0, 0, minOf(12, size))
    }

    private fun write(bytes: ByteArray, file: File, close: Boolean = true) {
        val w = SealedStream.Writer(file.outputStream(), keys, aad)
        // Uneven writes, like a pipe delivers them.
        var at = 0
        var step = 1
        while (at < bytes.size) {
            val n = minOf(step, bytes.size - at)
            w.write(bytes, at, n)
            at += n
            step = step * 3 % 7001 + 1
        }
        if (close) w.close() else w.flush()
    }

    private fun containsPlain(file: File, plain: ByteArray, window: Int = 32): Boolean {
        val hay = file.readBytes()
        if (plain.size < window) return false
        val needle = plain.copyOfRange(plain.size / 2, plain.size / 2 + window)
        return (0..hay.size - window).any { i -> (0 until window).all { hay[i + it] == needle[it] } } ||
            String(hay, Charsets.ISO_8859_1).contains("RIFF") || String(hay, Charsets.ISO_8859_1).contains("%PDF")
    }

    @Test
    fun roundTripsAtEverySizeAroundChunkBoundaries() {
        val c = SealedStream.CHUNK
        for (size in listOf(0, 1, 15, 16, c - 1, c, c + 1, 2 * c, 3 * c + 17, 200_000)) {
            val plain = audio(size, seed = size)
            val file = tmp.newFile()
            write(plain, file)
            assertFalse("size $size leaked", size >= 64 && containsPlain(file, plain))
            assertArrayEquals("size $size", plain, SealedStream.decryptAll(keys, aad, file))
            SealedStream.Reader(file, keys, aad).use { r ->
                assertTrue(r.complete)
                assertEquals(size.toLong(), r.size)
            }
        }
    }

    @Test
    fun randomAccessReadsMatch() {
        val plain = audio(5 * SealedStream.CHUNK + 1234)
        val file = tmp.newFile()
        write(plain, file)
        SealedStream.Reader(file, keys, aad).use { r ->
            val rnd = Random(7)
            repeat(200) {
                val pos = rnd.nextInt(plain.size + 10).toLong()
                val len = rnd.nextInt(1, 40_000)
                val buf = ByteArray(len)
                val n = r.read(pos, buf, 0, len)
                if (pos >= plain.size) {
                    assertEquals(-1, n)
                } else {
                    assertEquals(minOf(len.toLong(), plain.size - pos).toInt(), n)
                    assertArrayEquals(plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
                }
            }
        }
    }

    @Test
    fun cutOffStreamRecoversItsWholeChunksOnly() {
        val plain = audio(4 * SealedStream.CHUNK + 999)
        val file = tmp.newFile()
        write(plain, file, close = false) // process killed while recording: no final record
        // Not complete: refused unless recovering.
        try {
            SealedStream.decryptAll(keys, aad, file)
            fail("A cut-off stream must not read as complete")
        } catch (_: CorruptChunkException) {
        }
        val recovered = SealedStream.decryptAll(keys, aad, file, allowTruncated = true)
        assertArrayEquals(plain.copyOf(4 * SealedStream.CHUNK), recovered)
        assertFalse(containsPlain(file, plain))

        // A torn last record (half written) is dropped, the chunks before it still open.
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - SealedStream.RECORD / 2) }
        assertArrayEquals(plain.copyOf(3 * SealedStream.CHUNK), SealedStream.decryptAll(keys, aad, file, allowTruncated = true))

        // A complete stream cut inside its final record loses only that record.
        val whole = tmp.newFile()
        write(plain, whole)
        RandomAccessFile(whole, "rw").use { it.setLength(it.length() - 5) }
        SealedStream.Reader(whole, keys, aad, allowTruncated = true).use { r ->
            assertFalse(r.complete)
            assertEquals(4L * SealedStream.CHUNK, r.size)
        }
    }

    @Test
    fun wrongKeyAlteredOrMovedFilesAreRefused() {
        val plain = audio(3 * SealedStream.CHUNK)
        val file = tmp.newFile()
        write(plain, file)
        expectWrongKey { SealedStream.decryptAll(SealCrypto.Keys(SealCrypto.newDataKey()), aad, file, allowTruncated = true) }
        expectWrongKey { SealedStream.decryptAll(keys, AudioWorkFiles.aad(id, "rec-2"), file, allowTruncated = true) }
        val bytes = file.readBytes()
        bytes[SealedStream.HEADER_BYTES + 10] = (bytes[SealedStream.HEADER_BYTES + 10] + 1).toByte()
        file.writeBytes(bytes)
        expectWrongKey { SealedStream.decryptAll(keys, aad, file) }
        try {
            SealedStream.Reader(tmp.newFile().apply { writeBytes(plain) }, keys, aad, allowTruncated = true)
            fail("Plain bytes are no sealed stream")
        } catch (_: CorruptChunkException) {
        }
    }

    private fun expectWrongKey(block: () -> Unit) {
        try {
            block()
            fail("Expected WrongKeyException")
        } catch (_: WrongKeyException) {
        }
    }

    @Test
    fun lockingEncryptsPlainRecordingCopiesAndRemovingTheLockRestoresThem() {
        val dir = tmp.newFolder("audio", "nb-locked")
        val finished = audio(70_000, seed = 3)
        File(dir, "rec-1.m4a").writeBytes(finished)
        File(dir, "rec-2.rec").writeBytes(audio(5000, seed = 4)) // cut-off plain m4a: unplayable, dropped

        assertEquals(1, AudioWorkFiles.seal(dir, id, keys))
        assertTrue("no plaintext working file may remain", AudioWorkFiles.plainFiles(dir).isEmpty())
        val sealed = File(dir, "rec-1" + AudioWorkFiles.SEALED)
        assertFalse(containsPlain(sealed, finished))
        assertTrue(SealedStream.isSealedStream(sealed))

        assertEquals(1, AudioWorkFiles.unseal(dir, id, keys))
        assertArrayEquals(finished, File(dir, "rec-1.m4a").readBytes())
        assertFalse(sealed.exists())
    }

    @Test
    fun unsealRecoversACutOffEncryptedRecording() {
        val dir = tmp.newFolder("a")
        val plain = audio(3 * SealedStream.CHUNK + 50)
        write(plain, File(dir, "rec-1" + AudioWorkFiles.SEALED_PART), close = false) // killed while recording
        assertEquals(1, AudioWorkFiles.unseal(dir, id, keys))
        assertArrayEquals(plain.copyOf(3 * SealedStream.CHUNK), File(dir, "rec-1.m4a").readBytes())
    }

    @Test
    fun startSweepDeletesOrphanScratchLegacyCachesAndPlainParts() {
        val scratch = LockedScratch(File(tmp.root, "no_backup/locked-pdf"), File(tmp.root, "files/audio"))
        val pdf = File(scratch.pdfDir(id), "a.pdf").apply { parentFile.mkdirs(); writeBytes("%PDF-1.7 secret".encodeToByteArray()) }
        val legacy = File(tmp.root, "cache/pdf/nb-locked-1234/a.pdf").apply { parentFile.mkdirs(); writeBytes("%PDF".encodeToByteArray()) }
        val part = File(scratch.audioDir(id), "rec-3.rec").apply { parentFile.mkdirs(); writeBytes(audio(100)) }
        val otherPart = File(scratch.audioDir(NotebookId("plain")), "rec-4.rec").apply { parentFile.mkdirs(); writeBytes(audio(100)) }

        scratch.sweepAtStart(setOf(id), legacy = listOf(legacy.parentFile))

        assertFalse(pdf.exists())
        assertFalse(scratch.pdfRoot.exists())
        assertFalse(legacy.exists())
        assertFalse(part.exists())
        assertTrue("a plain notebook's recording in progress is not touched", otherPart.exists())
    }

    @Test
    fun relockDeletesScratchAndUnlockEncryptsLeftovers() = runTest {
        val scratch = LockedScratch(File(tmp.root, "no_backup/locked-pdf"), File(tmp.root, "files/audio"))
        val ring = NotebookKeyring()
        val header = SealHeader(keyCheck = SealCrypto.keyCheck(key), wraps = emptyList())
        val leftover = File(scratch.audioDir(id), "rec-1.m4a").apply { parentFile.mkdirs(); writeBytes(audio(40_000)) }
        val job = launch(Dispatchers.Unconfined) { scratch.follow(ring) }

        ring.put(id, key, header)
        yield()
        assertFalse("unlocking encrypts plain copies an older build left", leftover.exists())
        assertTrue(File(scratch.audioDir(id), "rec-1" + AudioWorkFiles.SEALED).isFile)

        val pdf = File(scratch.pdfDir(id), "doc.pdf").apply { parentFile.mkdirs(); writeBytes("%PDF".encodeToByteArray()) }
        ring.remove(id)
        yield()
        assertFalse("relock deletes the PDF scratch", pdf.exists())
        job.cancel()
    }

    @Test
    fun encryptHelperLeavesNoTempFile() {
        val dir = tmp.newFolder("enc")
        val plain = audio(SealedStream.CHUNK * 2 + 3)
        val target = File(dir, "x" + AudioWorkFiles.SEALED)
        SealedStream.encrypt(keys, aad, ByteArrayInputStream(plain), target)
        assertEquals(listOf(target.name), dir.list()!!.toList())
        assertArrayEquals(plain, SealedStream.decryptAll(keys, aad, target))
    }
}
