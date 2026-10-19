package app.bestnote.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.feature.settings.data.Attachment
import app.bestnote.feature.settings.data.AttachmentReader
import app.bestnote.feature.settings.data.DraftText
import app.bestnote.feature.settings.data.FeatureRequest
import app.bestnote.feature.settings.data.FeatureRequestSharer
import app.bestnote.feature.settings.data.FeatureRequestStore
import app.bestnote.feature.settings.data.FileFeatureRequestStore
import app.bestnote.feature.settings.data.GitHubRequestSettings
import app.bestnote.feature.settings.data.IssueComment
import app.bestnote.feature.settings.data.PendingAttachment
import app.bestnote.feature.settings.data.ReadResult
import app.bestnote.feature.settings.data.RequestEdit
import app.bestnote.feature.settings.data.RequestKind
import app.bestnote.feature.settings.data.RequestPriority
import app.bestnote.feature.settings.data.RequestSink
import app.bestnote.feature.settings.data.RequestStatus
import app.bestnote.feature.settings.data.RequestSync
import app.bestnote.feature.settings.data.RequestSyncException
import app.bestnote.feature.settings.data.SyncProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** A file picked for the form, already shrunk or read. Identity equality on purpose. */
class DraftFile(val id: Int, val attachment: PendingAttachment) {
    val isImage get() = attachment.mime.startsWith("image/")
}

/** The form for a new request. */
data class RequestDraft(
    val kind: RequestKind = RequestKind.Feature,
    val priority: RequestPriority = RequestPriority.Normal,
    val title: String = "",
    val details: String = "",
    val files: List<DraftFile> = emptyList(),
) {
    val canSend: Boolean get() = title.isNotBlank()
}

/** The form for changing a request that already exists. */
data class RequestEditState(
    val number: Int,
    val kind: RequestKind,
    val priority: RequestPriority,
    val title: String,
    val details: String,
    /** What the request already has, minus what was taken off. */
    val kept: List<Attachment>,
    val removed: Set<String> = emptySet(),
    val added: List<DraftFile> = emptyList(),
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = title.isNotBlank() && !saving
    val fileCount: Int get() = kept.size - kept.count { it.file in removed } + added.size
}

enum class RequestFilter { All, Open, Done }

/** What the list shows after search and filter, with the counts for the tabs. */
data class RequestList(
    val open: List<FeatureRequest> = emptyList(),
    val done: List<FeatureRequest> = emptyList(),
    val total: Int = 0,
    val openTotal: Int = 0,
    val doneTotal: Int = 0,
)

/** The request opened in the detail view, with GitHub's replies when it was sent. */
data class RequestDetail(
    val number: Int,
    val comments: List<IssueComment> = emptyList(),
    val loadingComments: Boolean = false,
)

/** GitHub connection as the screen needs it. The token itself is never exposed, only its last 4 characters. */
data class GitHubUiState(
    val connected: Boolean = false,
    val tokenHint: String? = null,
    val repo: String = "",
    val busy: Boolean = false,
    /** What went wrong the last time we talked to GitHub; null when fine. */
    val problem: SyncProblem? = null,
)

enum class Notice { FileTooLarge, FileUnreadable, TooManyFiles, Updated, Deleted, DoneCleared }

