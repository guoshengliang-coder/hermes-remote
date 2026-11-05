package com.hermes.client.domain

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * `::preview{file="…"}` — upstream Hermes' inline widget directive (docs/HERMES_CONTRACT.md §4,
 * docs/DESIGN.md §5.23).
 *
 * Upstream teaches it only on the `desktop` platform (`agent/prompt_builder.py`
 * `PLATFORM_HINTS["desktop"]`): the agent writes an HTML file, then puts the directive on its own
 * line, and the desktop app renders that file live inside the message. A conversation started on
 * the Mac and read on the phone therefore carries it, and before this parser the phone showed the
 * raw directive where the chart should have been.
 *
 * The grammar mirrors `apps/desktop/src/lib/transcript-directives.ts`: a directive starts a word
 * (`std::vector` is never one), the name is lowercase, the brace body is capped, and a name whose
 * brace never closes stays the text it is. Only `preview` is claimed — upstream renders nothing for
 * an unregistered name either, so every other `::name{…}` keeps reading as prose.
 *
 * Where upstream only looks inside text-only paragraphs, this also accepts a directive that sits
 * between other inline markup in the paragraph, as long as it does not cut through any of it, and
 * one inside a list item or quote, which is drawn after that block (lifting a card out of the
 * middle of a list would break the list). Inside code it is always text: the assistant is showing
 * the syntax, not using it.
 */
internal data class PreviewDirective(
    /** The `file` attribute as written (untrusted model output). */
    val file: String,
    /** The `height` attribute: a starting height only, clamped like upstream; null when absent. */
    val initialHeight: Int? = null,
)

internal sealed interface PreviewSegment {
    data class Prose(val text: String) : PreviewSegment
    data class Preview(val directive: PreviewDirective) : PreviewSegment
}

/** What a directive's `file` names once it is resolved against the conversation's folder. */
internal sealed interface PreviewTarget {
    /** An absolute path on the Mac. [isHtml] decides between the live frame and a file card. */
    data class File(val path: String, val isHtml: Boolean) : PreviewTarget {
        val name: String get() = path.substringAfterLast('/').ifBlank { path }
    }

    /** A web address: never loaded inside the app (previews have no network), offered as a link. */
    data class Web(val url: String) : PreviewTarget
}

internal const val PREVIEW_MIN_HEIGHT = 120
internal const val PREVIEW_MAX_HEIGHT = 1200
internal const val PREVIEW_DEFAULT_HEIGHT = 280

private const val DIRECTIVE_BODY_MAX = 2048

