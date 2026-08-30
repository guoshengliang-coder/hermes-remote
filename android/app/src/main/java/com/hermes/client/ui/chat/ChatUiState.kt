package com.hermes.client.ui.chat

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.network.strList
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val displayJson = Json { prettyPrint = true }
private val extraBlankLines = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+")

/**
 * Turns gateway payloads into human-readable text. Some Hermes tools wrap their actual output
 * as {"output":"...\\n..."}; showing that JSON verbatim leaves escaped newlines in chat.
 * Preserve genuinely structured results, but unwrap the common text containers first.
 */
internal fun normalizeDisplayPayload(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith("\"")) return raw
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return raw

    fun unwrap(element: JsonElement, depth: Int = 0): String? {
        if (depth > 2) return null
        if (element is JsonPrimitive && element.isString) return element.content
        if (element is JsonObject) {
            for (key in listOf("output", "result", "content", "text")) {
                val candidate = element[key] ?: continue
                unwrap(candidate, depth + 1)?.let { return it }
            }
        }
        return null
    }

    return unwrap(parsed) ?: runCatching {
        displayJson.encodeToString(JsonElement.serializer(), parsed)
    }.getOrDefault(raw)
}

private data class OrganizedAssistantContent(
    val text: String,
    val tools: List<ToolCall>,
)

/**
 * Hermes history can flatten terminal/tool payloads into the assistant's prose, for example:
 *
 *   Checking the process.
 *   {"status":"not_found", ...}
 *   {"output":"line 1\\nline 2"}
 *
 * Rendering that verbatim is both noisy and unreadable. Pull embedded JSON payloads back into
 * collapsed tool cards, hide expected polling misses, and keep only the actual answer as prose.
 */
private fun organizeAssistantContent(
    raw: String,
    existingTools: List<ToolCall> = emptyList(),
): OrganizedAssistantContent {
    // Hermes injects <untrusted_tool_result> blocks for the model's safety boundary. Those tags,
    // warnings, and raw payloads are protocol details. Consumer chat apps render only a compact
    // tool/source card, never the security wrapper as assistant prose.
    val untrusted = extractUntrustedToolResults(raw)
    val displayRaw = untrusted.text
    val initialTools = existingTools.toMutableList().apply {
        untrusted.tools.forEach { extracted ->
            if (none { it.output.trim() == extracted.output.trim() && it.name == extracted.name }) add(extracted)
        }
    }

    if (!displayRaw.contains('{')) {
        return OrganizedAssistantContent(normalizeDisplayPayload(displayRaw).trim(), initialTools)
    }

    val prose = StringBuilder()
    val tools = initialTools
    var cursor = 0
    var searchFrom = 0
    var extracted = 0

    while (searchFrom < displayRaw.length) {
        val start = displayRaw.indexOf('{', searchFrom)
        if (start < 0) break
        val match = parseEmbeddedObject(displayRaw, start)
        if (match == null) {
            searchFrom = start + 1
            continue
        }
        val (endExclusive, obj) = match
        val embedded = classifyEmbeddedObject(obj)
        if (embedded == null) {
            searchFrom = endExclusive
            continue
        }

        val before = displayRaw.substring(cursor, start)
        val (keptProse, processLabel) = detachProcessNarration(before)
        prose.append(keptProse)

        if (!embedded.hidden && embedded.output.isNotBlank() &&
            tools.none { it.output.trim() == embedded.output.trim() }
        ) {
            val embeddedMeta = parseToolPayloadMeta(obj.toString())
            tools += ToolCall(
                id = "embedded-${displayRaw.hashCode()}-${extracted++}",
                name = processLabel ?: embedded.defaultLabel,
                status = ToolStatus.DONE,
                output = embedded.output,
                command = embeddedMeta?.command,
                exitCode = embeddedMeta?.exitCode,
                durationMs = embeddedMeta?.durationMs,
            )
        }
        cursor = endExclusive
        searchFrom = endExclusive
    }

    // No recognized embedded payload: preserve ordinary prose/code containing braces.
    if (cursor == 0) {
        return OrganizedAssistantContent(normalizeDisplayPayload(displayRaw).trim(), initialTools)
    }

    prose.append(displayRaw.substring(cursor))
    val cleanText = extraBlankLines.replace(prose.toString(), "\n\n").trim()
    return OrganizedAssistantContent(cleanText, tools)
}

