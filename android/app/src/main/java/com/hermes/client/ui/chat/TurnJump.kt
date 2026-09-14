package com.hermes.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.components.ArrowToTopIcon
import com.hermes.client.ui.components.ChevronsDownIcon
import com.hermes.client.ui.components.PromptListIcon
import com.hermes.client.ui.components.SheetCloseHandle
import com.hermes.client.ui.components.ThinChevronIcon
import com.hermes.client.ui.components.hermesSheetState
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.ChatPillLabel
import com.hermes.client.ui.theme.ChatPromptLabel
import com.hermes.client.ui.theme.ChatPromptLabelCurrent
import com.hermes.client.ui.theme.ChatPromptTime
import com.hermes.client.ui.theme.ChatSheetCount
import com.hermes.client.ui.theme.ChatSheetTitle
import com.hermes.client.ui.theme.Motion
import com.hermes.client.ui.theme.chatChipBorder
import com.hermes.client.ui.theme.chatChipColor
import com.hermes.client.ui.theme.chatChipInkColor
import com.hermes.client.ui.theme.chatCurrentBorderColor
import com.hermes.client.ui.theme.chatCurrentDiscColor
import com.hermes.client.ui.theme.chatCurrentDiscInkColor
import com.hermes.client.ui.theme.chatCurrentFillColor
import com.hermes.client.ui.theme.chatCurrentTimeColor
import com.hermes.client.ui.theme.chatPillBorderColor
import com.hermes.client.ui.theme.chatPillDividerColor
import com.hermes.client.ui.theme.chatPillFillColor
import com.hermes.client.ui.theme.chatPillIconChipColor
import com.hermes.client.ui.theme.chatRowChevronColor
import com.hermes.client.ui.theme.chatSheetColor
import com.hermes.client.ui.theme.chatSheetHairlineColor
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

/** The pill's fixed height (docs/DESIGN.md §5.4, Stitch 基线-聊天页/滑动引导胶囊). */
internal val TURN_PILL_HEIGHT: Dp = 38.dp

/**
 * How wide the summary itself may get.
 *
 * This, not the 92% container clamp in ChatComponents, is what actually decides how wide the pill
 * ends up on a phone — the mock caps the label at 190px and lets the container clamp be a backstop.
 */
internal val TURN_PILL_LABEL_MAX_WIDTH: Dp = 190.dp

/** How long the prompt just jumped to keeps the landing highlight (docs/DESIGN.md §5.4). */
internal const val TURN_JUMP_FLASH_MS = 1_200L
/** Part of TURN_JUMP_FLASH_MS the outline holds at full strength before fading. */
internal const val TURN_JUMP_FLASH_HOLD_MS = 300L

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
 * @param searchOpen the search bar has the top bar's place; the pill stands down for it (HG-45).
 *   Both float over the transcript, and while you are stepping through hits the pill is answering
 *   a question you are not asking.
 */
internal fun turnPillFor(
    groups: List<TurnGroup>,
    topVisibleMessageIndex: Int,
    visibleMessageRange: IntRange,
    atBottom: Boolean,
    searchOpen: Boolean = false,
): TurnPillTarget? {
    if (searchOpen || atBottom || groups.isEmpty() || topVisibleMessageIndex < 0) return null
    val groupIndex = groupIndexOf(groups, topVisibleMessageIndex)
    if (groups[groupIndex].anchorIndex in visibleMessageRange) return null
    return TurnPillTarget(groupIndex, showList = groups.size >= TURN_PILL_LIST_MIN_GROUPS)
}

/**
 * Whether the round "back to the newest message" button is on screen.
 *
 * Pure for the same reason [turnPillShown] is: the only test that ever touched this button is an
 * androidTest, which CI does not run, so the rule lived exclusively inside a composable `if`.
 *
 * @param scrolling hidden mid-scroll on purpose — the tap that arrests a fling lands where the
 *   button would be, and used to be swallowed as a click (android/README.md).
 * @param searchOpen hidden while searching (HG-45): the search bar owns downward movement now,
 *   and its ↓ means "next hit", not "jump to the end".
 */
internal fun scrollToBottomShown(
    presentationReady: Boolean,
    atBottom: Boolean,
    scrolling: Boolean,
    searchOpen: Boolean,
): Boolean = presentationReady && !atBottom && !scrolling && !searchOpen

