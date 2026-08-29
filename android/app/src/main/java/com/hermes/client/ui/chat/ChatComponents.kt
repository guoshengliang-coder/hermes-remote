package com.hermes.client.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.hermes.client.ui.theme.LocalToolCallTechnical
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun ChatMessageList(
    state: ChatUiState,
    sessionId: String,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    isGenerating: Boolean = false,
    onEditResend: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    isSpeaking: Boolean = false,
    onReadAloud: (String) -> Unit = {},
    onStopReading: () -> Unit = {},
    highlightIndex: Int? = null,
    onBlankAreaTap: () -> Unit = {},
) {
    // Hermes stores a tool-using answer as multiple adjacent assistant records. Present them as
    // one consumer-facing turn so the action row appears once and acts on the complete answer.
    // During streaming only the tail changes. Cache the settled prefix so each token sanitizes and
    // groups one message instead of walking a potentially huge history on the main thread.
    val streamingTail = state.messages.lastOrNull()?.takeIf { it.isStreaming }
    val settledMessages = if (streamingTail != null) state.messages.dropLast(1) else state.messages
    val settledTurns = remember(sessionId, settledMessages) {
        settledMessages.organizedConversationTurns()
    }
    val displayMessages = remember(settledTurns, streamingTail) {
        val tail = streamingTail?.organizedForDisplay() ?: return@remember settledTurns
        val previous = settledTurns.lastOrNull()
        if (previous?.role == Role.ASSISTANT && tail.role == Role.ASSISTANT) {
            settledTurns.dropLast(1) + listOf(previous, tail).organizedConversationTurns()
        } else {
            settledTurns + tail
        }
    }
    val lastIndex = displayMessages.lastIndex
    // Only the most recent assistant turn can be regenerated — regenerating an earlier one
    // would silently drop everything the user and agent said after it.
    val lastAssistantId = displayMessages.lastOrNull { it.role == Role.ASSISTANT }?.id
    // Changes for reasoning, visible answer text, and live tool activity. The previous text-only key
    // never fired while Hermes was thinking, leaving the viewport frozen until answer text arrived.
    val streamRevision = state.messages.lastOrNull()?.streamContentRevision() ?: 0
    // Cached separately from the streaming tail: an authoritative history refresh may replace an
    // earlier turn without changing the message count or the final message. That must trigger a
    // second bottom calibration before the initial-entry window closes.
    val settledLayoutRevision = remember(settledMessages) {
        settledMessages.conversationLayoutRevision()
    }
    val endAnchorIndex = displayMessages.size
    var followLatest by remember(sessionId) { mutableStateOf(true) }

    // Initial entry is a small state machine instead of a one-shot boolean. The one-shot version
    // marked itself complete before scrolling, so a cancelled layout/scroll could never retry and
    // cached history could be revealed before the server's fuller transcript changed its height.
    var landingStage by remember(sessionId) { mutableStateOf(InitialLandingStage.WAITING) }
    var authoritativeLandingDone by remember(sessionId) { mutableStateOf(false) }
    val contentReady = landingStage == InitialLandingStage.READY
    LaunchedEffect(
        sessionId,
        displayMessages.isNotEmpty(),
        settledLayoutRevision,
        state.historyLoading,
        endAnchorIndex,
    ) {
        if (displayMessages.isEmpty() || authoritativeLandingDone) return@LaunchedEffect
        val wasAlreadyVisible = landingStage == InitialLandingStage.READY
        if (!wasAlreadyVisible) landingStage = InitialLandingStage.LANDING
        try {
            // Wait for the end anchor to exist in LazyColumn's measured item set.
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .filter { it >= endAnchorIndex + 1 }
                .first()

            // Repeatedly target the item *after* the final message. Markdown/code measurement can
            // grow across several frames, so declare success only after the absolute bottom remains
            // stable for multiple frames. The guard prevents a broken layout from hiding chat forever.
            var stableFrames = 0
            var guard = 0
            while (guard++ < 120 && stableFrames < 6) {
                listState.scrollToItem(endAnchorIndex)
                withFrameNanos { }
                if (listState.canScrollForward) stableFrames = 0 else stableFrames++
            }
            landingStage = InitialLandingStage.READY
            // Cached history may be shown after its own successful landing, but when the server
            // refresh completes we calibrate once more (without hiding the already-visible content).
            if (!state.historyLoading) authoritativeLandingDone = true
        } catch (cancelled: CancellationException) {
            // A new history/layout revision restarts this effect. Do not leave the initial skeleton
            // permanently covering the list if the first landing was cancelled before reveal.
            if (!wasAlreadyVisible) landingStage = InitialLandingStage.WAITING
            throw cancelled
        }
    }

    // Sending a new prompt always resumes follow mode. A user drag pauses it; reaching the absolute
    // bottom (manually or via the button) turns it back on.
    LaunchedEffect(state.messages.size) {
        if (displayMessages.lastOrNull()?.role == Role.USER) followLatest = true
    }
    LaunchedEffect(listState, contentReady, followLatest, endAnchorIndex) {
        if (!contentReady) return@LaunchedEffect
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward ->
            when {
                !canScrollForward -> followLatest = true
                followLatest -> {
                    // Catches asynchronous Markdown/font reflow and IME height changes even when no
                    // message text changed, keeping a genuine bottom-follow rather than index-follow.
                    withFrameNanos { }
                    listState.scrollToItem(endAnchorIndex)
                }
            }
        }
    }
    val userScrollConnection = remember(sessionId, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && listState.canScrollForward) {
                    followLatest = false
                }
                return Offset.Zero
            }
        }
    }

    // The anchor is a real item *after* the final message. Scrolling to the final message itself only
    // aligns the top of a long Markdown block and therefore does not follow its growing bottom.
    // Use a frame-coalesced immediate jump during token streaming; repeated animated scrolls cancel
    // one another and visibly judder at normal model token rates.
    LaunchedEffect(state.messages.size, streamRevision, followLatest, contentReady, endAnchorIndex) {
        if (lastIndex < 0 || !contentReady || !followLatest) return@LaunchedEffect
        withFrameNanos { }
        listState.scrollToItem(endAnchorIndex)
    }

    if (displayMessages.isEmpty()) {
        when {
            state.historyLoading -> ChatHistorySkeleton(modifier.fillMaxSize())
            state.historyError != null -> Box(
                modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "历史消息暂时无法加载，连接恢复后会自动更新。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "发条消息，开始和 Hermes 对话。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (contentReady) 1f else 0f,
        animationSpec = tween(150),
        label = "chat-history-reveal",
    )
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .alpha(contentAlpha)
                .nestedScroll(userScrollConnection)
                // A simple tap on conversation whitespace exits the expanded composer. Drag gestures
                // remain scrolling gestures, and taps consumed by message actions are left alone.
                .pointerInput(onBlankAreaTap) { detectTapGestures { onBlankAreaTap() } }
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // Key by position as well as id: the gateway reuses the model name as the
            // message id across a session's turns, so ids are NOT guaranteed unique.
            itemsIndexed(
                displayMessages,
                key = { index, msg -> "$index:${msg.id}" },
            ) { index, msg ->
                val canRegenerate = msg.id == lastAssistantId && !isGenerating
                val showAssistantActions = !(isGenerating && index == displayMessages.lastIndex)
                MessageBubble(
                    msg,
                    canRegenerate,
                    showAssistantActions,
                    onEditResend,
                    onRegenerate,
                    isSpeaking,
                    onReadAloud,
                    onStopReading,
                    highlighted = index == highlightIndex,
                )
            }
            item(key = "conversation-end-anchor") {
                Spacer(Modifier.height(1.dp))
            }
        }
        if (contentReady && !followLatest && listState.canScrollForward) {
            Surface(
                onClick = {
                    followLatest = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "回到最新消息",
                    modifier = Modifier.padding(10.dp).size(24.dp),
                    tint = LocalProfileAccent.current.accent,
                )
            }
        }
        if (contentAlpha < 1f) {
            ChatHistorySkeleton(Modifier.fillMaxSize().alpha(1f - contentAlpha))
        }
    }
}

