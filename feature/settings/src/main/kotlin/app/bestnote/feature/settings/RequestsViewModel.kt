package app.bestnote.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.feature.settings.data.FeatureRequest
import app.bestnote.feature.settings.data.FeatureRequestSharer
import app.bestnote.feature.settings.data.FeatureRequestStore
import app.bestnote.feature.settings.data.GitHubRequestSettings
import app.bestnote.feature.settings.data.ImageCompressor
import app.bestnote.feature.settings.data.RequestKind
import app.bestnote.feature.settings.data.RequestStatus
import app.bestnote.feature.settings.data.RequestSink
import app.bestnote.feature.settings.data.RequestSync
import app.bestnote.feature.settings.data.RequestSyncException
import app.bestnote.feature.settings.data.SyncProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** A screenshot attached to the form, already compressed. Identity equality on purpose. */
class DraftImage(val id: Int, val bytes: ByteArray)

/** The form on top of the Requests screen. */
data class RequestDraft(
    val kind: RequestKind = RequestKind.Feature,
    val title: String = "",
    val details: String = "",
    val images: List<DraftImage> = emptyList(),
) {
    val canSend: Boolean get() = title.isNotBlank()
}

/** GitHub connection as the screen needs it. The token itself is never exposed, only its last 4 characters. */
data class GitHubUiState(
    val connected: Boolean = false,
    val tokenHint: String? = null,
    val repo: String = "",
    val busy: Boolean = false,
    /** What went wrong the last time we talked to GitHub; null when fine. */
    val problem: SyncProblem? = null,
)

sealed interface RequestEvent {
    /** The request was saved; [sent] tells whether it also reached GitHub. */
    data class Saved(val request: FeatureRequest, val sent: Boolean) : RequestEvent
    data class Share(val uri: Uri) : RequestEvent
    data object Connected : RequestEvent
}

@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val store: FeatureRequestStore,
    private val sharer: FeatureRequestSharer,
    private val settings: GitHubRequestSettings,
    private val sink: RequestSink,
    private val compressor: ImageCompressor,
) : ViewModel() {
    val requests: StateFlow<List<FeatureRequest>> = store.requests

    private val _draft = MutableStateFlow(RequestDraft())
    val draft: StateFlow<RequestDraft> = _draft.asStateFlow()

    private val _github = MutableStateFlow(GitHubUiState(connected = settings.tokenHint != null, tokenHint = settings.tokenHint))
    val github: StateFlow<GitHubUiState> = _github.asStateFlow()

    private val _events = Channel<RequestEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val syncLock = Mutex()
    private var nextImageId = 0

    init {
        viewModelScope.launch {
            _github.update { it.copy(repo = settings.repo()) }
            sync()
        }
    }

    fun setKind(kind: RequestKind) = _draft.update { it.copy(kind = kind) }
    fun setTitle(title: String) = _draft.update { it.copy(title = title) }
    fun setDetails(details: String) = _draft.update { it.copy(details = details) }

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                if (_draft.value.images.size >= MAX_IMAGES) break
                val bytes = compressor.compress(uri) ?: continue
                _draft.update { it.copy(images = it.images + DraftImage(nextImageId++, bytes)) }
            }
        }
    }

    fun removeImage(image: DraftImage) = _draft.update { it.copy(images = it.images - image) }

    fun send() {
        val draft = _draft.value
        if (!draft.canSend) return
        viewModelScope.launch {
            val saved = store.add(draft.kind, draft.title, draft.details, draft.images.map { it.bytes }) ?: return@launch
            // Keep the chosen type so several requests of one kind can be added quickly.
            _draft.value = RequestDraft(kind = draft.kind)
            val problem = sync()
            val sent = requests.value.firstOrNull { it.number == saved.number }?.issueNumber != null
            _events.send(RequestEvent.Saved(saved, sent && problem == null))
        }
    }

    /** Sends whatever is still local and pulls closed/open state back. No-op without a token. */
    fun retry() {
        viewModelScope.launch { sync() }
    }

    private suspend fun sync(): SyncProblem? = syncLock.withLock {
        if (settings.token() == null) return null
        _github.update { it.copy(busy = true) }
        val result = try {
            RequestSync(store, sink).run()
        } finally {
            _github.update { it.copy(busy = false) }
        }
        _github.update { it.copy(problem = result.problem) }
        result.problem
    }

    /** Validates the token against the repo before keeping it. */
    fun connect(token: String, repo: String) {
        if (token.isBlank()) return
        viewModelScope.launch {
            _github.update { it.copy(busy = true, problem = null) }
            val previousRepo = settings.repo()
            settings.save(token, repo)
            try {
                sink.check()
            } catch (e: RequestSyncException) {
                settings.clear()
                _github.update { it.copy(busy = false, problem = e.problem, repo = previousRepo) }
                return@launch
            }
            _github.value = GitHubUiState(connected = true, tokenHint = settings.tokenHint, repo = settings.repo())
            _events.send(RequestEvent.Connected)
            sync()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            settings.clear()
            _github.update { GitHubUiState(repo = settings.repo()) }
        }
    }

    fun toggleDone(request: FeatureRequest) {
        val done = request.status != RequestStatus.Done
        viewModelScope.launch {
            store.setStatus(request.number, if (done) RequestStatus.Done else RequestStatus.Open)
            val issue = request.issueNumber
            // Keep GitHub in step; if it fails the next sync takes GitHub's state, so say so quietly.
            if (issue != null && settings.token() != null) {
                try {
                    sink.setClosed(issue, closed = done)
                } catch (e: RequestSyncException) {
                    _github.update { it.copy(problem = e.problem) }
                }
            }
        }
    }

    fun delete(request: FeatureRequest) {
        viewModelScope.launch { store.delete(request.number) }
    }

    fun share() {
        viewModelScope.launch { _events.send(RequestEvent.Share(sharer.export())) }
    }

    /** Thumbnails of a stored request, for the list. */
    fun imageFile(name: String) = store.imageFile(name)

    companion object {
        const val MAX_IMAGES = 4
    }
}
