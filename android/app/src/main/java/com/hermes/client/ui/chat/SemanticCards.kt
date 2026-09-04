package com.hermes.client.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.TodoItem
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.LocalToolCallTechnical
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// ---------------------------------------------------------------------------
// Payload metadata: recognize the common command-execution shape so the card
// can render `$ command` + terminal output instead of a raw JSON blob. Any
// payload this cannot parse falls back to the generic rendering — semantic
// cards must never make an unknown tool WORSE.
// ---------------------------------------------------------------------------

internal data class ToolPayloadMeta(
    val command: String?,
    val exitCode: Int?,
    val durationMs: Long?,
    val outputBody: String?,
    val todos: List<TodoItem> = emptyList(),
)

private val metaJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseToolPayloadMeta(raw: String): ToolPayloadMeta? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return null
    val obj = runCatching { metaJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
        ?: return null
    fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull {
        (obj[it] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
    }
    fun prim(vararg keys: String): JsonPrimitive? = keys.firstNotNullOfOrNull { obj[it] as? JsonPrimitive }
    val command = str("command", "cmd")
    val outputBody = str("output", "stdout", "result_text")
    val exitCode = prim("exit_code", "exitCode")?.intOrNull
    val durationMs = prim("duration_ms", "durationMs")?.longOrNull
    val todos = (obj["todos"] as? JsonArray)?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val content = (item["content"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            ?: return@mapNotNull null
        val status = (item["status"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "pending"
        TodoItem(content, status)
    }.orEmpty()
    // Only claim the shape when it actually carries something semantic.
    if (command == null && exitCode == null && outputBody == null && todos.isEmpty()) return null
    return ToolPayloadMeta(command, exitCode, durationMs, outputBody, todos)
}

/**
 * Typewriter pacing: how many characters of the received text to reveal on this tick. Network
 * deltas arrive in bursts; revealing them verbatim shoves several lines into the pinned viewport
 * at once — the residual streaming jitter. A floor keeps the reveal moving at a readable pace and
 * the backlog-proportional term catches up while [maxStep] remains a hard per-frame visual cap.
 */
internal fun nextRevealCount(
    current: Int,
    target: Int,
    minStep: Int = 8,
    catchUpDivisor: Int = 4,
    maxStep: Int = 64,
): Int {
    if (target <= current) return target
    val backlog = target - current
    // Never fast-forward a reconnect-sized backlog in one frame. The former >1500-character snap
    // was indistinguishable from the whole screen flashing and made completion appear to be the
    // first time the transcript reached the bottom. maxStep is now a hard visual invariant.
    val step = (backlog / catchUpDivisor).coerceIn(minStep, maxStep)
    return minOf(target, current + step)
}

/** Never cut a UTF-16 surrogate pair in half; back off one unit when the cut would. */
internal fun surrogateSafeCut(text: String, index: Int): Int {
    if (index <= 0 || index >= text.length) return index.coerceIn(0, text.length)
    return if (Character.isLowSurrogate(text[index])) index - 1 else index
}

internal fun formatToolDuration(durationMs: Long): String =
    if (durationMs < 1000) "${durationMs}ms" else "%.1fs".format(durationMs / 1000f)

// ---------------------------------------------------------------------------
// Running-status line: what a still-streaming turn is doing RIGHT NOW. The
// old indicator vanished as soon as the first text or tool arrived, leaving
// long turns looking finished while the agent was still mid-run.
// ---------------------------------------------------------------------------

internal sealed interface RunningStatus {
    data class Tool(val label: String) : RunningStatus
    data class Thinking(val preview: String) : RunningStatus
    data object Generating : RunningStatus
}

/** Max characters of a thinking line kept for the one-line preview; the TAIL is what matters. */
private const val THINKING_PREVIEW_CHARS = 24

/** "12秒" / "1分24秒" (or "12s" / "1m24s"): live elapsed time for the running-status line. */
internal fun formatElapsedTime(elapsedMs: Long, zh: Boolean): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes <= 0 -> if (zh) "${seconds}秒" else "${seconds}s"
        else -> if (zh) "${minutes}分${seconds}秒" else "${minutes}m${seconds}s"
    }
}

internal fun runningStatusFor(message: ChatMessage): RunningStatus {
    val tool = message.tools.lastOrNull { it.status == ToolStatus.RUNNING }
    if (tool != null) {
        val label = tool.command?.takeIf { it.isNotBlank() } ?: tool.name
        return RunningStatus.Tool(label.lineSequence().first().take(64))
    }
    if (message.text.isBlank() && message.thinking.isNotBlank()) {
        val line = message.thinking.trimEnd().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isNotEmpty()) {
            val truncated = line.length > THINKING_PREVIEW_CHARS
            val cut = surrogateSafeCut(line, line.length - THINKING_PREVIEW_CHARS)
            return RunningStatus.Thinking(if (truncated) "…" + line.substring(cut) else line)
        }
    }
    return RunningStatus.Generating
}

// ---------------------------------------------------------------------------
// Table export: markdown table source -> tab-separated text, so a copied table
// pastes straight into Excel / Sheets / Feishu as real cells.
// ---------------------------------------------------------------------------

private val tableSeparatorCell = Regex(":?-{2,}:?")

/** Column count of a markdown table (from its header row); 0 when no row parses. */
internal fun markdownTableColumnCount(raw: String): Int =
    raw.trim().lineSequence()
        .firstOrNull { it.contains('|') }
        ?.trim()?.removePrefix("|")?.removeSuffix("|")
        ?.split("|")?.size ?: 0

internal fun markdownTableToTsv(raw: String): String =
    raw.trim().lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.contains('|')) return@mapNotNull null
        val cells = trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
        // Drop the |---|:---:| alignment row.
        if (cells.isNotEmpty() && cells.all { it.isEmpty() || tableSeparatorCell.matches(it) }) null
        else cells.joinToString("\t")
    }.joinToString("\n")

