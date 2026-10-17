package app.bestnote.core.storage

import app.bestnote.core.model.NotebookId
import java.io.File
import java.io.IOException

/*
 * Working files of locked notebooks outside the package (LIB-11b): voice recording working copies and the
 * scratch space PDF backgrounds are opened from. See docs/architecture/locked-notebooks.md.
 */

/**
 * The working copies of a notebook's voice recordings (MEDIA-04), one folder per notebook under the audio root.
 * A plain notebook uses `<id>.m4a` (finished) and `<id>.rec` (recording). A locked notebook never has those: it
 * uses [SealedStream] files, `<id>.bnsa` (finished) and `<id>.bnsr` (recording, written encrypted as it goes).
 */
object AudioWorkFiles {
    const val PLAIN = ".m4a"
    const val PLAIN_PART = ".rec"
    const val SEALED = ".bnsa"
    const val SEALED_PART = ".bnsr"

    fun dir(audioRoot: File, notebookId: String): File = File(audioRoot, safeName(notebookId))

    /** Binds a working file to its notebook and recording (its file name may change: part → finished). */
    fun aad(id: NotebookId, recordingId: String): ByteArray = SealCrypto.aad(id, "work/audio/$recordingId")

    /** Plain audio files in [dir] (what must never exist for a locked notebook). */
    fun plainFiles(dir: File): List<File> = dir.listFiles { f -> f.isFile && (f.name.endsWith(PLAIN) || f.name.endsWith(PLAIN_PART)) }.orEmpty().toList()

    /**
     * The notebook was locked (or unlocked after an older build left plain copies): encrypts each finished plain
     * working copy and deletes the plain one; a plain part (a cut-off m4a, unplayable anyway) is deleted.
     * @return how many files were encrypted.
     */
    fun seal(dir: File, id: NotebookId, keys: SealCrypto.Keys): Int {
        var n = 0
        for (file in plainFiles(dir)) {
            if (file.name.endsWith(PLAIN)) {
                val rec = file.name.removeSuffix(PLAIN)
                val target = File(dir, rec + SEALED)
                try {
                    if (!target.isFile) {
                        file.inputStream().use { SealedStream.encrypt(keys, aad(id, rec), it, target) }
                        n++
                    }
                } catch (_: IOException) {
                    continue // keep the plain copy rather than lose the recording; retried on the next unlock
                }
            }
            file.delete()
        }
        return n
    }

    /**
     * The lock was removed: writes each encrypted working copy back as a plain one (a cut-off recording keeps what
     * was written) and deletes the encrypted file. Files the key does not open are left alone. @return how many.
     */
    fun unseal(dir: File, id: NotebookId, keys: SealCrypto.Keys): Int {
        var n = 0
        val sealed = dir.listFiles { f -> f.isFile && (f.name.endsWith(SEALED) || f.name.endsWith(SEALED_PART)) }.orEmpty()
        for (file in sealed) {
            val rec = file.name.removeSuffix(SEALED).removeSuffix(SEALED_PART)
            try {
                val plain = SealedStream.decryptAll(keys, aad(id, rec), file, allowTruncated = true)
                val target = File(dir, rec + PLAIN)
                if (plain.isNotEmpty() && !target.isFile) AtomicFiles.write(target, plain)
                file.delete()
                n++
            } catch (_: IOException) {
                // wrong key or unreadable: leave it
            }
        }
        return n
    }

    /** Deletes plain parts in [dir] (a cut-off plain recording of a now-locked notebook). */
    fun deletePlainParts(dir: File) {
        dir.listFiles { f -> f.name.endsWith(PLAIN_PART) }?.forEach { it.delete() }
    }

    internal fun safeName(notebookId: String) = notebookId.replace(Regex("[^A-Za-z0-9_-]"), "_")
}

/**
 * Keeps plaintext of locked notebooks off disk outside their package (LIB-11b). [pdfRoot] is the app-private,
 * no-backup scratch folder a locked notebook's PDFs are opened from (each file is unlinked once opened, so it
 * only exists for a moment); [audioRoot] holds the recording working copies ([AudioWorkFiles]).
 *
 * - at app start [sweepAtStart] deletes the whole scratch folder (orphans of a crash) and older builds' plain caches;
 * - [relocked] deletes a notebook's scratch when its key is dropped, [closed] when its editor closes;
 * - [sealed] / [unlocked] encrypt plain recording copies, [unsealed] writes them back when the lock is removed.
 */
class LockedScratch(val pdfRoot: File, val audioRoot: File) {
    /** Scratch folder for notebook [id]'s PDFs. */
    fun pdfDir(id: NotebookId): File = File(pdfRoot, AudioWorkFiles.safeName(id.value))

    fun audioDir(id: NotebookId): File = AudioWorkFiles.dir(audioRoot, id.value)

    /**
     * Start-up sweep: nothing is open yet, so every scratch file is an orphan. [legacy] are plain caches that
     * older builds or other features left for locked notebooks; [sealed] notebooks lose their plain recording parts.
     */
    fun sweepAtStart(sealed: Set<NotebookId>, legacy: List<File> = emptyList()) {
        pdfRoot.deleteRecursively()
        legacy.forEach { it.deleteRecursively() }
        for (id in sealed) AudioWorkFiles.deletePlainParts(audioDir(id))
    }

    fun closed(id: NotebookId) {
        pdfDir(id).deleteRecursively()
    }

    fun relocked(id: NotebookId) = closed(id)

    /** The notebook was just locked with [dataKey]. */
    fun sealed(id: NotebookId, dataKey: ByteArray) {
        AudioWorkFiles.seal(audioDir(id), id, SealCrypto.Keys(dataKey))
    }

    /** The key of [id] became available: plain recording copies an older build left are encrypted now. */
    fun unlocked(id: NotebookId, dataKey: ByteArray) = sealed(id, dataKey)

    /** The lock of [id] (key [dataKey]) was removed. */
    fun unsealed(id: NotebookId, dataKey: ByteArray) {
        AudioWorkFiles.unseal(audioDir(id), id, SealCrypto.Keys(dataKey))
    }

    /** Follows [keyring] until cancelled: a dropped key deletes that notebook's scratch, a new one tidies its audio. */
    suspend fun follow(keyring: NotebookKeyring) {
        var before = emptySet<NotebookId>()
        keyring.unlocked.collect { now ->
            for (id in before - now) runCatching { relocked(id) }
            for (id in now - before) keyring[id]?.let { k -> runCatching { unlocked(id, k.dataKey) } }
            before = now
        }
    }
}
