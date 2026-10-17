package app.bestnote.feature.editor.audio

import android.os.ParcelFileDescriptor
import android.util.Log
import app.bestnote.core.storage.SealCrypto
import app.bestnote.core.storage.SealedStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where MediaRecorder writes a locked notebook's recording (LIB-11b): the write end of a pipe; a thread reads the
 * other end and appends [SealedStream] records to [file]. Whole chunks reach the disk as they fill, so a crash keeps
 * everything up to the last chunk, encrypted. [finish] after the recorder is released closes the pipe and writes the
 * final record (or, for a failed recording, just stops).
 */
internal class SealedSink private constructor(
    private val read: ParcelFileDescriptor,
    private val write: ParcelFileDescriptor,
    private val out: FileOutputStream,
    private val writer: SealedStream.Writer,
) {
    /** The descriptor to hand to `MediaRecorder.setOutputFile`. */
    val recorderFd: FileDescriptor get() = write.fileDescriptor

    @Volatile var bytesIn = 0L
        private set

    @Volatile private var failed = false

    private val pump = Thread({ pump() }, "BestNoteSealedAudio").apply { start() }

    private fun pump() {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    writer.write(buf, 0, n)
                    bytesIn += n
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Sealed recording pipe failed", e)
            failed = true
        }
    }

    /**
     * Ends the recording: closes the pipe (the recorder already released its copy), waits for the pump and, when
     * [keep], writes the final record. @return true if the file is a complete sealed stream.
     */
    fun finish(keep: Boolean): Boolean {
        runCatching { write.close() }
        pump.join(JOIN_MS)
        if (pump.isAlive) {
            runCatching { read.close() }
            pump.join(JOIN_MS)
        }
        return try {
            if (keep && !failed && !pump.isAlive) {
                writer.close()
                true
            } else {
                out.close()
                false
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot finish sealed recording", e)
            runCatching { out.close() }
            false
        }
    }

    companion object {
        private const val TAG = "BestNoteAudio"
        private const val BUFFER = 8 * 1024
        private const val JOIN_MS = 5_000L

        fun open(file: File, keys: SealCrypto.Keys, aad: ByteArray): SealedSink {
            val out = FileOutputStream(file)
            val writer = try {
                SealedStream.Writer(out, keys, aad)
            } catch (e: IOException) {
                out.close()
                throw e
            }
            val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
            return SealedSink(read, write, out, writer)
        }
    }
}