private data class UntrustedExtraction(
    val text: String,
    val tools: List<ToolCall>,
)

private val untrustedOpenTag = Regex(
    "<untrusted_tool_result\\b([^>]*)>",
    setOf(RegexOption.IGNORE_CASE),
)
private val untrustedSource = Regex("source\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private const val UNTRUSTED_CLOSE_TAG = "</untrusted_tool_result>"

private fun extractUntrustedToolResults(raw: String): UntrustedExtraction {
    if (!raw.contains("<untrusted_tool_result", ignoreCase = true)) {
        return UntrustedExtraction(raw, emptyList())
    }

    val clean = StringBuilder()
    val tools = mutableListOf<ToolCall>()
    var cursor = 0
    var index = 0
    while (cursor < raw.length) {
        val open = untrustedOpenTag.find(raw, cursor) ?: break
        clean.append(raw.substring(cursor, open.range.first))
        val bodyStart = open.range.last + 1
        val closeStart = raw.indexOf(UNTRUSTED_CLOSE_TAG, bodyStart, ignoreCase = true)
        val bodyEnd = if (closeStart >= 0) closeStart else raw.length
        val body = raw.substring(bodyStart, bodyEnd).trim()
        val source = untrustedSource.find(open.groupValues[1])?.groupValues?.getOrNull(1)
        val usefulStart = body.indexOfFirst { it == '{' || it == '[' }
        val useful = if (usefulStart >= 0) normalizeDisplayPayload(body.substring(usefulStart)) else ""
        tools += ToolCall(
            id = "untrusted-${raw.hashCode()}-${index++}",
            name = toolSourceLabel(source),
            status = ToolStatus.DONE,
            // Keep data available behind an explicit expand action, but discard the repetitive
            // English "treat as data" safety preamble that has no user-facing value.
            output = useful.trim(),
        )
        cursor = if (closeStart >= 0) closeStart + UNTRUSTED_CLOSE_TAG.length else raw.length
    }
    if (cursor < raw.length) clean.append(raw.substring(cursor))
    return UntrustedExtraction(
        text = extraBlankLines.replace(clean.toString(), "\n\n").trim(),
        tools = tools,
    )
}

private fun toolSourceLabel(source: String?): String = when (source?.lowercase()) {
    "web_search", "web-search", "search" -> "网页搜索"
    "browser", "web_browser" -> "浏览器结果"
    "terminal", "shell", "bash" -> "终端结果"
    else -> "工具结果"
}

private data class EmbeddedPayload(
    val output: String,
    val defaultLabel: String,
    val hidden: Boolean = false,
)

private fun classifyEmbeddedObject(obj: JsonObject): EmbeddedPayload? {
    val status = (obj["status"] as? JsonPrimitive)?.content?.lowercase()
    val error = (obj["error"] as? JsonPrimitive)?.content.orEmpty()
    val wrapped = listOf("output", "result", "content", "text")
        .firstNotNullOfOrNull { key -> obj[key]?.let { normalizeDisplayPayload(it.toString()) } }

    if (wrapped != null) {
        val label = if (wrapped.contains("github.com", ignoreCase = true) ||
            wrapped.contains("Token scopes", ignoreCase = true)
        ) "终端结果" else "工具结果"
        return EmbeddedPayload(wrapped.trim(), label)
    }

    // A background-process poll commonly returns not_found after the command has already ended.
    // It is an implementation detail, not an answer or a user-facing error.
    if (status == "not_found" || error.contains("No process with ID", ignoreCase = true)) {
        return EmbeddedPayload("", "后台检查", hidden = true)
    }

    if (status != null || error.isNotBlank()) {
        return EmbeddedPayload(
            displayJson.encodeToString(JsonElement.serializer(), obj),
            "执行详情",
        )
    }
    return null
}

/** Returns the exclusive end plus parsed object, respecting braces inside JSON strings. */
private fun parseEmbeddedObject(raw: String, start: Int): Pair<Int, JsonObject>? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until raw.length) {
        val ch = raw[i]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    val end = i + 1
                    val parsed = runCatching {
                        Json.parseToJsonElement(raw.substring(start, end)) as? JsonObject
                    }.getOrNull() ?: return null
                    return end to parsed
                }
            }
        }
    }
    return null
}

