package com.hermes.client.ui.chat

// Compiled once at file load rather than per call, so re-opening the selection view on a long
// answer doesn't re-allocate patterns.
private val FENCE_REGEX = Regex("^\\s{0,3}(?:`{3,}|~{3,})")
private val HEADING_REGEX = Regex("^\\s{0,3}#{1,6}\\s+")
private val QUOTE_REGEX = Regex("^\\s{0,3}(?:>\\s?)+")
private val TASK_REGEX = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+")
private val BULLET_REGEX = Regex("^(\\s*)[-*+]\\s+")
private val RULE_REGEX = Regex("^\\s{0,3}(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val IMAGE_REGEX = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
private val LINK_REGEX = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val INLINE_CODE_REGEX = Regex("`([^`]*)`")

/**
 * Turn a markdown message into the prose a reader actually sees, for the text-selection view:
 * markers go away, structure stays. Unlike [speechText] — which serves TextToSpeech and therefore
 * throws fenced code away and collapses whitespace — this keeps code blocks verbatim, keeps blank
 * lines, and keeps list indentation, because the point is pasting an excerpt somewhere else.
 *
 * Line-by-line with a fence state machine rather than whole-string regexes: a `#` or `*` inside a
 * code block is code, not syntax, and must survive untouched. Best-effort, not a full parser —
 * 4-space indented code blocks are not protected, and reference-style link definitions are left
 * as they are.
 */
fun readableText(raw: String): String {
    if (raw.isBlank()) return ""
    val out = StringBuilder()
    var inFence = false
    for (line in raw.lineSequence()) {
        if (FENCE_REGEX.containsMatchIn(line)) {
            // The fence and its language tag are syntax; the code between them is content.
            inFence = !inFence
            continue
        }
        if (inFence) {
            out.appendLine(line)
            continue
        }
        // Horizontal rules and a table's alignment row draw nothing readable in plain text.
        if (RULE_REGEX.matches(line) || isTableDivider(line)) continue
        out.appendLine(inlineText(blockMarkers(line)).trimEnd())
    }
    return out.toString().lines()
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")
}

/**
 * A table's alignment row (`| --- | :-: |`). Checked as a predicate rather than one dense regex:
 * dialects differ on `---` vs `:-:` vs `-`, and the pipe is what separates it from a `-` bullet.
 */
private fun isTableDivider(line: String): Boolean =
    line.contains('|') &&
        line.contains('-') &&
        line.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }

/** Leading block markers: quote, heading, task box, bullet. Indentation is the nesting, so it stays. */
private fun blockMarkers(line: String): String {
    var s = QUOTE_REGEX.replace(line, "")
    s = HEADING_REGEX.replace(s, "")
    TASK_REGEX.find(s)?.let { m ->
        val box = if (m.groupValues[2].equals("x", ignoreCase = true)) "☑ " else "☐ "
        return m.groupValues[1] + box + s.substring(m.range.last + 1)
    }
    BULLET_REGEX.find(s)?.let { m ->
        // Ordered lists keep their own numbers; unordered ones get one shared bullet glyph.
        return m.groupValues[1] + "• " + s.substring(m.range.last + 1)
    }
    return s
}

/** Inline spans. Images first: `![a](b)` matched as a link would leave a stray `!`. */
private fun inlineText(line: String): String {
    var s = IMAGE_REGEX.replace(line) { it.groupValues[1] }
    s = LINK_REGEX.replace(s) { it.groupValues[1] }
    s = INLINE_CODE_REGEX.replace(s) { it.groupValues[1] }
    s = stripEmphasisMarkers(s).replace("~~", "")
    return s
}
