package com.hermes.client.ui.chat

// Compiled once at file load rather than per call, so repeated TTS reads don't re-allocate patterns.
private val FENCED_CODE_REGEX = Regex("```[\\s\\S]*?```")
private val LINK_REGEX = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val INLINE_CODE_REGEX = Regex("`([^`]*)`")
private val HEADING_REGEX = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
private val EMPHASIS_LEADING_REGEX = Regex("(?<![A-Za-z0-9])[*_](?=\\S)")
private val EMPHASIS_TRAILING_REGEX = Regex("(?<=\\S)[*_](?![A-Za-z0-9])")
private val SPACES_REGEX = Regex("[ \\t]+")
private val BLANK_LINES_REGEX = Regex("\\n{2,}")

/**
 * Strip common markdown so TextToSpeech reads content, not syntax. Best-effort and intentionally
 * simple — fenced code is dropped, inline code/emphasis/heading markers removed, links reduced to
 * their text. Not a full markdown parser.
 */
fun speechText(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw
    // Fenced code blocks: drop entirely (```lang ... ```).
    s = FENCED_CODE_REGEX.replace(s, " ")
    // Links [text](url) -> text.
    s = LINK_REGEX.replace(s) { it.groupValues[1] }
    // Inline code `code` -> code.
    s = INLINE_CODE_REGEX.replace(s) { it.groupValues[1] }
    // Heading markers at line start.
    s = HEADING_REGEX.replace(s, "")
    // Emphasis markers ** * __ _ (leave apostrophes/words intact).
    s = stripEmphasisMarkers(s)
    // Collapse whitespace runs and trim.
    s = SPACES_REGEX.replace(s, " ")
    s = BLANK_LINES_REGEX.replace(s, "\n").trim()
    return s
}

/**
 * Strip `**`, `__` and the single `*`/`_` that bound a word, leaving `snake_case` and apostrophes
 * alone. Shared with [readableText] so the two text transforms can't drift apart on emphasis.
 */
internal fun stripEmphasisMarkers(raw: String): String {
    var s = raw.replace("**", "").replace("__", "")
    s = EMPHASIS_LEADING_REGEX.replace(s, "")
    return EMPHASIS_TRAILING_REGEX.replace(s, "")
}
