package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DOC-03 "killing the process mid-write never corrupts a file".
 * 1. Fault injection: a crash at every single step of a save leaves a notebook that opens, and
 *    every page is either its old or its new version (never torn).
 * 2. A real `kill -9` of a separate JVM that is saving in a tight loop.
 */
class CrashSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private class SimulatedCrash : RuntimeException("crash")

    private val nid = NotebookId("nb")

    private fun version(v: Int, pages: Int) = NotebookPackage(
        Notebook(nid, "v$v", 1, v.toLong(), pageCount = pages),
        List(pages) { i -> PageContent(Page(nid, i), InkChunkCodec.encode(randomStrokes(20, seed = v * 100 + i)), "p$i") },
        mapOf("assets/a.bin" to ByteArray(64) { v.toByte() }),
    )

    private fun crashingStore(root: File, crashAt: Int): FileNotebookStore {
        var step = 0
        return FileNotebookStore(root, kotlinx.coroutines.Dispatchers.IO) { if (step++ == crashAt) throw SimulatedCrash() }
    }

    private fun countSteps(write: suspend (NotebookStore) -> Unit): Int {
        val root = tmp.newFolder()
        runBlocking { FileNotebookStore(root).write(version(1, 3)) }
        var steps = 0
        runBlocking { write(FileNotebookStore(root, kotlinx.coroutines.Dispatchers.IO) { steps++ }) }
        return steps
    }

    /** The notebook opens and each page decodes to one of the [allowed] versions of that page. */
    private suspend fun assertIntact(root: File, vararg allowed: NotebookPackage) {
        val read = FileNotebookStore(root).read(nid) ?: return fail("notebook vanished")
        assertTrue(read.notebook in allowed.map { it.notebook })
        for (page in read.pages) {
            val strokes = InkChunkCodec.decode(page.strokes)
            val candidates = allowed.mapNotNull { pkg -> pkg.pages.firstOrNull { it.id == page.id } }
            assertTrue("page ${page.id} is torn", candidates.any { InkChunkCodec.decode(it.strokes) == strokes })
        }
    }

    private fun crashAtEveryStep(old: NotebookPackage, new: NotebookPackage, write: suspend NotebookStore.() -> Unit) {
        val steps = countSteps(write)
        assertTrue(steps > 10)
        for (crashAt in 0 until steps) {
            val root = tmp.newFolder()
            runBlocking {
                FileNotebookStore(root).write(old)
                try {
                    crashingStore(root, crashAt).write()
                    fail("expected a crash at step $crashAt")
                } catch (_: SimulatedCrash) {
                    // the "process" died here
                }
                assertIntact(root, old, new)
                // After a restart the next save completes and cleans up the leftovers.
                FileNotebookStore(root).write(new)
                assertIntact(root, new)
                assertTrue(root.walk().none { it.name.endsWith(".tmp") })
            }
        }
    }

    @Test fun crashAtAnyStepOfAFullWriteNeverCorrupts() {
        val old = version(1, 3)
        val new = version(2, 4)
        crashAtEveryStep(old, new) { write(new) }
    }

    @Test fun crashAtAnyStepOfAnAutosaveNeverCorrupts() {
        val old = version(1, 3)
        val new = version(1, 3).let { base ->
            val changed = version(2, 3).pages[1]
            NotebookPackage(base.notebook.copy(updatedAtMillis = 2), listOf(base.pages[0], changed, base.pages[2]), base.assets)
        }
        crashAtEveryStep(old, new) { writePages(new, setOf("p1")) }
    }

    @Test fun killMinus9DuringSavesNeverCorrupts() = runTest {
        repeat(KILLS) { round ->
            val root = tmp.newFolder("kill$round")
            val child = ProcessBuilder(javaBinary(), "-cp", testClasspath(), KillTarget::class.java.name, root.path)
                .redirectErrorStream(true)
                .start()
            try {
                val ready = child.inputStream.bufferedReader().readLine()
                assertEquals("ready", ready)
                Thread.sleep(50L + round * 37L) // land the kill somewhere inside a save
            } finally {
                child.destroyForcibly() // SIGKILL: no finally blocks, no shutdown hooks
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            }
            val read = FileNotebookStore(root).read(nid) ?: return@repeat fail("notebook vanished")
            read.pages.forEach { InkChunkCodec.decode(it.strokes) } // throws if any page is torn
            assertTrue(read.notebook.updatedAtMillis >= 1)
        }
    }

    private fun javaBinary() = File(System.getProperty("java.home"), "bin/java").path

    /** The test JVM's classpath, plus the code sources of what the child needs (Gradle may use a classpath jar). */
    private fun testClasspath(): String {
        val markers = listOf(KillTarget::class.java, FileNotebookStore::class.java, Notebook::class.java, Unit::class.java, Json::class.java)
        val parts = markers.mapNotNull { it.protectionDomain?.codeSource?.location?.toURI()?.let(::File)?.path } +
            System.getProperty("java.class.path").split(File.pathSeparator) +
            listOf(kotlinx.serialization.KSerializer::class.java, kotlinx.coroutines.CoroutineScope::class.java)
                .mapNotNull { it.protectionDomain?.codeSource?.location?.toURI()?.let(::File)?.path }
        return parts.filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)
    }

    private companion object {
        const val KILLS = 6
    }
}

/** Child-process entry point for [CrashSafetyTest]: saves an ever-changing notebook until killed. */
object KillTarget {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val nid = NotebookId("nb")
        val store = FileNotebookStore(File(args[0]))
        var v = 1
        while (true) {
            val pages = List(6) { i -> PageContent(Page(nid, i), InkChunkCodec.encode(randomStrokes(400, seed = v * 10 + i)), "p$i") }
            store.write(NotebookPackage(Notebook(nid, "v$v", 1, v.toLong(), pageCount = pages.size), pages))
            if (v == 1) println("ready").also { System.out.flush() }
            v++
        }
    }
}
