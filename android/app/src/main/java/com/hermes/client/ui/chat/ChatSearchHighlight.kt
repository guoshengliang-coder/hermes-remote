package com.hermes.client.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.hermes.client.ui.sessions.highlightRanges
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownTokenTypes

/**
 * In-chat search, as seen by the rendered turns (docs/DESIGN.md §5.4 聊天内搜索): the query to
 * mark in text, and which turn / which source the counter currently points at, so that turn can
 * draw its marks stronger and expand the card the hit lives in.
 */
data class ChatSearchContext(
    val query: String,
    val currentMessageId: String?,
    val currentSource: SearchSource?,
)

val LocalChatSearch = compositionLocalOf<ChatSearchContext?> { null }

/** True inside the turn the search counter points at. */
val LocalTurnIsCurrentHit = compositionLocalOf { false }

/**
 * Terms to mark: the whole query first, then its whitespace-separated words so a multi-word
 * query still lights up inside Markdown, whose inline text is tokenized around whitespace and
 * punctuation (the whole phrase rarely sits inside one token).
 */
fun searchHighlightTerms(query: String): List<String> {
    val q = query.trim().replace(Regex("\\s+"), " ")
    if (q.isEmpty()) return emptyList()
    val words = q.split(" ").filter { it.length >= 2 }
    return (listOf(q) + words).distinct()
}

/** Non-overlapping ranges for every term, longest terms first so the phrase wins over its words. */
fun searchHighlightRangesFor(text: String, terms: List<String>): List<IntRange> {
    if (text.isEmpty() || terms.isEmpty()) return emptyList()
    val taken = BooleanArray(text.length)
    val out = mutableListOf<IntRange>()
    for (term in terms.sortedByDescending { it.length }) {
        for (r in highlightRanges(text, term)) {
            if ((r.first..r.last).none { taken[it] }) {
                for (i in r) taken[i] = true
                out += r
            }
        }
    }
    return out.sortedBy { it.first }
}

/**
 * Whether a collapsed card (reasoning / tool output) should open by itself: only in the turn the
 * counter points at, only for the source of that hit, and only if this card's body has the query.
 */
fun shouldAutoExpand(ctx: ChatSearchContext?, isCurrentTurn: Boolean, source: SearchSource, body: String): Boolean {
    if (ctx == null || !isCurrentTurn || ctx.currentSource != source) return false
    return body.contains(ctx.query.trim(), ignoreCase = true)
}

/** Mark style for search matches: stronger in the current turn, lighter elsewhere. */
@Composable
fun searchMarkStyle(current: Boolean): SpanStyle {
    val accent = MaterialTheme.colorScheme.primary
    return SpanStyle(background = accent.copy(alpha = if (current) 0.38f else 0.16f))
}

/** [text] with the active search marked, or plain when no search is open. */
@Composable
fun searchHighlighted(text: String): AnnotatedString {
    val ctx = LocalChatSearch.current ?: return AnnotatedString(text)
    val current = LocalTurnIsCurrentHit.current
    val style = searchMarkStyle(current)
    val terms = remember(ctx.query) { searchHighlightTerms(ctx.query) }
    val ranges = remember(text, terms) { searchHighlightRangesFor(text, terms) }
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/**
 * Markdown annotator that marks the active search inside TEXT tokens. Every other node keeps the
 * renderer's default handling (returns false), so links, code, emphasis render as before.
 */
@Composable
fun rememberSearchAnnotator(): MarkdownAnnotator {
    val ctx = LocalChatSearch.current
    val current = LocalTurnIsCurrentHit.current
    val style = searchMarkStyle(current)
    val terms = remember(ctx?.query) { ctx?.let { searchHighlightTerms(it.query) }.orEmpty() }
    return remember(terms, style) {
        if (terms.isEmpty()) {
            markdownAnnotator()
        } else {
            markdownAnnotator { content, child ->
                if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
                val text = content.substring(child.startOffset, child.endOffset)
                val ranges = searchHighlightRangesFor(text, terms)
                if (ranges.isEmpty()) return@markdownAnnotator false
                val base = length
                append(text)
                ranges.forEach { addStyle(style, base + it.first, base + it.last + 1) }
                true
            }
        }
    }
}
