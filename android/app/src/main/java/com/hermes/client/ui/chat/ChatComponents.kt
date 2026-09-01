package com.hermes.client.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding

data class ChatViewportAnchor(val blockKey: String, val offsetFromTopPx: Float)

/**
 * Semantic viewport state shared by the transcript and fullscreen overlays. Block coordinates are
 * deliberately transient; only the stable block key and its visual offset are saveable.
 */
class ChatViewportController(restored: ChatViewportAnchor? = null) {
    private val blockBounds = mutableMapOf<String, Rect>()
    private var viewportBounds: Rect? = null
    private var heldAnchor: ChatViewportAnchor? = restored
    private var lastWidth = 0
    private var lastConfigurationWidth = 0
    private var pinnedToBottom = true

    var restoreGeneration by androidx.compose.runtime.mutableIntStateOf(if (restored == null) 0 else 1)
        private set

    fun updateViewport(bounds: Rect) { viewportBounds = bounds }
    fun updateBlock(key: String, bounds: Rect) { blockBounds[key] = bounds }
    fun removeBlock(key: String) { blockBounds.remove(key) }
    fun setPinnedToBottom(value: Boolean) { pinnedToBottom = value }

    fun currentAnchor(): ChatViewportAnchor? {
        if (pinnedToBottom) return heldAnchor
        val viewport = viewportBounds ?: return heldAnchor
        val visible = blockBounds.entries.filter { (_, bounds) ->
            bounds.bottom > viewport.top && bounds.top < viewport.bottom
        }
        val chosen = visible
            .filter { (_, bounds) -> bounds.top <= viewport.top && bounds.bottom > viewport.top }
            .minByOrNull { (_, bounds) -> bounds.height }
            ?: visible.minByOrNull { (_, bounds) -> kotlin.math.abs(bounds.top - viewport.top) }
            ?: return heldAnchor
        return ChatViewportAnchor(chosen.key, chosen.value.top - viewport.top)
    }

    fun holdCurrent() { currentAnchor()?.let { heldAnchor = it } }

    fun requestHeldRestore() {
        if (heldAnchor != null) restoreGeneration++
    }

    fun onViewportWidth(width: Int) {
        if (lastWidth > 0 && width > 0 && width != lastWidth) {
            // onSizeChanged runs before the children's new global positions are published, so the
            // registry still describes the old layout at this point.
            if (heldAnchor == null) holdCurrent()
            requestHeldRestore()
        }
        if (width > 0) lastWidth = width
    }

    fun onConfigurationWidth(widthDp: Int) {
        if (lastConfigurationWidth > 0 && widthDp > 0 && widthDp != lastConfigurationWidth) {
            // Composition observes configuration/window-width changes before the new child layout
            // is positioned, which is the most reliable capture edge across foldable OEMs.
            if (heldAnchor == null) holdCurrent()
            requestHeldRestore()
        }
        if (widthDp > 0) lastConfigurationWidth = widthDp
    }

    fun correctionPx(): Float? {
        val anchor = heldAnchor ?: return null
        val viewport = viewportBounds ?: return null
        val block = blockBounds[anchor.blockKey] ?: return null
        return block.top - (viewport.top + anchor.offsetFromTopPx)
    }

    fun finishRestore(generation: Int) {
        if (generation == restoreGeneration) heldAnchor = null
    }

    fun saveAnchor(): ChatViewportAnchor? = heldAnchor ?: currentAnchor()

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<ChatViewportController, Any>(
            save = { controller ->
                controller.saveAnchor()?.let { listOf(it.blockKey, it.offsetFromTopPx) } ?: emptyList()
            },
            restore = { values ->
                if (values.size < 2) ChatViewportController()
                else ChatViewportController(ChatViewportAnchor(values[0] as String, values[1] as Float))
            },
        )
    }
}

private val LocalChatViewportController = staticCompositionLocalOf<ChatViewportController?> { null }

