package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for HG-24. The expectations below were checked against a CommonMark reference
 * implementation first: the "broken" strings really do render with literal asterisks, and the
 * repaired ones really do produce `<strong>`.
 */
class CjkEmphasisTest {
    private val zwsp = '\u200B'

    @Test fun `a closing run against chinese punctuation is repaired`() {
        val broken = "**关键问题：你有没有已经托管在 Cloudflare 的域名？**有的话可以直接迁移"
        val repaired = withCjkEmphasisRepaired(broken)

        assertEquals("**关键问题：你有没有已经托管在 Cloudflare 的域名？$zwsp**有的话可以直接迁移", repaired)
    }

    @Test fun `an opening run followed by punctuation is repaired`() {
        val broken = "见**「关键问题」**说明"
        val repaired = withCjkEmphasisRepaired(broken)

        assertEquals("见**$zwsp「关键问题」$zwsp**说明", repaired)
    }

    // Everything CommonMark already parses must come back byte for byte — the repair is not a
    // reformatter, and a zero-width character in text that did not need one is litter.
    @Test fun `text that already renders is untouched`() {
        listOf(
            "**bold**text",
            "**关键问题**：后面是中文",
            "**关键问题：域名？** 有的话",
            "英文 **bold phrase** 后面",
            "没有任何强调的一句话",
            "a * b * c",
        ).forEach { assertEquals(it, withCjkEmphasisRepaired(it)) }
    }

    // Asterisks inside code are content, not markup. Rewriting them would change what the reader
    // is being shown a copy of.
    @Test fun `code is passed through unchanged`() {
        val inline = "用 `**kwargs：**args` 传参"
        assertEquals(inline, withCjkEmphasisRepaired(inline))

        val fenced = "```python\nprint(f\"**总计：{n}**行\")\n```"
        assertEquals(fenced, withCjkEmphasisRepaired(fenced))
    }

    // An unmatched delimiter has no pair to repair, so it must be left exactly as it is rather
    // than picking up a stray zero-width space.
    @Test fun `a lone delimiter is not rewritten`() {
        listOf("请看第 **3 条", "总计 ** 三项", "**", "a**b").forEach {
            assertEquals(it, withCjkEmphasisRepaired(it))
        }
    }

    @Test fun `every emphasis in a paragraph is repaired independently`() {
        val broken = "**第一点。**说明；**第二点**：说明；**第三点？**说明"
        val repaired = withCjkEmphasisRepaired(broken)

        assertEquals("**第一点。$zwsp**说明；**第二点**：说明；**第三点？$zwsp**说明", repaired)
        // The middle one already worked and must not have gained a marker.
        assertTrue(repaired.contains("**第二点**："))
    }
}
