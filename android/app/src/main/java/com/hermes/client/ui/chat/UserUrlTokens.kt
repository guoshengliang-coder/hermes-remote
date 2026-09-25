package com.hermes.client.ui.chat

import java.net.URI

internal data class UserUrlLink(val start: Int, val endExclusive: Int, val url: String)
internal data class RenderedUserUrlTokens(val text: String, val links: List<UserUrlLink>)

private val USER_URL_TOKEN = Regex("""@url:`(https?://[^`\s]+)`""")
private val FENCE_MARKER = Regex("""^ {0,3}(`{3,}|~{3,})""")

/** Display-only conversion of Hermes' pasted-link token. The stored turn is never rewritten. */
internal fun renderUserUrlTokens(source: String, linkWord: String = "链接"): RenderedUserUrlTokens {
    if (!source.contains("@url:`")) return RenderedUserUrlTokens(source, emptyList())
    val out = StringBuilder(source.length)
    val links = mutableListOf<UserUrlLink>()
    var fence: Char? = null
    var fenceLength = 0
    source.splitToSequence('\n').forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) out.append('\n')
        val marker = FENCE_MARKER.find(line)?.groupValues?.get(1)
        if (marker != null) {
            if (fence == null) {
                fence = marker.first()
                fenceLength = marker.length
            } else if (marker.first() == fence && marker.length >= fenceLength) {
                fence = null
                fenceLength = 0
            }
            out.append(line)
        } else if (fence != null || line.startsWith("    ") || line.startsWith('\t')) {
            out.append(line)
        } else {
            var cursor = 0
            USER_URL_TOKEN.findAll(line).forEach { match ->
                out.append(line, cursor, match.range.first)
                val url = match.groupValues[1]
                val host = validUserUrlHost(url)
                if (host == null) {
                    out.append(match.value)
                } else {
                    val label = "$linkWord · $host"
                    val start = out.length
                    out.append(label)
                    links += UserUrlLink(start, out.length, url)
                }
                cursor = match.range.last + 1
            }
            out.append(line, cursor, line.length)
        }
    }
    return RenderedUserUrlTokens(out.toString(), links)
}

private fun validUserUrlHost(value: String): String? {
    if (value.length > 2048 || value.any { it.isISOControl() }) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.userInfo != null || uri.host.isNullOrBlank()) return null
    if (uri.port !in -1..65535) return null
    return uri.host
}
