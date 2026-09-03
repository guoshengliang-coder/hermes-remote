package com.hermes.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.components.ArrowToTopIcon
import com.hermes.client.ui.components.PromptListIcon
import com.hermes.client.ui.components.ThinChevronIcon
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

/** From this many groups on, the pill always carries the prompt-list segment (decision 2026-09-03). */
internal const val TURN_PILL_LIST_MIN_GROUPS = 3

/** How long the prompt just jumped to keeps the landing highlight (docs/DESIGN.md §5.4). */
internal const val TURN_JUMP_FLASH_MS = 1_500L

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
    return TurnPillTarget(groupIndex, showList = groups.size >= TURN_PILL_LIST_MIN_GROUPS)
}

/** Reversed LazyColumn index of a message: slot 0 is the permanent bottom edge, newest turn is 1. */
internal fun messageListIndex(messageCount: Int, messageIndex: Int): Int = messageCount - messageIndex

/** Inverse of [messageListIndex]; null for the bottom-edge slot. */
internal fun listMessageIndex(messageCount: Int, listIndex: Int): Int? =
    if (listIndex <= 0) null else messageCount - listIndex

/** Frame budget for a jump to settle while asynchronously rendered Markdown around the target grows. */
internal const val TURN_JUMP_SETTLE_FRAMES = 120
internal const val TURN_JUMP_STABLE_FRAMES = 3
internal const val TURN_JUMP_TOLERANCE_PX = 2
/** How long to keep re-placing a target while the list sits clamped at its end waiting for growth. */
internal const val TURN_JUMP_CLAMPED_FRAMES = 24

/**
 * Scroll so the item's TOP sits [topInsetPx] below the viewport's content edge and keep it there
 * while the transcript settles.
 *
 * What makes this hard: the turns BELOW the target (newer, lower indices under reverseLayout) are
 * composed lazily, and a freshly composed answer measures a line or two tall until its Markdown
 * parses asynchronously a few frames later. So the first absolute placement is computed against
 * undersized neighbours, the list clamps at its bottom end, and the moment those neighbours grow
 * the target is pushed off the top. Two earlier loops failed on exactly this: a relative-scrollBy
 * loop re-anchored with `scrollToItem(index)`, which scrolled the neighbours out of composition,
 * reset their parse and bounced every frame for 3 s (0.1.83); a loop that re-anchored only once
 * gave up at the bottom (0.1.84).
 *
 * Rules now (device-derived, 2026-09-03):
 *  * Every correction is an absolute `scrollToItem(index, offset)` from the target's last known
 *    size — never `scrollToItem(index)` alone once the target has been seen, so the neighbours
 *    below stay composed and keep the height they have grown to.
 *  * Aligned within [TURN_JUMP_TOLERANCE_PX] for [TURN_JUMP_STABLE_FRAMES] frames → done.
 *  * Clamped at the end the correction needs → keep re-placing for up to
 *    [TURN_JUMP_CLAMPED_FRAMES] frames so undersized neighbours can finish growing, then stop:
 *    a prompt in the last turn genuinely cannot reach the top, and a live answer streaming below
 *    it must not hold the scroll mutex for its whole life.
 *  * A user drag steals the scroll mutex and cancels the loop (caller handles it).
 */
