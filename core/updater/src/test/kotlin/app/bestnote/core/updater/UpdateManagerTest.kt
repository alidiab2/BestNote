package app.bestnote.core.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    private var now = 10_000_000_000L
    private var fetches = 0
    private var releases = listOf(rel(20))
    private var fail = false
    private val store = object : UpdateStore {
        var state = UpdateState()
        override fun load() = state
        override fun save(state: UpdateState) { this.state = state }
    }
    private val installed = mutableListOf<File>()
    private var canInstall = true

    private fun rel(code: Long) = ReleaseInfo("v0.1.$code", "t", "n", false, code, "0.1.$code", "https://x/a.apk", 3)

    private fun manager() = UpdateManager(
        source = { fetches++; if (fail) throw java.io.IOException("offline"); releases },
        downloader = object : ApkDownloader {
            override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit): File {
                onProgress(3, 3); return File("x.apk")
            }
        },
        installer = object : ApkInstaller {
            override fun canInstall() = canInstall
            override fun install(apk: File): InstallResult =
                if (canInstall) { installed += apk; InstallResult.Launched } else InstallResult.NeedsPermission
        },
        store = store,
        build = BuildInfo("0.1.10-abc", 10, "abc"),
        clock = { now },
    )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `start check prompts for a newer release and is throttled`() {
        val m = manager()
        m.checkOnStart()
        assertEquals(20L, m.prompt.value?.versionCode)
        m.dismissPrompt()
        m.checkOnStart()
        assertEquals(1, fetches)
        now += UpdatePolicy.INTERVAL_MILLIS
        m.checkOnStart()
        assertEquals(2, fetches)
    }

    @Test fun `later hides the prompt for a day, skip for that version`() {
        val m = manager()
        m.checkOnStart()
        m.later()
        assertNull(m.prompt.value)
        now += UpdatePolicy.INTERVAL_MILLIS
        m.checkOnStart()
        assertEquals(20L, m.prompt.value?.versionCode)
        m.skip(m.prompt.value!!)
        now += UpdatePolicy.INTERVAL_MILLIS
        m.checkOnStart()
        assertNull(m.prompt.value)
        releases = listOf(rel(21))
        now += UpdatePolicy.INTERVAL_MILLIS
        m.checkOnStart()
        assertEquals(21L, m.prompt.value?.versionCode)
    }

    @Test fun `auto check off means no network on start, manual check still works`() {
        val m = manager()
        m.setAutoCheck(false)
        m.checkOnStart()
        assertEquals(0, fetches)
        m.checkNow()
        assertEquals(1, fetches)
        assertTrue(m.status.value is UpdateStatus.Available)
    }

    @Test fun `manual check reports up to date and errors`() {
        releases = listOf(rel(10))
        val m = manager()
        m.checkNow()
        assertEquals(UpdateStatus.UpToDate, m.status.value)
        fail = true
        m.checkNow()
        assertTrue(m.status.value is UpdateStatus.Failed)
    }

    @Test fun `background failure is silent`() {
        fail = true
        val m = manager()
        m.checkOnStart()
        assertEquals(UpdateStatus.Idle, m.status.value)
        assertNull(m.prompt.value)
    }

    @Test fun `update downloads then installs, or asks for permission`() {
        val m = manager()
        m.update(rel(20))
        assertEquals(1, installed.size)
        canInstall = false
        m.update(rel(20))
        assertTrue(m.status.value is UpdateStatus.NeedsPermission)
    }
}
