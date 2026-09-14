package com.hermes.client.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import com.hermes.client.ui.sessions.highlightRanges
import com.hermes.client.ui.theme.ChatSearchHitOther
import com.hermes.client.ui.theme.ChatSearchHitOtherInk
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownTokenTypes

/**
 * In-chat search, as seen by the rendered turns (docs/DESIGN.md §5.4 聊天内搜索): the query to
 * mark, which turn the counter points at, and **which mark inside that turn** it points at.
 *
 * [currentOccurrence] is what makes `42/77` mean something on screen (HG-45): before it, every
 * mark in the current turn looked identical, so pressing ↓ between two hits in the same turn
 * changed the number and nothing else.
 */
data class ChatSearchContext(
    val query: String,
    val currentMessageId: String?,
    val currentOccurrence: Int?,
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
 * Mark style for search matches: the one the counter points at, and all the others.
 *
 * The "other" tier is the pale-blue sticker from Stitch 基线-聊天页/搜索, the same value in light
 * and dark — see the tokens for why. The FOCUS tier is not the mock's: the mock's current tier
 * (#A6C8FF) sits half a step from #D6E4FF and, per HG-45, does not read as "this is the one" when
 * both are on screen at once. It is a solid brand fill instead, through the scheme roles rather
 * than literals (§2.1) — 7.51:1 in light, and dark's own onPrimary in dark.
 *
 * A mark carries its own ink and weight rather than a tinted background alone: the pre-2026-09-12
 * `primary@38%` left the marked word at 1.6:1 under dark `onSurface`, the least readable text on
 * the screen, which is the opposite of what a mark is for.
 */
@Composable
fun searchMarkStyle(focus: Boolean): SpanStyle = SpanStyle(
    color = if (focus) MaterialTheme.colorScheme.onPrimary else ChatSearchHitOtherInk,
    background = if (focus) MaterialTheme.colorScheme.primary else ChatSearchHitOther,
    fontWeight = if (focus) FontWeight.SemiBold else FontWeight.Medium,
)

/**
 * [text] with the active search marked, or plain when no search is open.
 *
 * [rangeOffset] is how many marks precede this string inside the same turn, so the focused mark
 * can be identified across a turn that renders in several pieces. Zero for a turn rendered whole.
 */
@Composable
fun searchHighlighted(text: String, rangeOffset: Int = 0): AnnotatedString {
    val ctx = LocalChatSearch.current ?: return AnnotatedString(text)
    val focusIndex = if (LocalTurnIsCurrentHit.current) ctx.currentOccurrence else null
    val focusStyle = searchMarkStyle(focus = true)
    val otherStyle = searchMarkStyle(focus = false)
    val terms = remember(ctx.query) { searchHighlightTerms(ctx.query) }
    val ranges = remember(text, terms) { searchHighlightRangesFor(text, terms) }
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { index, r ->
            val style = if (rangeOffset + index == focusIndex) focusStyle else otherStyle
            addStyle(style, r.first, r.last + 1)
        }
    }
}

/**
 * Markdown annotator that marks the active search inside TEXT tokens. Every other node keeps the
 * renderer's default handling (returns false), so links, code, emphasis render as before.
 *
 * Finding "which mark is this" without any mutable state: the annotator is handed the whole block
 * plus the token's offset in it, so the marks before this token are just the ranges of the block
 * that start earlier. [rangeOffset] adds the blocks already rendered above. Position in the string
 * is deliberately NOT used to identify the focused mark — `markdownRenderBlocks` trims each block
 * and `withCjkEmphasisRepaired` can insert zero-width spaces, so offsets into `msg.text` do not
 * survive to here. Ordinals do.
 */
@Composable
fun rememberSearchAnnotator(rangeOffset: Int = 0): MarkdownAnnotator {
    val ctx = LocalChatSearch.current
    val focusIndex = if (LocalTurnIsCurrentHit.current) ctx?.currentOccurrence else null
    val focusStyle = searchMarkStyle(focus = true)
    val otherStyle = searchMarkStyle(focus = false)
    val terms = remember(ctx?.query) { ctx?.let { searchHighlightTerms(it.query) }.orEmpty() }
    return remember(terms, focusStyle, otherStyle, focusIndex, rangeOffset) {
        if (terms.isEmpty()) {
            markdownAnnotator()
        } else {
            markdownAnnotator { content, child ->
                if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
                val text = content.substring(child.startOffset, child.endOffset)
                val ranges = searchHighlightRangesFor(text, terms)
                if (ranges.isEmpty()) return@markdownAnnotator false
                val before = searchHighlightRangesFor(content, terms).count { it.first < child.startOffset }
                val base = length
                append(text)
                ranges.forEachIndexed { index, r ->
                    val style = if (rangeOffset + before + index == focusIndex) focusStyle else otherStyle
                    addStyle(style, base + r.first, base + r.last + 1)
                }
                true
            }
        }
    }
}
