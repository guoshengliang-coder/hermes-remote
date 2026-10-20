package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Indices of [messages] whose text contains [query] (case-insensitive); empty for a blank query. */
fun matchIndices(messages: List<ChatMessage>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
}

/** One occurrence of the query: which turn, and which mark within that turn (HG-45). */
data class SearchHit(
    val turnIndex: Int,
    /** 0-based position among this turn's hits — the same order the renderer draws its marks in. */
    val occurrence: Int,
)

/** A result belongs to one immutable transcript snapshot, not merely to its query. */
internal data class PreparedChatSearch(
    val query: String,
    val messages: List<ChatMessage>,
    val hits: List<SearchHit>,
    val markdownRanges: Map<String, List<IntRange>>,
)

private const val MAX_TOTAL_HITS = 200

/**
 * Occurrence-level search over what the reader can actually see: the user's own words and the
 * assistant's body text (HG-45, 2026-09-13). Reasoning and tool output are deliberately out of
 * scope — they used to be counted, which inflated `n/N` with hits inside collapsed cards and made
 * the counter disagree with the marks on screen.
 *
 * Matching goes through the SAME [searchHighlightTerms] / [searchHighlightRangesFor] pair the
 * renderer marks with, so "hit k" and "the k-th mark in this turn" are the same thing. They were
 * not before: this searched for the whole query while the renderer also marked each word of it,
 * so a two-word query produced more marks than hits.
 *
 * Timeline notes (`displayKind != null`) are skipped: `MessageBubble` renders them as plain
 * `TimelineNoteRow`s that never carry a mark, so counting them would count the invisible.
 */
fun searchHits(
    messages: List<ChatMessage>,
    query: String,
    linkWord: String = "链接",
): List<SearchHit> = prepareChatSearch(messages, query, linkWord).hits

internal fun prepareChatSearch(messages: List<ChatMessage>, query: String, linkWord: String): PreparedChatSearch {
    val ranges = mutableMapOf<String, List<IntRange>>()
    return PreparedChatSearch(query, messages, collectSearchHits(messages, query, linkWord, ranges), ranges)
}

private fun collectSearchHits(
    messages: List<ChatMessage>, query: String, linkWord: String,
    markdownRanges: MutableMap<String, List<IntRange>>,
): List<SearchHit> {
    val terms = searchHighlightTerms(query)
    if (terms.isEmpty()) return emptyList()
    val hits = mutableListOf<SearchHit>()
    for (index in messages.indices) {
        val message = messages[index]
        if (message.displayKind != null || message.text.isBlank()) continue
        val count = when {
            message.role == Role.USER -> searchHighlightRangesFor(renderUserUrlTokens(message.text, linkWord).text, terms).size
            message.isError -> searchHighlightRangesFor(message.text, terms).size
            else -> markdownRenderBlocks(message.text).sumOf {
                val content = withCjkEmphasisRepaired(it)
                val ranges = markdownRanges.getOrPut(content) { markdownSearchRangesFor(content, terms) }
                ranges.size
            }
        }
        for (occurrence in 0 until count) {
            if (hits.size >= MAX_TOTAL_HITS) return hits
            hits += SearchHit(index, occurrence)
        }
    }
    return hits
}

/** The same TEXT tokens the Markdown annotator can mark, in source order (HG-221).
 * Hidden destinations, image metadata and code fences are never counted as visible marks.
 */
internal fun markdownSearchRangesFor(content: String, terms: List<String>): List<IntRange> {
    if (terms.isEmpty() || content.isEmpty()) return emptyList()
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val ranges = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.IMAGE, MarkdownElementTypes.LINK_DESTINATION,
            MarkdownElementTypes.LINK_TITLE, MarkdownElementTypes.LINK_DEFINITION,
            MarkdownElementTypes.AUTOLINK -> return
            MarkdownTokenTypes.TEXT -> {
                searchHighlightRangesFor(content.substring(node.startOffset, node.endOffset), terms)
                    .forEach { ranges += (it.first + node.startOffset)..(it.last + node.startOffset) }
            }
            else -> node.children.forEach(::visit)
        }
    }
    visit(root)
    return ranges
}
