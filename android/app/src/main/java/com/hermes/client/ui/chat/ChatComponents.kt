package com.hermes.client.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Canvas
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
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.LocalContentColor
import androidx.compose.animation.animateColorAsState
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.Brush
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
import com.hermes.client.ui.components.ExternalLinkIcon
import com.hermes.client.ui.components.rememberSafeUriHandler
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.Motion
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
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
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownPadding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

data class ChatViewportAnchor(
    val blockKey: String,
    val offsetFromTopPx: Float,
    /** Position of the reading line inside the old block, resilient to width-driven reflow. */
    val blockFraction: Float = Float.NaN,
    /** Position of that same reading line inside the viewport. */
    val viewportFraction: Float = Float.NaN,
    /** Stable LazyColumn item identity and offset for exact same-width restoration. */
    val lazyItemKey: String? = null,
    val lazyItemIndex: Int = -1,
    val lazyItemOffset: Int = 0,
    val viewportWidthPx: Int = 0,
    /** Fullscreen overlays wait briefly for their original width before semantic fallback. */
    val preferExactWidth: Boolean = false,
    /** Distinguishes an open overlay from its post-dismiss restore across Activity recreation. */
    val overlayLocked: Boolean = false,
)

enum class ChatViewportMode { FOLLOWING_LIVE, BROWSING_HISTORY, OVERLAY_LOCKED, LAYOUT_RESTORING }

data class ChatExactRestoreTarget(val index: Int, val offset: Int)

/**
 * Semantic viewport state shared by the transcript and fullscreen overlays. Block coordinates are
 * deliberately transient; the semantic reading line plus an exact same-width lazy-item fallback
 * are saveable across configuration changes.
 */
class ChatViewportController(restored: ChatViewportAnchor? = null) {
    private val blockBounds = mutableMapOf<String, Rect>()
    private var viewportBounds: Rect? = null
    private var heldAnchor: ChatViewportAnchor? = restored
    private var lastWidth = 0
    private var lastConfigurationWidth = 0
    private var pinnedToBottom = true
    private var latestLazyItemKey: String? = null
    private var latestLazyItemIndex = -1
    private var latestLazyItemOffset = 0

    var mode: ChatViewportMode = when {
        restored?.overlayLocked == true -> ChatViewportMode.OVERLAY_LOCKED
        restored != null -> ChatViewportMode.LAYOUT_RESTORING
        else -> ChatViewportMode.FOLLOWING_LIVE
    }
        private set

    var restoreGeneration by androidx.compose.runtime.mutableIntStateOf(if (restored == null) 0 else 1)
        private set

    fun updateViewport(bounds: Rect) { viewportBounds = bounds }
    fun updateListPosition(key: String?, index: Int, offset: Int) {
        latestLazyItemKey = key
        latestLazyItemIndex = index
        latestLazyItemOffset = offset
    }
    fun updateBlock(key: String, bounds: Rect) { blockBounds[key] = bounds }
    fun removeBlock(key: String) { blockBounds.remove(key) }
    fun setPinnedToBottom(value: Boolean) {
        pinnedToBottom = value
        if (mode != ChatViewportMode.OVERLAY_LOCKED && mode != ChatViewportMode.LAYOUT_RESTORING) {
            mode = if (value) ChatViewportMode.FOLLOWING_LIVE else ChatViewportMode.BROWSING_HISTORY
        }
    }

    private fun withExactPosition(anchor: ChatViewportAnchor): ChatViewportAnchor = anchor.copy(
        lazyItemKey = latestLazyItemKey,
        lazyItemIndex = latestLazyItemIndex,
        lazyItemOffset = latestLazyItemOffset,
        viewportWidthPx = lastWidth,
    )

    fun currentAnchor(): ChatViewportAnchor? {
        if (pinnedToBottom) return heldAnchor ?: withExactPosition(BOTTOM_ANCHOR)
        val viewport = viewportBounds ?: return heldAnchor
        val visible = blockBounds.entries.filter { (_, bounds) ->
            bounds.bottom > viewport.top && bounds.top < viewport.bottom
        }
        // Anchor one physical pixel inside the viewport rather than exactly on its boundary.
        // That avoids floating-point edge ambiguity while still choosing the smallest Markdown
        // block crossing the reader's first visible line instead of its much larger whole turn.
        val readingLine = viewport.top + 1f
        val chosen = visible
            .filter { (_, bounds) -> bounds.top <= readingLine && bounds.bottom > readingLine }
            .minByOrNull { (_, bounds) -> bounds.height }
            ?: visible.minByOrNull { (_, bounds) -> kotlin.math.abs(bounds.top - readingLine) }
            ?: return heldAnchor
        val bounds = chosen.value
        val referenceY = readingLine.coerceIn(bounds.top, bounds.bottom)
        val blockFraction = if (bounds.height > 0f) {
            ((referenceY - bounds.top) / bounds.height).coerceIn(0f, 1f)
        } else 0f
        val viewportFraction = if (viewport.height > 0f) {
            ((referenceY - viewport.top) / viewport.height).coerceIn(0f, 1f)
        } else 0f
        return withExactPosition(ChatViewportAnchor(
            blockKey = chosen.key,
            offsetFromTopPx = bounds.top - viewport.top,
            blockFraction = blockFraction,
            viewportFraction = viewportFraction,
        ))
    }

    fun holdCurrent() {
        if (heldAnchor == null) currentAnchor()?.let { heldAnchor = it }
    }

    fun lockForOverlay() {
        val anchor = heldAnchor ?: currentAnchor()
        if (anchor != null) heldAnchor = anchor.copy(preferExactWidth = true, overlayLocked = true)
        mode = ChatViewportMode.OVERLAY_LOCKED
    }

    fun requestHeldRestore() {
        if (heldAnchor != null) {
            heldAnchor = heldAnchor?.copy(overlayLocked = false)
            mode = ChatViewportMode.LAYOUT_RESTORING
            restoreGeneration++
        }
    }

    /** Drop a refresh-only capture when no geometry changed or the request failed. */
    fun releaseHeldAnchor() {
        if (mode == ChatViewportMode.OVERLAY_LOCKED || mode == ChatViewportMode.LAYOUT_RESTORING) return
        heldAnchor = null
    }

    fun onViewportWidth(width: Int) {
        if (lastWidth > 0 && width > 0 && width != lastWidth) {
            // onSizeChanged runs before the children's new global positions are published, so the
            // registry still describes the old layout at this point.
            if (heldAnchor == null) holdCurrent()
            if (mode != ChatViewportMode.OVERLAY_LOCKED) requestHeldRestore()
        }
        if (width > 0) lastWidth = width
    }

    fun onConfigurationWidth(widthDp: Int) {
        if (lastConfigurationWidth > 0 && widthDp > 0 && widthDp != lastConfigurationWidth) {
            // Composition observes configuration/window-width changes before the new child layout
            // is positioned, which is the most reliable capture edge across foldable OEMs.
            if (heldAnchor == null) holdCurrent()
            if (mode != ChatViewportMode.OVERLAY_LOCKED) requestHeldRestore()
        }
        if (widthDp > 0) lastConfigurationWidth = widthDp
    }

    fun correctionPx(): Float? {
        val anchor = heldAnchor ?: return null
        if (anchor.blockKey == BOTTOM_ANCHOR_KEY) return 0f
        val viewport = viewportBounds ?: return null
        val block = blockBounds[anchor.blockKey] ?: return null
        return if (anchor.blockFraction.isFinite() && anchor.viewportFraction.isFinite()) {
            val currentReadingLine = block.top + block.height * anchor.blockFraction
            val desiredReadingLine = viewport.top + viewport.height * anchor.viewportFraction
            currentReadingLine - desiredReadingLine
        } else {
            // Backwards-compatible fallback for anchors saved by 0.1.68.
            block.top - (viewport.top + anchor.offsetFromTopPx)
        }
    }

