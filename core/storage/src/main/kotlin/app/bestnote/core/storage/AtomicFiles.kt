package app.bestnote.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Points inside one atomic file write, for fault-injection tests. */
internal enum class WriteStep { BeforeTemp, HalfWritten, BeforeSync, BeforeRename, AfterRename }

/** Crash-safe file replacement: write `<file>.tmp`, fsync, atomic rename. Readers see old or new, never half. */
internal object AtomicFiles {
    fun write(target: File, bytes: ByteArray, faults: (WriteStep) -> Unit = {}) {
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        faults(WriteStep.BeforeTemp)
        FileOutputStream(tmp).use { out ->
            out.write(bytes, 0, bytes.size / 2)
            faults(WriteStep.HalfWritten)
            out.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
            faults(WriteStep.BeforeSync)
            out.fd.sync()
        }
        faults(WriteStep.BeforeRename)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        faults(WriteStep.AfterRename)
    }

    /** Makes renames inside [dir] durable across power loss (best effort: not every platform can open a directory). */
    fun syncDirectory(dir: File) {
        if (!dir.isDirectory) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Directory fsync is unavailable here; the rename itself is still atomic.
        } catch (_: UnsupportedOperationException) {
            // Same: not supported on this file system.
        }
    }
}