// ---------------------------------------------------------------------------
// Diff parsing: unified-diff code fences render with red/green line semantics.
// ---------------------------------------------------------------------------

internal enum class DiffLineKind { ADD, DEL, HUNK, CONTEXT }
internal data class DiffLine(val kind: DiffLineKind, val text: String)

internal fun looksLikeDiff(code: String, language: String?): Boolean {
    if (language?.trim()?.lowercase() in setOf("diff", "patch")) return true
    val lines = code.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return false
    val add = lines.count { it.startsWith("+") && !it.startsWith("+++") }
    val del = lines.count { it.startsWith("-") && !it.startsWith("---") }
    if (add == 0 || del == 0) return false
    // Marker density guards against markdown lists that merely start with "-".
    return (add + del) * 100 >= lines.size * 30
}

internal fun parseDiffLines(code: String): List<DiffLine> = code.lines().map { line ->
    when {
        line.startsWith("@@") -> DiffLine(DiffLineKind.HUNK, line)
        line.startsWith("+++") || line.startsWith("---") -> DiffLine(DiffLineKind.HUNK, line)
        line.startsWith("+") -> DiffLine(DiffLineKind.ADD, line)
        line.startsWith("-") -> DiffLine(DiffLineKind.DEL, line)
        else -> DiffLine(DiffLineKind.CONTEXT, line)
    }
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

/** Status dot: pulsing accent while running, check when done, cross on failure. */
@Composable
private fun ToolStatusDot(running: Boolean, failed: Boolean) {
    val success = MaterialTheme.colorScheme.primary
    val failure = MaterialTheme.colorScheme.tertiary
    when {
        running -> {
            val transition = rememberInfiniteTransition(label = "tool-running")
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
                label = "pulse",
            )
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, success.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(success.copy(alpha = alpha)))
            }
        }
        failed -> Box(
            Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(11.dp), tint = failure)
        }
        else -> Box(
            Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(11.dp), tint = success)
        }
    }
}

/**
 * Semantic tool card: status dot + name + duration in the header; command-shaped
 * payloads render `$ command` and the terminal output under a hierarchy rail.
 * Unknown payloads keep the generic expandable-text behavior. Expansion works in
 * both display modes — Product shows the normalized result, Technical adds size
 * metadata and the raw payload.
 */