// JavaScript's `\s` (what upstream's `(?<=^|\s)` means) includes these Unicode spaces; Java's
// does not, so they are listed rather than switching every class in the pattern to Unicode.
private const val JS_SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
private val DIRECTIVE = Regex("(?<![^$JS_SPACE])::([a-z][a-z0-9-]{0,63})(?:\\{([^{}]{0,$DIRECTIVE_BODY_MAX})\\})?")
private val ATTRIBUTE = Regex("""([a-z][\w-]{0,63})=(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val AUTOLINK_ATTRIBUTE = Regex("^<(https?://[^<>\\s]+)>$", RegexOption.IGNORE_CASE)
private val HTML_FILE = Regex("\\.(?:html?|xhtml)$", RegexOption.IGNORE_CASE)
private val WEB_ADDRESS = Regex("^https?://", RegexOption.IGNORE_CASE)
private val LEADING_BLANK_LINES = Regex("^(?:[ \\t]*\\n)+")
// What is left of a sentence after the card is lifted out of it. Up to three spaces only: four
// would make the next line an indented code block, which is not ours to touch.
private val LEADING_SPACES = Regex("^ {1,3}(?![ \\t])")

private val CODE_TYPES = setOf(
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.HTML_BLOCK,
)

/** Upstream's `directiveFrameHeight`: an integer attribute, clamped to the frame's band. */
internal fun previewFrameHeight(raw: String?): Int? {
    val value = raw?.trim()?.toIntOrNull() ?: return null
    return value.coerceIn(PREVIEW_MIN_HEIGHT, PREVIEW_MAX_HEIGHT)
}

private fun attributes(body: String?): Map<String, String> {
    if (body.isNullOrEmpty()) return emptyMap()
    val out = linkedMapOf<String, String>()
    for (match in ATTRIBUTE.findAll(body)) {
        val value = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
        // Models copy the `<https://…>` autolink form into attributes too.
        out[match.groupValues[1].lowercase()] = AUTOLINK_ATTRIBUTE.find(value)?.groupValues?.get(1) ?: value
    }
    return out
}

private data class Candidate(val range: IntRange, val directive: PreviewDirective)

/** Every syntactically complete `::preview{file=…}` in [text], before any Markdown context. */
private fun candidates(text: String): List<Candidate> {
    if (!text.contains("::preview")) return emptyList()
    val out = mutableListOf<Candidate>()
    for (match in DIRECTIVE.findAll(text)) {
        if (match.groupValues[1] != "preview") continue
        val body = match.groups[2]?.value
        // A brace the body group refused (unclosed, or past the cap): half of a directive is not
        // one, and a card with its attributes silently dropped would be broken invisibly.
        if (body == null && text.getOrNull(match.range.last + 1) == '{') continue
        val attrs = attributes(body)
        // Upstream lifts a bare `::preview` out of the sentence and then draws nothing for it.
        // A card with no file has nothing to show, so the words stay where the author put them.
        val file = attrs["file"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        out += Candidate(match.range, PreviewDirective(file, previewFrameHeight(attrs["height"])))
    }
    return out
}

private class Placement(
    val candidate: Candidate,
    /** Characters removed from the prose. */
    val removed: IntRange,
    /** Where the card goes, in the ORIGINAL text's coordinates. */
    val insertAt: Int,
)

/**
 * Split one rendered block into its prose and the previews it asks for, in source order. Null when
 * the block holds no previewable directive, which is every block of almost every message — they
 * never reach the Markdown parser.
 */
internal fun segmentPreviewDirectives(text: String): List<PreviewSegment>? {
    val found = candidates(text)
    if (found.isEmpty()) return null
    val root = runCatching { MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text) }
        .getOrNull() ?: return null
    val placements = found.mapNotNull { placement(text, root, it) }
    if (placements.isEmpty()) return null

    val removedSorted = placements.map { it.removed }.sortedBy { it.first }
    fun shifted(position: Int): Int = position - removedSorted
        .filter { it.last < position }
        .sumOf { it.last - it.first + 1 }
    val prose = StringBuilder(text)
    removedSorted.asReversed().forEach { prose.delete(it.first, it.last + 1) }
    val remaining = prose.toString()

    val segments = mutableListOf<PreviewSegment>()
    var cursor = 0
    fun addProse(until: Int) {
        if (until <= cursor) return
        val piece = remaining.substring(cursor, until)
            .replace(LEADING_BLANK_LINES, "")
            .replace(LEADING_SPACES, "")
            .trimEnd()
        if (piece.isNotBlank()) segments += PreviewSegment.Prose(piece)
        cursor = until
    }
    for (placement in placements.sortedWith(compareBy<Placement> { it.insertAt }.thenBy { it.candidate.range.first })) {
        addProse(shifted(placement.insertAt).coerceIn(cursor, remaining.length))
        segments += PreviewSegment.Preview(placement.candidate.directive)
    }
    addProse(remaining.length)
    return segments
}

/** Where a candidate may become a card, or null when the Markdown around it says it is text. */
private fun placement(text: String, root: ASTNode, candidate: Candidate): Placement? {
    val start = candidate.range.first
    val end = candidate.range.last + 1
    var paragraph: ASTNode? = null
    var topLevel: ASTNode? = null
    var rejected = false

    fun walk(node: ASTNode, depth: Int) {
        if (rejected) return
        val overlaps = node.startOffset < end && node.endOffset > start
        if (!overlaps) return
        val contains = node.startOffset <= start && node.endOffset >= end
        val inside = node.startOffset >= start && node.endOffset <= end
        when {
            // Showing the syntax, not using it — wherever the code is.
            node.type in CODE_TYPES -> { rejected = true; return }
            node.type == MarkdownElementTypes.PARAGRAPH && contains -> paragraph = node
            // Emphasis, links and the like may sit beside the directive or wholly inside its
            // attributes, but lifting a card out of the middle of one would break it.
            paragraph != null && node.children.isNotEmpty() && !inside -> { rejected = true; return }
        }
        if (depth == 1 && contains) topLevel = node
        node.children.forEach { walk(it, depth + 1) }
    }
    root.children.forEach { walk(it, depth = 1) }
    val block = topLevel
    if (rejected || paragraph == null || block == null) return null

    // A paragraph of its own (possibly sharing it with other sentences): the card goes exactly where
    // the directive was. Anything nested — a list item, a quote — gets the card after that block.
    if (paragraph === block) return Placement(candidate, candidate.range, start)
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val aloneOnLine = text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()
    val removed = if (aloneOnLine) {
        // Drop the whole line, so the list item does not keep a blank line that loosens the list.
        if (lineEnd < text.length) lineStart..lineEnd else (if (lineStart > 0) lineStart - 1 else 0) until lineEnd
    } else candidate.range
    return Placement(candidate, removed, block.endOffset.coerceAtLeast(removed.last + 1))
}

/** Every previewable directive in a whole message, for consumers that do not render it. */
internal fun previewDirectives(text: String): List<PreviewDirective> {
    if (!text.contains("::preview")) return emptyList()
    return segmentPreviewDirectives(text).orEmpty()
        .filterIsInstance<PreviewSegment.Preview>()
        .map { it.directive }
}

/**
 * The message's prose with every recognised directive taken out — what read-aloud and search act
 * on, so neither speaks or matches a file path the reader never sees.
 */
internal fun withoutPreviewDirectives(text: String): String {
    val segments = segmentPreviewDirectives(text) ?: return text
    return segments.filterIsInstance<PreviewSegment.Prose>().joinToString("\n\n") { it.text }
}

/**
 * Resolve a directive's `file` the way upstream's `localPreviewTarget` does: surrounding backticks
 * dropped, `file://` decoded, a relative path joined to the conversation's folder with `.`/`..`
 * collapsed (never above the root). Without a folder a relative path stays relative, and the Mac
 * refuses it like any other path it cannot open.
 */
internal fun resolvePreviewTarget(file: String, cwd: String?): PreviewTarget? {
    val raw = file.trim().removePrefix("`").removeSuffix("`").trim()
    if (raw.isEmpty()) return null
    if (WEB_ADDRESS.containsMatchIn(raw)) return PreviewTarget.Web(raw)
    val path = when {
        raw.startsWith("file://", ignoreCase = true) -> runCatching { java.net.URI(raw).path }.getOrNull()
            ?: raw.substring("file://".length)
        raw.startsWith("/") -> raw
        !cwd.isNullOrBlank() -> joinPath(cwd, raw)
        else -> raw
    }
    if (path.isBlank()) return null
    return PreviewTarget.File(path, HTML_FILE.containsMatchIn(raw.substringBefore('?').substringBefore('#')))
}

private fun joinPath(base: String, relative: String): String {
    val parts = base.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (part in relative.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return "/" + parts.joinToString("/")
}
