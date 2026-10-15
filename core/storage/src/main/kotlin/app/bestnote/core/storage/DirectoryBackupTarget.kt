package app.bestnote.core.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** [BackupTarget] over an ordinary directory. Writes are atomic (temp file + rename). */
class DirectoryBackupTarget(private val root: File, private val io: CoroutineDispatcher = Dispatchers.IO) : BackupTarget {
    private fun file(path: String): File {
        require(path.split('/').none { it == ".." || it == "." } && !path.startsWith("/")) { "Invalid path '$path'" }
        return File(root, path)
    }

    override suspend fun exists(path: String) = withContext(io) { file(path).isFile }

    override suspend fun list(dir: String): List<String> =
        withContext(io) { file(dir.trimEnd('/')).listFiles { f -> f.isFile }.orEmpty().map { it.name }.sorted() }

    override suspend fun <T> read(path: String, block: (InputStream) -> T): T? = withContext(io) {
        val f = file(path)
        if (f.isFile) f.inputStream().buffered().use(block) else null
    }

    override suspend fun write(path: String, block: (OutputStream) -> Unit) = withContext(io) {
        val f = file(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().buffered().use { block(it) }
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "Cannot write $f" }
        }
    }

    override suspend fun delete(path: String) {
        withContext(io) { file(path).delete() }
    }
}