/**
 * A short paragraph immediately introducing a JSON payload is process narration, not the final
 * answer. Remove it from prose and reuse it as the collapsed card's label when concise.
 */
private fun detachProcessNarration(text: String): Pair<String, String?> {
    val trimmedEnd = text.trimEnd()
    if (trimmedEnd.isBlank()) return "" to null
    val split = trimmedEnd.lastIndexOf("\n\n")
    val paragraph = trimmedEnd.substring(split + 2).trim()
    val looksLikeNarration = paragraph.length in 2..80 &&
        !paragraph.contains("```") &&
        !paragraph.startsWith("#") &&
        !paragraph.startsWith("-") &&
        !paragraph.startsWith("•")
    if (!looksLikeNarration) return text to null

    val kept = if (split >= 0) trimmedEnd.substring(0, split).trimEnd() else ""
    val label = paragraph.trimEnd('。', '.', '：', ':').take(28)
    return kept to label
}

internal fun ChatMessage.organizedForDisplay(): ChatMessage {
    if (isError) return this
    // REST history preserves Hermes tool turns as role="tool"; the domain mapper represents
    // unknown/non-chat roles as SYSTEM. Those turns contain the same untrusted wrappers as live
    // assistant output and must be collapsed too. Leave ordinary system notices untouched.
    val isToolHistory = role == Role.SYSTEM &&
        text.contains("untrusted_tool_result", ignoreCase = true)
    if (role != Role.ASSISTANT && !isToolHistory) return this
    val organized = organizeAssistantContent(text, tools)
    val parsed = com.hermes.client.domain.parseMessageContent(organized.text)
    val parsedTools = organized.tools.map { tool ->
        val content = com.hermes.client.domain.parseMessageContent(tool.output)
        tool to content
    }
    return copy(
        text = parsed.text,
        images = (images + parsed.images + parsedTools.flatMap { it.second.images }).distinctBy { it.id },
        files = (files + parsed.files + parsedTools.flatMap { it.second.files }).distinctBy { it.id },
        tools = parsedTools.map { (tool, content) -> tool.copy(output = content.text) },
    )
}

/**
 * Hermes may persist one user turn as several adjacent assistant records (typically one around
 * each tool call). Consumer chat UIs present those records as one answer: otherwise every small
 * fragment gets its own copy/feedback/read-aloud row and those actions only target a fragment.
 *
 * Keep user/system boundaries intact, but combine adjacent assistant records after sanitizing
 * them. The newest record supplies the stable id and streaming state, while copy/read-aloud sees
 * the complete text produced during that turn.
 */
internal fun List<ChatMessage>.organizedConversationTurns(): List<ChatMessage> {
    val turns = mutableListOf<ChatMessage>()
    for (raw in this) {
        val message = raw.organizedForDisplay()
        val previous = turns.lastOrNull()
        if (message.role != Role.ASSISTANT || previous?.role != Role.ASSISTANT) {
            turns += message
            continue
        }

        turns[turns.lastIndex] = mergeAssistantTurns(previous, message)
    }
    return turns
}


/**
 * A turn shows a time separator above it when it is the first stamped turn or when more than
 * [gapMinutes] passed since the previous stamped turn. Unstamped turns never show one.
 */
internal fun showsTimeSeparator(previousTs: Long?, ts: Long?, gapMinutes: Long = 20): Boolean {
    ts ?: return false
    previousTs ?: return true
    return ts - previousTs >= gapMinutes * 60_000
}

/**
 * History reconciliation replaces the live transcript wholesale. When the gateway's history rows
 * carry no created_at, inherit the live message's local stamp by position+role so time separators
 * survive the swap; a position mismatch simply yields no stamp, never a wrong one.
 */