    fun restoringBottom(): Boolean = heldAnchor?.blockKey == BOTTOM_ANCHOR_KEY

    fun isRestoring(): Boolean = mode == ChatViewportMode.LAYOUT_RESTORING && heldAnchor != null

    fun waitingForExactWidth(): Boolean {
        val anchor = heldAnchor ?: return false
        return anchor.preferExactWidth && anchor.viewportWidthPx > 0 && lastWidth != anchor.viewportWidthPx
    }

    fun exactRestoreTarget(lazyKeys: List<String>): ChatExactRestoreTarget? {
        val anchor = heldAnchor ?: return null
        if (anchor.viewportWidthPx <= 0 || lastWidth != anchor.viewportWidthPx) return null
        return restoreTarget(lazyKeys)
    }

    /**
     * First stage of every non-bottom restore. Stable item identity remains useful after a width
     * change even though its old pixel offset is only approximate: it composes the original turn,
     * making the finer Markdown/row anchor available for the semantic correction stage.
     */
    fun restoreTarget(lazyKeys: List<String>): ChatExactRestoreTarget? {
        val anchor = heldAnchor ?: return null
        val key = anchor.lazyItemKey
        val index = when {
            key != null -> lazyKeys.indexOf(key).takeIf { it >= 0 }
            anchor.lazyItemIndex >= 0 -> anchor.lazyItemIndex.takeIf { it < lazyKeys.size }
            else -> null
        } ?: return null
        return ChatExactRestoreTarget(index, anchor.lazyItemOffset)
    }

    fun cancelRestoreForUser() {
        if (mode != ChatViewportMode.LAYOUT_RESTORING) return
        heldAnchor = null
        mode = ChatViewportMode.BROWSING_HISTORY
        restoreGeneration++
    }

    fun finishRestore(generation: Int) {
        if (generation == restoreGeneration) {
            heldAnchor = null
            mode = if (pinnedToBottom) ChatViewportMode.FOLLOWING_LIVE else ChatViewportMode.BROWSING_HISTORY
        }
    }

    fun saveAnchor(): ChatViewportAnchor? = heldAnchor ?: currentAnchor()

    companion object {
        private const val BOTTOM_ANCHOR_KEY = "__chat_bottom__"
        private val BOTTOM_ANCHOR = ChatViewportAnchor(BOTTOM_ANCHOR_KEY, 0f, 1f, 1f)

        val Saver = androidx.compose.runtime.saveable.listSaver<ChatViewportController, Any>(
            save = { controller ->
                controller.saveAnchor()?.let {
                    listOf(
                        it.blockKey,
                        it.offsetFromTopPx,
                        it.blockFraction,
                        it.viewportFraction,
                        it.lazyItemKey.orEmpty(),
                        it.lazyItemIndex,
                        it.lazyItemOffset,
                        it.viewportWidthPx,
                        it.preferExactWidth,
                        it.overlayLocked,
                    )
                } ?: emptyList()
            },
            restore = { values ->
                if (values.size < 2) ChatViewportController()
                else ChatViewportController(
                    ChatViewportAnchor(
                        blockKey = values[0] as String,
                        offsetFromTopPx = values[1] as Float,
                        blockFraction = (values.getOrNull(2) as? Float) ?: Float.NaN,
                        viewportFraction = (values.getOrNull(3) as? Float) ?: Float.NaN,
                        lazyItemKey = (values.getOrNull(4) as? String)?.ifEmpty { null },
                        lazyItemIndex = (values.getOrNull(5) as? Int) ?: -1,
                        lazyItemOffset = (values.getOrNull(6) as? Int) ?: 0,
                        viewportWidthPx = (values.getOrNull(7) as? Int) ?: 0,
                        preferExactWidth = (values.getOrNull(8) as? Boolean) ?: false,
                        overlayLocked = (values.getOrNull(9) as? Boolean) ?: false,
                    ),
                )
            },
        )
    }
}

private val LocalChatViewportController = staticCompositionLocalOf<ChatViewportController?> { null }

