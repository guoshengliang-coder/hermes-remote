package com.hermes.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.components.ArrowToTopIcon
import com.hermes.client.ui.components.PromptListIcon
import com.hermes.client.ui.components.hermesSheetState
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.Motion
import kotlin.math.abs

// Turn navigation for long transcripts (docs/DESIGN.md §5.4 「上一组对话胶囊」/「我的提问」):
//
//  * A GROUP is one user prompt plus everything Hermes said until the next prompt. Anything
//    before the first prompt (a greeting, scheduled-task output) forms one leading group with
//    no prompt — labelled 「会话开始」 so it still has a start to jump to.
//  * While the reader is inside a group's answers and that group's prompt has scrolled off the
//    screen, a pill at the top of the list names the prompt; tapping it aligns the prompt to the
//    top of the viewport. WHAT the pill names depends only on what is visible NOW, never on the
//    scroll direction or history, so it is predictable and unit-testable.
//  * WHETHER it is shown adds one timer (decision 2026-09-02): it behaves like a scroll
//    indicator, fading out TURN_PILL_IDLE_HIDE_MS after the finger lifts and the list settles,
//    and reappearing the moment the list moves again. Users read a pill that never leaves as a
//    permanent control; the old always-on rule needed a scroll to the bottom to get rid of it.
//  * Deep in history (the third group from the end or earlier) the pill grows a second segment
//    that opens the prompt list — the same list the top-bar menu reaches from anywhere.

/** One conversation group: the index range starts at [startIndex]; [promptIndex] is null for the leading prompt-less group. */
internal data class TurnGroup(val startIndex: Int, val promptIndex: Int?) {
    /** The message the group is anchored to when jumping: its prompt, or its first message. */
    val anchorIndex: Int get() = promptIndex ?: startIndex
}

/** Real prompts only — injected timeline notes (model switch, delegation summaries) are USER rows but not questions. */
internal fun ChatMessage.isPromptTurn(): Boolean = role == Role.USER && timelineNoteFor(this) == null

internal fun turnGroups(messages: List<ChatMessage>): List<TurnGroup> {
    if (messages.isEmpty()) return emptyList()
    val groups = mutableListOf<TurnGroup>()
    messages.forEachIndexed { index, message ->
        if (message.isPromptTurn()) {
            groups += TurnGroup(startIndex = index, promptIndex = index)
        } else if (groups.isEmpty()) {
            groups += TurnGroup(startIndex = 0, promptIndex = null)
        }
    }
    return groups
}

/** Index into [groups] of the group containing message [messageIndex]. */
internal fun groupIndexOf(groups: List<TurnGroup>, messageIndex: Int): Int {
    var found = 0
    for (i in groups.indices) {
        if (groups[i].startIndex <= messageIndex) found = i else break
    }
    return found
}

/**
 * The one-line label a prompt is known by: its first non-blank line with whitespace collapsed.
 * An attachment-only prompt names the attachment instead of showing an empty pill.
 */
internal fun promptSummary(message: ChatMessage, language: AppLanguage): String {
    val line = message.text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.replace(Regex("\\s+"), " ")
    if (!line.isNullOrEmpty()) return line
    message.files.firstOrNull()?.let { return localized(language, "文件：${it.name}", "File: ${it.name}") }
    if (message.images.isNotEmpty()) {
        val n = message.images.size
        return if (n == 1) localized(language, "图片", "Image") else localized(language, "图片 ×$n", "$n images")
    }
    return localized(language, "（空消息）", "(empty message)")
}

/** What the pill should show for the current viewport, or null to hide it. */
internal data class TurnPillTarget(val groupIndex: Int, val showList: Boolean)

/** Groups from the end that keep a plain pill; from the next one on the list segment appears. */
internal const val TURN_PILL_LIST_DEPTH = 2

/** How long the list must sit still before the pill fades (docs/DESIGN.md §5.4). */
internal const val TURN_PILL_IDLE_HIDE_MS = 1_500L

