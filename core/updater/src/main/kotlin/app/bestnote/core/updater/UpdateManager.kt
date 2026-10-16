package app.bestnote.core.updater

import dagger.Module
import dagger.Binds
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shows. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateStatus
    data class NeedsPermission(val release: ReleaseInfo) : UpdateStatus
    data class Failed(val release: ReleaseInfo?, val message: String) : UpdateStatus
}

/** Everything the screens need: [status], whether to show the [prompt], and the settings. */
@Singleton
class UpdateManager @Inject constructor(
    private val source: ReleaseSource,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val store: UpdateStore,
    val build: BuildInfo,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _prompt = MutableStateFlow<ReleaseInfo?>(null)

    /** The release to offer in the dialog, or null. */
    val prompt: StateFlow<ReleaseInfo?> = _prompt.asStateFlow()

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<UpdateState> = _settings.asStateFlow()

    private var job: Job? = null

    fun setAutoCheck(enabled: Boolean) = mutate { it.copy(autoCheck = enabled) }

    /** On app start: at most once per 24 h. */
    fun checkOnStart() {
        if (UpdatePolicy.shouldAutoCheck(_settings.value, clock.now())) check(manual = false)
    }

    /** "Check for updates": always asks, always shows the result. */
    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (manual) _status.value = UpdateStatus.Checking
            try {
                val latest = ReleaseParser.newest(source.fetch())
                mutate { UpdatePolicy.checked(it, clock.now()) }
                if (latest != null && UpdatePolicy.isNewer(latest, build.versionCode)) {
                    _status.value = UpdateStatus.Available(latest)
                    if (UpdatePolicy.shouldPrompt(latest, build.versionCode, _settings.value, clock.now(), manual)) _prompt.value = latest
                } else if (manual) {
                    _status.value = UpdateStatus.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed background check stays silent and is retried at the next start.
                if (manual) _status.value = UpdateStatus.Failed(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun later() {
        mutate { UpdatePolicy.later(it, clock.now()) }
        _prompt.value = null
    }

    fun skip(release: ReleaseInfo) {
        mutate { UpdatePolicy.skip(it, release) }
        _prompt.value = null
        _status.value = UpdateStatus.Idle
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    /** Update now: download, verify, hand to the system installer. */
    fun update(release: ReleaseInfo) {
        if (_status.value is UpdateStatus.Downloading) return
        _prompt.value = null
        job = scope.launch {
            _status.value = UpdateStatus.Downloading(release, 0, release.apkSize)
            try {
                val apk = downloader.download(release) { done, total ->
                    _status.update { if (it is UpdateStatus.Downloading) it.copy(done = done, total = total) else it }
                }
                _status.value = when (installer.install(apk)) {
                    InstallResult.Launched -> UpdateStatus.Available(release)
                    InstallResult.NeedsPermission -> UpdateStatus.NeedsPermission(release)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = UpdateStatus.Failed(release, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun mutate(transform: (UpdateState) -> UpdateState) {
        _settings.update(transform)
        store.save(_settings.value)
    }
}

fun interface Clock {
    fun now(): Long
}

@Module
@InstallIn(SingletonComponent::class)
interface UpdaterModule {
    @Binds fun source(impl: GitHubReleaseSource): ReleaseSource

    @Binds fun downloader(impl: OkHttpApkDownloader): ApkDownloader

    @Binds fun installer(impl: SystemApkInstaller): ApkInstaller

    @Binds fun store(impl: PrefsUpdateStore): UpdateStore
}

@Module
@InstallIn(SingletonComponent::class)
object UpdaterProvides {
    @Provides fun clock(): Clock = Clock { System.currentTimeMillis() }
}