@Composable
fun ChatMessageList(
    state: ChatUiState,
    // From the nav argument (NOT ChatUiState: that object is a mirror of runtime.chat and any
    // flag written into it is overwritten on the next runtime collect — the 0.1.88 bug).
    isNewSession: Boolean = false,
    sessionId: String,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    isGenerating: Boolean = false,
    onEditResend: (String) -> Unit = {},
    onRetrySend: (String) -> Unit = {},
    sendDiagnosticFor: (String) -> String? = { null },
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
    openPromptListTick: Long = 0L,
    /** Active in-chat search (query + current hit) for text marks and card auto-expand. */
    searchContext: ChatSearchContext? = null,
) {
    val language = LocalAppLanguage.current
    val semanticViewport = viewportController ?: remember(sessionId) { ChatViewportController() }
    val configurationWidth = LocalConfiguration.current.screenWidthDp
    androidx.compose.runtime.DisposableEffect(semanticViewport, configurationWidth) {
        val firstIndex = listState.firstVisibleItemIndex
        val firstKey = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == firstIndex }
            ?.key as? String
        semanticViewport.updateListPosition(
            firstKey,
            firstIndex,
            listState.firstVisibleItemScrollOffset,
        )
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
                    // Publish the exact final presentation first, then release the visual buffer
                    // only after its measured height has settled. The settled transcript takes
                    // over with identical content, so message.complete cannot snap the final row.
                    renderedTail = withContext(Dispatchers.Default) { newest.organizedForDisplay() }
                    // Keep ownership of the live-tail item until its height animation reaches the
                    // exact final Markdown measurement. Releasing after one frame disabled that
                    // animation mid-flight and reintroduced a small completion snap.
                    delay(STREAM_SIZE_ANIMATION_MS.toLong())
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
        // display_kind=hidden rows are context-only (compaction wrappers etc.) and
        // must not occupy a turn slot at all.
        val visible = settledTurns.filterNot(::isHiddenTimelineMessage)
        val tail = effectiveTail ?: return@remember visible
        val previous = visible.lastOrNull()
        if (previous?.role == Role.ASSISTANT && tail.role == Role.ASSISTANT) {
            visible.dropLast(1) + mergeAssistantTurns(previous, tail)
        } else {
            visible + tail
        }
    }
    val displayKeys = remember(displayMessages) { displayMessages.conversationRenderKeys() }
    val lazyKeys = remember(displayKeys) { listOf("bottom-edge") + displayKeys.asReversed() }
    // Only the most recent assistant turn can be regenerated — regenerating an earlier one
    // would silently drop everything the user and agent said after it.
    val lastAssistantId = displayMessages.lastOrNull { it.role == Role.ASSISTANT }?.id
    val processesVisible = visibleProcesses.isNotEmpty()
    val sessionRunIndicator = showsSessionRunIndicator(isGenerating, state.messages)

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

    // ---- Turn navigation (docs/DESIGN.md §5.4 上一组对话胶囊 / 我的提问) ----------------------
    // The visible span is the only layout-derived value: a small IntRange that changes when an
    // item enters or leaves the viewport, not on every scrolled pixel, so the pill recomposes a
    // handful of times per screen of scrolling rather than per frame.
    val turnGroups = remember(displayMessages) { turnGroups(displayMessages) }
    val turnCount = displayMessages.size
    val visibleSpan by remember(listState, turnCount) {
        derivedStateOf {
            var lowest = Int.MAX_VALUE
            var highest = -1
            for (item in listState.layoutInfo.visibleItemsInfo) {
                val messageIndex = listMessageIndex(turnCount, item.index) ?: continue
                if (messageIndex < lowest) lowest = messageIndex
                if (messageIndex > highest) highest = messageIndex
            }
            if (highest < 0) null else lowest..highest
        }
    }
    val pillTarget = visibleSpan?.let { span ->
        turnPillFor(turnGroups, topVisibleMessageIndex = span.first, visibleMessageRange = span, atBottom = atBottom)
    }
    val currentGroupIndex = visibleSpan?.let { groupIndexOf(turnGroups, it.first) } ?: turnGroups.lastIndex
    val turnTopInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { (2.dp - TURN_SPACING).roundToPx() }
    // One owner for programmatic turn jumps, mirroring bottomRequests: a user drag steals the
    // scroll mutex and surfaces as CancellationException, which is the user's call.
    val jumpRequests = remember(sessionId) { Channel<TurnJumpRequest>(Channel.CONFLATED) }
    // Landing feedback (docs/DESIGN.md §5.4, decision 2026-09-03): the prompt jumped to shows an
    // outline only — no fill, unlike the search highlight — that fades over TURN_JUMP_FLASH_MS.
    // It starts when the jump has LANDED, not when the row was tapped: closing the sheet and the
    // scroll itself take most of a second, and a fade started at the tap was gone on arrival.
    var jumpFlashIndex by remember(sessionId) { mutableStateOf<Int?>(null) }
    var jumpFlashTick by remember(sessionId) { mutableStateOf(0L) }
    val jumpFlash = remember(sessionId) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(jumpFlashTick) {
        if (jumpFlashIndex != null) {
            // Hold at full strength first so the eye catches it, then fade.
            jumpFlash.snapTo(1f)
            delay(TURN_JUMP_FLASH_HOLD_MS)
            jumpFlash.animateTo(0f, tween((TURN_JUMP_FLASH_MS - TURN_JUMP_FLASH_HOLD_MS).toInt(), easing = Motion.Standard))
            jumpFlashIndex = null
        }
    }
    LaunchedEffect(sessionId, listState, jumpRequests) {
        for (request in jumpRequests) {
            try {
                listState.alignItemTopToViewport(request.listIndex, turnTopInsetPx)
                jumpFlashIndex = request.anchorIndex
                jumpFlashTick = System.nanoTime()
            } catch (stolen: CancellationException) {
                // The user dragged during the jump: no landing to announce.
                currentCoroutineContext().ensureActive()
            }
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

    val jumpToGroup: (Int) -> Unit = { groupIndex ->
        turnGroups.getOrNull(groupIndex)?.let { group ->
            semanticViewport.cancelRestoreForUser()
            jumpRequests.trySend(TurnJumpRequest(messageListIndex(turnCount, group.anchorIndex), group.anchorIndex))
        }
    }
    var promptListOpen by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(openPromptListTick) { if (openPromptListTick > 0L) promptListOpen = true }
    if (promptListOpen) {
        val rows = remember(turnGroups, displayMessages, currentGroupIndex, language) {
            promptRows(turnGroups, displayMessages, currentGroupIndex, language) { formatTimeSeparator(it, language) }
        }
        PromptListSheet(
            rows = rows,
            onPick = { row ->
                promptListOpen = false
                jumpToGroup(row.groupIndex)
            },
            onLatest = {
                promptListOpen = false
                bottomRequests.trySend(Unit)
            },
            onDismiss = { promptListOpen = false },
        )
    }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) semanticViewport.cancelRestoreForUser()
    }
    // Capture an exact same-width fallback when a gesture settles. Unlike observing offsets, this
    // emits only at the beginning/end of a scroll, so it does not put per-frame work back onto the
    // hot fling path that previous versions worked hard to remove.
    LaunchedEffect(listState, semanticViewport) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    val firstIndex = listState.firstVisibleItemIndex
                    val firstKey = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == firstIndex }
                        ?.key as? String
                    semanticViewport.updateListPosition(
                        firstKey,
                        firstIndex,
                        listState.firstVisibleItemScrollOffset,
                    )
                }
            }
    }

    val openTableFullscreen: (String) -> Unit = { raw ->
        val firstIndex = listState.firstVisibleItemIndex
        val firstKey = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == firstIndex }
            ?.key as? String
        semanticViewport.updateListPosition(
            firstKey,
            firstIndex,
            listState.firstVisibleItemScrollOffset,
        )
        semanticViewport.lockForOverlay()
        onOpenTableFullscreen(raw)
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
        if (restoreGeneration <= 0 || !semanticViewport.isRestoring()) return@LaunchedEffect
        var stableFrames = 0
        var coarseTargetApplied = false
        var settled = false
        for (attempt in 0 until VIEWPORT_RESTORE_MAX_FRAMES) {
            androidx.compose.runtime.withFrameNanos { }
            if (!semanticViewport.isRestoring()) return@LaunchedEffect
            if (semanticViewport.restoringBottom()) {
                if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                    stableFrames = 0
                    try {
                        listState.scrollToItem(0)
                    } catch (stolen: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        return@LaunchedEffect
                    }
                } else {
                    stableFrames++
                    if (stableFrames >= VIEWPORT_RESTORE_STABLE_FRAMES) {
                        settled = true
                        break
                    }
                }
                continue
            }
            // Fullscreen rotation can trigger a short chain of configurations. Prefer returning
            // to the captured width, but do not require it: after the wait, the same stable turn
            // is still the correct coarse target at any width.
            if (
                semanticViewport.waitingForExactWidth() &&
                attempt < VIEWPORT_EXACT_WIDTH_WAIT_FRAMES
            ) continue
            if (!coarseTargetApplied) {
                val target = semanticViewport.restoreTarget(lazyKeys)
                if (target == null) continue
                if (
                    listState.firstVisibleItemIndex != target.index ||
                    listState.firstVisibleItemScrollOffset != target.offset
                ) {
                    try {
                        listState.scrollToItem(target.index, target.offset)
                    } catch (stolen: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        return@LaunchedEffect
                    }
                }
                coarseTargetApplied = true
                stableFrames = 0
                continue
            }
            val correction = semanticViewport.correctionPx() ?: continue
            if (kotlin.math.abs(correction) <= VIEWPORT_RESTORE_TOLERANCE_PX) {
                stableFrames++
                if (stableFrames >= VIEWPORT_RESTORE_STABLE_FRAMES) {
                    settled = true
                    break
                }
            } else {
                stableFrames = 0
                try {
                    // reverseLayout reverses the scroll axis: a block that moved down by +N pixels
                    // needs a -N programmatic delta to return to its previous window coordinate.
                    listState.scrollBy(-correction)
                } catch (stolen: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    return@LaunchedEffect
                }
            }
        }
        // Never discard the semantic anchor merely because async Markdown needed longer than the
        // bounded frame budget. A later configuration/dismiss request can resume the transaction,
        // and a user drag still cancels it immediately.
        if (settled && semanticViewport.isRestoring()) {
            semanticViewport.finishRestore(restoreGeneration)
        }
    }

    if (displayMessages.isEmpty() && visibleProcesses.isEmpty()) {
        when {
            // A locally created session has nothing to load and nothing to fail: the greeting
            // overlay (ChatScreen) owns this area, so render only the blank ground for it.
            isNewSession -> Box(modifier.fillMaxSize())
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

    // Loading completion only means that authoritative rows exist. Markdown and LazyColumn still
    // need several layout frames to measure the newest answer and settle reverseLayout at index 0.
    // Keep the transcript fully laid out but invisible behind the skeleton until those coordinates
    // remain unchanged across consecutive frames; otherwise the first visible frame contains a few
    // user bubbles and the next frame snaps to the correctly measured assistant tail.
    var initialPresentationReady by androidx.compose.runtime.saveable.rememberSaveable(sessionId) {
        mutableStateOf(!state.historyLoading && !state.historyLoaded)
    }
    LaunchedEffect(
        sessionId,
        state.historyLoading,
        state.historyLoaded,
        state.isGenerating,
        displayMessages.size,
    ) {
        if (initialPresentationReady && !state.historyLoading) return@LaunchedEffect
        when {
            state.isGenerating -> initialPresentationReady = true
            state.historyLoading -> initialPresentationReady = false
            !state.historyLoaded -> initialPresentationReady = true
            else -> {
                var previousSignature: List<Triple<Any, Int, Int>>? = null
                var stableFrames = 0
                repeat(INITIAL_PRESENTATION_MAX_FRAMES) {
                    androidx.compose.runtime.withFrameNanos { }
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.isEmpty()) return@repeat
                    val signature = visible.map { Triple(it.key, it.offset, it.size) }
                    stableFrames = if (signature == previousSignature) stableFrames + 1 else 0
                    previousSignature = signature
                    val settledAtTail = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                    if (settledAtTail && stableFrames >= INITIAL_PRESENTATION_STABLE_FRAMES) {
                        initialPresentationReady = true
                        return@LaunchedEffect
                    }
                }
                // Never leave a usable conversation permanently masked on an exotic layout. The
                // bounded fallback is still much later than the one-frame exposure it replaces.
                initialPresentationReady = true
            }
        }
    }
    val transcriptAlpha by animateFloatAsState(
        targetValue = if (initialPresentationReady) 1f else 0f,
        animationSpec = tween(durationMillis = INITIAL_PRESENTATION_CROSSFADE_MS),
        label = "chat-history-reveal",
    )

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
                .alpha(transcriptAlpha)
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
                Box(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize(
                            animationSpec = tween(
                                durationMillis = STREAM_SIZE_ANIMATION_MS,
                                easing = LinearOutSlowInEasing,
                            ),
                            alignment = Alignment.BottomStart,
                        ),
                ) {
                    if (processesVisible || sessionRunIndicator) {
                        Column(Modifier.padding(top = TURN_SPACING)) {
                            // The run is active but no bubble is streaming yet (docs/DESIGN.md §5.6):
                            // the indicator belongs to the session's run, so it lives in this
                            // permanent slot rather than in a bubble that may not exist.
                            if (sessionRunIndicator) RunningStatusLine(sessionRunPlaceholder(sessionId))
                            if (processesVisible) BackgroundProcessesCard(visibleProcesses)
                        }
                    } else {
                        Spacer(Modifier.height(1.dp))
                    }
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
                val smoothLiveResize = index == displayMessages.lastIndex && presentingSource != null
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
                        onRetrySend,
                        sendDiagnosticFor,
                        onRegenerate,
                        onRetryWithModel,
                        openTableFullscreen,
                        isSpeaking,
                        onReadAloud,
                        onStopReading,
                        onImageSave,
                        onImageSaveAs,
                        onImageShare,
                        savingImageId,
                        onFileOpen,
                        onFileShare,
                        smoothLiveResize = smoothLiveResize,
                        highlighted = index == highlightIndex,
                        landingAlpha = if (index == jumpFlashIndex) jumpFlash.value else 0f,
                        searchContext = searchContext,
                    )
                }
            }
        }
        // Turn-jump pill: names the group under the viewport's top edge once its prompt has left
        // the screen; a plain fade so it never shifts the transcript. The last content is held
        // through the exit fade so the label does not blank out while disappearing.
        val pillContent = pillTarget?.let { target ->
            val group = turnGroups.getOrNull(target.groupIndex) ?: return@let null
            val prompt = group.promptIndex?.let { displayMessages.getOrNull(it) }
            val label = if (prompt == null) localized(language, "会话开始", "Start of chat") else promptSummary(prompt, language)
            Triple(target.groupIndex, label, target.showList)
        }
        var heldPillContent by remember { mutableStateOf(pillContent) }
        if (pillContent != null && pillContent != heldPillContent) {
            androidx.compose.runtime.SideEffect { heldPillContent = pillContent }
        }
        // Scroll-indicator timing (docs/DESIGN.md §5.4): the pill fades TURN_PILL_IDLE_HIDE_MS after
        // the list settles and comes straight back when it moves. collectLatest cancels a pending
        // hide the moment a new scroll begins. It stays tappable until the fade starts.
        var pillIdleHidden by remember(sessionId) { mutableStateOf(false) }
        LaunchedEffect(listState, sessionId) {
            androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collectLatest { scrolling ->
                    if (scrolling) {
                        pillIdleHidden = false
                    } else {
                        delay(TURN_PILL_IDLE_HIDE_MS)
                        pillIdleHidden = true
                    }
                }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            val pillMaxWidth = maxWidth * 0.7f
            androidx.compose.animation.AnimatedVisibility(
                visible = initialPresentationReady && pillContent != null && !pillIdleHidden,
                enter = androidx.compose.animation.fadeIn(animationSpec = tween(com.hermes.client.ui.theme.Motion.DurationShort)),
                exit = androidx.compose.animation.fadeOut(animationSpec = tween(com.hermes.client.ui.theme.Motion.DurationShort)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            ) {
                val content = pillContent ?: heldPillContent
                if (content != null) {
                    val (groupIndex, label, showList) = content
                    TurnJumpPill(
                        label = label,
                        showList = showList,
                        onJump = { jumpToGroup(groupIndex) },
                        onOpenList = { promptListOpen = true },
                        modifier = Modifier.widthIn(max = pillMaxWidth),
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
        if (initialPresentationReady && !atBottom && !listState.isScrollInProgress) {
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
        if (transcriptAlpha < 1f) {
            ChatHistorySkeleton(
                Modifier
                    .fillMaxSize()
                    .alpha(1f - transcriptAlpha),
            )
        }
    }
    }
}

/**
 * Stable render keys survive local-id to REST-id replacement and adjacent assistant-record merges.
 * User text anchors a conversation turn; assistant/system ordinals only advance inside that turn.
 */
internal fun List<ChatMessage>.conversationRenderKeys(): List<String> = map { it.id }

internal fun ChatMessage.streamContentRevision(): Int =
    text.length + thinking.length + images.sumOf { it.localPath.orEmpty().length + it.state.ordinal } + tools.sumOf { tool ->
        tool.name.length + tool.output.length + if (tool.status == ToolStatus.RUNNING) 1 else 2
    }

@Composable
private fun ChatHistorySkeleton(modifier: Modifier = Modifier) {
    // One skeleton language app-wide: same base, same highlight, same 1200ms sweep as the list
    // skeleton (ui/components/BrandLoader.kt). This used to run at its own 1350ms.
    val base = com.hermes.client.ui.components.skeletonBaseColor()
    val highlight = com.hermes.client.ui.components.skeletonHighlightColor()
    val sweep = com.hermes.client.ui.components.rememberSkeletonSweep()
    fun Modifier.bar(widthFraction: Float): Modifier = this
        .fillMaxWidth(widthFraction)
        .height(18.dp)
        .clip(RoundedCornerShape(9.dp))
        .drawBehind {
            val centerX = (-0.55f + sweep * 2.1f) * size.width
            val halfBand = size.width * 0.34f
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(centerX - halfBand, 0f),
                    end = Offset(centerX + halfBand, 0f),
                ),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
            )
        }
    Column(
        modifier
            .testTag("chat-history-skeleton")
            .padding(horizontal = 24.dp, vertical = 22.dp),
        // Bottom-anchored like the reverseLayout transcript it precedes, so the skeleton->content
        // transition doesn't move the visual mass from the top of the pane to the bottom.
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        Box(Modifier.bar(0.82f))
        Box(Modifier.bar(0.94f))
        Box(Modifier.bar(0.68f))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.bar(0.9f))
        Box(Modifier.bar(0.74f))
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
    onRetrySend: (String) -> Unit,
    sendDiagnosticFor: (String) -> String?,
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
    smoothLiveResize: Boolean = false,
    highlighted: Boolean = false,
    landingAlpha: Float = 0f,
    searchContext: ChatSearchContext? = null,
) {
    // Server-injected timeline markers render as a quiet centered note (no bubble,
    // no long-press actions) — see TimelineNote.kt for the classification rules.
    timelineNoteFor(msg)?.let { note ->
        TimelineNoteRow(note, msg)
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChatSearch provides searchContext,
        LocalTurnIsCurrentHit provides (searchContext != null && searchContext.currentMessageId == msg.id),
    ) {
        when (msg.role) {
            Role.USER -> UserBubble(msg, onEditResend, onImageSave, onImageSaveAs, onImageShare, savingImageId, onFileOpen, onFileShare, highlighted = highlighted, landingAlpha = landingAlpha, onRetrySend = onRetrySend, sendDiagnostic = sendDiagnosticFor(msg.id))
            else -> AssistantTurn(msg, canRegenerate, showAssistantActions, onRegenerate, onRetryWithModel, onOpenTableFullscreen, isSpeaking, onReadAloud, onStopReading, onImageSave, onImageSaveAs, onImageShare, savingImageId, onFileOpen, onFileShare, smoothLiveResize = smoothLiveResize, highlighted = highlighted, landingAlpha = landingAlpha)
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserBubble(
    msg: ChatMessage,
    onEditResend: (String) -> Unit,
    onImageSave: (ChatImage) -> Unit,
    onImageSaveAs: (ChatImage) -> Unit,
    onImageShare: (ChatImage) -> Unit,
    savingImageId: String?,
    onFileOpen: (ChatFile) -> Unit,
    onFileShare: (ChatFile) -> Unit,
    highlighted: Boolean = false,
    landingAlpha: Float = 0f,
    onRetrySend: (String) -> Unit = {},
    sendDiagnostic: String? = null,
) {
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
    // Delivery three-state (docs/DESIGN.md §5.4): the "sending" look is revealed only after
    // 250ms without an ack, so the common sub-300ms send never flickers; "failed" shows at once.
    val sending = msg.delivery == com.hermes.client.domain.DeliveryState.SENDING
    val failed = msg.delivery == com.hermes.client.domain.DeliveryState.FAILED
    var revealSending by remember(msg.id) { mutableStateOf(false) }
    LaunchedEffect(msg.id, sending) {
        if (sending) { delay(SENDING_REVEAL_DELAY_MS); revealSending = true } else revealSending = false
    }
    val dim = revealSending || failed
    val bg by animateColorAsState(
        targetValue = if (msg.isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (dim) 0.5f else 0.78f),
        animationSpec = tween(if (dim) 200 else 190),
        label = "userBubbleFill",
    )
    val textColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.7f else 1f),
        animationSpec = tween(if (dim) 200 else 190),
        label = "userBubbleText",
    )
    val accent = MaterialTheme.colorScheme.primary
    val userShape = RoundedCornerShape(22.dp, 22.dp, 7.dp, 22.dp)
    // Proportional cap instead of a fixed 320dp: a fixed value reads fine on a phone but
    // leaves user bubbles oddly narrow on tablets/landscape. ~82% tracks the Claude app.
    val bubbleMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    val sendingLabel = localized(language, "发送中", "Sending")
    val failedLabel = localized(language, "未发送 · 点按重试", "Not sent · Tap to retry")
    val failedCode = com.hermes.client.data.error.AppErrorCode.MESSAGE_SEND_FAILED.compact
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Column(horizontalAlignment = Alignment.End) {
        Box {
            Column(
                Modifier
                    .widthIn(max = bubbleMaxWidth)
                    // Asymmetric corners (a small "tail" corner) mark this as the sender's bubble.
                    .clip(userShape)
                    .background(bg)
                    .then(
                        when {
                            highlighted -> Modifier.background(accent.copy(alpha = 0.18f)).border(1.5.dp, accent, userShape)
                            // Landing outline: border only, fading — never the search fill.
                            landingAlpha > 0f -> Modifier.border(1.5.dp, accent.copy(alpha = landingAlpha), userShape)
                            else -> Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp)
                    .semantics {
                        if (revealSending) stateDescription = sendingLabel
                        if (failed) stateDescription = "$failedLabel $failedCode"
                    }
                    .combinedClickable(
                        onClick = { if (failed) onRetrySend(msg.id) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true },
                    ),
            ) {
              CompositionLocalProvider(LocalContentColor provides textColor) {
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
                        searchHighlighted(msg.text),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                            letterSpacing = 0.sp,
                        ),
                    )
                }
              }
            }
            // Tail marker: breathing ring while sending, error "!" when it failed. Sits on the
            // tail corner over an 18dp disc of the page background so it never touches the text.
            if (revealSending || failed) {
                DeliveryTailMarker(failed = failed, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp))
            }
            if (menuOpen) {
                MessageActionSheet(
                    actions = buildList {
                        add(MessageAction(Icons.Rounded.ContentCopy, localized(language, "复制", "Copy")) {
                            copyToClipboard(msg.text, clipboard, context, localized(language, "已复制", "Copied"))
                        })
                        add(MessageAction(Icons.Rounded.Edit, localized(language, "编辑并重新发送", "Edit & resend")) { onEditResend(msg.text) })
                        add(MessageAction(Icons.Rounded.SelectAll, localized(language, "选择文本", "Select text")) { selectingText = true })
                        if (failed && sendDiagnostic != null) {
                            add(MessageAction(Icons.Rounded.ContentCopy, localized(language, "复制诊断信息", "Copy diagnostics")) {
                                copyToClipboard(sendDiagnostic, clipboard, context, localized(language, "诊断信息已复制", "Diagnostics copied"))
                            })
                        }
                    },
                    onDismiss = { menuOpen = false },
                )
            }
            if (selectingText) {
                TextSelectionDialog(text = msg.text, onDismiss = { selectingText = false })
            }
        }
        if (failed) {
            // Action copy in error colour, then the compact code (docs/ERROR_HANDLING.md
            // presentation rules) in the neutral colour so it reads as a footnote, not a shout.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 4.dp, end = 4.dp)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onRetrySend(msg.id) },
            ) {
                Text(failedLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(failedCode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
      }
    }
}

private const val SENDING_REVEAL_DELAY_MS = 250L

/**
 * Whether the sending ring breathes. Screenshot tests provide false: an infinite transition
 * never lets the compose clock go idle, so a golden image could never be captured.
 */
val LocalDeliveryMotionEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * 18dp disc in the page background carrying a 12dp glyph: a stroke ring that breathes
 * (alpha 0.35↔1, 1200ms) while sending, or an error-coloured "!" circle when the send failed.
 */
@Composable
private fun DeliveryTailMarker(failed: Boolean, modifier: Modifier = Modifier) {
    val disc = MaterialTheme.colorScheme.background
    val ringColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val alpha = if (failed || !LocalDeliveryMotionEnabled.current) 1f else {
        val transition = rememberInfiniteTransition(label = "sendingRing")
        val breathing by transition.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
            label = "sendingRingAlpha",
        )
        breathing
    }
    androidx.compose.foundation.Canvas(modifier.size(18.dp)) {
        drawCircle(color = disc)
        val r = 5.dp.toPx()
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        if (failed) {
            drawCircle(color = errorColor, radius = r, style = stroke)
            val cx = center.x
            drawLine(errorColor, androidx.compose.ui.geometry.Offset(cx, center.y - r * 0.55f), androidx.compose.ui.geometry.Offset(cx, center.y + r * 0.1f), strokeWidth = 1.7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(color = errorColor, radius = 0.9.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, center.y + r * 0.5f))
        } else {
            drawCircle(color = ringColor.copy(alpha = alpha), radius = r, style = stroke)
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
            com.hermes.client.ui.components.HermesMark(size = 24.dp)
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
                    // Stays an M3 spinner: white-on-photo, where a single-colour brand mark
                    // has no guaranteed contrast (docs/DESIGN.md §5.6).
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
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        FileTransferState.UPLOADING -> com.hermes.client.ui.components.HermesMark(size = 22.dp)
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
                    com.hermes.client.ui.components.HermesMark(size = 17.dp)
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
internal fun AssistantTurn(
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
    smoothLiveResize: Boolean = false,
    highlighted: Boolean = false,
    landingAlpha: Float = 0f,
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
                // A streamed Markdown tail grows in line-sized steps and may briefly shrink when
                // an unfinished construct resolves. Animate only the live tail's measured height:
                // settled history remains immediate, while reverseLayout receives a continuous
                // size curve instead of alternating one-frame jumps.
                .then(
                    if (smoothLiveResize) {
                        Modifier.animateContentSize(
                            animationSpec = tween(
                                durationMillis = STREAM_SIZE_ANIMATION_MS,
                                easing = LinearOutSlowInEasing,
                            ),
                            alignment = Alignment.BottomStart,
                        )
                    } else Modifier
                )
                // No conditional padding here: background/border draw within existing bounds, so
                // toggling the highlight causes no layout shift (a conditional .padding would).
                .then(
                    when {
                        highlighted -> Modifier.clip(hlShape).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, hlShape)
                        landingAlpha > 0f -> Modifier.clip(hlShape).border(1.5.dp, accent.copy(alpha = landingAlpha), hlShape)
                        else -> Modifier
                    },
                )
                .padding(vertical = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }),
        ) {
            if (msg.thinking.isNotBlank()) ThinkingCard(msg.id, msg.thinking)
            remember(msg.tools) { groupToolsForDisplay(msg.tools) }.forEach { group ->
                when (group) {
                    is ToolDisplayGroup.Single -> SemanticToolCard(group.tool)
                    is ToolDisplayGroup.Timeline -> ToolTimelineCard(
                        group.tools,
                        completed = !msg.isStreaming,
                        stateKey = "${msg.id}:${group.tools.first().id}",
                    )
                }
            }
            if (msg.images.isNotEmpty()) {
                ChatImageGrid(msg.images, onImageSave, onImageSaveAs, onImageShare, savingImageId)
                if (renderedText.isNotBlank() || msg.files.isNotEmpty()) Spacer(Modifier.height(8.dp))
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
                    val blocks = remember(renderedText) { markdownRenderBlocks(renderedText) }
                    Column {
                        blocks.forEachIndexed { blockIndex, block ->
                            key(blockIndex) {
                                AssistantMarkdownBlock(
                                    content = block,
                                    anchorKey = "${msg.id}:markdown:$blockIndex",
                                    onOpenTableFullscreen = onOpenTableFullscreen,
                                    modifier = Modifier.testTag("chat-block-${msg.id}-$blockIndex"),
                                )
                            }
                        }
                    }
                }
            }
            // Attachments sit BELOW the prose (docs/DESIGN.md §5.4, decision 2026-09-05): an assistant
            // file is the artifact the prose just delivered, and a long report scrolls the card out of
            // view when it renders on top — users read to the end and conclude nothing was delivered.
            if (msg.files.isNotEmpty()) {
                if (renderedText.isNotBlank() || msg.images.isNotEmpty()) Spacer(Modifier.height(8.dp))
                ChatFileList(msg.files, onFileOpen, onFileShare, Modifier.testTag("chat-files-${msg.id}"))
            }
            val showCompletedActions = showActions && !msg.isStreaming && msg.text.isNotBlank() && !msg.isError
            if (msg.isStreaming || showCompletedActions) Box(Modifier.fillMaxWidth().height(48.dp)) {
                if (msg.isStreaming) {
                    // Reserve the same footer height during and after a run. Replacing a short
                    // status row with the 48dp action row used to grow the last item exactly when
                    // message.complete arrived, producing the final visible jump to the bottom.
                    RunningStatusLine(msg)
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
    anchorKey: String,
    onOpenTableFullscreen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = LocalChatViewportController.current
    val components = remember(onOpenTableFullscreen, anchorKey) {
        chatMarkdownComponents(onOpenTableFullscreen, anchorKey)
    }
    androidx.compose.runtime.DisposableEffect(viewport, anchorKey) {
        onDispose { viewport?.removeBlock(anchorKey) }
    }
    val body = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 17.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.sp,
    )
    val linkColor = MaterialTheme.colorScheme.primary
    // Colour AND underline AND a leading glyph: in CJK body text an underlined run is nearly
    // indistinguishable from **bold**, and colour alone is not an accessible-enough signal.
    val linkStyles = remember(linkColor) {
        TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            pressedStyle = SpanStyle(color = linkColor.copy(alpha = 0.7f), textDecoration = TextDecoration.Underline),
        )
    }
    // Held across recompositions on purpose: InlineTextContent has no equals(), so rebuilding
    // this map on every streaming tick would hand Markdown a "changed" argument each frame.
    // The placeholder is 17sp square while the glyph is 13sp, which is where the gap between
    // icon and link text comes from; both are sp so the pair tracks the system font scale.
    val linkIcon = remember(linkColor) {
        DefaultMarkdownInlineContent(
            mapOf(
                MARKDOWN_LINK_ICON_TAG to InlineTextContent(
                    Placeholder(17.sp, 17.sp, PlaceholderVerticalAlign.TextCenter),
                ) {
                    Icon(
                        ExternalLinkIcon,
                        contentDescription = null,
                        modifier = Modifier.size(with(LocalDensity.current) { 13.sp.toDp() }),
                        tint = linkColor,
                    )
                },
            ),
        )
    }
    // Two annotators share this one slot. They are disjoint — search highlighting only claims
    // TEXT tokens, the glyph only reacts to link nodes — so the link pass runs first and always
    // defers, then search decides whether it handled the node.
    val searchAnnotator = rememberSearchAnnotator()
    val annotator = remember(searchAnnotator) {
        markdownAnnotator(config = searchAnnotator.config) { content, child ->
            if (shouldPrefixLinkIcon(child)) {
                appendInlineContent(MARKDOWN_LINK_ICON_TAG, "\uFFFC")
                // WORD JOINER: without it the line breaker treats the glyph as its own word and
                // happily leaves it stranded at the end of the previous line.
                append('\u2060')
            }
            searchAnnotator.annotate?.invoke(this, content, child) ?: false
        }
    }
    // The renderer captures LocalUriHandler when it builds the link annotations, so the guarded
    // handler has to be in scope around Markdown() rather than at the tap site.
    CompositionLocalProvider(LocalUriHandler provides rememberSafeUriHandler()) {
    Markdown(
        content = content,
        annotator = annotator,
        modifier = modifier.onGloballyPositioned { viewport?.updateBlock(anchorKey, it.boundsInWindow()) },
        colors = markdownColor(
            inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.headlineSmall.copy(lineHeight = 34.sp),
            h2 = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 32.sp),
            h3 = MaterialTheme.typography.titleMedium.copy(fontSize = 18.5.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            // h4/h5/h6 used to fall back to titleMedium/titleSmall (16sp/13sp), i.e. a "level 5
            // heading" rendered SMALLER than the 17sp body it introduced. A heading never sits
            // below body size; below h4 the hierarchy is carried by weight, not by shrinking.
            h4 = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
            h5 = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            h6 = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            text = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            quote = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            textLink = linkStyles,
            table = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
        ),
        components = components,
        inlineContent = linkIcon,
        padding = markdownPadding(
            block = MD_BLOCK,
            list = MD_LIST,
            listItemTop = MD_LIST_ITEM,
            listItemBottom = MD_LIST_ITEM,
            listIndent = MD_LIST_INDENT,
        ),
        dimens = markdownDimens(tableCellWidth = 110.dp, tableCellPadding = 8.dp),
    )
    }
}

