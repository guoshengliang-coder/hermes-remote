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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.FileTransferState
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
    onImageSave: (ChatImage) -> Unit = {},
    onImageSaveAs: (ChatImage) -> Unit = {},
    onImageShare: (ChatImage) -> Unit = {},
    savingImageId: String? = null,
    onFileOpen: (ChatFile) -> Unit = {},
    onFileShare: (ChatFile) -> Unit = {},
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
    // One 64ms gate for the WHOLE streaming tail — text, thinking, and tool churn together.
    // WebSocket deltas arrive far faster than display frames; recomposing the tail item per delta
    // thrashed layout, and gating only the markdown text (the previous design) still let every
    // delta reflow the thinking and tool sections around it. Each snapshot also runs the cheap
    // streaming stabilizer so half-open fences and unfinished tool payloads cannot restyle
    // already-rendered lines between snapshots. Full sanitization stays deferred to
    // message.complete — per-token regex passes would approach O(n²).
    val toolDataPlaceholder = localized(language, "工具数据接收中…", "Receiving tool data…")
    val latestTail by rememberUpdatedState(streamingTail)
    val latestPlaceholder by rememberUpdatedState(toolDataPlaceholder)
    var renderedTail by remember(sessionId) {
        mutableStateOf(streamingTail?.stabilizedForStreaming(toolDataPlaceholder))
    }
    LaunchedEffect(sessionId, streamingTail?.id, streamingTail != null) {
        if (latestTail == null) {
            renderedTail = null
            return@LaunchedEffect
        }
        var snapshotSource: ChatMessage? = null
        while (isActive) {
            val newest = latestTail
            if (newest !== snapshotSource) {
                snapshotSource = newest
                // Regex-based organization of a long tail is a few milliseconds — enough to steal
                // from a 16ms frame, so snapshot off the main thread and publish the result.
                renderedTail = if (newest == null) null else withContext(Dispatchers.Default) {
                    newest.stabilizedForStreaming(latestPlaceholder)
                }
            }
            delay(STREAM_RENDER_INTERVAL_MS)
        }
    }
    // The ticker clears/replaces renderedTail one frame after composition sees the state change.
    // Without this guard, the frame where a stream completes would merge the settled turn with
    // the stale snapshot of the same content — a one-frame duplicated-text flash.
    val effectiveTail = when {
        streamingTail == null -> null
        renderedTail?.id == streamingTail.id -> renderedTail
        else -> streamingTail // a new record's first frame; the ticker stabilizes it next pass
    }
    val displayMessages = remember(settledTurns, effectiveTail) {
        val tail = effectiveTail ?: return@remember settledTurns
        val previous = settledTurns.lastOrNull()
        if (previous?.role == Role.ASSISTANT && tail.role == Role.ASSISTANT) {
            settledTurns.dropLast(1) + mergeAssistantTurns(previous, tail)
        } else {
            settledTurns + tail
        }
    }
    val displayKeys = remember(displayMessages) { displayMessages.conversationRenderKeys() }
    // Only the most recent assistant turn can be regenerated — regenerating an earlier one
    // would silently drop everything the user and agent said after it.
    val lastAssistantId = displayMessages.lastOrNull { it.role == Role.ASSISTANT }?.id
    val processesVisible = visibleProcesses.isNotEmpty()

    // reverseLayout pins the viewport to the newest content natively: while the list rests at
    // (firstVisibleItemIndex = 0, offset = 0), a growing streaming turn stays glued to the bottom
    // with ZERO programmatic scrolling. The previous forward layout chased the tail with an
    // instant scrollToItem every 64ms — the visible stream jitter — and needed pixel-tolerance
    // "did we land" checks that made the jump button unreliable on dense screens.
    val atBottom by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Single owner of programmatic bottom snaps. A user drag holds the scroll mutex at UserInput
    // priority, so a losing Default-priority snap surfaces as a CancellationException; swallow the
    // theft (the user wants control) and rethrow only when this effect itself is being cancelled.
    // One uncaught theft used to kill the old scroll executor for the rest of the screen.
    val bottomRequests = remember(sessionId) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(sessionId, listState, bottomRequests) {
        for (request in bottomRequests) {
            try {
                listState.scrollToItem(0)
            } catch (stolen: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    // Sending always returns to the bottom, even from deep in history — it is the user's own
    // action. Keyed on the newest USER turn's stable render key, not raw list size, so history
    // reconciliation and session-resume backfill cannot fake it.
    val lastTurnKey = displayKeys.lastOrNull()
    val lastTurnIsUser = displayMessages.lastOrNull()?.role == Role.USER
    var followedUserTurnKey by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(lastTurnKey, lastTurnIsUser) {
        if (!lastTurnIsUser || lastTurnKey == null) return@LaunchedEffect
        val previous = followedUserTurnKey
        followedUserTurnKey = lastTurnKey
        if (previous != null && previous != lastTurnKey && !externalScrollActive) {
            bottomRequests.trySend(Unit)
        }
    }

    // Search-highlight navigation, mapped from turn order to the reversed list order. The size is
    // a key on purpose: new turns shift the reversed index of the same logical match. Offset 1
    // skips the permanent bottom-edge slot at index 0.
    LaunchedEffect(highlightIndex, displayMessages.size) {
        val target = highlightIndex ?: return@LaunchedEffect
        val listIndex = 1 + (displayMessages.lastIndex - target)
        if (listIndex >= 0) {
            try {
                listState.animateScrollToItem(listIndex)
            } catch (stolen: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
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

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Item 0 renders at the bottom edge. A viewport resting at (index 0, offset 0) stays
            // pinned to the newest content as it grows — the whole "follow the tail" machinery of
            // the previous forward layout is simply the physics of this orientation.
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                .testTag("chat-message-list")
                // A simple tap on conversation whitespace exits the expanded composer. Drag gestures
                // remain scrolling gestures, and taps consumed by message actions are left alone.
                .pointerInput(onBlankAreaTap) { detectTapGestures { onBlankAreaTap() } }
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            // Index 0 is a PERMANENT slot pinned to the bottom edge. When the processes card lived
            // in a conditional item, every process start/stop inserted or removed it at index 0,
            // shoving the pinned viewport up by a card height and snapping it back — the largest
            // single source of visible jumping during agent runs. A permanent slot only grows and
            // shrinks in place, which the bottom-pinned layout absorbs smoothly — and because new
            // turns now insert ABOVE this anchored slot, a pinned viewer sees them without any
            // programmatic scroll at all. The 1dp spacer keeps the empty slot measurable so the
            // at-bottom check stays exactly (index 0, offset 0).
            item(key = "bottom-edge") {
                if (processesVisible) {
                    Box(Modifier.padding(top = TURN_SPACING)) {
                        BackgroundProcessesCard(visibleProcesses)
                    }
                } else {
                    Spacer(Modifier.height(1.dp))
                }
            }
            // Stable turn keys are independent of list position and gateway ids. The latter can be
            // duplicated (model name) or replaced when REST history reconciles a locally streamed
            // turn — and with reverseLayout they are also what keeps an upward reader anchored
            // while new turns insert at the bottom of the list. Inter-turn spacing rides on each
            // turn's top edge instead of Arrangement.spacedBy so the permanent (possibly empty)
            // bottom slot never contributes a phantom gap.
            val turnCount = displayMessages.size
            itemsIndexed(
                displayMessages.asReversed(),
                key = { reversed, _ -> displayKeys[turnCount - 1 - reversed] },
            ) { reversed, msg ->
                val index = turnCount - 1 - reversed
                val canRegenerate = msg.id == lastAssistantId && !isGenerating
                val showAssistantActions = !(isGenerating && index == displayMessages.lastIndex)
                val previousTs = if (index > 0) displayMessages[index - 1].timestamp else null
                Column(Modifier.padding(top = TURN_SPACING)) {
                    if (showsTimeSeparator(previousTs, msg.timestamp)) {
                        Text(
                            text = formatTimeSeparator(msg.timestamp ?: 0L, language),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 10.dp),
                        )
                    }
                    MessageBubble(
                        msg,
                        canRegenerate,
                        showAssistantActions,
                        onEditResend,
                        onRegenerate,
                        isSpeaking,
                        onReadAloud,
                        onStopReading,
                        onImageSave,
                        onImageSaveAs,
                        onImageShare,
                        savingImageId,
                        onFileOpen,
                        onFileShare,
                        highlighted = index == highlightIndex,
                    )
                }
            }
        }
        if (!atBottom) {
            Surface(
                onClick = { bottomRequests.trySend(Unit) },
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
    }
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
                val fileSignature = message.files.joinToString(",") { "${it.name}:${it.mimeType}" }
                val fingerprint = 31 * (31 * message.text.trim().hashCode() + imageSignature.hashCode()) + fileSignature.hashCode()
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
    message.files.forEach { file ->
        next = 31 * next + file.id.hashCode()
        next = 31 * next + file.localPath.hashCode()
        next = 31 * next + file.state.hashCode()
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
    onImageSave: (ChatImage) -> Unit,
    onImageSaveAs: (ChatImage) -> Unit,
    onImageShare: (ChatImage) -> Unit,
    savingImageId: String?,
    onFileOpen: (ChatFile) -> Unit,
    onFileShare: (ChatFile) -> Unit,
    highlighted: Boolean = false,
) {
    when (msg.role) {
        Role.USER -> UserBubble(msg, onEditResend, onImageSave, onImageSaveAs, onImageShare, savingImageId, onFileOpen, onFileShare, highlighted = highlighted)
        else -> AssistantTurn(msg, canRegenerate, showAssistantActions, onRegenerate, isSpeaking, onReadAloud, onStopReading, onImageSave, onImageSaveAs, onImageShare, savingImageId, onFileOpen, onFileShare, highlighted = highlighted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    msg: ChatMessage,
    onEditResend: (String) -> Unit,
    onImageSave: (ChatImage) -> Unit,
    onImageSaveAs: (ChatImage) -> Unit,
    onImageShare: (ChatImage) -> Unit,
    savingImageId: String?,
    onFileOpen: (ChatFile) -> Unit,
    onFileShare: (ChatFile) -> Unit,
    highlighted: Boolean = false,
) {
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
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
                    ChatImageGrid(msg.images, onImageSave, onImageSaveAs, onImageShare, savingImageId)
                    if (msg.text.isNotBlank() || msg.files.isNotEmpty()) Spacer(Modifier.height(8.dp))
                }
                if (msg.files.isNotEmpty()) {
                    ChatFileList(msg.files, onFileOpen, onFileShare)
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
                DropdownMenuItem(
                    text = { Text(localized(language, "选择文本", "Select text")) },
                    onClick = { selectingText = true; menuOpen = false },
                )
            }
            if (selectingText) {
                TextSelectionDialog(text = msg.text, onDismiss = { selectingText = false })
            }
        }
    }
}

@Composable
private fun ChatImageGrid(
    images: List<ChatImage>,
    onSave: (ChatImage) -> Unit,
    onSaveAs: (ChatImage) -> Unit,
    onShare: (ChatImage) -> Unit,
    savingImageId: String?,
) {
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
    selected?.let { image ->
        FullScreenImage(
            image = image,
            saving = savingImageId == image.id,
            onSave = { onSave(image) },
            onSaveAs = { onSaveAs(image) },
            onShare = { onShare(image) },
            onDismiss = { selected = null },
        )
    }
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
            ((image.remotePath != null || image.sourceUrl != null) && image.state != ImageTransferState.FAILED)
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
private fun FullScreenImage(
    image: ChatImage,
    saving: Boolean,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    var scale by remember(image.id) { mutableStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    var menuOpen by remember(image.id) { mutableStateOf(false) }
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
            FullScreenImageAction(
                contentDescription = localized(LocalAppLanguage.current, "关闭", "Close"),
                modifier = Modifier.align(Alignment.TopStart).padding(top = 30.dp, start = 18.dp),
                onClick = onDismiss,
            ) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FullScreenImageAction(
                    contentDescription = localized(LocalAppLanguage.current, "保存图片", "Save image"),
                    enabled = !saving,
                    onClick = onSave,
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Rounded.Download, null, tint = Color.White)
                }
                FullScreenImageAction(
                    contentDescription = localized(LocalAppLanguage.current, "分享图片", "Share image"),
                    enabled = !saving,
                    onClick = onShare,
                ) { Icon(Icons.Rounded.Share, null, tint = Color.White) }
                Box {
                    FullScreenImageAction(
                        contentDescription = localized(LocalAppLanguage.current, "更多图片操作", "More image actions"),
                        enabled = !saving,
                        onClick = { menuOpen = true },
                    ) { Icon(Icons.Rounded.MoreVert, null, tint = Color.White) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(localized(LocalAppLanguage.current, "另存为…", "Save as…")) },
                            onClick = { menuOpen = false; onSaveAs() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenImageAction(
    contentDescription: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.58f),
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp)) { icon() }
        }
    }
}

@Composable
private fun ChatFileList(
    files: List<ChatFile>,
    onOpen: (ChatFile) -> Unit,
    onShare: (ChatFile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, modifier = Modifier.size(25.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        val detail = listOfNotNull(
                            file.mimeType?.substringAfter('/'),
                            file.sizeBytes?.let(::attachmentSizeLabel),
                        ).joinToString(" · ")
                        if (detail.isNotBlank()) {
                            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when (file.state) {
                        FileTransferState.UPLOADING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        FileTransferState.FAILED -> Icon(Icons.Rounded.BrokenImage, contentDescription = localized(LocalAppLanguage.current, "文件不可用", "File unavailable"))
                        FileTransferState.READY -> {
                            if (file.remotePath != null || file.localPath != null) {
                                IconButton(onClick = { onOpen(file) }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Rounded.OpenInNew, localized(LocalAppLanguage.current, "打开文件", "Open file"), modifier = Modifier.size(19.dp))
                                }
                                IconButton(onClick = { onShare(file) }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Rounded.Share, localized(LocalAppLanguage.current, "分享文件", "Share file"), modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                }
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
    onImageSave: (ChatImage) -> Unit,
    onImageSaveAs: (ChatImage) -> Unit,
    onImageShare: (ChatImage) -> Unit,
    savingImageId: String?,
    onFileOpen: (ChatFile) -> Unit,
    onFileShare: (ChatFile) -> Unit,
    highlighted: Boolean = false,
) {
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
    var feedback by remember(msg.id) { mutableStateOf(0) }
    // The streaming tail arrives pre-throttled: ChatMessageList publishes whole-message snapshots
    // at STREAM_RENDER_INTERVAL_MS, so text, thinking, and tools reflow together at one cadence.
    val renderedText = msg.text
    // The read-aloud affordance is meaningless mid-stream; skipping the regex strip until the
    // turn settles avoids running it on every render snapshot.
    val speakable = if (msg.isStreaming) false else remember(msg.text) { speechText(msg.text).isNotBlank() }
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
            if (msg.thinking.isNotBlank()) ThinkingCard(msg.id, msg.thinking)
            remember(msg.tools) { groupToolsForDisplay(msg.tools) }.forEach { group ->
                when (group) {
                    is ToolDisplayGroup.Single -> SemanticToolCard(group.tool)
                    is ToolDisplayGroup.Timeline -> ToolTimelineCard(group.tools)
                }
            }
            if (msg.images.isNotEmpty()) {
                ChatImageGrid(msg.images, onImageSave, onImageSaveAs, onImageShare, savingImageId)
                if (renderedText.isNotBlank() || msg.files.isNotEmpty()) Spacer(Modifier.height(8.dp))
            }
            if (msg.files.isNotEmpty()) {
                ChatFileList(msg.files, onFileOpen, onFileShare)
                if (renderedText.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
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
                        // Inline code renders as a soft chip on the mint surface tone, matching
                        // the approved semantic-rendering mockups.
                        colors = markdownColor(
                            inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                        ),
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
            DropdownMenuItem(
                text = { Text(localized(language, "选择文本", "Select text")) },
                onClick = { selectingText = true; menuOpen = false },
            )
        }
        if (selectingText) {
            TextSelectionDialog(text = msg.text, onDismiss = { selectingText = false })
        }
    }
}

private const val STREAM_RENDER_INTERVAL_MS = 64L
private val TURN_SPACING = 22.dp

/** "14:32" today, "昨天 14:32" yesterday, "8月30日 14:32" this year, full date otherwise. */
private fun formatTimeSeparator(ts: Long, language: com.hermes.client.ui.localization.AppLanguage): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(ts).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val date = time.toLocalDate()
    val hm = "%02d:%02d".format(time.hour, time.minute)
    val zh = language == com.hermes.client.ui.localization.AppLanguage.ZH
    return when {
        date == today -> hm
        date == today.minusDays(1) -> if (zh) "昨天 $hm" else "Yesterday $hm"
        date.year == today.year ->
            if (zh) "${time.monthValue}月${time.dayOfMonth}日 $hm" else "${date.month.name.take(3)} ${time.dayOfMonth}, $hm"
        else ->
            if (zh) "${time.year}年${time.monthValue}月${time.dayOfMonth}日 $hm" else "${date.month.name.take(3)} ${time.dayOfMonth} ${time.year}, $hm"
    }
}

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
        // Section headings get breathing room above so long answers read in visual chapters
        // (approved normal-content mockup): spacing carries the hierarchy, not decoration.
        heading2 = { m ->
            Column(Modifier.padding(top = 10.dp)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h2)
            }
        },
        heading3 = { m ->
            Column(Modifier.padding(top = 6.dp)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h3)
            }
        },
        // Tables: bordered rounded container with a tinted, semibold header row instead of the
        // library's bare default.
        table = { m ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                com.mikepenz.markdown.compose.elements.MarkdownTable(
                    m.content,
                    m.node,
                    style = m.typography.table,
                    headerBlock = { content, header, tableWidth, style ->
                        Box(
                            Modifier.background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            ),
                        ) {
                            com.mikepenz.markdown.compose.elements.MarkdownTableHeader(
                                content,
                                header,
                                tableWidth,
                                style.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    },
                    rowBlock = { content, row, tableWidth, style ->
                        com.mikepenz.markdown.compose.elements.MarkdownTableRow(
                            content,
                            row,
                            tableWidth,
                            style,
                        )
                    },
                )
            }
        },
    )

@Composable
private fun CodeWithCopy(code: String, language: String?, style: TextStyle) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    // Unified diffs get semantic red/green rows instead of a flat code block.
    if (remember(code, language) { looksLikeDiff(code, language) }) {
        DiffBlock(code)
        return
    }
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

/**
 * Full-screen plain-text selection view. The markdown body is not selectable (SelectionContainer
 * and the markdown renderer's block structure do not compose well), so partial quoting runs
 * through this dialog: the raw text, selectable, scrollable, nothing else.
 */
@Composable
private fun TextSelectionDialog(text: String, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = localized(language, "关闭", "Close"),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        localized(language, "选择文本", "Select text"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                SelectionContainer(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
            }
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
private fun ThinkingCard(messageId: String, text: String) {
    val language = LocalAppLanguage.current
    // rememberSaveable keyed by the message id: plain remember lost the expanded state whenever
    // the item scrolled out of the Lazy viewport and was recycled.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(messageId) { mutableStateOf(false) }
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

internal fun formatPayloadSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
}
