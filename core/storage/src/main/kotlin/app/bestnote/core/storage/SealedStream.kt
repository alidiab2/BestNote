package app.bestnote.core.storage

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/*
 * Chunked AES-256-GCM for long files of locked notebooks (LIB-11b): voice recordings are encrypted while they are
 * recorded and decrypted chunk by chunk for playback, so neither ever needs the whole file in memory or in plain.
 *
 *   "BNSS" | u8 version | 8-byte random prefix | record 0 | record 1 | ... | final record
 *   record i = AES-GCM(enc key, nonce = prefix | u32 i, aad = file aad | u32 i | u8 final, chunk i) (+16-byte tag)
 *
 * Every chunk but the last holds exactly [SealedStream.CHUNK] bytes, so a position maps to a record without an
 * index. The final flag in the AAD tells a complete file from one cut off by a crash: a cut-off file still yields
 * the chunks written before (all authenticated), never a byte of plaintext from disk.
 */
object SealedStream {
    /** Plaintext bytes per record: about 2 s of 64 kbit/s audio, the most a crash can lose. */
    const val CHUNK = 16 * 1024
    private const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8
    private const val PREFIX_BYTES = 8
    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte())
    internal val HEADER_BYTES = MAGIC.size + 1 + PREFIX_BYTES
    internal const val RECORD = CHUNK + TAG_BYTES

    /** True when [file] starts like a sealed stream (it says nothing about the key). */
    fun isSealedStream(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(MAGIC.size)
            input.read(head) == MAGIC.size && head.contentEquals(MAGIC)
        }
    } catch (_: IOException) {
        false
    }

    /** Encrypts [input] into [target] (temp file + rename). */
    fun encrypt(keys: SealCrypto.Keys, aad: ByteArray, input: InputStream, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            Writer(tmp.outputStream().buffered(), keys, aad).use { input.copyTo(it, CHUNK) }
            if (!tmp.renameTo(target)) throw IOException("Cannot move $tmp to $target")
        } finally {
            tmp.delete()
        }
    }

    /** All plaintext of [file]; with [allowTruncated] the chunks before a crash cut it off. */
    fun decryptAll(keys: SealCrypto.Keys, aad: ByteArray, file: File, allowTruncated: Boolean = false): ByteArray =
        Reader(file, keys, aad, allowTruncated).use { r ->
            val size = r.size
            if (size > Int.MAX_VALUE) throw IOException("Too large to read at once: $size bytes")
            val out = ByteArray(size.toInt())
            var at = 0
            while (at < out.size) {
                val n = r.read(at.toLong(), out, at, out.size - at)
                if (n <= 0) throw IOException("Sealed stream ended early")
                at += n
            }
            out
        }

    internal fun nonce(prefix: ByteArray, index: Int): ByteArray = ByteBuffer.allocate(PREFIX_BYTES + Int.SIZE_BYTES).put(prefix).putInt(index).array()

    internal fun recordAad(aad: ByteArray, index: Int, final: Boolean): ByteArray =
        ByteBuffer.allocate(aad.size + Int.SIZE_BYTES + 1).put(aad).putInt(index).put(if (final) 1 else 0).array()

    /**
     * Writes a sealed stream to [out] (the header at once, then each full chunk as soon as it fills). [close] writes
     * the final record; until then the data written so far is recoverable as a truncated stream.
     */
    class Writer(private val out: OutputStream, private val keys: SealCrypto.Keys, private val aad: ByteArray) : OutputStream() {
        private val prefix = SealCrypto.randomBytes(PREFIX_BYTES)
        private val buffer = ByteArray(CHUNK)
        private var filled = 0
        private var index = 0
        private var closed = false

        init {
            out.write(MAGIC)
            out.write(byteArrayOf(VERSION))
            out.write(prefix)
            out.flush()
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Writer closed" }
            var from = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - filled)
                System.arraycopy(b, from, buffer, filled, n)
                filled += n
                from += n
                left -= n
                if (filled == CHUNK) emit(final = false)
            }
        }

        /** Pushes the records written so far to disk; the partial chunk waits for more data or [close]. */
        override fun flush() {
            out.flush()
        }

        private fun emit(final: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keys.enc, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
            cipher.updateAAD(recordAad(aad, index, final))
            out.write(cipher.doFinal(buffer, 0, filled))
            out.flush()
            buffer.fill(0, 0, filled)
            filled = 0
            index++
        }

        override fun close() {
            if (closed) return
            try {
                emit(final = true)
            } finally {
                closed = true
                out.close()
            }
        }
    }

    /**
     * Random-access plaintext view of a sealed stream file, decrypting one record at a time (one is cached).
     * @throws WrongKeyException if the key is wrong or a record was altered.
     * @throws CorruptChunkException if the file is no sealed stream, or was cut off and [allowTruncated] is false.
     */
    class Reader(file: File, private val keys: SealCrypto.Keys, private val aad: ByteArray, allowTruncated: Boolean = false) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val prefix = ByteArray(PREFIX_BYTES)
        private val records: Int
        private val lastSize: Int

        /** Plaintext length. */
        val size: Long

        /** False when the stream lacks its final record (cut off); [size] then counts the chunks written before. */
        val complete: Boolean

        private var cachedIndex = -1
        private var cached = ByteArray(0)

        init {
            try {
                val len = raf.length()
                val head = ByteArray(MAGIC.size + 1)
                if (len < HEADER_BYTES) throw CorruptChunkException("Not a sealed stream")
                raf.readFully(head)
                if (!MAGIC.indices.all { head[it] == MAGIC[it] }) throw CorruptChunkException("Not a sealed stream")
                if (head[MAGIC.size] != VERSION) throw CorruptChunkException("Unknown sealed stream version ${head[MAGIC.size]}")
                raf.readFully(prefix)
                val body = len - HEADER_BYTES
                val full = (body / RECORD).toInt()
                val rem = (body % RECORD).toInt()
                // The final record is the short tail, or the last full record when the data ended on a chunk boundary.
                val finalIndex = if (rem >= TAG_BYTES) full else full - 1
                val finalPlain = if (finalIndex < 0) null else tryRecord(finalIndex, final = true)
                if (finalPlain != null) {
                    complete = true
                    records = finalIndex + 1
                    lastSize = finalPlain.size
                    remember(finalIndex, finalPlain)
                } else {
                    // Cut off: keep the full non-final records; a torn tail is dropped. The last kept one must open.
                    val last = if (full > 0) tryRecord(full - 1, final = false) ?: throw WrongKeyException("Sealed stream does not decrypt with this key") else null
                    if (!allowTruncated) throw CorruptChunkException("Sealed stream is cut off")
                    complete = false
                    records = full
                    lastSize = CHUNK
                    if (last != null) remember(full - 1, last)
                }
                size = if (records == 0) 0 else (records - 1).toLong() * CHUNK + lastSize
            } catch (e: IOException) {
                raf.close()
                throw e
            }
        }

        private fun recordSize(index: Int): Int = if (index == records - 1 && complete) lastSize + TAG_BYTES else RECORD

        private fun tryRecord(index: Int, final: Boolean, length: Int = -1): ByteArray? {
            val offset = HEADER_BYTES + index.toLong() * RECORD
            val n = if (length >= 0) length else minOf(RECORD.toLong(), raf.length() - offset).toInt()
            if (n < TAG_BYTES) return null
            val bytes = ByteArray(n)
            raf.seek(offset)
            raf.readFully(bytes)
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keys.enc, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
                cipher.updateAAD(recordAad(aad, index, final))
                cipher.doFinal(bytes)
            } catch (_: GeneralSecurityException) {
                null
            }
        }

        private fun remember(index: Int, plain: ByteArray) {
            cachedIndex = index
            cached = plain
        }

        private fun chunk(index: Int): ByteArray {
            if (index == cachedIndex) return cached
            val final = complete && index == records - 1
            val plain = tryRecord(index, final, recordSize(index)) ?: throw WrongKeyException("Sealed stream record $index does not decrypt")
            remember(index, plain)
            return plain
        }

        /** Reads up to [len] bytes at [position] into [buf]; -1 at the end. Thread-safe. */
        @Synchronized
        fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            if (len == 0) return 0
            var at = position
            var done = 0
            while (done < len && at < size) {
                val index = (at / CHUNK).toInt()
                val inChunk = (at % CHUNK).toInt()
                val plain = chunk(index)
                val n = minOf(len - done, plain.size - inChunk)
                if (n <= 0) break
                System.arraycopy(plain, inChunk, buf, off + done, n)
                done += n
                at += n
            }
            return done
        }

        @Synchronized
        override fun close() {
            cached.fill(0)
            cached = ByteArray(0)
            cachedIndex = -1
            raf.close()
        }
    }
}