private const val STREAM_RENDER_INTERVAL_MS = 64L
private const val STREAM_SIZE_ANIMATION_MS = 120
private const val INITIAL_PRESENTATION_CROSSFADE_MS = 120
private const val INITIAL_PRESENTATION_MAX_FRAMES = 30
private const val INITIAL_PRESENTATION_STABLE_FRAMES = 4
// Long enough for a large asynchronously parsed answer after a fold/unfold, still bounded so an
// exotic missing landmark cannot keep a frame loop alive indefinitely.
private const val VIEWPORT_RESTORE_MAX_FRAMES = 90
private const val VIEWPORT_EXACT_WIDTH_WAIT_FRAMES = 18
private const val VIEWPORT_RESTORE_STABLE_FRAMES = 4
private const val VIEWPORT_RESTORE_TOLERANCE_PX = 0.75f
private val TURN_SPACING = 22.dp

// Assistant body layout scale (DESIGN.md §5.4). Compose trims the half-leading above the first
// line and below the last line of every Text, so the 29sp body lineHeight buys air INSIDE a
// paragraph or list item and none at all BETWEEN two of them. Before this scale existed, two
// list items sat 11dp apart while two wrapped lines of ONE item sat 12.6dp apart — the item
// boundary was weaker than the line boundary, which is what made numbered answers read as a wall.
private val MD_BLOCK = 5.dp        // paragraph <-> paragraph (~17.6dp of visible white)
private val MD_LIST = 4.dp         // above/below a whole list; the item padding already
                                   // separates the group, so a big value here punches a hole
