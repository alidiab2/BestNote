package app.bestnote.engine.pdf

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.UUID

/** Read-only, seekable descriptors over bytes that must not stay on disk in plain (LIB-11b). */
internal object PrivateFiles {
    /**
     * A descriptor positioned at 0 holding [bytes]: a memfd on Android 11+, otherwise a file in [scratchDir] that is
     * unlinked once open (it lives on only as the open descriptor; a crash in between leaves it for the start sweep).
     */
    fun descriptor(bytes: ByteArray, scratchDir: File): ParcelFileDescriptor {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return memory(bytes)
            } catch (_: ErrnoException) {
                // no memfd (old kernel, seccomp): fall back to the scratch file
            }
        }
        scratchDir.mkdirs()
        val file = File(scratchDir, UUID.randomUUID().toString())
        try {
            file.writeBytes(bytes)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            file.delete()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun memory(bytes: ByteArray): ParcelFileDescriptor {
        val fd: FileDescriptor = Os.memfd_create("bestnote-pdf", 0)
        try {
            var at = 0
            while (at < bytes.size) {
                val n = Os.write(fd, bytes, at, bytes.size - at)
                if (n <= 0) throw IOException("memfd write failed")
                at += n
            }
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }
}
