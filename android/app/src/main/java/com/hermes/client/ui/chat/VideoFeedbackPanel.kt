package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hermes.client.data.video.*
import com.hermes.client.ui.localization.*

/** Theme surface beside the black media surface. All content scrolls above export actions. */
@Composable
internal fun VideoFeedbackPanel(
    state: VideoFeedbackState, durationMs: Long, position: () -> Long,
    onDraft: (VideoNoteDraft?) -> Unit, onCommit: () -> Unit, onDelete: (String) -> Unit,
    onSeek: (Long) -> Unit, onRetry: () -> Unit, onSaveFile: () -> Unit,
    onAttach: () -> Unit, onClose: () -> Unit, busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val document = state.document
    var deleting by remember { mutableStateOf<String?>(null) }
    Surface(modifier.testTag("video-notes-panel")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .imePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(localized(language, "视频批注", "Video notes"), Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); onClose() }) { Text(localized(language, "返回视频", "Back to video")) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)) {
                item {
                    Text(when {
                        !state.loaded -> localized(language, "正在读取批注…", "Loading notes…")
                        state.saving -> localized(language, "正在保存到本机…", "Saving on this device…")
                        state.dirty -> localized(language, "有尚未保存的更改", "Some changes are not saved")
                        else -> localized(language, "已保存到本机 · 当前视频版本", "Saved on this device · This video version")
                    }, style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { error -> item {
                    Text(error.localizedMessage(language), color = MaterialTheme.colorScheme.error)
                    FlowRow {
                        if (error.retryable) TextButton(onClick = onRetry) { Text(localized(language, "重试", "Retry")) }
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                android.content.ClipData.newPlainText("Hermes diagnostics", error.sanitizedDiagnostic()))
                        }) { Text(localized(language, "复制诊断", "Copy diagnostics")) }
                    }
                } }
                if (state.loaded) {
                    val draft = document.draft
                    if (draft == null) {
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = durationMs > 0 && document.notes.size < VIDEO_NOTE_LIMIT,
                                    onClick = { onDraft(VideoNoteDraft(start = videoNoteTime(position()))) }) {
                                    Text(localized(language, "标记当前时间", "Note this time"))
                                }
                                OutlinedButton(enabled = durationMs > 0 && document.notes.size < VIDEO_NOTE_LIMIT,
                                    onClick = { onDraft(VideoNoteDraft(start = videoNoteTime(position()), range = true)) }) {
                                    Text(localized(language, "标记片段", "Note a range"))
                                }
                            }
                            if (document.notes.size >= VIDEO_NOTE_LIMIT) Text(
                                localized(language, "每个视频最多 500 条批注，可编辑或删除已有批注。", "Up to 500 notes per video. Edit or delete an existing note."),
                                style = MaterialTheme.typography.bodySmall)
                            if (durationMs <= 0) Text(localized(language, "视频时长就绪后可添加批注。", "Notes become available when the video duration is ready."))
                        }
                    } else {
                        item {
                            Text(localized(language, "时间格式：时:分:秒.毫秒（也支持 分:秒）", "Time: hh:mm:ss.mmm (mm:ss also works)"),
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = draft.start, onValueChange = { if (it.length <= 32) onDraft(draft.copy(start = it)) },
                                label = { Text(localized(language, "开始 / 时间点", "Start / point")) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) position() })
                            TextButton(onClick = { onDraft(draft.copy(start = videoNoteTime(position()))) }) {
                                Text(localized(language, "用当前播放位置设为开始", "Set start to current position"))
                            }
                            if (draft.range) {
                                OutlinedTextField(value = draft.end, onValueChange = { if (it.length <= 32) onDraft(draft.copy(end = it)) },
                                    label = { Text(localized(language, "结束", "End")) }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) position() })
                                TextButton(onClick = { onDraft(draft.copy(end = videoNoteTime(position()))) }) {
                                    Text(localized(language, "用当前播放位置设为结束", "Set end to current position"))
                                }
                            }
                            OutlinedTextField(value = draft.text, onValueChange = { if (it.length <= VIDEO_NOTE_TEXT_LIMIT) onDraft(draft.copy(text = it)) },
                                label = { Text(localized(language, "修改意见", "Feedback")) }, minLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("video-note-text").onFocusChanged { if (it.isFocused) position() })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onCommit(); focus.clearFocus(); keyboard?.hide() }) { Text(localized(language, "保存批注", "Save note")) }
                                TextButton(onClick = { onDraft(null); focus.clearFocus(); keyboard?.hide() }) { Text(localized(language, "取消编辑", "Cancel edit")) }
                            }
                        }
                    }
                    if (document.notes.isEmpty() && draft == null) item {
                        Text(localized(language, "暂停在需要修改的画面，添加第一条批注。", "Pause at a frame to add your first note."),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    items(document.notes.sortedBy { it.startMs }, key = { it.id }) { note ->
                        Column(Modifier.fillMaxWidth().testTag("video-note:${note.id}")) {
                            TextButton(onClick = { onSeek(note.startMs) }, contentPadding = PaddingValues(0.dp)) {
                                Text(videoNoteTime(note.startMs) + (note.endMs?.let { " – ${videoNoteTime(it)}" } ?: "")) // l10n-allow: media time.
                            }
                            Text(note.text, style = MaterialTheme.typography.bodyMedium)
                            FlowRow {
                                TextButton(enabled = draft == null, onClick = {
                                    position() // Pause before editing without changing the note's timestamp.
                                    onDraft(VideoNoteDraft(note.id, videoNoteTime(note.startMs), note.endMs?.let(::videoNoteTime).orEmpty(), note.endMs != null, note.text))
                                }) { Text(localized(language, "编辑", "Edit")) }
                                TextButton(enabled = draft == null, onClick = { deleting = note.id }) { Text(localized(language, "删除", "Delete")) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            if (document.draft != null && !keyboardVisible) Text(localized(language, "草稿自动保存；保存当前批注后可导出。", "Draft saved automatically. Save this note before exporting."),
                style = MaterialTheme.typography.bodySmall)
            if (!keyboardVisible) FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = state.loaded && document.notes.isNotEmpty() && document.draft == null && !busy
                OutlinedButton(enabled = enabled, onClick = onSaveFile) { Text(localized(language, "保存 .md", "Save .md")) }
                Button(enabled = enabled, onClick = onAttach) { Text(localized(language, "加入原会话", "Attach to chat")) }
            }
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(localized(language, "删除这条批注？", "Delete this note?")) },
        confirmButton = { TextButton(onClick = { onDelete(id); deleting = null }) { Text(localized(language, "删除", "Delete")) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(localized(language, "取消", "Cancel")) } }) }
}
