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

internal data class VideoFeedbackState(
    val document: VideoFeedbackDocument = VideoFeedbackDocument(), val loaded: Boolean = false,
    val saving: Boolean = false, val dirty: Boolean = false, val error: AppError? = null,
)

/** Single writer coalesces keystrokes; closing the viewer doesn't cancel a pending disk commit. */
internal class VideoFeedbackViewModel(private val store: VideoFeedbackStore, private val key: String,
    private val scopeOverride: CoroutineScope? = null) : ViewModel() {
    var state by mutableStateOf(VideoFeedbackState())
        private set
    private val scope get() = scopeOverride ?: viewModelScope
    private var revision = 0L
    private var saved = 0L
    private var job: Job? = null
    init { retry() }

    fun retry() {
        if (job?.isActive == true) return
        if (state.loaded) { persist(); return }
        job = scope.launch {
            state = state.copy(error = null)
            try { state = VideoFeedbackState(document = store.read(key), loaded = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = state.copy(error = videoFeedbackError(e)) }
        }
    }
    private fun change(document: VideoFeedbackDocument) {
        if (!state.loaded) return
        revision++
        state = state.copy(document = document, dirty = true, error = null)
        persist()
    }
    private fun persist() {
        if (job?.isActive == true) return
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
    fun edit(draft: VideoNoteDraft?) = change(state.document.copy(draft = draft))
    fun delete(id: String) = change(state.document.copy(notes = state.document.notes.filterNot { it.id == id }))
    fun commit(duration: Long): Boolean {
        val draft = state.document.draft ?: return false
        val start = parseVideoNoteTime(draft.start)
        val end = if (draft.range) parseVideoNoteTime(draft.end) else null
        val note = start?.let { VideoNote(draft.id ?: java.util.UUID.randomUUID().toString(), it, end, draft.text) }
        if (note == null || draft.range && end == null || !note.valid(duration) ||
            draft.id == null && state.document.notes.size >= VIDEO_NOTE_LIMIT) {
            state = state.copy(error = AppError(AppErrorCode.VIDEO_NOTES_INVALID, false, stage = "video_note_validation"))
            return false
        }
        change(state.document.copy(notes = state.document.notes.filterNot { it.id == note.id } + note, draft = null))
        return true
    }
}
