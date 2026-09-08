package com.hermes.client.ui.chat

/**
 * Make `**强调**` survive CommonMark's delimiter rules when it sits against Chinese punctuation.
 *
 * CommonMark decides whether a `**` run may open or close emphasis from the two characters around
 * it. A closing run must be *right-flanking*: not preceded by whitespace, and — if it IS preceded
 * by punctuation — followed by whitespace or punctuation. English prose almost never trips this,
 * because a bold phrase is followed by a space. Chinese prose trips it constantly, because the
 * sentence-ending mark goes INSIDE the emphasis and the next word follows immediately:
 *
 *     **关键问题：你有没有托管在 Cloudflare 的域名？**有的话可以直接迁移
 *
 * The closing run is preceded by `？` (punctuation) and followed by `有` (a letter), so it cannot
 * close, the opening run has no partner, and the reader sees four literal asterisks. Adding one
 * space after the `**` fixes it, which is why the same text renders correctly in some tools and
 * not others — and why this looked like a transcript bug rather than a typesetting rule (HG-24).
 *
 * The mirrored case exists too: an opening run followed by punctuation (`中文**「引用」**`) is not
 * left-flanking and cannot open.
 *
 * The repair is to give the delimiter a neighbour it is allowed to have. A ZERO WIDTH SPACE is in
 * Unicode category Cf, which CommonMark counts as neither whitespace nor punctuation, so placing
 * one just inside the delimiter satisfies the flanking rule while adding nothing visible and
 * nothing selectable-looking. It is inserted only where the pair would otherwise fail to parse.
 *
 * This runs on display only. Copy, share, export and read-aloud all read the original message
 * text, so no zero-width character ever reaches the clipboard or a file.
 */
private const val ZERO_WIDTH_SPACE = '\u200B'

/**
 * `**…**` on a single line, with no whitespace immediately inside either delimiter and no nested
 * `**`. Matching pairs rather than individual runs keeps a stray `**` (a footnote marker, a glob)
 * from being rewritten.
 */
private val STRONG_PAIR = Regex("""\*\*(?![\s*])((?:[^*\n]|\*(?!\*))+?)(?<![\s*])\*\*""")

/** Fenced code blocks and inline code spans, which must be passed through byte for byte. */
private val CODE_SPAN = Regex("""(?s)```.*?```|`[^`\n]+`""")

/**
 * CommonMark's "Unicode punctuation": ASCII punctuation plus the Unicode P* categories. Chinese
 * marks land in Po (？。，：), Ps (「（) and Pe (」）), which is exactly the set that breaks the
 * flanking rules above.
 */
private val UNICODE_PUNCTUATION_TYPES = setOf(
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
)

private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun Char.isMarkdownPunctuation(): Boolean =
    Character.getType(this) in UNICODE_PUNCTUATION_TYPES || this in ASCII_PUNCTUATION

/** A run is blocked by the character on its outer side only when that character is a word char. */
private fun Char?.blocksFlanking(): Boolean = this != null && Character.isLetterOrDigit(this)

/**
 * [text] with zero-width spaces inserted inside any `**…**` pair that Chinese punctuation would
 * otherwise stop CommonMark from parsing. Text that already parses is returned unchanged.
 */
fun withCjkEmphasisRepaired(text: String): String {
    if (!text.contains("**")) return text
    val protectedRanges = CODE_SPAN.findAll(text).map { it.range }.toList()

    return STRONG_PAIR.replace(text) { match ->
        if (protectedRanges.any { match.range.first in it }) return@replace match.value

        val body = match.groupValues[1]
        // The opening run cannot open when the character it introduces is punctuation and the one
        // before it is a word character; the closing run cannot close in the mirror image.
        val openBlocked = body.first().isMarkdownPunctuation() &&
            text.getOrNull(match.range.first - 1).blocksFlanking()
        val closeBlocked = body.last().isMarkdownPunctuation() &&
            text.getOrNull(match.range.last + 1).blocksFlanking()

        if (!openBlocked && !closeBlocked) {
            match.value
        } else {
            buildString {
                append("**")
                if (openBlocked) append(ZERO_WIDTH_SPACE)
                append(body)
                if (closeBlocked) append(ZERO_WIDTH_SPACE)
                append("**")
            }
        }
    }
}
