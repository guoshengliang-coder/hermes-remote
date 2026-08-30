package com.hermes.client.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import android.widget.Toast
import android.graphics.BitmapFactory
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ImageTransferState
import com.hermes.client.ui.theme.LocalToolCallTechnical
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
    externalScrollActive: Boolean = false,
    onBlankAreaTap: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    val visibleProcesses = state.backgroundProcesses.filter { it.running }
    // Hermes stores a tool-using answer as multiple adjacent assistant records. Present them as
    // one consumer-facing turn so the action row appears once and acts on the complete answer.
    // During streaming only the tail changes. Cache the settled prefix so each token groups one
    // message instead of walking a potentially huge history on the main thread.
    val streamingTail = state.messages.lastOrNull()?.takeIf { it.isStreaming }
    val settledMessages = if (streamingTail != null) state.messages.dropLast(1) else state.messages
    val settledTurns = remember(sessionId, settledMessages) {
        settledMessages.organizedConversationTurns()
    }
    val displayMessages = remember(settledTurns, streamingTail) {
        // Full sanitization runs several regex passes over the complete answer. Repeating it for
        // every token makes long replies approach O(n²). The reducer performs that cleanup once
        // when message.complete arrives, so live text can be rendered directly here.
        val tail = streamingTail ?: return@remember settledTurns
        val previous = settledTurns.lastOrNull()
        if (previous?.role == Role.ASSISTANT && tail.role == Role.ASSISTANT) {
            settledTurns.dropLast(1) + mergeAssistantTurns(previous, tail)
        } else {
            settledTurns + tail
        }
    }
    val displayKeys = remember(displayMessages) { displayMessages.conversationRenderKeys() }
    val lastIndex = displayMessages.lastIndex
    // Only the most recent assistant turn can be regenerated — regenerating an earlier one
    // would silently drop everything the user and agent said after it.
    val lastAssistantId = displayMessages.lastOrNull { it.role == Role.ASSISTANT }?.id
    // Changes for reasoning, visible answer text, and live tool activity. The previous text-only key
    // never fired while Hermes was thinking, leaving the viewport frozen until answer text arrived.
    val streamRevision = (state.messages.lastOrNull()?.streamContentRevision() ?: 0) +
        state.backgroundProcesses.sumOf { it.outputTail.length + if (it.running) 1 else 2 }
    val latestStreamRevision by rememberUpdatedState(streamRevision)
    var renderedStreamRevision by remember(sessionId) { mutableStateOf(streamRevision) }
    LaunchedEffect(sessionId, streamingTail?.id, streamingTail != null) {
        if (streamingTail == null) {
            renderedStreamRevision = latestStreamRevision
            return@LaunchedEffect
        }
        while (isActive) {
            renderedStreamRevision = latestStreamRevision
            delay(STREAM_RENDER_INTERVAL_MS)
        }
    }
    // Cached separately from the streaming tail: an authoritative history refresh may replace an
    // earlier turn without changing the message count or the final message. That must trigger a
    // second bottom calibration before the initial-entry window closes.
    val settledLayoutRevision = remember(settledMessages) {
        settledMessages.conversationLayoutRevision()
    }
    val endAnchorIndex = displayMessages.size + if (visibleProcesses.isNotEmpty()) 1 else 0
    var scrollMode by remember(sessionId) { mutableStateOf(ChatScrollMode.INITIALIZING) }
    var contentReady by remember(sessionId) { mutableStateOf(false) }
    var atTail by remember(sessionId) { mutableStateOf(false) }
    val scrollRequests = remember(sessionId) { Channel<ChatScrollRequest>(Channel.CONFLATED) }
    val latestEndAnchorIndex by rememberUpdatedState(endAnchorIndex)
    val latestScrollMode by rememberUpdatedState(scrollMode)

    // This coroutine is the only owner allowed to mutate LazyListState. Previously initial landing,
    // stream following, the bottom button, and layout observation all issued independent scrolls;
    // those mutators cancelled one another and left the list at arbitrary offsets.
    LaunchedEffect(sessionId, listState, scrollRequests) {
        suspend fun waitForTailItem(): Boolean {
            repeat(90) {
                if (listState.layoutInfo.totalItemsCount > latestEndAnchorIndex) return true
                withFrameNanos { }
            }
            return false
        }

        suspend fun settleAtTail(requiredStableFrames: Int, expectedMode: ChatScrollMode): Boolean {
            if (!waitForTailItem()) return false
            var stableFrames = 0
            var guard = 0
            while (guard++ < 24 && stableFrames < requiredStableFrames) {
                if (latestScrollMode != expectedMode) return false
                val target = latestEndAnchorIndex
                if (listState.layoutInfo.totalItemsCount <= target) {
                    withFrameNanos { }
                    continue
                }
                listState.scrollToItem(target)
                withFrameNanos { }
                stableFrames = if (listState.layoutInfo.isAtConversationTail()) stableFrames + 1 else 0
            }
            return stableFrames >= requiredStableFrames
        }

        for (request in scrollRequests) {
            when (request) {
                ChatScrollRequest.PAUSE -> Unit
                ChatScrollRequest.INITIALIZE -> {
                    val landed = settleAtTail(3, ChatScrollMode.INITIALIZING)
                    // Never keep the transcript hidden forever if a malformed item cannot settle.
                    contentReady = true
                    if (landed && latestScrollMode == ChatScrollMode.INITIALIZING) {
                        scrollMode = ChatScrollMode.FOLLOWING_TAIL
                    } else if (latestScrollMode == ChatScrollMode.INITIALIZING) {
                        scrollMode = ChatScrollMode.USER_BROWSING
                    }
                }
                ChatScrollRequest.FOLLOW -> {
                    withFrameNanos { }
                    if (contentReady && latestScrollMode == ChatScrollMode.FOLLOWING_TAIL) {
                        val target = latestEndAnchorIndex
                        if (listState.layoutInfo.totalItemsCount > target) listState.scrollToItem(target)
                    }
                }
                ChatScrollRequest.JUMP_TO_TAIL -> {
                    val landed = settleAtTail(2, ChatScrollMode.PROGRAMMATIC_JUMP)
                    if (landed && latestScrollMode == ChatScrollMode.PROGRAMMATIC_JUMP) {
                        scrollMode = ChatScrollMode.FOLLOWING_TAIL
                    }
                }
            }
        }
    }

    val hasRenderableContent = displayMessages.isNotEmpty() || visibleProcesses.isNotEmpty()
    LaunchedEffect(sessionId, hasRenderableContent) {
        if (hasRenderableContent && !contentReady) scrollRequests.trySend(ChatScrollRequest.INITIALIZE)
    }

    // A strict tail observation requires the real final anchor to be visible. canScrollForward by
    // itself transiently becomes false while Markdown/images are remeasured and used to re-enable
    // follow mode even while the user was reading older content.
    LaunchedEffect(sessionId, listState, contentReady, externalScrollActive) {
        snapshotFlow {
            TailObservation(
                atTail = listState.layoutInfo.isAtConversationTail(),
                scrolling = listState.isScrollInProgress,
            )
        }.distinctUntilChanged().collect { observation ->
            atTail = observation.atTail
            if (contentReady && observation.atTail && !observation.scrolling &&
                scrollMode == ChatScrollMode.USER_BROWSING && !externalScrollActive
            ) {
                scrollMode = ChatScrollMode.FOLLOWING_TAIL
            }
        }
    }

    val userScrollConnection = remember(sessionId, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Any deliberate drag owns the viewport, including the first upward drag that starts
                // at the absolute bottom (where canScrollForward is still false at gesture start).
                if (source == NestedScrollSource.UserInput && available != Offset.Zero && contentReady) {
                    scrollMode = ChatScrollMode.USER_BROWSING
                    scrollRequests.trySend(ChatScrollRequest.PAUSE)
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(externalScrollActive, contentReady) {
        if (externalScrollActive && contentReady) {
            scrollMode = ChatScrollMode.USER_BROWSING
            scrollRequests.trySend(ChatScrollRequest.PAUSE)
        }
    }

    // Sending resumes following. All stream/layout invalidations are conflated through the channel,
    // so token bursts can request at most one jump per rendered frame.
    LaunchedEffect(state.messages.size) {
        if (displayMessages.lastOrNull()?.role == Role.USER && contentReady && !externalScrollActive) {
            scrollMode = ChatScrollMode.FOLLOWING_TAIL
            scrollRequests.trySend(ChatScrollRequest.FOLLOW)
        }
    }
    LaunchedEffect(
        state.messages.size,
        renderedStreamRevision,
        settledLayoutRevision,
        endAnchorIndex,
        contentReady,
        externalScrollActive,
    ) {
        if (lastIndex >= 0 && contentReady && !externalScrollActive &&
            scrollMode == ChatScrollMode.FOLLOWING_TAIL
        ) {
            scrollRequests.trySend(ChatScrollRequest.FOLLOW)
        }
    }

    if (displayMessages.isEmpty() && visibleProcesses.isEmpty()) {
        when {
            state.historyLoading -> ChatHistorySkeleton(modifier.fillMaxSize())
            state.historyError != null -> Box(
                modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    localized(language, "历史消息暂时无法加载，连接恢复后会自动更新。", "History is temporarily unavailable and will update after reconnecting."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    localized(language, "发条消息，开始和 Hermes 对话。", "Send a message to start chatting with Hermes."),
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
                .testTag("chat-message-list")
                // A simple tap on conversation whitespace exits the expanded composer. Drag gestures
                // remain scrolling gestures, and taps consumed by message actions are left alone.
                .pointerInput(onBlankAreaTap) { detectTapGestures { onBlankAreaTap() } }
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // Stable turn keys are independent of list position and gateway ids. The latter can be
            // duplicated (model name) or replaced when REST history reconciles a locally streamed turn.
            itemsIndexed(
                displayMessages,
                key = { index, _ -> displayKeys[index] },
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
            if (visibleProcesses.isNotEmpty()) {
                item(key = "background-processes") {
                    BackgroundProcessesCard(visibleProcesses)
                }
            }
            item(key = "conversation-end-anchor") {
                Spacer(Modifier.height(1.dp))
            }
        }
        if (contentReady && !atTail && scrollMode == ChatScrollMode.USER_BROWSING) {
            Surface(
                onClick = {
                    scrollMode = ChatScrollMode.PROGRAMMATIC_JUMP
                    scrollRequests.trySend(ChatScrollRequest.JUMP_TO_TAIL)
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = localized(language, "回到最新消息", "Jump to latest message"),
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

private enum class ChatScrollMode { INITIALIZING, FOLLOWING_TAIL, USER_BROWSING, PROGRAMMATIC_JUMP }
private enum class ChatScrollRequest { INITIALIZE, FOLLOW, JUMP_TO_TAIL, PAUSE }
private data class TailObservation(val atTail: Boolean, val scrolling: Boolean)

private fun androidx.compose.foundation.lazy.LazyListLayoutInfo.isAtConversationTail(): Boolean {
    if (totalItemsCount == 0) return false
    val last = visibleItemsInfo.lastOrNull() ?: return false
    return last.index == totalItemsCount - 1 && last.offset + last.size <= viewportEndOffset + 2
}

/**
 * Stable render keys survive local-id to REST-id replacement and adjacent assistant-record merges.
 * User text anchors a conversation turn; assistant/system ordinals only advance inside that turn.
 */
internal fun List<ChatMessage>.conversationRenderKeys(): List<String> {
    val userOccurrences = mutableMapOf<Int, Int>()
    var userAnchor = "conversation-start"
    var assistantOrdinal = 0
    var systemOrdinal = 0
    return map { message ->
        when (message.role) {
            Role.USER -> {
                // Do not include local attachment ids or remote paths: both change after upload and
                // REST hydration. MIME/count is stable enough, with occurrences disambiguating repeats.
                val imageSignature = message.images.joinToString(",") { it.mimeType.orEmpty() }
                val fingerprint = 31 * message.text.trim().hashCode() + imageSignature.hashCode()
                val occurrence = userOccurrences.getOrDefault(fingerprint, 0)
                userOccurrences[fingerprint] = occurrence + 1
                userAnchor = "user:$fingerprint:$occurrence"
                assistantOrdinal = 0
                systemOrdinal = 0
                userAnchor
            }
            Role.ASSISTANT -> "assistant:$userAnchor:${assistantOrdinal++}"
            Role.SYSTEM -> "system:$userAnchor:${systemOrdinal++}:${message.isError}"
        }
    }
}

internal fun List<ChatMessage>.conversationLayoutRevision(): Int = fold(1) { revision, message ->
    var next = 31 * revision + message.id.hashCode()
    next = 31 * next + message.text.hashCode()
    next = 31 * next + message.thinking.hashCode()
    message.images.forEach { image ->
        next = 31 * next + image.id.hashCode()
        next = 31 * next + image.localPath.hashCode()
        next = 31 * next + image.state.hashCode()
    }
    message.tools.forEach { tool ->
        next = 31 * next + tool.id.hashCode()
        next = 31 * next + tool.name.hashCode()
        next = 31 * next + tool.output.hashCode()
        next = 31 * next + tool.status.hashCode()
    }
    next
}

internal fun ChatMessage.streamContentRevision(): Int =
    text.length + thinking.length + images.sumOf { it.localPath.orEmpty().length + it.state.ordinal } + tools.sumOf { tool ->
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
    val language = LocalAppLanguage.current
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
                if (msg.images.isNotEmpty()) {
                    ChatImageGrid(msg.images)
                    if (msg.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
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
                    text = { Text(localized(language, "复制", "Copy")) },
                    onClick = { copyToClipboard(msg.text, clipboard, context, localized(language, "已复制", "Copied")); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(localized(language, "编辑并重新发送", "Edit & resend")) },
                    onClick = { onEditResend(msg.text); menuOpen = false },
                )
            }
        }
    }
}

@Composable
private fun ChatImageGrid(images: List<ChatImage>) {
    var selected by remember { mutableStateOf<ChatImage?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.chunked(2).forEach { rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowImages.forEach { image ->
                    ChatImageThumbnail(
                        image = image,
                        modifier = Modifier.weight(1f).height(if (images.size == 1) 190.dp else 132.dp),
                        onClick = { if (image.localPath != null) selected = image },
                    )
                }
                if (rowImages.size == 1 && images.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    selected?.let { image -> FullScreenImage(image) { selected = null } }
}

@Composable
private fun ChatImageThumbnail(image: ChatImage, modifier: Modifier, onClick: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image.localPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            image.localPath?.let { decodeImageFile(it, 900) }
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = localized(LocalAppLanguage.current, "聊天图片", "Chat image"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (image.state == ImageTransferState.UPLOADING ||
            (image.remotePath != null && image.state != ImageTransferState.FAILED)
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Rounded.BrokenImage,
                contentDescription = localized(LocalAppLanguage.current, "图片加载失败", "Image unavailable"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FullScreenImage(image: ChatImage, onDismiss: () -> Unit) {
    var scale by remember(image.id) { mutableStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image.localPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            image.localPath?.let { decodeImageFile(it, 4096) }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(image.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = localized(LocalAppLanguage.current, "查看原图", "View full image"),
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

private fun decodeImageFile(path: String, requestedPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > requestedPx * 2 || bounds.outHeight / sample > requestedPx * 2) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}

@Composable
private fun BackgroundProcessesCard(processes: List<com.hermes.client.data.repository.BackgroundProcess>) {
    val language = LocalAppLanguage.current
    val running = processes.count { it.running }
    var expanded by remember(processes.map { it.id }) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running > 0) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (running > 0) localized(language, "后台任务运行中 · $running", "$running background task(s) running")
                    else localized(language, "后台任务已结束", "Background tasks finished"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            processes.forEach { process ->
                Text(
                    process.command.ifBlank { localized(language, "后台进程", "Background process") },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded && process.outputTail.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            process.outputTail.takeLast(4_000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var feedback by remember(msg.id) { mutableStateOf(0) }
    val speakable = remember(msg.text) { speechText(msg.text).isNotBlank() }
    val accent = LocalProfileAccent.current.accent
    val hlShape = RoundedCornerShape(12.dp)
    val latestText by rememberUpdatedState(msg.text)
    var renderedText by remember(msg.id) { mutableStateOf(msg.text) }
    LaunchedEffect(msg.id, msg.isStreaming, msg.text.takeUnless { msg.isStreaming }) {
        if (!msg.isStreaming) {
            renderedText = latestText
            return@LaunchedEffect
        }
        // WebSocket deltas can arrive faster than display frames. Rebuilding a complete Markdown
        // tree for every token causes repeated height changes and main-thread layout thrash; keep
        // the state authoritative but publish render snapshots at a human-invisible cadence.
        while (isActive) {
            val newest = latestText
            if (renderedText != newest) renderedText = newest
            delay(STREAM_RENDER_INTERVAL_MS)
        }
    }
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
            if (renderedText.isNotBlank()) {
                if (msg.isError) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            renderedText,
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
                        content = renderedText,
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
                        onClick = { copyToClipboard(msg.text, clipboard, context, localized(language, "已复制", "Copied")) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, localized(language, "复制回复", "Copy response"), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { feedback = if (feedback == 1) 0 else 1 },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ThumbUp,
                            localized(language, "有帮助", "Helpful"),
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
                            localized(language, "需要改进", "Needs improvement"),
                            Modifier.size(20.dp),
                            tint = if (feedback == -1) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (speakable) {
                        IconButton(
                            onClick = { if (isSpeaking) onStopReading() else onReadAloud(msg.text) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Rounded.VolumeUp, if (isSpeaking) localized(language, "停止朗读", "Stop reading") else localized(language, "朗读", "Read aloud"), Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (canRegenerate) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Refresh, localized(language, "重新生成", "Regenerate"), Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, localized(language, "更多操作", "More actions"), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(localized(language, "复制", "Copy")) },
                onClick = { copyToClipboard(msg.text, clipboard, context, localized(language, "已复制", "Copied")); menuOpen = false },
            )
            if (canRegenerate) {
                DropdownMenuItem(
                    text = { Text(localized(language, "重新生成", "Regenerate")) },
                    onClick = { onRegenerate(); menuOpen = false },
                )
            }
            if (speakable && !msg.isError) {
                DropdownMenuItem(
                    text = { Text(if (isSpeaking) localized(language, "停止", "Stop") else localized(language, "朗读", "Read aloud")) },
                    onClick = {
                        if (isSpeaking) onStopReading() else onReadAloud(msg.text)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

private const val STREAM_RENDER_INTERVAL_MS = 64L

private fun copyToClipboard(
    text: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
    copiedMessage: String,
) {
    if (text.isNotBlank()) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
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
    val appLanguage = LocalAppLanguage.current
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
                Toast.makeText(context, localized(appLanguage, "代码已复制", "Code copied"), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = localized(appLanguage, "复制代码", "Copy code"),
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
    val language = LocalAppLanguage.current
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = !expanded },
        label = { Text(if (expanded) localized(language, "收起思考过程", "Hide reasoning") else localized(language, "查看思考过程", "View reasoning")) },
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
    val language = LocalAppLanguage.current
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
                        tool.status == ToolStatus.RUNNING -> localized(language, "运行中", "Running")
                        technical && hasOutput -> localized(language, "已完成 · ${formatPayloadSize(outputSize)}", "Completed · ${formatPayloadSize(outputSize)}")
                        else -> localized(language, "已完成", "Completed")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (technical && hasOutput) {
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) localized(language, "收起结果", "Collapse result") else localized(language, "展开结果", "Expand result"),
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
                        Text(localized(language, "复制结果", "Copy result"), modifier = Modifier.padding(start = 5.dp))
                    }
                }
                if (tool.output.length > 12_000) {
                    Text(
                        localized(language, "内容较长，界面仅预览前 12,000 字符；复制可获取完整结果。", "Long content: the app previews 12,000 characters. Copy to get the full result."),
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