internal suspend fun LazyListState.alignItemTopToViewport(listIndex: Int, topInsetPx: Int) {
    fun info(index: Int): LazyListItemInfo? = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    fun contentArea(): Int {
        val li = layoutInfo
        return li.viewportEndOffset - li.viewportStartOffset - li.beforeContentPadding - li.afterContentPadding
    }
    // Pixels the item's top edge sits above its target line (positive = too high on screen).
    fun overshoot(item: LazyListItemInfo): Int {
        val li = layoutInfo
        val desiredFarEdge = li.viewportEndOffset - li.afterContentPadding - topInsetPx
        return (item.offset + item.size) - desiredFarEdge
    }
    fun offsetFor(size: Int): Int = size - contentArea() + topInsetPx

    val initial = info(listIndex)
    var lastSize = initial?.size ?: -1
    if (initial != null) {
        // Near target: one animated move; a stale size only leaves a residual the loop absorbs.
        animateScrollToItem(listIndex, offsetFor(initial.size))
    } else {
        scrollToItem(listIndex)
    }
    var stable = 0
    var clamped = 0
    repeat(TURN_JUMP_SETTLE_FRAMES) {
        withFrameNanos { }
        val item = info(listIndex)
        if (item == null) {
            // Pushed off the top by neighbours growing below it: re-place it from its last known
            // size. Only an item never seen at all is brought back with a bare scrollToItem.
            if (lastSize > 0) scrollToItem(listIndex, offsetFor(lastSize)) else scrollToItem(listIndex)
            stable = 0
            return@repeat
        }
        lastSize = item.size
        val delta = overshoot(item)
        if (abs(delta) <= TURN_JUMP_TOLERANCE_PX) {
            if (++stable >= TURN_JUMP_STABLE_FRAMES) return
            return@repeat
        }
        stable = 0
        // Too low (negative) -> content must move up, towards newer turns = the list start under
        // reverseLayout. Already there: wait for neighbours to grow, but only for a bounded number
        // of frames — counted regardless of what a streaming tail below keeps doing to the residual.
        val atBottom = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
        val atNeededEnd = if (delta < 0) atBottom || !canScrollBackward else !canScrollForward
        if (atNeededEnd) {
            if (++clamped >= TURN_JUMP_CLAMPED_FRAMES) return
        } else {
            clamped = 0
        }
        scrollToItem(listIndex, offsetFor(item.size))
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
                    .combinedClickable(onClick = onJump, onLongClick = onOpenList)
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

/** One row of the prompt list. [ordinal] is 1-based over prompt groups; null for the leading group. */
internal data class PromptRow(
    val groupIndex: Int,
    val ordinal: Int?,
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
): List<PromptRow> {
    var ordinal = 0
    return groups.mapIndexed { index, group ->
        val prompt = group.promptIndex?.let { messages[it] }
        PromptRow(
            groupIndex = index,
            ordinal = if (prompt == null) null else ++ordinal,
            label = if (prompt == null) localized(language, "会话开始", "Start of chat") else promptSummary(prompt, language),
            time = prompt?.timestamp?.let(formatTime),
            isCurrent = index == currentGroupIndex,
            isLeading = prompt == null,
        )
    }
}

/** Rows above the current one kept in view when the sheet opens, so "here" sits mid-list. */
internal const val PROMPT_LIST_ROWS_ABOVE_CURRENT = 2

internal fun promptListInitialIndex(rows: List<PromptRow>): Int =
    (rows.indexOfFirst { it.isCurrent } - PROMPT_LIST_ROWS_ABOVE_CURRENT).coerceAtLeast(0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptListSheet(
    rows: List<PromptRow>,
    onPick: (PromptRow) -> Unit,
    onLatest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = hermesSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PromptListHeader(count = rows.count { !it.isLeading }, onLatest = onLatest)
        PromptListContent(rows, onPick)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Centred title with the prompt count as its subtitle (the model-sheet header pattern, DESIGN.md
 * §5.8) and 「回到最新」 on the right: the only way out of a long list that is not "scroll".
 */
@Composable
internal fun PromptListHeader(count: Int, onLatest: () -> Unit, modifier: Modifier = Modifier) {
    val language = LocalAppLanguage.current
    Box(modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(localized(language, "我的提问", "Your prompts"), style = MaterialTheme.typography.titleMedium)
            Text(
                localized(language, "$count 条", "$count prompts"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onLatest, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).testTag("prompt-list-latest")) {
            Text(localized(language, "回到最新", "Latest"))
        }
    }
}

/**
 * The rows (docs/DESIGN.md §5.4, decision 2026-09-03): ordinal circle → prompt (≤2 lines) → time
 * only when the message has one → thin chevron. The ordinal is the stable coordinate because
 * gateway history carries no timestamps; the current row is a primaryContainer block with a filled
 * ordinal and NO extra text — the block and the circle already say "here"; TalkBack gets it as a
 * state description instead.
 */
@Composable
internal fun PromptListContent(
    rows: List<PromptRow>,
    onPick: (PromptRow) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = promptListInitialIndex(rows)),
) {
    val language = LocalAppLanguage.current
    val hairline = MaterialTheme.colorScheme.surfaceContainerHigh
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().testTag("prompt-list")) {
        itemsIndexed(rows, key = { _, row -> row.groupIndex }) { index, row ->
            val current = row.isCurrent
            val description = when {
                row.isLeading -> row.label
                else -> localized(language, "第 ${row.ordinal} 条：${row.label}", "Prompt ${row.ordinal}: ${row.label}")
            }
            val hereLabel = localized(language, "当前位置", "You are here")
            Column {
                // Hairline from the text edge; none around the highlighted block.
                if (index > 0 && !current && !rows[index - 1].isCurrent) {
                    HorizontalDivider(color = hairline, modifier = Modifier.padding(start = 64.dp, end = 16.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (current) {
                                Modifier
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            } else Modifier,
                        )
                        .clickable { onPick(row) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            if (current) stateDescription = hereLabel
                        }
                        .padding(start = if (current) 16.dp else 24.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.ordinal != null) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                row.ordinal.toString(),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Spacer(Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (row.isLeading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.time != null) {
                            Text(
                                row.time,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        ThinChevronIcon,
                        contentDescription = null,
                        tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
