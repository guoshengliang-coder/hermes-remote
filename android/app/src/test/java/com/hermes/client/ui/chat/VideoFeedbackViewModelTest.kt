package com.hermes.client.ui.chat

import com.hermes.client.data.video.*
import com.hermes.client.data.error.*
import com.hermes.client.ui.localization.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class VideoFeedbackViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = videoFeedbackKey("account/mac/profile/session", "clip", "version")
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(1) }

    @Test fun drafts_commit_edits_and_deletes_are_durable_and_latest_edit_wins() = runBlocking {
        val store = VideoFeedbackStore(temporary.newFolder())
        val vm = VideoFeedbackViewModel(store, key, this)
        waitFor { vm.state.loaded }
        repeat(20) { vm.edit(VideoNoteDraft(start = "0:01.234", text = "反馈 $it")) }
        waitFor { !vm.state.dirty }
        assertEquals("反馈 19", VideoFeedbackViewModel(store, key, this).let { restored ->
            waitFor { restored.state.loaded }; restored.state.document.draft!!.text
        })
        assertTrue(vm.commit(5_000))
        waitFor { !vm.state.dirty }
        assertNull(store.read(key).draft)
        val note = vm.state.document.notes.single()
        vm.edit(VideoNoteDraft(note.id, "0:01.234", "0:03", true, "拉长这段"))
        assertTrue(vm.commit(5_000))
        waitFor { !vm.state.dirty }
        assertEquals(3_000L, store.read(key).notes.single().endMs)
        vm.delete(note.id)
        waitFor { !vm.state.dirty }
        assertTrue(store.read(key).notes.isEmpty())
    }
    @Test fun invalid_range_is_retained_for_correction() = runBlocking {
        val vm = VideoFeedbackViewModel(VideoFeedbackStore(temporary.newFolder()), key, this)
        waitFor { vm.state.loaded }
        val draft = VideoNoteDraft(start = "0:04", end = "0:03", range = true, text = "片段")
        vm.edit(draft)
        waitFor { !vm.state.dirty }
        assertFalse(vm.commit(5_000))
        assertEquals(draft, vm.state.document.draft)
        assertEquals(AppErrorCode.VIDEO_NOTES_INVALID, vm.state.error!!.code)
        assertFalse(vm.state.error!!.retryable)
    }
    @Test fun failed_load_never_allows_an_empty_overwrite() = runBlocking {
        val directory = temporary.newFolder()
        val file = File(directory, "$key.json").apply { writeText("corrupt") }
        val vm = VideoFeedbackViewModel(VideoFeedbackStore(directory), key, this)
        waitFor { vm.state.error != null }
        vm.edit(VideoNoteDraft(start = "0:01", text = "不能覆盖"))
        assertFalse(vm.state.loaded)
        assertEquals("corrupt", file.readText())
        file.writeText("{}")
        vm.retry()
        waitFor { vm.state.loaded }
    }
    @Test fun failed_save_retains_edits_and_retry_writes_the_latest_snapshot() = runBlocking {
        val directory = File(temporary.newFolder(), "notes")
        val vm = VideoFeedbackViewModel(VideoFeedbackStore(directory), key, this)
        waitFor { vm.state.loaded }
        directory.writeText("blocked")
        vm.edit(VideoNoteDraft(start = "0:01", text = "保留"))
        waitFor { vm.state.error != null }
        assertTrue(vm.state.dirty)
        assertEquals("保留", vm.state.document.draft!!.text)
        directory.delete()
        vm.retry()
        waitFor { !vm.state.dirty }
        assertEquals("保留", VideoFeedbackStore(directory).read(key).draft!!.text)
    }
    @Test fun failures_have_bilingual_codes_recovery_and_no_private_diagnostics() {
        for (export in listOf(false, true)) {
            val error = videoFeedbackError(IOException("token=private /Users/private notes"), export)
            assertEquals(if (export) "HR-ANNOT-002" else "HR-ANNOT-001", error.code.value)
            assertEquals(error.code, AppErrorCode.fromValue(error.code.value))
            assertTrue(error.retryable)
            for (language in AppLanguage.entries) assertTrue(error.localizedMessage(language).contains(error.code.value))
            assertNotEquals(error.localizedSummary(AppLanguage.ZH), error.localizedSummary(AppLanguage.EN))
            assertFalse(error.sanitizedDiagnostic().contains("private"))
        }
        val invalid = AppError(AppErrorCode.VIDEO_NOTES_INVALID, false)
        assertEquals(invalid.code, AppErrorCode.fromValue("HR-ANNOT-003"))
        assertNotEquals(invalid.localizedSummary(AppLanguage.ZH), invalid.localizedSummary(AppLanguage.EN))
    }
}