/** A queued jump: the reversed list index to align plus the message the landing feedback marks. */
internal data class TurnJumpRequest(val listIndex: Int, val anchorIndex: Int)

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
    // De-blued 2026-09-12 (docs/DESIGN.md §5.4, Stitch 基线-聊天页/滑动引导胶囊 / 暗夜). This is
    // NO LONGER the session list's 「需要你处理」 pill: paper fill, a hairline ring, a full round,
    // 38dp tall. That pill is a to-do prompt and the brand colour is its meaning; this one is a
    // scroll-position indicator that sits in front of the reader while they scroll, and a solid
    // brand fill made it look like a control that had to be dealt with. Do not re-merge them.
    Surface(
        color = chatPillFillColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        border = BorderStroke(1.dp, chatPillBorderColor()),
        shadowElevation = 2.dp,
        modifier = modifier.testTag("turn-jump-pill"),
    ) {
        Row(
            Modifier.height(TURN_PILL_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight()
                    .combinedClickable(onClick = onJump, onLongClick = onOpenList)
                    .semantics { contentDescription = jumpDescription }
                    .padding(start = 12.dp, end = if (showList) 10.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(chatPillIconChipColor()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ArrowToTopIcon, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                Text(
                    label,
                    style = ChatPillLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .widthIn(max = TURN_PILL_LABEL_MAX_WIDTH),
                )
            }
            AnimatedVisibility(
                visible = showList,
                enter = expandHorizontally(animationSpec = tween(Motion.DurationShort)) +
                    fadeIn(animationSpec = tween(Motion.DurationShort)),
                exit = shrinkHorizontally(animationSpec = tween(Motion.DurationShort)) +
                    fadeOut(animationSpec = tween(Motion.DurationShort)),
            ) {
                Row(
                    Modifier.fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(15.dp)
                            .background(chatPillDividerColor()),
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .clickable(onClick = onOpenList)
                            .semantics { contentDescription = listDescription }
                            .testTag("turn-jump-pill-list")
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(PromptListIcon, contentDescription = null, modifier = Modifier.size(16.dp))
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
    // Sheet gestures OFF (docs/DESIGN.md §5.8 global rule): scrolling the list never drags or
    // closes the sheet. Closing is the grab bar, the scrim, back — and, since 2026-09-12, the
    // header's ✕ as a FOURTH way in, not a replacement for any of the three (§5.8).
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = chatSheetColor(),
        // 24dp: the one documented exception to §5.8's global 16dp, because the mock draws it.
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { SheetCloseHandle(onDismiss) },
    ) {
        PromptListHeader(
            count = rows.count { !it.isLeading },
            onLatest = onLatest,
            onDismiss = onDismiss,
        )
        PromptListContent(rows, onPick)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Left-aligned title, the count as a chip beside it, and two 32dp round buttons on the right
 * (docs/DESIGN.md §5.4, Stitch 基线-聊天页/我的提问, 2026-09-12).
 *
 * This used to be a centred title with the count as a subtitle, justified as "the model sheet's
 * pattern". That justification had already expired: §5.5 made sheet titles left-aligned and the
 * model sheet followed, leaving this the last centred one in the app.
 *
 * The title follows the conversation's origin — read from [LocalBotOrigin], the same source the
 * user bubbles' speaker label uses — like the menu entry that opens this sheet already did.
 * Calling a bot's own messages 「我的提问」 while the menu that opened the sheet said 「对方的提问」
 * was a plain bug (fixed 2026-09-12).
 */
@Composable
internal fun PromptListHeader(
    count: Int,
    onLatest: () -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val botOrigin = LocalBotOrigin.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(promptListTitle(botOrigin, language), style = ChatSheetTitle)
            Spacer(Modifier.width(8.dp))
            Surface(
                color = chatChipColor(),
                contentColor = chatChipInkColor(),
                shape = CircleShape,
                border = chatChipBorder(),
            ) {
                Text(
                    localized(language, "$count 条", "$count prompts"),
                    style = ChatSheetCount,
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("prompt-list-count"),
                )
            }
            Spacer(Modifier.weight(1f))
            PromptListHeaderButton(
                icon = ChevronsDownIcon,
                description = localized(language, "回到最新", "Latest"),
                testTag = "prompt-list-latest",
                onClick = onLatest,
            )
            Spacer(Modifier.width(8.dp))
            PromptListHeaderButton(
                icon = Icons.Rounded.Close,
                description = localized(language, "关闭", "Close"),
                testTag = "prompt-list-close",
                onClick = onDismiss,
            )
        }
        HorizontalDivider(color = chatSheetHairlineColor())
    }
}

/** One of the header's two round buttons: 32dp of paint, 48dp of touch (§5.7). */
@Composable
private fun PromptListHeaderButton(
    icon: ImageVector,
    description: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = chatChipColor(),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape,
            border = chatChipBorder(),
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * The rows (docs/DESIGN.md §5.4; restyled 2026-09-12 off Stitch 基线-聊天页/我的提问): ordinal
 * disc → prompt (≤2 lines) → time only when the message has one → thin chevron. The ordinal is the
 * stable coordinate; time is the supplement.
 *
 * The current row is de-blued: a neutral block with a hairline ring and an INVERTED ordinal disc
 * (ink on paper in light, paper on ink in dark) where it used to be `primaryContainer` with a
 * `primary` disc. Still NO extra text — the block and the disc already say "here"; TalkBack gets
 * it as a state description instead.
 *
 * ≤2 lines is a deliberate departure from the mock, which truncates to one: "an over-long summary"
 * is one of the states this feature has to cover, and the words the mock drops are exactly the
 * ones that tell two similar prompts apart.
 */
@Composable
internal fun PromptListContent(
    rows: List<PromptRow>,
    onPick: (PromptRow) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = promptListInitialIndex(rows)),
) {
    val language = LocalAppLanguage.current
    val hairline = chatSheetHairlineColor()
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
                    HorizontalDivider(color = hairline, modifier = Modifier.padding(start = 66.dp, end = 20.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = if (current) 4.dp else 0.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (current) {
                                Modifier
                                    .background(chatCurrentFillColor())
                                    .border(1.dp, chatCurrentBorderColor(), RoundedCornerShape(12.dp))
                            } else Modifier,
                        )
                        .clickable { onPick(row) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            if (current) stateDescription = hereLabel
                        }
                        .padding(horizontal = if (current) 14.dp else 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.ordinal != null) {
                        Surface(
                            color = if (current) chatCurrentDiscColor() else chatChipColor(),
                            contentColor = if (current) chatCurrentDiscInkColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = CircleShape,
                            border = if (current) null else chatChipBorder(),
                            modifier = Modifier.size(26.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    row.ordinal.toString(),
                                    style = ChatSheetCount.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.label,
                            style = if (current) ChatPromptLabelCurrent else ChatPromptLabel,
                            color = if (row.isLeading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.time != null) {
                            Text(
                                row.time,
                                style = ChatPromptTime,
                                color = if (current) chatCurrentTimeColor() else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        ThinChevronIcon,
                        contentDescription = null,
                        tint = if (current) MaterialTheme.colorScheme.onSurface else chatRowChevronColor(),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
