package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.client.data.video.*
import com.hermes.client.ui.components.FeedbackBubbleIcon
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesMono

internal data class VideoFeedbackActions(
    val point: () -> Unit = {}, val start: () -> Unit = {}, val end: () -> Unit = {},
    val cancelSelection: () -> Unit = {}, val list: () -> Unit = {}, val drafts: () -> Unit = {},
    val back: () -> Unit = {}, val returnToVideo: () -> Unit = {}, val continueMarking: () -> Unit = {},
    val resumeDraft: (String) -> Unit = {}, val text: (String) -> Unit = {}, val commit: () -> Unit = {},
    val cancel: () -> Unit = {}, val later: () -> Unit = {}, val editNote: (String) -> Unit = {},
    val delete: (String) -> Unit = {}, val discardDraft: (String) -> Unit = {}, val seek: (Long) -> Unit = {},
    val retry: () -> Unit = {}, val saveFile: () -> Unit = {}, val attach: () -> Unit = {},
)

/** Separate watching, writing and reviewing surfaces; no timestamp text input. */
@Composable
internal fun VideoFeedbackPanel(
    state: VideoFeedbackState, durationMs: Long, positionMs: Long, canMark: Boolean,
    actions: VideoFeedbackActions, busy: Boolean = false, modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val document = state.document
    var deleting by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Surface(modifier.testTag("video-notes-panel")) {
        val body = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp)
        when {
            state.editing && document.draft != null -> VideoFeedbackEditor(state, actions, body.fillMaxSize())
            state.screen == VideoFeedbackScreen.DRAFTS -> Column(body.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized(language, "未完成草稿", "Unfinished drafts"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = actions.back) { Text(localized(language, "返回视频", "Back to video")) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item { VideoFeedbackStatus(state, actions.retry) }
                    items(document.allDrafts, key = { it.draftKey }) { draft ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("video-note-draft:${draft.draftKey}")) {
                            Text(draftTime(draft), fontFamily = HermesMono, style = MaterialTheme.typography.labelMedium)
                            Text(draft.text.ifBlank { localized(language, "尚未填写意见", "Feedback not written yet") }, style = MaterialTheme.typography.bodyMedium)
                            FlowRow {
                                TextButton(onClick = { actions.resumeDraft(draft.draftKey) }) { Text(localized(language, "继续填写", "Continue draft")) }
                                TextButton(onClick = { deleting = draft.draftKey to true }) { Text(localized(language, "删除草稿", "Delete draft")) }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (document.allDrafts.isEmpty()) item { Text(localized(language, "没有未完成草稿", "No unfinished drafts"), Modifier.padding(vertical = 16.dp)) }
                }
            }
            state.screen == VideoFeedbackScreen.LIST -> Column(body.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized(language, "批注汇总 · ${document.notes.size}", "Notes · ${document.notes.size}"),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = actions.returnToVideo) { Text(localized(language, "返回视频", "Back to video")) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("video-note-list")) {
                    item { VideoFeedbackStatus(state, actions.retry) }
                    items(document.notes.sortedBy { it.startMs }, key = { "note:${it.id}" }) { note ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("video-note:${note.id}")) {
                            TextButton(onClick = { actions.seek(note.startMs) }, contentPadding = PaddingValues(0.dp)) {
                                Text(referenceTime(note.startMs, note.endMs),
                                    fontFamily = HermesMono, style = MaterialTheme.typography.labelMedium)
                            }
                            Text(note.text, style = MaterialTheme.typography.bodyMedium)
                            FlowRow {
                                TextButton(onClick = { actions.editNote(note.id) }) { Text(localized(language, "编辑", "Edit")) }
                                TextButton(onClick = { deleting = note.id to false }) { Text(localized(language, "删除", "Delete")) }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (document.notes.isEmpty()) item { Text(
                        localized(language, "还没有批注，返回视频开始打点。", "No notes yet. Return to the video to mark a moment."),
                        Modifier.padding(vertical = 16.dp)) }
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    TextButton(onClick = actions.continueMarking, enabled = !busy) { Text(localized(language, "继续批注", "Continue annotating")) }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val enabled = state.loaded && document.notes.isNotEmpty() && !state.dirty && !state.saving && state.error == null && !busy
                        Button(enabled = enabled, onClick = actions.attach) { Text(localized(language, "加入原会话", "Attach to chat")) }
                        OutlinedButton(enabled = enabled, onClick = actions.saveFile) { Text(localized(language, "保存 .md", "Save .md")) }
                    }
                    Text(localized(language, "加入待发附件，由你检查后发送", "Review in chat before sending"), style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> Column(body.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized(language, "视频批注", "Video notes"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = state.loaded && document.notes.isNotEmpty() && !state.selecting && state.screen == VideoFeedbackScreen.TOOLS, onClick = actions.list) {
                        Text(localized(language, "查看批注（${document.notes.size}）", "View notes (${document.notes.size})"))
                    }
                }
                if (state.error != null || !state.loaded) VideoFeedbackStatus(state, actions.retry)
                val selecting = state.selectionStart
                val retimePoint = state.screen == VideoFeedbackScreen.RETIME_POINT
                val retimeRange = state.screen == VideoFeedbackScreen.RETIME_RANGE
                Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (selecting != null) {
                        Text(localized(language, "起点已标记", "Start marked"), style = MaterialTheme.typography.bodySmall)
                        Text(shortTime(selecting) + " → " + if (positionMs > selecting) shortTime(positionMs) else localized(language, "等待终点", "Awaiting end"),
                            color = MaterialTheme.colorScheme.primary, fontFamily = HermesMono, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = actions.cancelSelection, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(localized(language, "取消选段", "Cancel range"))
                        }
                    } else if (retimePoint || retimeRange) {
                        Text(localized(language, "意见已保留，请定位后重新打点。", "Feedback retained. Find the new position and mark it."),
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = actions.later, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(localized(language, "稍后填写", "Finish later")) }
                    } else Text(localized(language, "播放或拖动视频，在需要反馈的位置打点。", "Play or seek, then mark the moment you want to discuss."),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                }
                val mayCreate = document.workCount < VIDEO_NOTE_LIMIT
                val pointEnabled = canMark && state.loaded && !state.committing && selecting == null && !retimeRange && (mayCreate || retimePoint)
                val startEnabled = canMark && state.loaded && !state.committing && !retimePoint && (mayCreate || selecting != null || retimeRange)
                val endEnabled = canMark && selecting != null && positionMs > selecting && positionMs <= durationMs
                val point: @Composable (Modifier) -> Unit = { m -> MarkButton(localized(language, "单点批注", "Point note"), FeedbackBubbleIcon,
                    pointEnabled, actions.point, m.testTag("video-note-point")) }
                val start: @Composable (Modifier) -> Unit = { m -> MarkButton(if (selecting != null) localized(language, "重设起点", "Reset start") else localized(language, "起始点", "Start point"),
                    VideoRangeStartIcon, startEnabled, actions.start, m.testTag("video-note-start")) }
                val end: @Composable (Modifier) -> Unit = { m -> MarkButton(localized(language, "终止点", "End point"), VideoRangeEndIcon,
                    endEnabled, actions.end, m.testTag("video-note-end")) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (LocalDensity.current.fontScale > 1.15f || maxWidth < 320.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        point(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { start(Modifier.weight(1f)); end(Modifier.weight(1f)) }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { point(Modifier.weight(1f)); start(Modifier.weight(1f)); end(Modifier.weight(1f)) }
                }
                Text(when {
                    !canMark -> localized(language, "等待画面定位完成后可打点。", "Marking is available once the frame is ready.")
                    selecting != null && positionMs <= selecting -> localized(language, "请播放或拖到起点之后，再标记终点。", "Play or seek past the start before marking the end.")
                    selecting != null -> localized(language, "找到结束画面后，点终止点写片段意见。", "At the final frame, mark the end to write feedback.")
                    !mayCreate -> localized(language, "批注与新建草稿合计最多 500 条，请先删除已有内容。", "Up to 500 notes and new drafts. Delete existing work to add more.")
                    else -> localized(language, "单点直接写意见；起点和终点组成片段。", "A point opens feedback; start and end form a range.")
                }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (state.screen == VideoFeedbackScreen.TOOLS && document.allDrafts.isNotEmpty()) TextButton(
                    onClick = actions.drafts, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(localized(language, "未完成草稿 · ${document.allDrafts.size}　继续填写", "Unfinished drafts · ${document.allDrafts.size}　Continue"))
                }
            }
        }
    }
    deleting?.let { (id, draft) -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(if (draft) localized(language, "删除这份草稿？", "Delete this draft?") else localized(language, "删除这条批注？", "Delete this note?")) },
        text = { Text(if (draft) localized(language, "未提交的意见将被删除。", "The unfinished feedback will be deleted.")
            else localized(language, "这条批注及其未完成编辑将被删除。", "The note and its unfinished edits will be deleted.")) },
        confirmButton = { TextButton(onClick = { if (draft) actions.discardDraft(id) else actions.delete(id); deleting = null }) { Text(localized(language, "删除", "Delete")) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(localized(language, "取消", "Cancel")) } }) }
}

@Composable
private fun VideoFeedbackEditor(state: VideoFeedbackState, actions: VideoFeedbackActions, modifier: Modifier) {
    val language = LocalAppLanguage.current
    val draft = state.document.draft ?: return
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedOnEntry by rememberSaveable(draft.draftKey) { mutableStateOf(false) }
    LaunchedEffect(draft.draftKey) {
        if (!focusedOnEntry) { focusedOnEntry = true; focus.requestFocus(); keyboard?.show() }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when { draft.id != null -> localized(language, "编辑批注", "Edit note")
                draft.range -> localized(language, "片段批注", "Range note") else -> localized(language, "单点批注", "Point note") },
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        Text(draftTime(draft), fontFamily = HermesMono, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("video-note-time"))
        Text(if (draft.range) localized(language, "大致片段范围 · 已暂停", "Approximate range · Paused")
            else localized(language, "该时间附近的画面 · 已暂停", "Nearby footage · Paused"), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = draft.text, onValueChange = actions.text, enabled = !state.committing,
                label = { Text(localized(language, "修改意见", "Feedback")) }, minLines = 3,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("video-note-text"))
            VideoFeedbackStatus(state, when { state.cancelFailed -> actions.cancel; state.commitFailed -> actions.commit; else -> actions.retry })
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = actions.commit, enabled = draft.text.isNotBlank() && !state.committing,
                modifier = Modifier.fillMaxWidth().testTag("video-note-commit")) {
                Text(when { state.committing -> localized(language, "正在保存…", "Saving…")
                    draft.id != null -> localized(language, "保存修改", "Save changes") else -> localized(language, "提交批注", "Submit note") })
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = actions.later, enabled = !state.committing,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("video-note-later")) {
                    Text(localized(language, "稍后填写", "Finish later"))
                }
                TextButton(onClick = actions.cancel, enabled = !state.committing,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("video-note-cancel")) {
                    Text(localized(language, "取消返回", "Cancel and return"))
                }
            }
        }
    }
}

@Composable
private fun VideoFeedbackStatus(state: VideoFeedbackState, retry: () -> Unit) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    Text(when {
        !state.loaded -> localized(language, "正在读取批注…", "Loading notes…")
        state.saving || state.committing -> localized(language, "正在保存到本机…", "Saving on this device…")
        state.dirty -> localized(language, "有尚未保存的更改", "Some changes are not saved")
        state.editing || state.screen == VideoFeedbackScreen.DRAFTS -> localized(language, "草稿已保存到本机", "Draft saved on this device")
        else -> localized(language, "批注已保存到本机", "Notes saved on this device")
    }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    state.error?.let { error ->
        Text(error.localizedMessage(language), color = MaterialTheme.colorScheme.error)
        FlowRow {
            if (error.retryable) TextButton(enabled = !state.committing, onClick = retry) { Text(localized(language, "重试", "Retry")) }
            TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                    android.content.ClipData.newPlainText("Hermes diagnostics", error.sanitizedDiagnostic()))
            }) { Text(localized(language, "复制诊断", "Copy diagnostics")) }
        }
    }
}