@Composable
fun ChatMessageList(
    state: ChatUiState,
    sessionId: String,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    isGenerating: Boolean = false,
    onEditResend: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onRetryWithModel: () -> Unit = {},
    onOpenTableFullscreen: (String) -> Unit = {},
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
    scrollToBottomTick: Long = 0L,
    viewportController: ChatViewportController? = null,
    onBlankAreaTap: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    val semanticViewport = viewportController ?: remember(sessionId) { ChatViewportController() }
    val configurationWidth = LocalConfiguration.current.screenWidthDp
    androidx.compose.runtime.DisposableEffect(semanticViewport, configurationWidth) {
        semanticViewport.onConfigurationWidth(configurationWidth)
        onDispose { }
    }
    val visibleProcesses = state.backgroundProcesses.filter { it.running }
    // Hermes stores a tool-using answer as multiple adjacent assistant records. Present them as
    // one consumer-facing turn so the action row appears once and acts on the complete answer.
    // During streaming only the tail changes. Cache the settled prefix so each token groups one
    // message instead of walking a potentially huge history on the main thread.
    // One 64ms gate for the WHOLE streaming tail — text, thinking, and tool churn together.
    // WebSocket deltas arrive far faster than display frames; recomposing the tail item per delta
    // thrashed layout, and gating only the markdown text (the previous design) still let every
    // delta reflow the thinking and tool sections around it. Each snapshot also runs the cheap
    // streaming stabilizer so half-open fences and unfinished tool payloads cannot restyle
    // already-rendered lines between snapshots. Full sanitization stays deferred to
    // message.complete — per-token regex passes would approach O(n²).
    val toolDataPlaceholder = localized(language, "工具数据接收中…", "Receiving tool data…")
    val latestLastMessage by rememberUpdatedState(state.messages.lastOrNull())
    val latestPlaceholder by rememberUpdatedState(toolDataPlaceholder)
    var activeStreamId by androidx.compose.runtime.saveable.rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var revealedCount by androidx.compose.runtime.saveable.rememberSaveable(sessionId) { androidx.compose.runtime.mutableIntStateOf(0) }
    var renderedTail by remember(sessionId) { mutableStateOf<ChatMessage?>(null) }
    LaunchedEffect(sessionId) {
        var renderedSource: ChatMessage? = null
        // Typewriter reveal: decouple the display from the network's bursty delta cadence.
        // This executor deliberately survives message.start -> delta -> complete. The previous
        // effect restarted around those boundaries and briefly rendered the authoritative full
        // tail before rewinding to a short prefix. Completion also bypassed the reveal buffer and
        // replaced it with the full answer in one frame. Both paths looked like screen flashing.
        while (isActive) {
            val newest = latestLastMessage?.takeIf { it.role == Role.ASSISTANT }
            if (newest?.isStreaming == true && activeStreamId != newest.id) {
                activeStreamId = newest.id
                revealedCount = 0
                renderedSource = null
                renderedTail = newest.copy(text = "", thinking = "", tools = emptyList(), images = emptyList(), files = emptyList())
            }
            if (newest != null && newest.id == activeStreamId) {
                val target = newest.text.length
                // An authoritative completion may correct/shorten the last delta. That is the only
                // legal backwards move; ordinary streaming prefixes are strictly monotone.
                if (target < revealedCount) revealedCount = target
                val paced = nextRevealCount(revealedCount, target)
                if (newest !== renderedSource || paced != revealedCount) {
                    renderedSource = newest
                    revealedCount = paced
                    val cut = surrogateSafeCut(newest.text, revealedCount)
                    val visible = if (cut >= target) newest else newest.copy(text = newest.text.take(cut))
                    // Regex-based organization of a long tail is a few milliseconds — enough to
                    // steal from a 16ms frame, so snapshot off the main thread.
                    renderedTail = withContext(Dispatchers.Default) {
                        visible.stabilizedForStreaming(latestPlaceholder)
                    }
                }
                if (!newest.isStreaming && revealedCount >= target) {
                    // Publish the exact final presentation first, then release the visual buffer on
                    // the next frame. The settled transcript takes over with identical content, so
                    // message.complete no longer produces a final full-answer jump.
                    renderedTail = withContext(Dispatchers.Default) { newest.organizedForDisplay() }
                    androidx.compose.runtime.withFrameNanos { }
                    if (latestLastMessage?.id == newest.id && latestLastMessage?.isStreaming == false) {
                        activeStreamId = null
                        renderedTail = null
                        renderedSource = null
                    }
                }
            }
            delay(STREAM_RENDER_INTERVAL_MS)
        }
    }
    // Keep the authoritative final record out of the settled prefix while its visual buffer drains.
    // A newly observed streaming record starts as an empty indicator instead of flashing a burst
    // of text and then rewinding when the reveal executor gets its first frame.
    val presentingSource = state.messages.lastOrNull()?.takeIf { last ->
        last.role == Role.ASSISTANT && (last.isStreaming || last.id == activeStreamId)
    }
    val settledMessages = if (presentingSource != null) state.messages.dropLast(1) else state.messages
    val settledTurns = remember(sessionId, settledMessages) { settledMessages.organizedConversationTurns() }
    val effectiveTail = presentingSource?.let { source ->
        renderedTail?.takeIf { it.id == source.id }
            ?: if (activeStreamId == source.id && revealedCount > 0) {
                val cut = surrogateSafeCut(source.text, revealedCount.coerceAtMost(source.text.length))
                source.copy(text = source.text.take(cut)).stabilizedForStreaming(toolDataPlaceholder)
            } else {
                source.copy(text = "", thinking = "", tools = emptyList(), images = emptyList(), files = emptyList())
            }
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
    androidx.compose.runtime.SideEffect { semanticViewport.setPinnedToBottom(atBottom) }

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

    // Sending returns to the bottom because the USER pressed send — driven directly by that
    // action via [scrollToBottomTick]. The previous inference (watching the last user turn's
    // content-derived render key) misfired whenever background history reconciliation nudged
    // that key (text normalization, image metadata hydration, occurrence shifts), yanking a
    // reader mid-history to the bottom. Data changes must never steal the viewport.
    LaunchedEffect(scrollToBottomTick) {
        if (scrollToBottomTick > 0L) {
            // submit() updates the tick before the ViewModel's launched send job appends the user
            // turn, and the IME/composer also resize the viewport. Wait for those two layout edges
            // so the one explicit snap targets the new transcript, not the old pre-send bottom.
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            bottomRequests.trySend(Unit)
        }
    }

    // ---- Diagnostics probes ---------------------------------------------------------------
    // Installed ONLY while Settings→诊断 is on. The previous always-installed version read
    // listState.layoutInfo inside snapshotFlow — that made it emit on EVERY layout pass of a
    // fling and burned a main-thread collect per frame even with diagnostics off, which is
    // exactly the kind of frame cost that collapses a hard fling into a single-frame jump.
    val probesEnabled = com.hermes.client.data.diagnostics.DebugLog.isEnabled()
    if (probesEnabled) {
        // Probe A: viewport anchor movements. Observes only the anchor INDEX (offset/layoutInfo
        // deliberately not read); the key is sampled once, only when a jump is detected.
        LaunchedEffect(listState, sessionId) {
            var lastIndex = -1
            androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
                .collect { index ->
                    if (lastIndex > 3 && index <= 1) {
                        com.hermes.client.data.diagnostics.DebugLog.log(
                            "anchor",
                            "JUMP index $lastIndex->$index key=" +
                                "${listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key} " +
                                "scrolling=${listState.isScrollInProgress}",
                        )
                    }
                    lastIndex = index
                }
        }
        // Probe B: render-key churn — the signature of keys being reshuffled under a reader.
        var probeKeys by remember(sessionId) { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(displayKeys) {
            val old = probeKeys
            if (old.isNotEmpty() && old.size == displayKeys.size && old != displayKeys) {
                val changed = old.indices.count { old[it] != displayKeys[it] }
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "keys",
                    "reshuffle: $changed/${displayKeys.size} keys changed, tail=${displayKeys.takeLast(2)}",
                )
            } else if (old.size != displayKeys.size) {
                com.hermes.client.data.diagnostics.DebugLog.log("keys", "count ${old.size} -> ${displayKeys.size}")
            }
            probeKeys = displayKeys
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

    val restoreGeneration = semanticViewport.restoreGeneration
    LaunchedEffect(restoreGeneration, listState) {
        if (restoreGeneration <= 0) return@LaunchedEffect
        var foundAnchor = false
        for (attempt in 0 until 30) {
            androidx.compose.runtime.withFrameNanos { }
            val correction = semanticViewport.correctionPx() ?: continue
            foundAnchor = true
            if (kotlin.math.abs(correction) <= 1f) break
            try {
                // reverseLayout reverses the scroll axis: a block that moved down by +N pixels
                // needs a -N programmatic delta to return to its previous window coordinate.
                listState.scrollBy(-correction)
            } catch (stolen: CancellationException) {
                currentCoroutineContext().ensureActive()
                return@LaunchedEffect
            }
        }
        if (foundAnchor) semanticViewport.finishRestore(restoreGeneration)
    }

    // Initial presentation gate: cached rows may already exist while the authoritative history
    // request is still in flight. Showing those rows and replacing them a frame later produced the
    // cache -> REST -> bottom ghost flash. An actively running transcript remains visible because
    // its live state is newer than REST and acceptHistory will retain it.
    if (state.historyLoading && !state.isGenerating) {
        ChatHistorySkeleton(modifier.fillMaxSize())
        return
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

    // Claude-style keyboard dismissal: a downward drag on the conversation collapses the IME
    // instead of requiring a tap on whitespace. Observation-only — it never consumes scroll.
    val keyboard = LocalSoftwareKeyboardController.current
    val imeDismissConnection = remember(keyboard) {
        object : NestedScrollConnection {
            // hide() is an IPC; calling it on every pre-scroll frame of a downward drag was a
            // per-frame cost on the hot scroll path. Latch once per gesture, re-arm on rest.
            private var hidThisGesture = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 4f && !hidThisGesture) {
                    hidThisGesture = true
                    keyboard?.hide()
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                hidThisGesture = false
                return Velocity.Zero
            }
        }
    }

    CompositionLocalProvider(LocalChatViewportController provides semanticViewport) {
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
                .onGloballyPositioned { semanticViewport.updateViewport(it.boundsInWindow()) }
                .onSizeChanged { semanticViewport.onViewportWidth(it.width) }
                // A simple tap on conversation whitespace exits the expanded composer. Drag gestures
                // remain scrolling gestures, and taps consumed by message actions are left alone.
                .pointerInput(onBlankAreaTap) { detectTapGestures { onBlankAreaTap() } }
                .nestedScroll(imeDismissConnection)
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
                // The action row is persistent only on the LATEST assistant turn; every earlier
                // turn reaches the same actions through its long-press menu. A row under every
                // turn put 6+ icons on screen per answer and duplicated that menu.
                val showAssistantActions = msg.id == lastAssistantId && !isGenerating
                val previousTs = if (index > 0) displayMessages[index - 1].timestamp else null
                val turnAnchorKey = "${msg.id}:turn"
                val turnViewport = LocalChatViewportController.current
                androidx.compose.runtime.DisposableEffect(turnViewport, turnAnchorKey) {
                    onDispose { turnViewport?.removeBlock(turnAnchorKey) }
                }
                Column(
                    Modifier
                        .padding(top = TURN_SPACING)
                        .onGloballyPositioned { turnViewport?.updateBlock(turnAnchorKey, it.boundsInWindow()) },
                ) {
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
                        onRetryWithModel,
                        onOpenTableFullscreen,
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
        // Hidden while ANY scroll (drag or fling) is in progress: the tap that arrests a hard
        // fling is the same gesture users aim at the conversation, and with the button already
        // materialized under the thumb it used to swallow that tap as a click -> scrollToItem(0)
        // -> the "sudden snap to bottom". While scrolling the node simply does not exist, so the
        // arresting tap cannot hit it. Avoid an exit animation here: AnimatedVisibility keeps its
        // exiting subtree interactive until the fade completes, recreating the same ghost target.
        if (!atBottom && !listState.isScrollInProgress) {
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
                    // Neutral tint + 48dp target: this is a navigation aid, not a semantic
                    // action, so it follows the composer's quiet icon language instead of
                    // spending accent color (audit: accent density already runs high).
                    modifier = Modifier.padding(12.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}

/**
 * Stable render keys survive local-id to REST-id replacement and adjacent assistant-record merges.
 * User text anchors a conversation turn; assistant/system ordinals only advance inside that turn.
 */
internal fun List<ChatMessage>.conversationRenderKeys(): List<String> = map { it.id }

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
        // Bottom-anchored like the reverseLayout transcript it precedes, so the skeleton->content
        // transition doesn't move the visual mass from the top of the pane to the bottom.
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
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
    onRetryWithModel: () -> Unit,
    onOpenTableFullscreen: (String) -> Unit,
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
        else -> AssistantTurn(msg, canRegenerate, showAssistantActions, onRegenerate, onRetryWithModel, onOpenTableFullscreen, isSpeaking, onReadAloud, onStopReading, onImageSave, onImageSaveAs, onImageShare, savingImageId, onFileOpen, onFileShare, highlighted = highlighted)
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
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
    val bg = if (msg.isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    val accent = MaterialTheme.colorScheme.primary
    val userShape = RoundedCornerShape(22.dp, 22.dp, 7.dp, 22.dp)
    // Proportional cap instead of a fixed 320dp: a fixed value reads fine on a phone but
    // leaves user bubbles oddly narrow on tablets/landscape. ~82% tracks the Claude app.
    val bubbleMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            Column(
                Modifier
                    .widthIn(max = bubbleMaxWidth)
                    // Asymmetric corners (a small "tail" corner) mark this as the sender's bubble.
                    .clip(userShape)
                    .background(bg)
                    .then(if (highlighted) Modifier.background(accent.copy(alpha = 0.18f)).border(1.5.dp, accent, userShape) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 11.dp)
                    .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }),
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
            if (menuOpen) {
                MessageActionSheet(
                    actions = listOf(
                        MessageAction(Icons.Rounded.ContentCopy, localized(language, "复制", "Copy")) {
                            copyToClipboard(msg.text, clipboard, context, localized(language, "已复制", "Copied"))
                        },
                        MessageAction(Icons.Rounded.Edit, localized(language, "编辑并重新发送", "Edit & resend")) { onEditResend(msg.text) },
                        MessageAction(Icons.Rounded.SelectAll, localized(language, "选择文本", "Select text")) { selectingText = true },
                    ),
                    onDismiss = { menuOpen = false },
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
    onRetryWithModel: () -> Unit,
    onOpenTableFullscreen: (String) -> Unit,
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
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
    var feedback by remember(msg.id) { mutableStateOf(0) }
    // The streaming tail arrives pre-throttled: ChatMessageList publishes whole-message snapshots
    // at STREAM_RENDER_INTERVAL_MS, so text, thinking, and tools reflow together at one cadence.
    val renderedText = msg.text
    // The read-aloud affordance is meaningless mid-stream; skipping the regex strip until the
    // turn settles avoids running it on every render snapshot.
    val speakable = if (msg.isStreaming) false else remember(msg.text) { speechText(msg.text).isNotBlank() }
    val accent = MaterialTheme.colorScheme.primary
    val hlShape = RoundedCornerShape(12.dp)
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                // No conditional padding here: background/border draw within existing bounds, so
                // toggling the highlight causes no layout shift (a conditional .padding would).
                .then(if (highlighted) Modifier.clip(hlShape).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, hlShape) else Modifier)
                .padding(vertical = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }),
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
                    val mdComponents = remember(onOpenTableFullscreen) { chatMarkdownComponents(onOpenTableFullscreen) }
                    val blocks = remember(renderedText) { markdownRenderBlocks(renderedText) }
                    Column {
                        blocks.forEachIndexed { blockIndex, block ->
                            key(blockIndex) {
                                AssistantMarkdownBlock(
                                    content = block,
                                    components = mdComponents,
                                    anchorKey = "${msg.id}:markdown:$blockIndex",
                                    modifier = Modifier.testTag("chat-block-${msg.id}-$blockIndex"),
                                )
                            }
                        }
                    }
                }
            }
            val showCompletedActions = showActions && !msg.isStreaming && msg.text.isNotBlank() && !msg.isError
            if (msg.isStreaming || showCompletedActions) Box(Modifier.fillMaxWidth().height(48.dp)) {
                if (msg.isStreaming) {
                    // Reserve the same footer height during and after a run. Replacing a short
                    // status row with the 48dp action row used to grow the last item exactly when
                    // message.complete arrived, producing the final visible jump to the bottom.
                    if (msg.text.isBlank() && msg.tools.isEmpty() && msg.thinking.isBlank()) {
                        TypingIndicator()
                    } else {
                        RunningStatusLine(msg)
                    }
                } else {
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart),
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
        }
        if (menuOpen) {
            MessageActionSheet(
                actions = buildList {
                    add(MessageAction(Icons.Rounded.ContentCopy, localized(language, "复制", "Copy")) {
                        copyToClipboard(msg.text, clipboard, context, localized(language, "已复制", "Copied"))
                    })
                    if (canRegenerate) {
                        add(MessageAction(Icons.Rounded.Refresh, localized(language, "重新生成", "Regenerate")) { onRegenerate() })
                        add(MessageAction(Icons.Rounded.SwapHoriz, localized(language, "换个模型重试", "Retry with another model")) { onRetryWithModel() })
                    }
                    if (speakable && !msg.isError) {
                        add(
                            MessageAction(
                                Icons.Rounded.VolumeUp,
                                if (isSpeaking) localized(language, "停止朗读", "Stop reading") else localized(language, "朗读", "Read aloud"),
                            ) { if (isSpeaking) onStopReading() else onReadAloud(msg.text) },
                        )
                    }
                    add(MessageAction(Icons.Rounded.SelectAll, localized(language, "选择文本", "Select text")) { selectingText = true })
                },
                onDismiss = { menuOpen = false },
            )
        }
        if (selectingText) {
            TextSelectionDialog(text = msg.text, onDismiss = { selectingText = false })
        }
    }
}

@Composable
private fun AssistantMarkdownBlock(
    content: String,
    components: MarkdownComponents,
    anchorKey: String,
    modifier: Modifier = Modifier,
) {
    val viewport = LocalChatViewportController.current
    androidx.compose.runtime.DisposableEffect(viewport, anchorKey) {
        onDispose { viewport?.removeBlock(anchorKey) }
    }
    val body = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 17.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.sp,
    )
    Markdown(
        content = content,
        modifier = modifier.onGloballyPositioned { viewport?.updateBlock(anchorKey, it.boundsInWindow()) },
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
        components = components,
        padding = markdownPadding(block = 5.dp, listItemTop = 2.dp, listItemBottom = 2.dp),
        dimens = markdownDimens(tableCellWidth = 110.dp, tableCellPadding = 8.dp),
    )
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
private fun chatMarkdownComponents(onOpenTableFullscreen: (String) -> Unit): MarkdownComponents =
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
            Column(Modifier.padding(top = 6.dp)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h2)
            }
        },
        heading3 = { m ->
            Column(Modifier.padding(top = 4.dp)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h3)
            }
        },
        // Tables render as a card with a header bar (label + copy-as-TSV + fullscreen), matching
        // the code-block header pattern. Copy produces tab-separated text that pastes into
        // Excel/Sheets as real cells; fullscreen opens a roomy dialog for wide tables.
        table = { m ->
            val raw = remember(m.content, m.node) {
                m.content.substring(m.node.startOffset, m.node.endOffset)
            }
            ChatTableCard(raw = raw, onOpenFullscreen = { onOpenTableFullscreen(raw) }) {
                StyledMarkdownTable(m.content, m.node, m.typography.table)
            }
        },
    )

