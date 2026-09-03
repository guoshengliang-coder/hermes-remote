package com.hermes.client.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.util.relativeTimeLabel

/** [text] with every occurrence of [query] in the primary colour at Medium weight. */
@Composable
fun highlighted(text: String, query: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val ranges = remember(text, query) { highlightRanges(text, query) }
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val style = SpanStyle(color = accent, fontWeight = FontWeight.Medium)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/** Section header of the search page: primary label left, count / status / action right. */
@Composable
fun SearchSectionHeader(label: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = if (onTrailing != null) 4.dp else 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            if (onTrailing != null) {
                TextButton(onClick = onTrailing) {
                    Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Title match: existing list row + subline, with the query highlighted in the title. */
@Composable
fun TitleMatchRow(match: TitleMatch, query: String, defaultProjectPath: String?, onClick: () -> Unit, pinned: Boolean = false) {
    val language = LocalAppLanguage.current
    ListItem(
        headlineContent = { Text(highlighted(match.session.title, query), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { SessionSubline(match.session, defaultProjectPath = defaultProjectPath, pinned = pinned) },
        trailingContent = if (match.archived) ({ ArchivedTag(language) }) else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Message hit (docs/DESIGN.md §5.2 搜索页): title as anchor + relative time; the snippet as
 * evidence (two lines, query highlighted); the project only when it is not the default.
 */
@Composable
fun MessageHitRow(hit: MessageHit, query: String, nowMs: Long, onClick: () -> Unit) {
    val language = LocalAppLanguage.current
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlighted(hit.title, query),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hit.archived) {
                    Spacer(Modifier.width(8.dp))
                    ArchivedTag(language)
                }
                if (hit.lastActiveMs != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeTimeLabel(hit.lastActiveMs, nowMs, language),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (hit.snippet.isNotEmpty()) {
                    Text(
                        highlighted(hit.snippet, query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hit.projectLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            FolderStrokeIcon,
                            contentDescription = null,
                            tint = LocalContentColor.current,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(hit.projectLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ArchivedTag(language: com.hermes.client.ui.localization.AppLanguage) {
    Text(
        localized(language, "已归档", "Archived"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Recent search: history glyph + query, × removes just this entry. */
@Composable
fun RecentSearchRow(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val language = LocalAppLanguage.current
    ListItem(
        leadingContent = {
            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = localized(language, "删除“$query”", "Remove \"$query\""),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * HR-SEARCH-001 strip inside the message section: localized summary with the code, Retry, and a
 * long-press that copies the redacted diagnostic (docs/ERROR_HANDLING.md presentation rules).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchErrorStrip(error: AppError, onRetry: () -> Unit) {
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .combinedClickable(onClick = onRetry, onLongClick = { clipboard.setText(AnnotatedString(error.sanitizedDiagnostic())) })
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            error.localizedMessage(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(localized(language, "重试", "Retry"), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