/**
 * Whether a pill that HAS a target is shown right now: always while the list is moving, and for
 * [TURN_PILL_IDLE_HIDE_MS] after it stops. Pure so the timing rule is unit-testable.
 */
internal fun turnPillShown(hasTarget: Boolean, scrolling: Boolean, idleMs: Long): Boolean =
    hasTarget && (scrolling || idleMs < TURN_PILL_IDLE_HIDE_MS)

/**
 * @param topVisibleMessageIndex message index of the item touching the top of the viewport.
 * @param visibleMessageRange every message index with any part on screen.
 * @param atBottom the list rests at the newest content (following the live tail).
 */
internal fun turnPillFor(
    groups: List<TurnGroup>,
    topVisibleMessageIndex: Int,
    visibleMessageRange: IntRange,
    atBottom: Boolean,
): TurnPillTarget? {
    if (atBottom || groups.isEmpty() || topVisibleMessageIndex < 0) return null
    val groupIndex = groupIndexOf(groups, topVisibleMessageIndex)
    if (groups[groupIndex].anchorIndex in visibleMessageRange) return null
    val fromEnd = groups.lastIndex - groupIndex
    return TurnPillTarget(groupIndex, showList = fromEnd >= TURN_PILL_LIST_DEPTH)
}

/** Reversed LazyColumn index of a message: slot 0 is the permanent bottom edge, newest turn is 1. */
internal fun messageListIndex(messageCount: Int, messageIndex: Int): Int = messageCount - messageIndex

/** Inverse of [messageListIndex]; null for the bottom-edge slot. */
internal fun listMessageIndex(messageCount: Int, listIndex: Int): Int? =
    if (listIndex <= 0) null else messageCount - listIndex

/** Frame budget for a jump to settle while asynchronously rendered Markdown around the target grows. */
internal const val TURN_JUMP_SETTLE_FRAMES = 180
internal const val TURN_JUMP_STABLE_FRAMES = 3

/**
 * Scroll so the item's TOP sits [topInsetPx] below the viewport's content edge and keep it there
 * while the transcript settles. A far jump lands among items whose Markdown has not been
 * measured yet: they grow over the next frames and would carry the target away, so the position
 * is corrected frame by frame (instant [scrollBy] deltas, like the viewport-restore logic).
 *
 * "Settled" means the remaining error has not CHANGED for [TURN_JUMP_STABLE_FRAMES] frames, not
 * that it is zero: when the newer turns below the target are still placeholders the list clamps
 * at its bottom edge and the error stays large, then shrinks frame by frame as those turns
 * measure. Only once nothing moves any more — error zero, or a genuine end of the list — does the
 * loop stop. reverseLayout measures offsets from the bottom edge, hence the far-edge arithmetic.
 */
internal suspend fun LazyListState.alignItemTopToViewport(listIndex: Int, topInsetPx: Int) {
    fun info(index: Int): LazyListItemInfo? = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    // Pixels the item's top edge sits above its target line (positive = too high on screen).
    fun overshoot(item: LazyListItemInfo): Int {
        val li = layoutInfo
        val desiredFarEdge = li.viewportEndOffset - li.afterContentPadding - topInsetPx
        return (item.offset + item.size) - desiredFarEdge
    }
    val initial = info(listIndex)
    if (initial != null) {
        // Near target: one animated move; a stale size only leaves a residual the loop absorbs.
        val li = layoutInfo
        val contentArea = li.viewportEndOffset - li.viewportStartOffset - li.beforeContentPadding - li.afterContentPadding
        animateScrollToItem(listIndex, initial.size - contentArea + topInsetPx)
    } else {
        scrollToItem(listIndex)
    }
    var lastDelta: Int? = null
    var stable = 0
    repeat(TURN_JUMP_SETTLE_FRAMES) {
        withFrameNanos { }
        val item = info(listIndex)
        if (item == null) {
            // Growth below the anchor pushed the target off-screen: bring it back and re-measure.
            scrollToItem(listIndex)
            lastDelta = null
            stable = 0
            return@repeat
        }
        val delta = overshoot(item)
        if (delta == lastDelta) {
            if (++stable >= TURN_JUMP_STABLE_FRAMES) return
        } else {
            stable = 0
            lastDelta = delta
        }
        // Too high -> content must move down -> a positive (forward, towards older turns) delta.
        if (abs(delta) > 1) scrollBy(delta.toFloat())
    }
}

