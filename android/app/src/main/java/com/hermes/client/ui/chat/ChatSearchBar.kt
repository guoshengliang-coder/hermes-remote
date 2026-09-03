package com.hermes.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.SearchField
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.sessions.highlighted

/**
 * In-chat search, in the top bar's place (docs/DESIGN.md §5.4 聊天内搜索): the shared
 * SearchField, the occurrence counter, previous/next, close; beneath it either the current hit's
 * source tag + snippet, or — with nothing found — the way out to the global search.
 */
@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    currentHit: SearchHit?,
    historyLoaded: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onSearchAll: ((String) -> Unit)?,
    requestFocus: Boolean = true,
) {
    val language = LocalAppLanguage.current
    val accent = MaterialTheme.colorScheme.primary
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) focus.requestFocus() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = localized(language, "在对话中搜索…", "Search in chat…"),
                modifier = Modifier.weight(1f).focusRequester(focus),
                onSearch = onNext,
            )
            // Coerce into range: the cursor can transiently exceed a shrunk match set before the
            // reset effect runs — avoids a glitchy counter like "5/2".
            val displayIndex = if (matchCount == 0) 0 else currentIndex.coerceAtMost(matchCount - 1) + 1
            Text(
                "$displayIndex/$matchCount",
                color = if (matchCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onPrevious, enabled = matchCount > 0) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = localized(language, "上一个匹配项", "Previous match"), tint = if (matchCount > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onNext, enabled = matchCount > 0) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = localized(language, "下一个匹配项", "Next match"), tint = if (matchCount > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = localized(language, "关闭搜索", "Close search"))
            }
        }
        val q = query.trim()
        when {
            currentHit != null -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        when (currentHit.source) {
                            SearchSource.TEXT -> localized(language, "正文", "Text")
                            SearchSource.THINKING -> localized(language, "思考", "Reasoning")
                            SearchSource.TOOL -> localized(language, "工具", "Tool")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    highlighted(currentHit.snippet, q),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            q.length >= 2 && !historyLoaded -> Text(
                localized(language, "正在加载对话…", "Loading the conversation…"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
            )
            q.length >= 2 -> Row(
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
    }
}
