package com.hermes.client.domain

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Where a Markdown construct sits, as far as the attachment rules care.
 *
 * This is the distinction the old rules could not make. They were regexes run over the whole
 * message in sequence, and a regex cannot see that it is standing inside a fenced example — so an
 * assistant explaining Markdown syntax had its own example rewritten underneath it, and the
 * message grew an image card and a downloadable file the user was never offered. Same family as
 * HG-23 and HG-25: the rule was right, the context was invisible.
 */
internal enum class MarkdownSpanContext {
    /** Inside a fence, an indented code block or a code span: content, never markup to act on. */
    CODE,

    /** Sharing a line with text — a mark in a table cell, a logo mid-sentence. Punctuation. */
    INLINE,

    /** Alone on its line. An image here is the content itself (DESIGN.md §5.4 「图片不下移」). */
    STANDALONE,
}

/** A Markdown image or link, with enough context to decide what to do with it. */
internal data class MarkdownAttachmentSpan(
    val range: IntRange,
    val label: String,
    val destination: String,
    val isImage: Boolean,
    val context: MarkdownSpanContext,
)

/**
 * Every Markdown image and inline link in [text], in source order.
 *
 * Returns empty without parsing when the text cannot contain either. That guard is the reason this
 * is affordable: [parseMessageContent] runs on every 64 ms streaming snapshot over a growing
 * message, and the overwhelming majority of messages contain no link at all, so they never reach
 * the parser. A message that does have one pays a single parse instead of four regex passes.
 */
internal fun markdownAttachmentSpans(text: String): List<MarkdownAttachmentSpan> {
    if (!text.contains("](")) return emptyList()
    val tree = runCatching {
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    }.getOrNull() ?: return emptyList()

    val spans = mutableListOf<MarkdownAttachmentSpan>()
    fun walk(node: ASTNode, insideCode: Boolean) {
        val nowInsideCode = insideCode || node.type in CODE_TYPES
        when {
            node.type == MarkdownElementTypes.IMAGE ->
                span(text, node, isImage = true, insideCode = nowInsideCode)?.let { spans += it }
            // Reached only for a link that is not inside an image: the image branch above does not
            // descend, so the link an image owns is never counted twice — which would otherwise
            // rewrite the destination out from under the image rule.
            node.type == MarkdownElementTypes.INLINE_LINK ->
                span(text, node, isImage = false, insideCode = nowInsideCode)?.let { spans += it }
            else -> node.children.forEach { walk(it, nowInsideCode) }
        }
    }
    tree.children.forEach { walk(it, insideCode = false) }
    return spans.sortedBy { it.range.first }
}

private val CODE_TYPES = setOf(
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_SPAN,
)

private fun span(
    text: String,
    node: ASTNode,
    isImage: Boolean,
    insideCode: Boolean,
): MarkdownAttachmentSpan? {
    val link = if (isImage) {
        node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK } ?: return null
    } else {
        node
    }
    val destination = link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
        ?.let { text.substring(it.startOffset, it.endOffset) }
        ?.trim()
        ?.removeSurrounding("<", ">")
        ?: return null
    val label = link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        ?.let { text.substring(it.startOffset, it.endOffset) }
        ?.removeSurrounding("[", "]")
        .orEmpty()
    val range = node.startOffset until node.endOffset
    return MarkdownAttachmentSpan(
        range = range,
        label = label,
        destination = destination,
        isImage = isImage,
        context = when {
            insideCode -> MarkdownSpanContext.CODE
            isImage && isAloneOnItsLine(text, range) -> MarkdownSpanContext.STANDALONE
            else -> MarkdownSpanContext.INLINE
        },
    )
}

/**
 * True when [range] covers everything on its line except whitespace and other images.
 *
 * A line may carry several images and still be "the image is the content" — a row of generated
 * pictures is one thought, not three sentences.
 */
private fun isAloneOnItsLine(text: String, range: IntRange): Boolean {
    val lineStart = text.lastIndexOf('\n', range.first).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', range.last).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    return IMAGE_MARKUP.replace(line, "").isBlank()
}

private val IMAGE_MARKUP = Regex("""!\[[^]\r\n]*]\([^)\r\n]*\)""")

/**
 * Applies [replace] to each span, right to left so earlier offsets stay valid. Returning null
 * leaves a span exactly as the author wrote it.
 */
internal fun String.rewriteSpans(
    spans: List<MarkdownAttachmentSpan>,
    replace: (MarkdownAttachmentSpan) -> String?,
): String {
    if (spans.isEmpty()) return this
    val out = StringBuilder(this)
    spans.sortedByDescending { it.range.first }.forEach { span ->
        replace(span)?.let { out.replace(span.range.first, span.range.last + 1, it) }
    }
    return out.toString()
}