private val MD_LIST_ITEM = 5.dp    // each side of an item -> ~17dp between items
private val MD_LIST_INDENT = 20.dp // per nesting level (library default 8dp read as noise)
private val MD_RULE_GAP = 16.dp    // above/below a thematic break
private val MD_BLOCK_ELEMENT_GAP = 8.dp // code blocks and table cards against their neighbours
private val MD_QUOTE_GAP = 8.dp    // a quote is a block, not a paragraph continuation
// Headings: generous above (a heading opens a chapter), modest but unambiguous below. The old
// values gave h2 6dp above and NOTHING below, gluing every heading to its first paragraph.
private val MD_H1_TOP = 22.dp
private val MD_H2_TOP = 20.dp
private val MD_H3_TOP = 16.dp
private val MD_H4_TOP = 14.dp
private val MD_H_BOTTOM_LARGE = 8.dp
private val MD_H_BOTTOM_SMALL = 6.dp

private val MARKDOWN_LINK_TYPES = setOf(
    MarkdownElementTypes.INLINE_LINK,
    MarkdownElementTypes.AUTOLINK,
    MarkdownElementTypes.FULL_REFERENCE_LINK,
    MarkdownElementTypes.SHORT_REFERENCE_LINK,
    GFMTokenTypes.GFM_AUTOLINK,
)
private const val MARKDOWN_LINK_ICON_TAG = "hermes-link-icon"

