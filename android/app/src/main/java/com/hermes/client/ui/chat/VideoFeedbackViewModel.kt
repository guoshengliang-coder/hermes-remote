package com.hermes.client.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.error.videoFeedbackError
import com.hermes.client.data.video.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class VideoFeedbackScreen { TOOLS, SELECTING, EDITOR, LIST, RETIME_POINT, RETIME_RANGE }
internal data class VideoFeedbackState(
    val document: VideoFeedbackDocument = VideoFeedbackDocument(), val loaded: Boolean = false,
    val saving: Boolean = false, val dirty: Boolean = false, val error: AppError? = null,
    val screen: VideoFeedbackScreen = VideoFeedbackScreen.TOOLS, val committing: Boolean = false,
    val commitFailed: Boolean = false,
) {
    val selecting get() = screen == VideoFeedbackScreen.SELECTING
    val selectionStart get() = if (selecting) document.draft?.start?.let(::parseVideoNoteTime) else null
    val editing get() = screen == VideoFeedbackScreen.EDITOR
}

/** The screen is transient: restored work is offered separately, never forced over a new mark. */
internal class VideoFeedbackViewModel(private val store: VideoFeedbackStorage, private val key: String,
    private val scopeOverride: CoroutineScope? = null) : ViewModel() {
    var state by mutableStateOf(VideoFeedbackState())
        private set
    private val scope get() = scopeOverride ?: viewModelScope
    private var revision = 0L
    private var saved = 0L
    private var job: Job? = null
    init { retry() }

    fun retry() {
        if (job?.isActive == true || state.committing) return
        if (state.loaded) { persist(); return }
        job = scope.launch {
            state = state.copy(error = null)
            try { state = VideoFeedbackState(document = store.read(key), loaded = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = state.copy(error = videoFeedbackError(e)) }
        }
    }
    private fun change(document: VideoFeedbackDocument, screen: VideoFeedbackScreen = state.screen) {
        if (!state.loaded || state.committing) return
        revision++
        state = state.copy(document = document, screen = screen, dirty = true, error = null, commitFailed = false)
        persist()
    }
    private fun persist() {
        if (job?.isActive == true || state.committing) return
        job = scope.launch {
            state = state.copy(saving = true, error = null)
            try {
                while (saved < revision) {
                    val target = revision
                    store.write(key, state.document)
                    saved = target
                }
                state = state.copy(dirty = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = state.copy(error = videoFeedbackError(e)) }
            finally { state = state.copy(saving = false) }
        }
    }
    private fun parked(): VideoFeedbackDocument = state.document.let { doc ->
        doc.draft?.let { doc.copy(draft = null, parkedDrafts = doc.parkedDrafts + it) } ?: doc
    }
    private fun validPosition(position: Long, duration: Long) = state.loaded && !state.committing && duration > 0 && position in 0..duration
    private fun canCreate() = state.document.workCount < VIDEO_NOTE_LIMIT
    fun point(position: Long, duration: Long) {
        if (!validPosition(position, duration)) return
        val retiming = state.screen == VideoFeedbackScreen.RETIME_POINT
        if (!retiming && (state.screen != VideoFeedbackScreen.TOOLS || !canCreate())) return
        val doc = if (retiming) state.document else parked()
        val draft = if (retiming) doc.draft?.copy(start = videoNoteTime(position), end = "", range = false) else VideoNoteDraft(start = videoNoteTime(position))
        change(doc.copy(draft = draft), VideoFeedbackScreen.EDITOR)
    }
    fun markStart(position: Long, duration: Long) {
        if (!validPosition(position, duration)) return
        val replacing = state.selecting || state.screen == VideoFeedbackScreen.RETIME_RANGE
        if (!replacing && (state.screen != VideoFeedbackScreen.TOOLS || !canCreate())) return
        val doc = if (replacing) state.document else parked()
        val draft = if (replacing) doc.draft?.copy(start = videoNoteTime(position), end = "", range = true)
            else VideoNoteDraft(start = videoNoteTime(position), range = true)
        change(doc.copy(draft = draft), VideoFeedbackScreen.SELECTING)
    }
    fun markEnd(position: Long, duration: Long) {
        val start = state.selectionStart ?: return
        if (!validPosition(position, duration) || position <= start) return
        change(state.document.copy(draft = state.document.draft?.copy(end = videoNoteTime(position))), VideoFeedbackScreen.EDITOR)
    }
    fun cancelSelection() {
        if (state.committing) return
        // Explicit cancellation only discards an empty new selection. Retimed feedback survives.
        val draft = state.document.draft
        val keep = draft != null && (draft.text.isNotBlank() || draft.id != null)
        change(if (keep) parked() else state.document.copy(draft = null), VideoFeedbackScreen.TOOLS)
    }
    fun edit(draft: VideoNoteDraft?) = change(state.document.copy(draft = draft))
    fun text(value: String) { if (value.length <= VIDEO_NOTE_TEXT_LIMIT) state.document.draft?.let { edit(it.copy(text = value)) } }
    fun later() { if (!state.committing) state = state.copy(screen = VideoFeedbackScreen.TOOLS) }
    fun showList() { if (state.loaded && !state.committing && state.screen == VideoFeedbackScreen.TOOLS) state = state.copy(screen = VideoFeedbackScreen.LIST) }
    fun resumeDraft(draftKey: String) {
        if (state.committing) return
        val draft = state.document.allDrafts.find { it.draftKey == draftKey } ?: return
        val doc = if (state.document.draft?.draftKey == draftKey) state.document else parked().let {
            it.copy(draft = draft, parkedDrafts = it.parkedDrafts.filterNot { d -> d.draftKey == draftKey })
        }
        val start = parseVideoNoteTime(draft.start)
        val end = parseVideoNoteTime(draft.end)
        val screen = when {
            start == null -> if (draft.range) VideoFeedbackScreen.RETIME_RANGE else VideoFeedbackScreen.RETIME_POINT
            draft.range && (end == null || end <= start) -> VideoFeedbackScreen.SELECTING
            else -> VideoFeedbackScreen.EDITOR
        }
        change(doc, screen)
    }
    fun reselect() {
        if (state.committing) return
        state.document.draft?.let { state = state.copy(screen = if (it.range) VideoFeedbackScreen.RETIME_RANGE else VideoFeedbackScreen.RETIME_POINT) }
    }
    fun editNote(id: String) {
        if (state.committing) return
        state.document.allDrafts.find { it.id == id }?.let { resumeDraft(it.draftKey); return }
        val note = state.document.notes.find { it.id == id } ?: return
        change(parked().copy(draft = VideoNoteDraft(note.id, videoNoteTime(note.startMs),
            note.endMs?.let(::videoNoteTime).orEmpty(), note.endMs != null, note.text)), VideoFeedbackScreen.EDITOR)
    }
    fun delete(id: String) = change(state.document.copy(notes = state.document.notes.filterNot { it.id == id },
        draft = state.document.draft?.takeUnless { it.id == id }, parkedDrafts = state.document.parkedDrafts.filterNot { it.id == id }))
    fun discardDraft(key: String) = change(state.document.copy(draft = state.document.draft?.takeUnless { it.draftKey == key },
        parkedDrafts = state.document.parkedDrafts.filterNot { it.draftKey == key }))

    /** Return success only after the candidate is on disk; failure keeps the original editor. */
    fun submit(duration: Long, result: (Boolean) -> Unit) = scope.launch { result(commit(duration)) }

    suspend fun commit(duration: Long): Boolean {
        if (!state.loaded || state.committing) return false
        val draft = state.document.draft ?: return false
        val start = parseVideoNoteTime(draft.start)
        val end = if (draft.range) parseVideoNoteTime(draft.end) else null
        val note = start?.let { VideoNote(draft.id ?: java.util.UUID.randomUUID().toString(), it, end, draft.text.trim()) }
        if (note == null || draft.range && end == null || !note.valid(duration)) {
            state = state.copy(error = AppError(AppErrorCode.VIDEO_NOTES_INVALID, false, stage = "video_note_validation"))
            return false
        }
        val candidate = state.document.copy(notes = state.document.notes.filterNot { it.id == note.id } + note, draft = null)
        state = state.copy(committing = true, error = null, commitFailed = false)
        try {
            job?.join()
            store.write(key, candidate)
            revision++; saved = revision
            state = state.copy(document = candidate, dirty = false, error = null,
                screen = if (draft.id == null) VideoFeedbackScreen.TOOLS else VideoFeedbackScreen.LIST)
            return true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state = state.copy(error = videoFeedbackError(e), commitFailed = true); return false }
        finally { state = state.copy(committing = false) }
    }
}
