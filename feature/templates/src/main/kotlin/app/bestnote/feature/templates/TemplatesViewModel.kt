package app.bestnote.feature.templates

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.ai.vision.ChatTurn
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.core.fonts.FontRegistry
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.templates.DesignParseException
import app.bestnote.engine.templates.DesignWithAi
import app.bestnote.engine.templates.TemplateChangeItem
import app.bestnote.engine.templates.TemplateExchange
import app.bestnote.engine.templates.TemplateImportException
import app.bestnote.engine.templates.TemplateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One line of the Design-with-AI chat. [changes] is what the AI's answer changed in the template. */
data class ChatEntry(
    val fromUser: Boolean,
    val text: String,
    val changes: List<TemplateChangeItem> = emptyList(),
    val adjusted: Int = 0,
    val failure: ChatFailure? = null,
)

enum class ChatFailure { NoKey, Network, Unusable, Blocked, Limit }

/** A template being edited. [undo] holds earlier drafts made by the AI (one per answer). */
data class EditorState(
    val draft: NoteTemplate,
    val saved: NoteTemplate,
    val undo: List<NoteTemplate> = emptyList(),
    val chat: List<ChatEntry> = emptyList(),
    val busy: Boolean = false,
) {
    val dirty: Boolean get() = draft != saved
}

data class TemplatesUiState(
    val templates: List<NoteTemplate> = emptyList(),
    val editor: EditorState? = null,
    /** A built-in being looked at (read-only). */
    val viewing: NoteTemplate? = null,
    val message: TemplatesMessage? = null,
)

enum class TemplatesMessage { Imported, ImportNotATemplate, ImportTooLarge, ImportTooNew, Exported, Saved }

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TemplateStore,
    private val providers: AiProviders,
) : ViewModel() {
    private val registry = FontRegistry.get(context).also { it.start() }
    private val fontOptions = RegistryFontOptions(registry)
    private val state = MutableStateFlow(TemplatesUiState(templates = store.all()))
    val ui: StateFlow<TemplatesUiState> = state.asStateFlow()

    private fun reload() = state.update { it.copy(templates = store.all()) }

    fun consumeMessage() = state.update { it.copy(message = null) }

    fun create(name: String) {
        val t = store.create(name)
        reload()
        edit(t.id)
    }

    fun duplicate(id: String, thenEdit: Boolean = true) {
        val t = store.duplicate(id) ?: return
        reload()
        state.update { it.copy(viewing = null) }
        if (thenEdit) edit(t.id)
    }

    fun rename(id: String, name: String) {
        store.rename(id, name)
        reload()
    }

    fun delete(id: String) {
        store.delete(id)
        reload()
    }

    /** Opens [id]: a user template in the editor, a built-in read-only. */
    fun edit(id: String) {
        val t = store.find(id) ?: return
        if (t.builtIn) state.update { it.copy(viewing = t) } else state.update { it.copy(editor = EditorState(t, t)) }
    }

    fun closeViewing() = state.update { it.copy(viewing = null) }

    fun close() {
        state.update { it.copy(editor = null) }
        reload()
    }

    fun update(change: (NoteTemplate) -> NoteTemplate) = state.update { s ->
        val e = s.editor ?: return@update s
        s.copy(editor = e.copy(draft = change(e.draft)))
    }

    fun save() {
        val e = state.value.editor ?: return
        val saved = store.save(e.draft)
        state.update { it.copy(editor = e.copy(draft = saved, saved = saved), message = TemplatesMessage.Saved) }
        reload()
    }

    // --- files ---------------------------------------------------------------------------------

    fun exportText(id: String): String? = store.find(id)?.let(TemplateExchange::export)

    fun fileName(id: String): String = store.find(id)?.let(TemplateExchange::fileName) ?: "template${TemplateExchange.EXTENSION}"

    /** Writes an exported template to the file the user picked. */
    fun writeTo(uri: Uri, text: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream") }.isSuccess
            }
            if (ok) markExported()
        }
    }

    fun markExported() = state.update { it.copy(message = TemplatesMessage.Exported) }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(TemplateExchange.MAX_BYTES + 1) } ?: error("unreadable")
                    if (bytes.size > TemplateExchange.MAX_BYTES) throw TemplateImportException(TemplateImportException.Kind.TooLarge, "large")
                    store.import(String(bytes, Charsets.UTF_8))
                    TemplatesMessage.Imported
                }.getOrElse { e ->
                    when ((e as? TemplateImportException)?.kind) {
                        TemplateImportException.Kind.TooLarge -> TemplatesMessage.ImportTooLarge
                        TemplateImportException.Kind.TooNew -> TemplatesMessage.ImportTooNew
                        else -> TemplatesMessage.ImportNotATemplate
                    }
                }
            }
            reload()
            state.update { it.copy(message = msg) }
        }
    }

    // --- Design with AI -----------------------------------------------------------------------

    /** Sends [words] to the user's provider; the answer becomes the new draft (undoable) and the preview updates. */
    fun design(words: String) {
        val e = state.value.editor ?: return
        if (words.isBlank() || e.busy) return
        state.update { it.copy(editor = e.copy(chat = e.chat + ChatEntry(true, words.trim()), busy = true)) }
        viewModelScope.launch {
            val result = runCatching {
                if (!providers.isConfigured()) throw VisionError.MissingKey()
                val system = DesignWithAi.system(fontOptions)
                val turns = listOf(ChatTurn(true, DesignWithAi.userMessage(words, e.draft)))
                val answer = providers.create().completeText(system, turns, json = true)
                DesignWithAi.parse(answer, e.draft, fontOptions)
            }
            state.update { s ->
                val cur = s.editor ?: return@update s
                val next = result.fold(
                    onSuccess = { out ->
                        val reply = out.message.ifBlank { "" }
                        cur.copy(
                            draft = out.template,
                            undo = cur.undo + cur.draft,
                            chat = cur.chat + ChatEntry(false, reply, out.changes, out.adjusted.size),
                            busy = false,
                        )
                    },
                    onFailure = { err ->
                        val why = when (err) {
                            is VisionError.MissingKey, is VisionError.InvalidKey -> ChatFailure.NoKey
                            is VisionError.Network, is VisionError.ServiceUnavailable -> ChatFailure.Network
                            is VisionError.RateLimited -> ChatFailure.Limit
                            is VisionError.Blocked -> ChatFailure.Blocked
                            is DesignParseException, is VisionError -> ChatFailure.Unusable
                            else -> ChatFailure.Unusable
                        }
                        cur.copy(chat = cur.chat + ChatEntry(false, "", failure = why), busy = false)
                    },
                )
                s.copy(editor = next)
            }
        }
    }

    /** Takes back the last AI change. */
    fun undoAi() = state.update { s ->
        val e = s.editor ?: return@update s
        val prev = e.undo.lastOrNull() ?: return@update s
        s.copy(editor = e.copy(draft = prev, undo = e.undo.dropLast(1)))
    }
}
