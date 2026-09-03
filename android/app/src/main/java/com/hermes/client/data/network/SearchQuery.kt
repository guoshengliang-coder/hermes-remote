package com.hermes.client.data.network

/**
 * Shapes the user's raw search text for the gateway's `/api/sessions/search`.
 *
 * The gateway appends a prefix wildcard to every unquoted token ("nimb" → "nimb*"). That is
 * right for Latin words but breaks CJK on the production store: the CJK path (trigram index, or
 * the LIKE fallback for 1–2 char terms) treats `词*` as a literal that never matches (verified
 * against the Mac mini on 2026-09-03: `的历史记录` → 1 hit, `的历史记录*` → 0). Quoting a token
 * makes the gateway keep it as-is, and a quoted phrase is exact substring matching on the CJK
 * paths — which is what a Chinese reader expects. Latin tokens stay unquoted so partial words
 * keep matching.
 */
fun buildSearchQuery(raw: String): String {
    val tokens = TOKEN.findAll(raw.trim()).map { it.value }.toList()
    return tokens.joinToString(" ") { token ->
        when {
            token.startsWith("\"") -> token
            token.endsWith("*") -> token
            token.any { it.isCjk() } -> "\"" + token.replace("\"", "") + "\""
            else -> token
        }
    }
}

/** True for CJK unified ideographs, kana, and Hangul — scripts the gateway's wildcard breaks. */
fun Char.isCjk(): Boolean {
    val c = code
    return c in 0x4E00..0x9FFF || // CJK Unified Ideographs
        c in 0x3400..0x4DBF ||    // Extension A
        c in 0xF900..0xFAFF ||    // Compatibility Ideographs
        c in 0x3040..0x30FF ||    // Hiragana, Katakana
        c in 0xAC00..0xD7AF       // Hangul syllables
}

/** True when [text] contains any CJK character. */
fun containsCjk(text: String): Boolean = text.any { it.isCjk() }

private val TOKEN = Regex("\"[^\"]*\"|\\S+")