sealed interface RequestEvent {
    /** The request was saved; [sent] tells whether it also reached GitHub. */
    data class Saved(val request: FeatureRequest, val sent: Boolean) : RequestEvent
    data class Share(val uri: Uri) : RequestEvent
    data object Connected : RequestEvent
    data class Message(val notice: Notice, val count: Int = 0) : RequestEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val store: FeatureRequestStore,
    private val sharer: FeatureRequestSharer,
    private val settings: GitHubRequestSettings,
    private val sink: RequestSink,
    private val reader: AttachmentReader,
) : ViewModel() {
    val requests: StateFlow<List<FeatureRequest>> = store.requests

    private val _draft = MutableStateFlow(store.readDraft()?.let { RequestDraft(it.kind, it.priority, it.title, it.details) } ?: RequestDraft())
    val draft: StateFlow<RequestDraft> = _draft.asStateFlow()

    private val _edit = MutableStateFlow<RequestEditState?>(null)
    val edit: StateFlow<RequestEditState?> = _edit.asStateFlow()

    private val _detail = MutableStateFlow<RequestDetail?>(null)
    val detail: StateFlow<RequestDetail?> = _detail.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(RequestFilter.All)
    val filter: StateFlow<RequestFilter> = _filter.asStateFlow()

    val list: StateFlow<RequestList> = combine(store.requests, _query, _filter) { all, query, filter ->
        val q = query.trim()
        val matches = all.filter { r ->
            q.isEmpty() || r.title.contains(q, ignoreCase = true) || r.details.contains(q, ignoreCase = true) || r.ref.contains(q, ignoreCase = true)
        }
        // Most urgent first, then newest.
        val order = compareByDescending<FeatureRequest> { it.priority.ordinal }.thenByDescending { it.number }
        RequestList(
            open = if (filter == RequestFilter.Done) emptyList() else matches.filter { it.status == RequestStatus.Open }.sortedWith(order),
            done = if (filter == RequestFilter.Open) emptyList() else matches.filter { it.status == RequestStatus.Done }.sortedByDescending { it.number },
            total = all.size,
            openTotal = all.count { it.status == RequestStatus.Open },
            doneTotal = all.count { it.status == RequestStatus.Done },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RequestList())

    private val _github = MutableStateFlow(GitHubUiState(connected = settings.tokenHint != null, tokenHint = settings.tokenHint))
    val github: StateFlow<GitHubUiState> = _github.asStateFlow()

    private val _events = Channel<RequestEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val syncLock = Mutex()
    private var nextFileId = 0

    init {
        viewModelScope.launch {
            _github.update { it.copy(repo = settings.repo()) }
            sync()
        }
        // A half-written request survives leaving the screen; files are not kept in the draft.
        viewModelScope.launch {
            _draft.map { DraftText(it.kind, it.priority, it.title, it.details) }.drop(1).debounce(DRAFT_SAVE_MS).collect { store.writeDraft(it) }
        }
    }

    // ---- new request

    fun setKind(kind: RequestKind) = _draft.update { it.copy(kind = kind) }
    fun setPriority(priority: RequestPriority) = _draft.update { it.copy(priority = priority) }
    fun setTitle(title: String) = _draft.update { it.copy(title = title) }
    fun setDetails(details: String) = _draft.update { it.copy(details = details) }

    fun addDraftFiles(uris: List<Uri>) {
        viewModelScope.launch {
            read(uris, room = { MAX_FILES - _draft.value.files.size }) { file -> _draft.update { it.copy(files = it.files + file) } }
        }
    }

    fun removeDraftFile(file: DraftFile) = _draft.update { it.copy(files = it.files - file) }

    fun send() {
        val draft = _draft.value
        if (!draft.canSend) return
        viewModelScope.launch {
            val saved = store.add(draft.kind, draft.title, draft.details, draft.priority, draft.files.map { it.attachment }) ?: return@launch
            // Keep the chosen type and priority so several requests of one kind can be added quickly.
            _draft.value = RequestDraft(kind = draft.kind, priority = draft.priority)
            store.writeDraft(null)
            val problem = sync()
            val sent = requests.value.firstOrNull { it.number == saved.number }?.issueNumber != null
            _events.send(RequestEvent.Saved(saved, sent && problem == null))
        }
    }

    // ---- edit

    fun startEdit(request: FeatureRequest) {
        _edit.value = RequestEditState(
            number = request.number,
            kind = request.kind,
            priority = request.priority,
            title = request.title,
            details = request.details,
            kept = request.attachments,
        )
    }

    fun cancelEdit() {
        _edit.value = null
    }

    fun editKind(kind: RequestKind) = _edit.update { it?.copy(kind = kind) }
    fun editPriority(priority: RequestPriority) = _edit.update { it?.copy(priority = priority) }
    fun editTitle(title: String) = _edit.update { it?.copy(title = title) }
    fun editDetails(details: String) = _edit.update { it?.copy(details = details) }

    fun addEditFiles(uris: List<Uri>) {
        viewModelScope.launch {
            read(uris, room = { MAX_FILES - (_edit.value?.fileCount ?: MAX_FILES) }) { file -> _edit.update { it?.copy(added = it.added + file) } }
        }
    }

    fun removeEditExisting(attachment: Attachment) = _edit.update { it?.copy(removed = it.removed + attachment.file) }
    fun restoreEditExisting(attachment: Attachment) = _edit.update { it?.copy(removed = it.removed - attachment.file) }
    fun removeEditAdded(file: DraftFile) = _edit.update { it?.copy(added = it.added - file) }

    fun saveEdit() {
        val state = _edit.value?.takeIf { it.canSave } ?: return
        _edit.value = state.copy(saving = true)
        viewModelScope.launch {
            val updated = store.update(
                state.number,
                RequestEdit(state.kind, state.priority, state.title, state.details, state.added.map { it.attachment }, state.removed),
            )
            _edit.value = null
            if (updated != null) {
                _events.send(RequestEvent.Message(Notice.Updated))
                sync()
            }
        }
    }

    // ---- detail

    fun openDetail(request: FeatureRequest) {
        _detail.value = RequestDetail(request.number, loadingComments = request.issueNumber != null)
        val issue = request.issueNumber ?: return
        viewModelScope.launch {
            val comments = if (settings.token() == null) {
                emptyList()
            } else {
                try {
                    sink.comments(issue)
                } catch (e: RequestSyncException) {
                    emptyList()
                }
            }
            _detail.update { if (it?.number == request.number) it.copy(comments = comments, loadingComments = false) else it }
        }
    }

    fun closeDetail() {
        _detail.value = null
    }

    // ---- list

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setFilter(filter: RequestFilter) {
        _filter.value = filter
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

    /** Deletes here; with [closeIssue] the issue is also closed as not planned, since GitHub can't delete issues. */
    fun delete(request: FeatureRequest, closeIssue: Boolean) {
        viewModelScope.launch {
            if (_detail.value?.number == request.number) _detail.value = null
            if (_edit.value?.number == request.number) _edit.value = null
            store.delete(request.number)
            if (closeIssue) discard(request)
            _events.send(RequestEvent.Message(Notice.Deleted))
        }
    }

    /** Removes every finished request; their issues are already closed. */
    fun clearDone() {
        viewModelScope.launch {
            val gone = store.deleteDone()
            if (_detail.value?.number in gone.map { it.number }) _detail.value = null
            _events.send(RequestEvent.Message(Notice.DoneCleared, gone.size))
        }
    }

    private suspend fun discard(request: FeatureRequest) {
        val issue = request.issueNumber ?: return
        if (settings.token() == null) return
        try {
            sink.discard(issue)
        } catch (e: RequestSyncException) {
            _github.update { it.copy(problem = e.problem) }
        }
    }

    // ---- sharing and files

    fun share(only: Int? = null) {
        viewModelScope.launch { _events.send(RequestEvent.Share(sharer.export(only))) }
    }

    fun uriFor(attachment: Attachment): Uri = sharer.uriFor(attachment)

    /** The stored file, for thumbnails. */
    fun fileOf(attachment: Attachment) = store.file(attachment.file)

    // ---- GitHub

    /** Sends whatever is still local, pushes edits and pulls closed/open state back. No-op without a token. */
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

    /** Reads each picked file, up to what [room] still allows, and reports the ones that can't be used. */
    private suspend fun read(uris: List<Uri>, room: () -> Int, accept: (DraftFile) -> Unit) {
        for (uri in uris) {
            if (room() <= 0) {
                _events.send(RequestEvent.Message(Notice.TooManyFiles, MAX_FILES))
                return
            }
            when (val result = reader.read(uri)) {
                is ReadResult.Ok -> accept(DraftFile(nextFileId++, result.attachment))
                ReadResult.TooLarge -> _events.send(RequestEvent.Message(Notice.FileTooLarge, MAX_FILE_MB))
                ReadResult.Unreadable -> _events.send(RequestEvent.Message(Notice.FileUnreadable))
            }
        }
    }

    companion object {
        const val MAX_FILES = FileFeatureRequestStore.MAX_FILES
        const val MAX_FILE_MB = FileFeatureRequestStore.MAX_FILE_BYTES / (1024 * 1024)
        private const val DRAFT_SAVE_MS = 400L
    }
}
