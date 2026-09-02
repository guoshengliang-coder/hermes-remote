package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Server-injected system turns (background-task reports, model switches, crash resumes) ride
 * the wire as role=user messages so the agent sees them as context. They are NOT something the
 * user said, so the transcript renders them as a one-line timeline note instead of a user
 * bubble — matching the official desktop client's display_kind semantics. Notes that carry a
 * real body (delegation reports) expand into a collapsed monospace card on tap.
 */
data class TimelineNote(
    val glyph: String,
    val zh: String,
    val en: String,
    val expandable: Boolean,
    val hidden: Boolean = false,
)

/** True when the message must not be rendered at all (display_kind == "hidden"). */
fun isHiddenTimelineMessage(message: ChatMessage): Boolean =
    timelineNoteFor(message)?.hidden == true

// Model names carry dots (gpt-5.6-sol): capture up to " via …", the sentence end, or "]".
private val MODEL_SWITCH_NAME = Regex("changed to (\\S+?)(?: via |\\. |\\.]|$)")

/**
 * Classify a message as a timeline note, or null for a real conversation turn.
 *
 * display_kind (server marker) always wins; the prefix fallback below covers notices the
 * server injects WITHOUT a marker (verified against the production tui_gateway source:
 * background-process reports go through _run_prompt_submit with no display_kind). The
 * fallback only fires for role=user messages whose WHOLE text starts with the marker —
 * quoting these strings mid-conversation never matches.
 */
fun timelineNoteFor(message: ChatMessage): TimelineNote? {
    when (message.displayKind) {
        null, "" -> {}
        "hidden" -> return TimelineNote("", "", "", expandable = false, hidden = true)
        "async_delegation_complete" -> {
            val n = message.displayTaskCount
            val failed = message.displayFailedCount ?: 0
            val zhBase = if (n != null) "$n 个后台子任务已完成" else "后台子任务已完成"
            val enBase = if (n != null) "$n background task${if (n == 1) "" else "s"} finished" else "Background tasks finished"
            val zh = if (failed > 0) "$zhBase，$failed 个失败" else zhBase
            val en = if (failed > 0) "$enBase, $failed failed" else enBase
            return TimelineNote("⚙", zh, en, expandable = true)
        }
        "model_switch" -> {
            val model = MODEL_SWITCH_NAME.find(message.text)?.groupValues?.get(1)
            val suffix = model?.let { " · $it" }.orEmpty()
            return TimelineNote("⇄", "已切换模型$suffix", "Model switched$suffix", expandable = false)
        }
        "personality_switch" ->
            return TimelineNote("◐", "已切换人格", "Personality changed", expandable = false)
        "auto_continue" ->
            return TimelineNote("↻", "已从中断处继续", "Resumed interrupted turn", expandable = false)
        else ->
            // Forward compatibility: any future marker renders as a quiet note, never a bubble.
            return TimelineNote("ℹ", "系统备注", "System note", expandable = true)
    }
    if (message.role != Role.USER) return null
    val text = message.text
    return when {
        text.startsWith("[ASYNC DELEGATION") ->
            TimelineNote("⚙", "后台子任务已完成", "Background tasks finished", expandable = true)
        text.startsWith("[IMPORTANT: Background process") ->
            TimelineNote("⚙", "后台进程通报", "Background process report", expandable = true)
        else -> null
    }
}

private const val NOTE_MAX_BODY_HEIGHT = 230

@Composable
internal fun TimelineNoteRow(note: TimelineNote, message: ChatMessage) {
    if (note.hidden) return
    val language = LocalAppLanguage.current
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = if (note.expandable) MaterialTheme.colorScheme.outline.copy(alpha = 0.07f)
            else androidx.compose.ui.graphics.Color.Transparent,
            onClick = { if (note.expandable) expanded = !expanded },
            enabled = note.expandable,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (note.glyph.isNotEmpty()) {
                    Text(note.glyph, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) // l10n-allow: glyph
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    localized(language, note.zh, note.en),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (note.expandable) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (expanded) localized(language, "收起 ▴", "Hide ▴")
                        else localized(language, "展开 ▾", "Show ▾"),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                    )
                }
            }
        }
        if (expanded) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                onClick = { expanded = false },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = NOTE_MAX_BODY_HEIGHT.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}