/** The styled table body shared by the in-chat card and the fullscreen dialog. */
@Composable
private fun StyledMarkdownTable(content: String, node: org.intellij.markdown.ast.ASTNode, style: TextStyle) {
    // Faint full grid (WorkBuddy-style): light enough to stay quiet, present enough that a
    // wrapped multi-line cell reads unambiguously as one column.
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    fun Modifier.tableGrid(cells: Int, drawTop: Boolean = false): Modifier = drawBehind {
        val stroke = 1.dp.toPx()
        if (drawTop) drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), stroke)
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), stroke)
        if (cells > 1) {
            val cellWidth = size.width / cells
            for (i in 1 until cells) {
                val x = cellWidth * i
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), stroke)
            }
        }
    }
    fun cellCount(rowNode: org.intellij.markdown.ast.ASTNode): Int =
        rowNode.children.count { it.type == org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL }.coerceAtLeast(1)
    com.mikepenz.markdown.compose.elements.MarkdownTable(
        content,
        node,
        style = style,
        headerBlock = { c, header, tableWidth, s ->
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .tableGrid(cellCount(header)),
            ) {
                com.mikepenz.markdown.compose.elements.MarkdownTableHeader(
                    c,
                    header,
                    tableWidth,
                    s.copy(fontWeight = FontWeight.SemiBold),
                    // Library default is maxLines = 1 + ellipsis, which silently truncated
                    // real cell content on device. Wrap instead.
                    maxLines = Int.MAX_VALUE,
                )
            }
        },
        rowBlock = { c, row, tableWidth, s ->
            Box(Modifier.tableGrid(cellCount(row))) {
                com.mikepenz.markdown.compose.elements.MarkdownTableRow(
                    c,
                    row,
                    tableWidth,
                    s,
                    maxLines = Int.MAX_VALUE,
                )
            }
        },
    )
}

