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
        val full = AppError(AppErrorCode.VIDEO_NOTES_ATTACHMENTS_FULL, false)
        assertEquals(full.code, AppErrorCode.fromValue("HR-ANNOT-004"))
        assertFalse(full.retryable)
        assertTrue(full.localizedSummary(AppLanguage.ZH).contains("移除"))
        assertTrue(full.localizedSummary(AppLanguage.EN).contains("Remove"))
    }
    @Test fun restored_draft_never_pins_a_fresh_point_and_both_survive_restart() = runBlocking {
        val store = VideoFeedbackStore(temporary.newFolder())
        store.write(key, VideoFeedbackDocument(draft = VideoNoteDraft(start = "0:01", text = "旧意见")))
        val vm = VideoFeedbackViewModel(store, key, this)
        waitFor { vm.state.loaded }
        assertEquals(VideoFeedbackScreen.TOOLS, vm.state.screen)
        vm.point(12_345, 60_000)
        assertEquals(12_345L, parseVideoNoteTime(vm.state.document.draft!!.start))
        assertEquals("旧意见", vm.state.document.parkedDrafts.single().text)
        vm.text("新意见"); vm.later()
        vm.point(24_678, 60_000); vm.text("第三条")
        waitFor { !vm.state.dirty }
        val restored = VideoFeedbackViewModel(store, key, this)
        waitFor { restored.state.loaded }
        assertEquals(VideoFeedbackScreen.TOOLS, restored.state.screen)
        assertEquals(3, restored.state.document.allDrafts.size)
        restored.resumeDraft(restored.state.document.parkedDrafts.first().draftKey)
        assertEquals("旧意见", restored.state.document.draft!!.text)
        assertEquals(VideoFeedbackScreen.EDITOR, restored.state.screen)
    }
    @Test fun range_marks_reject_bad_end_reset_cancel_and_resume_without_manual_time_input() = runBlocking {
        val store = VideoFeedbackStore(temporary.newFolder())
        val vm = VideoFeedbackViewModel(store, key, this)
        waitFor { vm.state.loaded }
        vm.markEnd(3_000, 60_000); assertNull(vm.state.document.draft)
        vm.markStart(10_123, 60_000)
        vm.markEnd(10_123, 60_000); assertEquals(VideoFeedbackScreen.SELECTING, vm.state.screen)
        vm.markEnd(9_000, 60_000); assertEquals(VideoFeedbackScreen.SELECTING, vm.state.screen)
        vm.point(15_000, 60_000); assertTrue(vm.state.selecting)
        vm.markStart(12_345, 60_000); assertEquals(12_345L, vm.state.selectionStart)
        vm.later(); waitFor { !vm.state.dirty }
        val restored = VideoFeedbackViewModel(store, key, this)
        waitFor { restored.state.loaded }
        assertEquals(VideoFeedbackScreen.TOOLS, restored.state.screen)
        restored.resumeDraft(restored.state.document.draft!!.draftKey)
        assertEquals(12_345L, restored.state.selectionStart)
        restored.markEnd(23_456, 60_000); assertTrue(restored.state.editing)
        restored.text("拉长片段")
        restored.reselect(); restored.markStart(14_000, 60_000); restored.markEnd(25_000, 60_000)
        assertEquals("拉长片段", restored.state.document.draft!!.text)
        assertTrue(restored.commit(60_000))
        assertEquals(14_000L, store.read(key).notes.single().startMs)
        assertEquals(25_000L, store.read(key).notes.single().endMs)
        restored.markStart(30_000, 60_000); restored.cancelSelection()
        assertNull(restored.state.document.draft); assertNull(restored.state.selectionStart)
    }
    @Test fun committing_waits_for_durable_write_and_failure_keeps_the_editor_until_retry() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val disk = VideoFeedbackStore(temporary.newFolder())
        var fail = true
        val storage = object : VideoFeedbackStorage {
            override suspend fun read(key: String) = disk.read(key)
            override suspend fun write(key: String, document: VideoFeedbackDocument) {
                if (document.draft == null && document.notes.isNotEmpty()) {
                    entered.complete(Unit); release.await()
                    if (fail) throw IOException("private path")
                }
                disk.write(key, document)
            }
        }
        val vm = VideoFeedbackViewModel(storage, key, this)
        waitFor { vm.state.loaded }; vm.point(1_234, 60_000); vm.text("保留输入")
        val commit = async { vm.commit(60_000) }
        entered.await()
        assertTrue(vm.state.committing); assertTrue(vm.state.editing)
        assertEquals("保留输入", vm.state.document.draft!!.text)
        assertTrue(vm.state.document.notes.isEmpty())
        assertFalse(vm.commit(60_000))
        release.complete(Unit); assertFalse(commit.await())
        assertTrue(vm.state.editing); assertTrue(vm.state.commitFailed)
        assertEquals("HR-ANNOT-001", vm.state.error!!.code.value)
        assertEquals("保留输入", disk.read(key).draft!!.text)
        fail = false; assertTrue(vm.commit(60_000))
        assertNull(vm.state.document.draft); assertEquals(VideoFeedbackScreen.TOOLS, vm.state.screen)
        assertEquals(1, disk.read(key).notes.size)
    }
    @Test fun retiming_point_retains_text_and_existing_edits_return_to_list() = runBlocking {
        val vm = VideoFeedbackViewModel(VideoFeedbackStore(temporary.newFolder()), key, this)
        waitFor { vm.state.loaded }
        vm.point(1_000, 60_000); vm.text("原意见"); vm.reselect()
        vm.point(5_678, 60_000)
        assertEquals("原意见", vm.state.document.draft!!.text)
        assertEquals(5_678L, parseVideoNoteTime(vm.state.document.draft!!.start))
        assertTrue(vm.commit(60_000))
        vm.editNote(vm.state.document.notes.single().id); vm.text("修订意见")
        assertTrue(vm.commit(60_000))
        assertEquals(VideoFeedbackScreen.LIST, vm.state.screen)
        assertEquals(1, vm.state.document.notes.size)
    }
    @Test fun old_json_is_compatible_and_parked_work_is_excluded_from_markdown() = runBlocking {
        val dir = temporary.newFolder()
        File(dir, "$key.json").writeText("""{"draft":{"start":"00:00:01.000","text":"旧草稿"}}""")
        val disk = VideoFeedbackStore(dir)
        val vm = VideoFeedbackViewModel(disk, key, this); waitFor { vm.state.loaded }
        vm.point(5_000, 60_000); vm.text("已完成"); assertTrue(vm.commit(60_000))
        val doc = disk.read(key)
        assertEquals("旧草稿", doc.parkedDrafts.single().text)
        val md = videoFeedbackMarkdown("片段", "source", "chat", 60_000, doc)
        assertTrue(md.contains("已完成")); assertFalse(md.contains("旧草稿"))
        vm.discardDraft(doc.parkedDrafts.single().draftKey); waitFor { !vm.state.dirty }
        assertTrue(disk.read(key).allDrafts.isEmpty())
    }

}