private fun shortTime(value: Long) = videoNoteReferenceTime(value)
@Composable
internal fun referenceTime(start: Long, end: Long? = null): String {
    val language = LocalAppLanguage.current
    val a = shortTime(start)
    val b = end?.let(::shortTime)
    return when {
        b == null -> localized(language, "约 $a 附近", "Around $a")
        b == a -> localized(language, "约 $a 附近的片段", "Range near $a")
        else -> localized(language, "约 $a–$b", "Approx. $a–$b")
    }
}
@Composable
private fun draftTime(draft: VideoNoteDraft): String {
    val language = LocalAppLanguage.current
    val start = parseVideoNoteTime(draft.start) ?: return localized(language, "待重新打点", "Mark a new position")
    val end = if (draft.range) parseVideoNoteTime(draft.end) else null
    return if (draft.range && end == null) localized(language, "起点约 ${shortTime(start)} · 待选终点", "Start around ${shortTime(start)} · End not marked")
        else referenceTime(start, end)
}

@Composable
private fun MarkButton(label: String, icon: ImageVector, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier) {
    val content: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
    val m = modifier.heightIn(min = 72.dp)
    OutlinedButton(onClick, m, enabled, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(8.dp), content = content)
}

private fun rangeIcon(end: Boolean) = ImageVector.Builder(if (end) "VideoRangeEnd" else "VideoRangeStart", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        val x = if (end) 18f else 6f; val outer = if (end) 5f else 19f; val inner = if (end) 10f else 14f
        moveTo(x, 3f); lineTo(x, 21f); moveTo(x, 5f); lineTo(outer, 5f); lineTo(inner, 10f); lineTo(outer, 15f); lineTo(x, 15f)
    }
}.build()
private val VideoRangeStartIcon by lazy { rangeIcon(false) }
private val VideoRangeEndIcon by lazy { rangeIcon(true) }