private enum class InitialLandingStage { WAITING, LANDING, READY }

internal fun List<ChatMessage>.conversationLayoutRevision(): Int = fold(1) { revision, message ->
    var next = 31 * revision + message.id.hashCode()
    next = 31 * next + message.text.hashCode()
    next = 31 * next + message.thinking.hashCode()
    message.tools.forEach { tool ->
        next = 31 * next + tool.id.hashCode()
        next = 31 * next + tool.name.hashCode()
        next = 31 * next + tool.output.hashCode()
        next = 31 * next + tool.status.hashCode()
    }
    next
}

internal fun ChatMessage.streamContentRevision(): Int =
    text.length + thinking.length + tools.sumOf { tool ->
        tool.name.length + tool.output.length + if (tool.status == ToolStatus.RUNNING) 1 else 2
    }

@Composable
private fun ChatHistorySkeleton(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    Column(
        modifier.padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.82f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(block))
        Box(Modifier.fillMaxWidth(0.94f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(block))
        Box(Modifier.fillMaxWidth(0.68f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(block))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(0.9f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(block))
        Box(Modifier.fillMaxWidth(0.74f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(block))
    }
}

// Hybrid layout: the user's own turns stay as compact right-aligned bubbles, while the agent's
// turns render full-width and document-style (like the desktop / modern AI apps) so long answers,
// code, and tool traces have room to breathe and read as a transcript rather than an SMS thread.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    canRegenerate: Boolean,
    showAssistantActions: Boolean,
    onEditResend: (String) -> Unit,
    onRegenerate: () -> Unit,
    isSpeaking: Boolean,
    onReadAloud: (String) -> Unit,
    onStopReading: () -> Unit,
    highlighted: Boolean = false,
) {
    when (msg.role) {
        Role.USER -> UserBubble(msg, onEditResend, highlighted = highlighted)
        else -> AssistantTurn(msg, canRegenerate, showAssistantActions, onRegenerate, isSpeaking, onReadAloud, onStopReading, highlighted = highlighted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: ChatMessage, onEditResend: (String) -> Unit, highlighted: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (msg.isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    val accent = LocalProfileAccent.current.accent
    val userShape = RoundedCornerShape(22.dp, 22.dp, 7.dp, 22.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    // Asymmetric corners (a small "tail" corner) mark this as the sender's bubble.
                    .clip(userShape)
                    .background(bg)
                    .then(if (highlighted) Modifier.background(accent.copy(alpha = 0.18f)).border(1.5.dp, accent, userShape) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 11.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            ) {
                if (msg.text.isNotBlank()) {
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                            letterSpacing = 0.sp,
                        ),
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = { copyToClipboard(msg.text, clipboard, context); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text("Edit & resend") },
                    onClick = { onEditResend(msg.text); menuOpen = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantTurn(
    msg: ChatMessage,
    canRegenerate: Boolean,
    showActions: Boolean,
    onRegenerate: () -> Unit,
    isSpeaking: Boolean,
    onReadAloud: (String) -> Unit,
    onStopReading: () -> Unit,
    highlighted: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var feedback by remember(msg.id) { mutableStateOf(0) }
    val speakable = remember(msg.text) { speechText(msg.text).isNotBlank() }
    val accent = LocalProfileAccent.current.accent
    val hlShape = RoundedCornerShape(12.dp)
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                // No conditional padding here: background/border draw within existing bounds, so
                // toggling the highlight causes no layout shift (a conditional .padding would).
                .then(if (highlighted) Modifier.clip(hlShape).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, hlShape) else Modifier)
                .padding(vertical = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
        ) {
            if (msg.thinking.isNotBlank()) ThinkingCard(msg.thinking)
            msg.tools.forEach { ToolCard(it) }
            if (msg.text.isNotBlank()) {
                if (msg.isError) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            msg.text,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    val mdComponents = remember { chatMarkdownComponents() }
                    val body = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        lineHeight = 29.sp,
                        letterSpacing = 0.sp,
                    )
                    Markdown(
                        content = msg.text,
                        colors = markdownColor(),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.headlineSmall.copy(lineHeight = 34.sp),
                            h2 = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 31.sp),
                            h3 = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                            h4 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            h5 = MaterialTheme.typography.titleSmall,
                            h6 = MaterialTheme.typography.titleSmall,
                            text = body,
                            paragraph = body,
                            ordered = body,
                            bullet = body,
                            list = body,
                            quote = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            table = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
                        ),
                        components = mdComponents,
                    )
                }
            }
            if (msg.isStreaming && msg.text.isBlank() && msg.tools.isEmpty()) {
                TypingIndicator()
            }
            if (showActions && !msg.isStreaming && msg.text.isNotBlank() && !msg.isError) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { copyToClipboard(msg.text, clipboard, context) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, "复制回复", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { feedback = if (feedback == 1) 0 else 1 },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ThumbUp,
                            "有帮助",
                            Modifier.size(20.dp),
                            tint = if (feedback == 1) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { feedback = if (feedback == -1) 0 else -1 },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ThumbDown,
                            "需要改进",
                            Modifier.size(20.dp),
                            tint = if (feedback == -1) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (speakable) {
                        IconButton(
                            onClick = { if (isSpeaking) onStopReading() else onReadAloud(msg.text) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Rounded.VolumeUp, if (isSpeaking) "停止朗读" else "朗读", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (canRegenerate) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Refresh, "重新生成", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, "更多操作", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = { copyToClipboard(msg.text, clipboard, context); menuOpen = false },
            )
            if (canRegenerate) {
                DropdownMenuItem(
                    text = { Text("Regenerate") },
                    onClick = { onRegenerate(); menuOpen = false },
                )
            }
            if (speakable && !msg.isError) {
                DropdownMenuItem(
                    text = { Text(if (isSpeaking) "Stop" else "Read aloud") },
                    onClick = {
                        if (isSpeaking) onStopReading() else onReadAloud(msg.text)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

private fun copyToClipboard(
    text: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
) {
    if (text.isNotBlank()) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Markdown component set that renders code blocks/fences with a copy button. The library's fence
 * renderer already extracts the clean code text and hands it to this block, so we just overlay a
 * copy affordance on the default code rendering.
 */
private fun chatMarkdownComponents(): MarkdownComponents =
    markdownComponents(
        codeFence = { m ->
            MarkdownCodeFence(m.content, m.node, style = m.typography.code) { code, language, style ->
                CodeWithCopy(code, language, style)
            }
        },
        codeBlock = { m ->
            MarkdownCodeBlock(m.content, m.node, style = m.typography.code) { code, language, style ->
                CodeWithCopy(code, language, style)
            }
        },
    )

@Composable
private fun CodeWithCopy(code: String, @Suppress("UNUSED_PARAMETER") language: String?, style: TextStyle) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = code,
            style = style,
            modifier = Modifier
                // Reserve the copy-button area OUTSIDE the scroll so long code never slides under it.
                .padding(end = 44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(code))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "Copy code",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Three pulsing dots while the agent composes its first token — replaces the literal "…". */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun ThinkingCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = !expanded },
        label = { Text(if (expanded) "收起思考过程" else "查看思考过程") },
    )
    if (expanded) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun ToolCard(tool: ToolCall) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val technical = LocalToolCallTechnical.current
    val hasOutput = tool.output.isNotBlank()
    val outputSize = tool.output.toByteArray().size
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        onClick = { if (technical && hasOutput) expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (tool.status == ToolStatus.RUNNING) Icons.Rounded.PlayArrow else Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalProfileAccent.current.accent,
                )
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        tool.status == ToolStatus.RUNNING -> "运行中"
                        technical && hasOutput -> "已完成 · ${formatPayloadSize(outputSize)}"
                        else -> "已完成"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (technical && hasOutput) {
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起结果" else "展开结果",
                        modifier = Modifier.size(20.dp).padding(start = 3.dp),
                    )
                }
            }
            if (technical && expanded && hasOutput) {
                SelectionContainer {
                    Text(
                        tool.output.take(12_000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(tool.output)) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("复制结果", modifier = Modifier.padding(start = 5.dp))
                    }
                }
                if (tool.output.length > 12_000) {
                    Text(
                        "内容较长，界面仅预览前 12,000 字符；复制可获取完整结果。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun formatPayloadSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
}