@Composable
internal fun TurnJumpPill(
    label: String,
    showList: Boolean,
    onJump: () -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val jumpDescription = localized(language, "回到这条提问：$label", "Back to this prompt: $label")
    val listDescription = localized(language, "我的提问", "Your prompts")
    // Same pill as the session list's 「需要你处理」 (docs/DESIGN.md §5.2): primaryContainer,
    // 18dp radius, 4dp shadow, labelLarge. Icons are 18dp with a 2.4 stroke so they read at the
    // same weight as the text (§4.1 small-icon compensation).
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 4.dp,
        modifier = modifier.testTag("turn-jump-pill"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clickable(onClick = onJump)
                    .semantics { contentDescription = jumpDescription }
                    .padding(start = 14.dp, end = if (showList) 12.dp else 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ArrowToTopIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            AnimatedVisibility(
                visible = showList,
                enter = expandHorizontally(animationSpec = tween(Motion.DurationShort)) +
                    fadeIn(animationSpec = tween(Motion.DurationShort)),
                exit = shrinkHorizontally(animationSpec = tween(Motion.DurationShort)) +
                    fadeOut(animationSpec = tween(Motion.DurationShort)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)),
                    )
                    Box(
                        Modifier
                            .clickable(onClick = onOpenList)
                            .semantics { contentDescription = listDescription }
                            .testTag("turn-jump-pill-list")
                            .padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                    ) {
                        Icon(PromptListIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** One row of the prompt list. */
internal data class PromptRow(
    val groupIndex: Int,
    val label: String,
    val time: String?,
    val isCurrent: Boolean,
    val isLeading: Boolean,
)

internal fun promptRows(
    groups: List<TurnGroup>,
    messages: List<ChatMessage>,
    currentGroupIndex: Int?,
    language: AppLanguage,
    formatTime: (Long) -> String,
): List<PromptRow> = groups.mapIndexed { index, group ->
    val prompt = group.promptIndex?.let { messages[it] }
    PromptRow(
        groupIndex = index,
        label = if (prompt == null) localized(language, "会话开始", "Start of chat") else promptSummary(prompt, language),
        time = prompt?.timestamp?.let(formatTime),
        isCurrent = index == currentGroupIndex,
        isLeading = prompt == null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptListSheet(
    rows: List<PromptRow>,
    onPick: (PromptRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val sheetState = hermesSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Left-aligned title on the 24dp text baseline shared with the rows (docs/DESIGN.md §5.5).
        Text(
            localized(language, "我的提问", "Your prompts"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        )
        PromptListContent(rows, onPick)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun PromptListContent(
    rows: List<PromptRow>,
    onPick: (PromptRow) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfFirst { it.isCurrent }.coerceAtLeast(0),
    ),
) {
    val language = LocalAppLanguage.current
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().testTag("prompt-list")) {
        itemsIndexed(rows, key = { _, row -> row.groupIndex }) { _, row ->
            // Only the current row paints a container; the others let the sheet's own colour through.
            val background = if (row.isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(background)
                    .clickable { onPick(row) }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (row.isLeading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val supporting = when {
                        row.isCurrent && row.time != null -> localized(language, "${row.time} · 当前位置", "${row.time} · You are here")
                        row.isCurrent -> localized(language, "当前位置", "You are here")
                        else -> row.time
                    }
                    if (supporting != null) {
                        Text(
                            supporting,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (row.isCurrent) {
                    Icon(
                        ArrowToTopIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                    )
                }
            }
        }
    }
}