/**
 * Whether this AST node is a link that should get the external-link glyph in front of it.
 *
 * A GFM autolink is only a link when it stands on its own in the prose. The parser also emits one
 * inside `[label](url)` — as the LINK_DESTINATION, and as the LINK_TEXT too when the label happens
 * to be a URL — and those are parts of the surrounding INLINE_LINK, not links in their own right.
 * Counting them would put two or three glyphs on a single link.
 */
internal fun shouldPrefixLinkIcon(node: ASTNode): Boolean {
    if (node.type !in MARKDOWN_LINK_TYPES) return false
    if (node.type != GFMTokenTypes.GFM_AUTOLINK) return true
    return node.parent?.type !in NESTED_AUTOLINK_PARENTS
}

/** Parents whose GFM_AUTOLINK child is a component of an enclosing link, not a link itself. */
private val NESTED_AUTOLINK_PARENTS = setOf(
    MarkdownElementTypes.LINK_TEXT,
    MarkdownElementTypes.LINK_DESTINATION,
    MarkdownElementTypes.LINK_LABEL,
)
// One marker column for both list types, so ordered and unordered share a text edge. Sized in sp,
// not dp: 28sp clears a two-digit "10." beside 17sp body — a list running past nine items keeps a
// straight left edge instead of stepping right at item 10 — and it has to keep clearing it when
// the reader turns the system font up.
private val MD_MARKER_WIDTH = 28.sp