internal fun inheritTimestamps(history: List<ChatMessage>, current: List<ChatMessage>): List<ChatMessage> =
    history.mapIndexed { index, message ->
        if (message.timestamp != null) return@mapIndexed message
        val live = current.getOrNull(index)
        if (live != null && live.role == message.role) message.copy(timestamp = live.timestamp) else message
    }

/** Merge two already display-ready assistant records without re-running content sanitization. */
internal fun mergeAssistantTurns(previous: ChatMessage, message: ChatMessage): ChatMessage {
    val toolsById = linkedMapOf<String, ToolCall>()
    (previous.tools + message.tools).forEach { tool -> toolsById[tool.id] = tool }
    return message.copy(
        text = joinTurnParts(previous.text, message.text),
        thinking = joinTurnParts(previous.thinking, message.thinking),
        timestamp = previous.timestamp ?: message.timestamp,
        images = (previous.images + message.images).distinctBy { it.id },
        files = (previous.files + message.files).distinctBy { it.id },
        tools = toolsById.values.toList(),
        isError = previous.isError || message.isError,
        interrupted = previous.interrupted || message.interrupted,
    )
}

/**
 * Cheap per-snapshot stabilizer for RAW streaming markdown. Full sanitization stays deferred to
 * message.complete (regex passes per token would be O(n²)); these bounded passes remove only the
 * constructs that make the parse structure of already-rendered text flip between 64ms snapshots —
 * which, with the list pinned to the bottom edge, reads as violent content jumping:
 *
 * 1. An `<untrusted_tool_result>` wrapper whose close tag has not streamed in yet: everything
 *    after the opener is unfinished tool payload; hide it behind [toolDataPlaceholder].
 * 2. A trailing tool-payload JSON object that has not balanced yet: its `#`/`*`/backtick bytes
 *    stream straight into the markdown parser and restyle earlier lines on every snapshot.
 * 3. An odd number of code fences leaves the final fence open, so trailing prose renders as code
 *    until the closing fence arrives and then reflows; close the fence per snapshot instead.
 */
internal fun stabilizeStreamingMarkdown(raw: String, toolDataPlaceholder: String): String {
    val wrapperStart = raw.lastIndexOf("<untrusted_tool_result", ignoreCase = true)
    if (wrapperStart >= 0 && raw.indexOf(UNTRUSTED_CLOSE_TAG, wrapperStart, ignoreCase = true) < 0) {
        return closeOpenFence(raw.substring(0, wrapperStart)).withStreamingPlaceholder(toolDataPlaceholder)
    }
    val jsonStart = findTrailingUnbalancedJson(raw)
    if (jsonStart >= 0) {
        return closeOpenFence(raw.substring(0, jsonStart)).withStreamingPlaceholder(toolDataPlaceholder)
    }
    return closeOpenFence(raw)
}

/**
 * Render snapshot for a streaming tail: mask the unfinished trailing payload, then run the SAME
 * display organization the completion pass uses. A tool payload that balances mid-stream thereby
 * becomes a collapsed tool card in the very next snapshot — instead of first exploding into raw
 * markdown and then collapsing again at message.complete, the two largest visible jumps left.
 * Running this per token would approach O(n²); per 64ms snapshot on a background dispatcher it is
 * a few regex passes over one record.
 */
internal fun ChatMessage.stabilizedForStreaming(toolDataPlaceholder: String): ChatMessage =
    if (text.isBlank()) this
    else copy(text = stabilizeStreamingMarkdown(text, toolDataPlaceholder)).organizedForDisplay()

private val fenceLine = Regex("(?m)^\\s*```")
private val streamedJsonLineStart = Regex("(?m)^\\s*\\{\\s*\"")

private fun closeOpenFence(text: String): String =
    if (fenceLine.findAll(text).count() % 2 == 1) "$text\n```" else text

private fun String.withStreamingPlaceholder(placeholder: String): String =
    if (placeholder.isBlank()) this else trimEnd() + "\n\n*$placeholder*"

