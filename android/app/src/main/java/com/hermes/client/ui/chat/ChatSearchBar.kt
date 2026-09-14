package com.hermes.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.SearchField
import com.hermes.client.ui.components.SearchFieldStyle
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.ChatSearchCount
import com.hermes.client.ui.theme.ChatSearchQuery
import com.hermes.client.ui.theme.chatChipBorder
import com.hermes.client.ui.theme.chatSearchArrowColor
import com.hermes.client.ui.theme.chatSearchClearDiscColor
import com.hermes.client.ui.theme.chatSearchDividerColor
import com.hermes.client.ui.theme.chatSearchFieldColor
import com.hermes.client.ui.theme.chatSearchHairlineColor
import com.hermes.client.ui.theme.chatSearchInkFaintColor

/**
 * In-chat search, in the top bar's place (docs/DESIGN.md §5.4 聊天内搜索, Stitch 基线-聊天页/搜索).
 *
 * One row: back, then a single pill that carries the query, its clear button, the `n/N` counter and
 * the two navigation arrows. The 2026-09-12 pull collapsed what used to be two rows — the hit's
 * source tag and snippet are gone, because the mock draws the hit state without them and the bar
 * was spending a whole row restating what the highlighted, scrolled-to turn already shows.
 *
 * A second row survives for the two states the mock does NOT draw, which are the two where the
 * chat itself can say nothing: history still loading, and nothing found (with the way out to the
 * global search). Those are 保留项 under §7 item 8, not omissions to copy.
 */
@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    historyLoaded: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onSearchAll: ((String) -> Unit)?,
    requestFocus: Boolean = true,
) {
    val language = LocalAppLanguage.current
    val accent = MaterialTheme.colorScheme.primary
    val faint = chatSearchInkFaintColor()
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) focus.requestFocus() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = localized(language, "关闭搜索", "Close search"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = localized(language, "在对话中搜索…", "Search in chat…"),
                modifier = Modifier.weight(1f).padding(start = 8.dp).focusRequester(focus),
                onSearch = onNext,
                style = chatSearchFieldStyle(),
            ) {
                // Nothing to count and nowhere to go until something is typed: the un-typed mock
                // draws no counter and no arrows, and the field gets the whole pill to itself.
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .padding(start = 6.dp)
                            .size(width = 1.dp, height = 28.dp)
                            .background(chatSearchDividerColor()),
                    )
                    // Coerce into range: the cursor can transiently exceed a shrunk match set before
                    // the reset effect runs — avoids a glitchy counter like "5/2".
                    val displayIndex = if (matchCount == 0) 0 else currentIndex.coerceAtMost(matchCount - 1) + 1
                    Text(
                        buildAnnotatedString {
                            append("$displayIndex")
                            withStyle(SpanStyle(color = faint)) { append("/$matchCount") }
                        },
                        color = if (matchCount == 0) faint else accent,
                        style = ChatSearchCount,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    ChatSearchArrow(
                        icon = Icons.Rounded.KeyboardArrowUp,
                        label = localized(language, "上一个匹配项", "Previous match"),
                        enabled = matchCount > 0,
                        onClick = onPrevious,
                    )
                    ChatSearchArrow(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        label = localized(language, "下一个匹配项", "Next match"),
                        enabled = matchCount > 0,
                        onClick = onNext,
                    )
                }
            }
        }
        val q = query.trim()
        when {
            q.length >= 2 && !historyLoaded -> Text(
                localized(language, "正在加载对话…", "Loading the conversation…"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            q.length >= 2 && matchCount == 0 -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    localized(language, "此会话中没有匹配", "No matches in this chat"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onSearchAll != null) {
                    TextButton(onClick = { onSearchAll(q) }) {
                        Text(localized(language, "在全部会话中搜索", "Search all chats"))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(chatSearchHairlineColor()))
    }
}

/**
 * One of the two navigation arrows inside the field.
 *
 * The two mocks disagree on the tint — light paints them the brand blue, dark paints them plain
 * `onSurface` — and each is transcribed as drawn (§7 item 8: geometry from light, colour from
 * whichever tier you are in). Disabled drops to the same faint tier the `/N` uses.
 */
@Composable
private fun ChatSearchArrow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(28.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) chatSearchArrowColor() else chatSearchInkFaintColor(),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** The chat bar's field values, read off 基线-聊天页/搜索 (docs/DESIGN.md §5.4). */
@Composable
private fun chatSearchFieldStyle(): SearchFieldStyle = SearchFieldStyle(
    shape = RoundedCornerShape(percent = 50),
    containerColor = chatSearchFieldColor(),
    // Literally the sheet chips' ring: the mock gives the dark field the same 1dp #262C35 it gives
    // every other dark inset, and draws none in light, where the fill carries itself.
    border = chatChipBorder(),
    leadingTint = MaterialTheme.colorScheme.outline,
    textStyle = ChatSearchQuery.copy(color = MaterialTheme.colorScheme.onSurface),
    placeholderColor = MaterialTheme.colorScheme.outline,
    clearButtonSize = 28.dp,
    clearDiscSize = 16.dp,
    clearIconSize = 10.dp,
    clearTint = chatSearchInkFaintColor(),
    clearBackground = chatSearchClearDiscColor(),
    endPadding = 6.dp,
)