/** "14:32" today, "昨天 14:32" yesterday, "8月30日 14:32" this year, full date otherwise. */
internal fun formatTimeSeparator(ts: Long, language: com.hermes.client.ui.localization.AppLanguage): String {
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
private fun chatMarkdownComponents(
    onOpenTableFullscreen: (String) -> Unit,
    anchorPrefix: String,
): MarkdownComponents =
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
        // (approved normal-content mockup): spacing carries the hierarchy, not decoration. The
        // space BELOW matters just as much — without it a heading reads as the paragraph's first
        // line rather than as its title.
        heading1 = { m ->
            Column(Modifier.padding(top = MD_H1_TOP, bottom = MD_H_BOTTOM_LARGE)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h1)
            }
        },
        heading2 = { m ->
            Column(Modifier.padding(top = MD_H2_TOP, bottom = MD_H_BOTTOM_LARGE)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h2)
            }
        },
        heading3 = { m ->
            Column(Modifier.padding(top = MD_H3_TOP, bottom = MD_H_BOTTOM_SMALL)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h3)
            }
        },
        heading4 = { m ->
            Column(Modifier.padding(top = MD_H4_TOP, bottom = MD_H_BOTTOM_SMALL)) {
                com.mikepenz.markdown.compose.elements.MarkdownHeader(m.content, m.node, m.typography.h4)
            }
        },
        // A proportional marker column let "10." push its text further right than "9.", so the
        // left edge of a 10+ item list came out ragged. Reserve one fixed column instead.
        orderedList = { m ->
            // markerModifier is not a composable lambda, so resolve the scaled width out here.
            val markerWidth = with(LocalDensity.current) { MD_MARKER_WIDTH.toDp() }
            com.mikepenz.markdown.compose.elements.MarkdownOrderedList(
                content = m.content,
                node = m.node,
                depth = m.listDepth,
                markerModifier = { Modifier.widthIn(min = markerWidth) },
            )
        },
        // Same fixed column for bullets, so ordered and unordered lists share one text edge.
        // Rebuilt on MarkdownListItems rather than MarkdownBulletList because the latter hangs
        // padding(bottom = listItemBottom) on the marker itself: at listItemBottom = 5dp that
        // lifted every bullet off its own first baseline.
        unorderedList = { m ->
            val markerWidth = with(LocalDensity.current) { MD_MARKER_WIDTH.toDp() }
            com.mikepenz.markdown.compose.elements.MarkdownListItems(
                content = m.content,
                node = m.node,
                depth = m.listDepth,
                markerModifier = { Modifier.widthIn(min = markerWidth) },
            ) { _, _, _ ->
                MarkdownBullet(m.listDepth, m.typography.bullet)
            }
        },
        // Passing a custom component set drops the M3 checkbox that m3 Markdown() injects by
        // default, which rendered GFM task lists as the literal text "[ ]" / "[x]".
        checkbox = { m ->
            com.mikepenz.markdown.m3.elements.MarkdownCheckBox(m.content, m.node, m.typography.text)
        },
        horizontalRule = {
            Column(Modifier.padding(vertical = MD_RULE_GAP)) {
                com.mikepenz.markdown.compose.elements.MarkdownDivider(Modifier.fillMaxWidth())
            }
        },
        // Without an outer margin a quote sat 10.7dp under its paragraph — closer than two
        // wrapped lines of that paragraph, so it read as a continuation rather than an aside.
        blockQuote = { m ->
            Column(Modifier.padding(vertical = MD_QUOTE_GAP)) {
                com.mikepenz.markdown.compose.elements.MarkdownBlockQuote(m.content, m.node)
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
                StyledMarkdownTable(
                    m.content,
                    m.node,
                    m.typography.table,
                    anchorPrefix = "$anchorPrefix:table:${m.node.startOffset}",
                )
            }
        },
    )