/**
 * Index of a trailing, still-unbalanced `{"…` object, or -1. Masking starts with the blob's very
 * first characters: showing raw payload and yanking it back later is itself a visible jump.
 *
 * The scan MUST be string-aware so its verdict stays monotone. An earlier naive brace count
 * treated braces inside JSON string values as structure; while a payload full of code streamed
 * in, the depth repeatedly crossed zero and the mask flipped on and off at snapshot cadence —
 * measured on a screen recording as the whole answer's rendered height oscillating thousands of
 * pixels at ~7Hz. With quotes and escapes tracked, "still open" holds continuously until the
 * real closing brace arrives, and "closed" holds forever after: one transition per blob.
 */
private fun findTrailingUnbalancedJson(text: String): Int {
    val start = streamedJsonLineStart.findAll(text).lastOrNull()?.range?.first ?: return -1
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val c = text[index]
        when {
            escaped -> escaped = false
            inString -> when (c) {
                '\\' -> escaped = true
                '"' -> inString = false
            }
            else -> when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return -1
                }
            }
        }
    }
    return if (depth > 0) start else -1
}

private fun joinTurnParts(first: String, second: String): String = when {
    first.isBlank() -> second
    second.isBlank() -> first
    else -> first.trimEnd() + "\n\n" + second.trimStart()
}

data class ApprovalRequest(
    val command: String,
    val description: String,
    val patternKeys: List<String>,
    val allowPermanent: Boolean,
    val smartDenied: Boolean = false,
)
data class ClarifyRequest(val question: String, val options: List<String>, val requestId: String = "")

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val backgroundProcesses: List<com.hermes.client.data.repository.BackgroundProcess> = emptyList(),
    val pendingApproval: ApprovalRequest? = null,
    val pendingClarify: ClarifyRequest? = null,
    val isGenerating: Boolean = false,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    // Loading history is not the same thing as a confirmed empty conversation. Keeping these
    // separate prevents the chat from flashing the new-chat empty state before REST returns.
    val historyLoading: Boolean = false,
    val historyLoaded: Boolean = false,
    val historyError: String? = null,
) {
    companion object { fun empty() = ChatUiState() }
}

fun ChatUiState.withUserMessage(
    text: String,
    images: List<com.hermes.client.domain.ChatImage> = emptyList(),
    files: List<com.hermes.client.domain.ChatFile> = emptyList(),
    messageId: String = "u-${messages.size}",
): ChatUiState =
    copy(
        messages = messages + ChatMessage(
            id = messageId,
            role = Role.USER,
            text = text,
            timestamp = System.currentTimeMillis(),
            images = images,
            files = files,
        ),
        isGenerating = true,
    )