@Composable
internal fun SemanticToolCard(tool: ToolCall) {
    if (tool.todos.isNotEmpty()) {
        TodoCard(tool)
        return
    }
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    val technical = LocalToolCallTechnical.current
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    // A tool-output hit in the current turn opens the card so the hit is visible (see ThinkingCard).
    val autoExpand = shouldAutoExpand(LocalChatSearch.current, LocalTurnIsCurrentHit.current, SearchSource.TOOL, tool.output)
    androidx.compose.runtime.LaunchedEffect(autoExpand) { if (autoExpand) expanded = true }
    val hasOutput = tool.output.isNotBlank()
    val running = tool.status == ToolStatus.RUNNING
    val failed = !running && (tool.exitCode ?: 0) != 0
    val outputSize = remember(tool.id, tool.output.length) { tool.output.toByteArray().size }
    val borderColor = when {
        failed -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    val railColor = if (failed) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
    else MaterialTheme.colorScheme.outlineVariant

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        onClick = { if (hasOutput) expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolStatusDot(running = running, failed = failed)
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        running -> localized(language, "运行中…", "Running…")
                        failed -> "exit ${tool.exitCode}"
                        tool.durationMs != null -> formatToolDuration(tool.durationMs)
                        technical && hasOutput -> formatPayloadSize(outputSize)
                        else -> localized(language, "已完成", "Completed")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        running -> MaterialTheme.colorScheme.primary
                        failed -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (hasOutput) {
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) localized(language, "收起结果", "Collapse result")
                        else localized(language, "展开结果", "Expand result"),
                        modifier = Modifier.size(20.dp).padding(start = 3.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Command-shaped payload: `$ command` always visible, output behind the rail.
            if (tool.command != null) {
                Row(Modifier.padding(top = 8.dp).height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .padding(start = 7.dp, end = 10.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(railColor),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "$ ${tool.command}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                        )
                        val body = tool.output
                        if (expanded && body.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = searchHighlighted(body.take(12_000)),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                    ),
                                    color = if (failed) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (failed) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        )
                                        .padding(horizontal = 11.dp, vertical = 9.dp),
                                )
                            }
                        }
                    }
                }
            } else if (expanded && hasOutput) {
                // Generic payload: normalized text for everyone; Product mode included.
                SelectionContainer {
                    Text(
                        tool.output.take(12_000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            if (expanded && hasOutput) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(tool.output)) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(localized(language, "复制结果", "Copy result"), modifier = Modifier.padding(start = 5.dp))
                    }
                }
                if (tool.output.length > 12_000) {
                    Text(
                        localized(
                            language,
                            "内容较长，界面仅预览前 12,000 字符；复制可获取完整结果。",
                            "Long content: the app previews 12,000 characters. Copy to get the full result.",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


/** Unified-diff rendering: red/green rows plus an add/del summary header. */
@Composable
internal fun DiffBlock(code: String) {
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    val lines = remember(code) { parseDiffLines(code) }
    val adds = lines.count { it.kind == DiffLineKind.ADD }
    val dels = lines.count { it.kind == DiffLineKind.DEL }
    val addText = MaterialTheme.colorScheme.onPrimaryContainer
    val addBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    val delText = MaterialTheme.colorScheme.onTertiaryContainer
    val delBg = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 20.sp,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "+$adds",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "−$dels",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = localized(language, "复制代码", "Copy code"),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            lines.forEach { line ->
                val (bg, fg) = when (line.kind) {
                    DiffLineKind.ADD -> addBg to addText
                    DiffLineKind.DEL -> delBg to delText
                    DiffLineKind.HUNK -> null to MaterialTheme.colorScheme.primary
                    DiffLineKind.CONTEXT -> null to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = line.text.ifEmpty { " " },
                    style = mono,
                    color = fg,
                    softWrap = false,
                    modifier = Modifier
                        .then(if (bg != null) Modifier.background(bg) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 0.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Display grouping: a long agent run emits many consecutive tool calls; three
// or more in a row collapse into ONE timeline card instead of a wall of cards.
// Task-list payloads always stand alone as checklist cards.
// ---------------------------------------------------------------------------

internal sealed interface ToolDisplayGroup {
    data class Single(val tool: ToolCall) : ToolDisplayGroup
    data class Timeline(val tools: List<ToolCall>) : ToolDisplayGroup
}

internal fun groupToolsForDisplay(
    tools: List<ToolCall>,
    timelineThreshold: Int = 3,
): List<ToolDisplayGroup> {
    val groups = mutableListOf<ToolDisplayGroup>()
    val run = mutableListOf<ToolCall>()
    fun flush() {
        if (run.size >= timelineThreshold) {
            groups += ToolDisplayGroup.Timeline(run.toList())
        } else {
            run.forEach { groups += ToolDisplayGroup.Single(it) }
        }
        run.clear()
    }
    for (tool in tools) {
        if (tool.todos.isNotEmpty()) {
            flush()
            groups += ToolDisplayGroup.Single(tool)
        } else {
            run += tool
        }
    }
    flush()
    return groups
}

/** Progress over a task list: done counts completed; total excludes cancelled. */
internal fun todoProgress(todos: List<TodoItem>): Pair<Int, Int> {
    var done = 0
    var total = 0
    for (todo in todos) {
        if (todo.status == "cancelled") continue
        total++
        if (todo.status == "completed") done++
    }
    return done to total
}

/** Checklist card for task-list payloads: progress bar plus per-item state. */
@Composable
internal fun TodoCard(tool: ToolCall) {
    val language = LocalAppLanguage.current
    val (done, total) = todoProgress(tool.todos)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TodoStateBox(status = "in_progress")
                Text(
                    localized(language, "任务清单", "Task list"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$done/$total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (total > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(done / total.toFloat())
                            .height(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                tool.todos.forEach { todo ->
                    val doneOrCancelled = todo.status == "completed" || todo.status == "cancelled"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TodoStateBox(status = todo.status)
                        Text(
                            todo.content,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (todo.status == "in_progress") FontWeight.SemiBold else FontWeight.Normal,
                                textDecoration = if (doneOrCancelled) {
                                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                                } else {
                                    null
                                },
                            ),
                            color = when {
                                doneOrCancelled -> MaterialTheme.colorScheme.outline
                                todo.status == "in_progress" -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(start = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoStateBox(status: String) {
    val shape = RoundedCornerShape(4.dp)
    when (status) {
        "completed" -> Box(
            Modifier.size(15.dp).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        "in_progress" -> Box(
            Modifier.size(15.dp).clip(shape).border(1.5.dp, MaterialTheme.colorScheme.primary, shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        "cancelled" -> Box(
            Modifier.size(15.dp).clip(shape).border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        else -> Box(
            Modifier.size(15.dp).clip(shape).border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        )
    }
}

/**
 * Timeline card: three or more consecutive calls in one shell, one row per call
 * with the command (or first output line) as the summary; tapping a row expands
 * its output inline behind the hierarchy rail.
 */
@Composable
internal fun ToolTimelineCard(tools: List<ToolCall>) {
    val language = LocalAppLanguage.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 7.dp)) {
            tools.forEach { tool ->
                var expanded by rememberSaveable("timeline-${tool.id}") { mutableStateOf(false) }
                val running = tool.status == ToolStatus.RUNNING
                val failed = !running && (tool.exitCode ?: 0) != 0
                val summary = tool.command
                    ?: tool.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .let { base ->
                            if (tool.output.isNotBlank()) {
                                base.then(
                                    Modifier.background(androidx.compose.ui.graphics.Color.Transparent),
                                )
                            } else {
                                base
                            }
                        }
                        .padding(vertical = 5.dp)
                        .then(
                            if (tool.output.isNotBlank()) {
                                Modifier.clickableNoIndication { expanded = !expanded }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    ToolStatusDot(running = running, failed = failed)
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            color = if (running) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = when {
                            running -> localized(language, "运行中…", "Running…")
                            failed -> "exit ${tool.exitCode}"
                            tool.durationMs != null -> formatToolDuration(tool.durationMs)
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            running -> MaterialTheme.colorScheme.primary
                            failed -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (expanded && tool.output.isNotBlank()) {
                    Row(Modifier.height(IntrinsicSize.Min).padding(bottom = 6.dp)) {
                        Box(
                            Modifier
                                .padding(start = 7.dp, end = 10.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                tool.output.take(12_000),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Rows toggle without a ripple so the card reads as one quiet timeline.
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(onClick = onClick),
)