/**
 * List bullet drawn rather than typeset. A "\u2022" glyph sits at Latin x-height, which reads as
 * floating well above the optical centre of a CJK line, and the library additionally hangs
 * listItemBottom padding on the marker text. Drawing inside a box exactly one line tall centres
 * the mark on the item's first line at any font scale, and gives the three depths one geometry.
 */
@Composable
private fun MarkdownBullet(depth: Int, style: TextStyle) {
    val color = com.mikepenz.markdown.compose.LocalMarkdownColors.current.text
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
        when (depth % 3) {
            0 -> Canvas(Modifier.size(5.dp)) { drawCircle(color) }
            1 -> Canvas(Modifier.size(5.5.dp)) {
                drawCircle(color, radius = size.minDimension / 2f - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
            else -> Canvas(Modifier.size(4.5.dp)) { drawRect(color) }
        }
    }
}

/** The styled table body shared by the in-chat card and the fullscreen dialog. */
@Composable
private fun StyledMarkdownTable(
    content: String,
    node: org.intellij.markdown.ast.ASTNode,
    style: TextStyle,
    anchorPrefix: String? = null,
) {
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
            SemanticAnchorBox(
                anchorKey = anchorPrefix?.let { "$it:header:${header.startOffset}" },
                modifier = Modifier
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
            SemanticAnchorBox(
                anchorKey = anchorPrefix?.let { "$it:row:${row.startOffset}" },
                modifier = Modifier.tableGrid(cellCount(row)),
            ) {
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

/** A zero-style semantic landmark; table rows give cross-width restores line-sized precision. */
@Composable
private fun SemanticAnchorBox(
    anchorKey: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val viewport = LocalChatViewportController.current
    if (anchorKey == null || viewport == null) {
        Box(modifier) { content() }
        return
    }
    androidx.compose.runtime.DisposableEffect(viewport, anchorKey) {
        onDispose { viewport.removeBlock(anchorKey) }
    }
    Box(modifier.onGloballyPositioned { viewport.updateBlock(anchorKey, it.boundsInWindow()) }) {
        content()
    }
}

/** Gallery/sample entry: renders a raw markdown table with the chat table styling. */
@Composable
internal fun StyledMarkdownTableSample(raw: String) {
    Markdown(
        content = raw,
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(),
        typography = markdownTypography(
            table = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
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
        modifier = Modifier.padding(vertical = MD_BLOCK_ELEMENT_GAP),
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
            .padding(vertical = MD_BLOCK_ELEMENT_GAP)
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = com.hermes.client.ui.components.hermesSheetState()) {
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

/**
 * Whether the transcript needs a session-level running indicator: the run is active but no
 * assistant bubble carries the streaming state. Happens when the Relay observes `run.started`
 * before `message.start` arrives, or when a history reconcile swapped the in-flight row out
 * (HG-8, 2026-09-05). Pure so the rule is unit-testable.
 */
internal fun showsSessionRunIndicator(isGenerating: Boolean, messages: List<ChatMessage>): Boolean =
    isGenerating && messages.none { it.role == Role.ASSISTANT && it.isStreaming }

/**
 * A content-less streaming record for [RunningStatusLine]: with no output there is no label and no
 * elapsed suffix, so the mark stands alone exactly as it does before a real turn's first token.
 */
internal fun sessionRunPlaceholder(sessionId: String) = ChatMessage(
    id = "run-indicator-$sessionId",
    role = Role.ASSISTANT,
    text = "",
    isStreaming = true,
)

/**
 * The one thing on screen that says "your Mac is working". It stays put from the moment the turn
 * starts to the first token to the tool that follows: only the text beside it changes, so nothing
 * jumps (docs/DESIGN.md §5.6). The three bouncing dots it replaced were an instant-messaging idiom
 * for "someone is typing", which is not what happens here.
 */
@Composable
internal fun RunningStatusLine(msg: ChatMessage) {
    val language = LocalAppLanguage.current
    val hasOutput = msg.text.isNotBlank() || msg.tools.isNotEmpty() || msg.thinking.isNotBlank()
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
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.hermes.client.ui.components.HermesMark(
            size = 14.dp,
            modifier = Modifier.padding(end = 8.dp),
            contentDescription = localized(language, "正在生成", "Generating"),
        )
        // Before the first token there is nothing true to say yet; the mark alone says it.
        // "Preparing…" would only be read once and then replaced a beat later by the real status.
        val style = MaterialTheme.typography.bodySmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        if (hasOutput) when (status) {
            is RunningStatus.Tool -> Text(
                localized(language, "正在运行 ", "Running ") + status.label + "…" + elapsedSuffix,
                style = style.copy(fontFamily = FontFamily.Monospace),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            is RunningStatus.Thinking -> Text(
                status.preview + elapsedSuffix,
                style = style.copy(fontStyle = FontStyle.Italic),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RunningStatus.Generating -> Text(
                localized(language, "生成中…", "Generating…") + elapsedSuffix,
                style = style,
                color = color,
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
    // The search counter landed on a hit inside this reasoning: open it so the hit is visible.
    // Closing the search does not fold it back; the reader may be mid-read.
    val autoExpand = shouldAutoExpand(LocalChatSearch.current, LocalTurnIsCurrentHit.current, SearchSource.THINKING, text)
    LaunchedEffect(autoExpand) { if (autoExpand) expanded = true }
    AssistChip(
        onClick = { expanded = !expanded },
        label = { Text(if (expanded) localized(language, "收起思考过程", "Hide reasoning") else localized(language, "查看思考过程", "View reasoning")) },
    )
    if (expanded) {
        SelectionContainer {
            Text(
                searchHighlighted(text),
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