/** Pure reducer: folds one server event into the chat state. */
fun ChatUiState.reduce(event: ServerEvent): ChatUiState {
    val state = this
    fun ChatUiState.ensureStreamingAssistant(): ChatUiState {
        val streamingIndex = messages.indexOfLast { it.role == Role.ASSISTANT && it.isStreaming }
        val lastUserIndex = messages.indexOfLast { it.role == Role.USER }
        if (streamingIndex > lastUserIndex) return this
        return copy(
            messages = messages + ChatMessage(
                id = "a-${messages.size}-${event.str("message_id") ?: "recovered"}",
                role = Role.ASSISTANT,
                text = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
            ),
            isGenerating = true,
        )
    }

    // Targets the last STREAMING assistant message.
    fun ChatUiState.mutateLastAssistant(block: (ChatMessage) -> ChatMessage): ChatUiState {
        val idx = messages.indexOfLast { it.role == Role.ASSISTANT && it.isStreaming }
        if (idx < 0) return this
        val updated = messages.toMutableList()
        updated[idx] = block(updated[idx])
        return copy(messages = updated)
    }

    // Targets the last assistant message regardless of streaming state.
    fun ChatUiState.mutateLastAssistantAny(block: (ChatMessage) -> ChatMessage): ChatUiState {
        val idx = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (idx < 0) return this
        val updated = messages.toMutableList()
        updated[idx] = block(updated[idx])
        return copy(messages = updated)
    }

    return when (event.type) {
        // A reconnect can replay start after a recovered delta, so do not append a duplicate.
        "message.start" -> state.ensureStreamingAssistant()
        // Gateway streams text under payload.text (not "delta"/"content").
        // If start was lost during a reconnect, recover a streaming assistant instead of silently
        // discarding every delta until the user reopens the conversation.
        "message.delta" -> state.ensureStreamingAssistant()
            .mutateLastAssistant { it.copy(text = it.text + (event.str("text") ?: "")) }
        // Real reasoning arrives as reasoning.delta/reasoning.available (payload.text).
        // thinking.delta is only a transient spinner status, so it's ignored (else branch).
        "reasoning.delta", "reasoning.available" ->
            state.ensureStreamingAssistant()
                .mutateLastAssistant { it.copy(thinking = it.thinking + (event.str("text") ?: "")) }
        // Complete normally contains the authoritative final text. Upsert a recovered assistant
        // when start/delta were missed so the most valuable terminal event is never thrown away.
        "message.complete" -> {
            val complete = event.str("text") ?: event.str("rendered")
            val prepared = if (state.messages.any { it.role == Role.ASSISTANT && it.isStreaming } || !complete.isNullOrBlank()) {
                state.ensureStreamingAssistant()
            } else state
            prepared.mutateLastAssistant {
                it.copy(text = complete ?: it.text, isStreaming = false).organizedForDisplay()
            }.copy(isGenerating = false)
        }
        "tool.start" -> state.ensureStreamingAssistant().mutateLastAssistant {
            it.copy(tools = it.tools + ToolCall(
                id = event.str("tool_id") ?: "t-${it.tools.size}",
                name = event.str("name") ?: "tool",
                status = ToolStatus.RUNNING,
            ))
        }
        "tool.complete" -> state.mutateLastAssistantAny { msg ->
            val tid = event.str("tool_id")
            // Parse semantic metadata (command/exit/duration) from the RAW payload; display
            // normalization below unwraps the JSON to readable text and would discard it.
            val raw = event.str("result")
            val meta = raw?.let(::parseToolPayloadMeta)
            msg.copy(tools = msg.tools.map {
                if (it.id == tid) it.copy(
                    status = ToolStatus.DONE,
                    output = raw?.let(::normalizeDisplayPayload) ?: "",
                    command = meta?.command,
                    exitCode = meta?.exitCode,
                    durationMs = meta?.durationMs,
                    todos = meta?.todos.orEmpty(),
                ) else it
            })
        }
        "approval.request" -> state.copy(
            pendingApproval = ApprovalRequest(
                command = event.str("command") ?: "",
                description = event.str("description") ?: "",
                patternKeys = event.strList("pattern_keys")
                    .ifEmpty { event.str("pattern_key")?.let { listOf(it) } ?: emptyList() },
                allowPermanent = event.bool("allow_permanent") ?: false,
                smartDenied = event.bool("smart_denied") ?: false,
            ),
        )
        "clarify.request" -> state.copy(
            pendingClarify = ClarifyRequest(
                event.str("question") ?: "", emptyList(), event.str("request_id") ?: "",
            ),
        )
        "error" -> state.copy(
            messages = state.messages + ChatMessage(
                id = "e-${state.messages.size}", role = Role.SYSTEM,
                text = event.str("message") ?: "error", isError = true,
            ),
            isGenerating = false,
        )
        else -> state
    }
}

/**
 * Pure helper: marks the current generation as interrupted.
 * - Finds the last assistant message with isStreaming==true; if present, sets
 *   isStreaming=false and interrupted=true on it.
 * - Always sets isGenerating=false, whether or not a streaming message was found.
 */
fun ChatUiState.markInterrupted(): ChatUiState {
    val idx = messages.indexOfLast { it.role == Role.ASSISTANT && it.isStreaming }
    val newMessages = if (idx >= 0) {
        messages.toMutableList().also { list ->
            list[idx] = list[idx].copy(isStreaming = false, interrupted = true)
        }.toList()
    } else {
        messages
    }
    return copy(messages = newMessages, isGenerating = false)
}

fun ChatUiState.withAttachment(a: PendingAttachment): ChatUiState =
    copy(pendingAttachments = pendingAttachments.plusCapped(a))

fun ChatUiState.withoutAttachment(id: String): ChatUiState =
    copy(pendingAttachments = pendingAttachments.filterNot { it.id == id })