/** Gallery/sample entry: renders a raw markdown table with the chat table styling. */
@Composable
internal fun StyledMarkdownTableSample(raw: String) {
    Markdown(
        content = raw,
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(),
        typography = markdownTypography(
            table = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
        ),
        components = markdownComponents(
            table = { m -> StyledMarkdownTable(m.content, m.node, m.typography.table) },
        ),
        dimens = markdownDimens(tableCellWidth = 110.dp, tableCellPadding = 8.dp),
    )
}

internal enum class TableExportAction { SAVE, SHARE }

/**
 * Renders [raw] as a FULL-WIDTH table on a zero-sized, non-clipping host: the content records
 * into a GraphicsLayer without ever drawing to screen, so exports contain every column even
 * when the on-screen card shows a scrollable slice. immediate parsing guarantees the table is
 * present on the first frame; two frame-waits let layout+draw complete before capture.
 */
@Composable
internal fun OffscreenTableExporter(raw: String, action: TableExportAction, onDone: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val columns = remember(raw) { markdownTableColumnCount(raw) }
    val tableWidth = (columns.coerceAtLeast(1) * 170 + 24).dp
    val mdState = com.mikepenz.markdown.model.rememberMarkdownState(raw, immediate = true)
    Box(
        Modifier
            .size(0.dp)
            .wrapContentSize(align = Alignment.TopStart, unbounded = true),
    ) {
        Column(
            Modifier
                .width(tableWidth)
                .background(MaterialTheme.colorScheme.background)
                // record only — no drawLayer, so nothing appears on screen.
                .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
        ) {
            Markdown(
                markdownState = mdState,
                modifier = Modifier.fillMaxWidth(),
                colors = markdownColor(),
                typography = markdownTypography(
                    table = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp),
                ),
                components = markdownComponents(
                    table = { m -> StyledMarkdownTable(m.content, m.node, m.typography.table) },
                ),
                dimens = markdownDimens(tableCellWidth = 170.dp, tableCellPadding = 10.dp),
            )
        }
    }
    LaunchedEffect(raw, action) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        runCatching {
            val bmp = layer.toImageBitmap().asAndroidBitmap()
            when (action) {
                TableExportAction.SAVE -> {
                    val uri = TableExport.saveToGallery(context, bmp)
                    Toast.makeText(
                        context,
                        if (uri != null) localized(language, "已保存到相册", "Saved to gallery")
                        else localized(language, "保存失败", "Couldn't save"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                TableExportAction.SHARE ->
                    TableExport.share(context, bmp, localized(language, "分享表格图片", "Share table image"))
            }
        }.onFailure {
            Toast.makeText(context, localized(language, "导出失败", "Export failed"), Toast.LENGTH_SHORT).show()
        }
        onDone()
    }
}

@Composable
internal fun ChatTableCard(
    raw: String,
    onOpenFullscreen: () -> Unit,
    content: @Composable () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var exportAction by remember { mutableStateOf<TableExportAction?>(null) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    localized(language, "表格", "Table"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(markdownTableToTsv(raw)))
                        Toast.makeText(context, localized(language, "表格已复制，可直接粘贴为单元格", "Table copied as cells"), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = localized(language, "复制表格", "Copy table"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { if (exportAction == null) exportAction = TableExportAction.SAVE },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = localized(language, "保存为图片", "Save as image"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { if (exportAction == null) exportAction = TableExportAction.SHARE },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = localized(language, "分享表格图片", "Share table image"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenFullscreen, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.OpenInFull,
                        contentDescription = localized(language, "全屏查看", "View fullscreen"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
    exportAction?.let { action ->
        OffscreenTableExporter(raw, action) { exportAction = null }
    }
}

/** Roomy fullscreen view for wide tables: full width, larger cells, both-axis scrolling. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun TableFullscreenDialog(raw: String, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    val activity = LocalContext.current.findActivity()
    var exportAction by remember { mutableStateOf<TableExportAction?>(null) }
    var ownsOrientation by androidx.compose.runtime.saveable.rememberSaveable(raw) { mutableStateOf(false) }
    val isLandscape =
        LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // Orientation is reset ONLY on the explicit close paths (button / back-dismiss). The previous
    // composition-teardown hook also fired during the rotation-triggered Activity recreation and
    // yanked a freshly forced landscape straight back to portrait when auto-rotate was off.
    fun close() {
        // Opening/closing an overlay must not renegotiate the Activity configuration. Some
        // foldables recreate or remeasure the chat even when UNSPECIFIED is assigned twice. Hand
        // orientation back only when this viewer's rotate button actually took ownership.
        if (ownsOrientation) {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ownsOrientation = false
        }
        onDismiss()
    }
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Immersive while viewing: the DIALOG owns its own window (hiding bars on the Activity
        // window does nothing while the dialog holds focus). Swipe from an edge brings them
        // back transiently; closing the dialog restores them automatically with the window.
        val dialogView = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(Unit) {
            val window = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                ?: return@LaunchedEffect
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, dialogView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // Edge-to-edge window: keep interactive chrome clear of cutouts/bars while the
                    // background paints the full screen.
                    .windowInsetsPadding(
                        androidx.compose.foundation.layout.WindowInsets.displayCutout,
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { close() }) { Text(localized(language, "关闭", "Close")) }
                    Text(
                        localized(language, "表格", "Table"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(
                        onClick = { if (exportAction == null) exportAction = TableExportAction.SAVE },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = localized(language, "保存为图片", "Save as image"),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { if (exportAction == null) exportAction = TableExportAction.SHARE },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Share,
                            contentDescription = localized(language, "分享表格图片", "Share table image"),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Manual rotate for users with auto-rotate locked; sensor rotation also works
                    // because the open state survives the recreation (rememberSaveable above).
                    IconButton(
                        onClick = {
                            if (ownsOrientation && isLandscape) {
                                activity?.requestedOrientation =
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                ownsOrientation = false
                            } else if (!isLandscape) {
                                ownsOrientation = true
                                activity?.requestedOrientation =
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ScreenRotation,
                            contentDescription = localized(language, "旋转屏幕", "Rotate screen"),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val columns = remember(raw) { markdownTableColumnCount(raw) }
                val exportCellWidth = 170
                val tableWidth = (columns.coerceAtLeast(1) * exportCellWidth + 24).dp
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                ) {
                    // immediate = true parses synchronously: the async default parsed off the
                    // first frame and nothing recomposed this subtree afterwards, leaving the
                    // dialog blank until ANY interaction forced a recomposition.
                    val mdState = com.mikepenz.markdown.model.rememberMarkdownState(raw, immediate = true)
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Column(
                        Modifier
                            .width(tableWidth)
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        Markdown(
                            markdownState = mdState,
                            modifier = Modifier.fillMaxWidth(),
                            colors = markdownColor(),
                            typography = markdownTypography(
                                table = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp),
                            ),
                            components = markdownComponents(
                                table = { m -> StyledMarkdownTable(m.content, m.node, m.typography.table) },
                            ),
                            dimens = markdownDimens(tableCellWidth = exportCellWidth.dp, tableCellPadding = 10.dp),
                        )
                    }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                exportAction?.let { action ->
                    OffscreenTableExporter(raw, action) { exportAction = null }
                }
            }
        }
    }
}

@Composable
internal fun CodeWithCopy(code: String, language: String?, style: TextStyle) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    // Unified diffs get semantic red/green rows instead of a flat code block.
    if (remember(code, language) { looksLikeDiff(code, language) }) {
        DiffBlock(code)
        return
    }
    // Header-bar form (approved phase-3 mockup): language label + copy affordance live in a
    // slim tinted bar above the code, so the copy button no longer floats over code text and
    // the language is visible at a glance.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language?.takeIf { it.isNotBlank() }?.lowercase() ?: "code",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(context, localized(appLanguage, "代码已复制", "Code copied"), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = localized(appLanguage, "复制代码", "Copy code"),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = code,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
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
/** One row of the message action sheet: icon + label, closing the sheet after the action. */
private data class MessageAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

// Message actions surface as a bottom sheet instead of a DropdownMenu: the menu anchors to the
// full-width message composable, so it used to pop far from the finger. A sheet always arrives
// from the bottom, near the thumb, with roomy icon+label rows.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(actions: List<MessageAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { action.onClick(); onDismiss() }
                        .padding(horizontal = 24.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        action.label,
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RunningStatusLine(msg: ChatMessage) {
    val language = LocalAppLanguage.current
    val status = runningStatusFor(msg)
    // Live elapsed time, anchored on the turn's real timestamp (not a local counter), so a
    // process restart or reconnect still shows the true duration of the run.
    var nowTick by remember(msg.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(msg.id) {
        while (isActive) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedSuffix = msg.timestamp?.let { start ->
        " · " + formatElapsedTime(nowTick - start, zh = language == com.hermes.client.ui.localization.AppLanguage.ZH)
    }.orEmpty()
    val transition = rememberInfiniteTransition(label = "running")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulse)),
        )
        val style = MaterialTheme.typography.bodySmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        when (status) {
            is RunningStatus.Tool -> Text(
                localized(language, "正在运行 ", "Running ") + status.label + "…" + elapsedSuffix,
                style = style.copy(fontFamily = FontFamily.Monospace),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(pulse.coerceAtLeast(0.7f)),
            )
            is RunningStatus.Thinking -> Text(
                status.preview + elapsedSuffix,
                style = style.copy(fontStyle = FontStyle.Italic),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(pulse.coerceAtLeast(0.7f)),
            )
            RunningStatus.Generating -> Text(
                localized(language, "生成中…", "Generating…") + elapsedSuffix,
                style = style,
                color = color,
                modifier = Modifier.alpha(pulse),
            )
        }
    }
}

@Composable
internal fun TypingIndicator() {
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
