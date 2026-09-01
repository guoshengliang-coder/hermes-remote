package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownStabilizerTest {
    @Test fun completedParagraphsBecomeIndependentStableRenderBlocks() {
        assertEquals(
            listOf("第一段", "## 标题\n第二段", "最后一段"),
            markdownRenderBlocks("第一段\n\n## 标题\n第二段\n\n最后一段"),
        )
    }

    @Test fun blankLinesInsideFenceNeverSplitTheCodeBlock() {
        val code = "```kotlin\nfun main() {\n\n  println(1)\n}\n```"
        assertEquals(listOf(code, "结尾"), markdownRenderBlocks("$code\n\n结尾"))
    }

    @Test fun addingStreamingTailDoesNotMutateCommittedBlockPrefix() {
        val before = markdownRenderBlocks("稳定段落\n\n正在生成")
        val after = markdownRenderBlocks("稳定段落\n\n正在生成更多文字")
        assertEquals(before.dropLast(1), after.dropLast(1))
        assertEquals("稳定段落", after.first())
    }

    @Test fun looseListsAndReferenceLinksKeepTheirDocumentScope() {
        val looseList = "1. 第一项\n\n2. 第二项\n\n结尾"
        assertEquals(listOf("1. 第一项\n\n2. 第二项", "结尾"), markdownRenderBlocks(looseList))

        val references = "查看 [说明][doc]。\n\n[doc]: https://example.test"
        assertEquals(listOf(references), markdownRenderBlocks(references))
    }

    @Test fun plainProsePassesThrough() {
        val text = "hello **world**\n\n- item"
        assertEquals(text, stabilizeStreamingMarkdown(text, "P"))
    }

    @Test fun openFenceIsClosedPerSnapshot() {
        val out = stabilizeStreamingMarkdown("intro\n```kotlin\nval x = 1", "P")
        assertEquals("intro\n```kotlin\nval x = 1\n```", out)
    }

    @Test fun balancedFencesAreUntouched() {
        val text = "a\n```\ncode\n```\nafter"
        assertEquals(text, stabilizeStreamingMarkdown(text, "P"))
    }

    @Test fun unclosedUntrustedWrapperIsMasked() {
        val raw = "answer so far\n<untrusted_tool_result source=\"web\">{\"partial\": \"da"
        val out = stabilizeStreamingMarkdown(raw, "接收中")
        assertTrue(out.startsWith("answer so far"))
        assertFalse(out.contains("untrusted", ignoreCase = true))
        assertTrue(out.contains("接收中"))
    }

    @Test fun closedUntrustedWrapperIsLeftForCompletionPass() {
        val raw = "before\n<untrusted_tool_result source=\"web\">{\"ok\":1}</untrusted_tool_result>\nafter"
        assertEquals(raw, stabilizeStreamingMarkdown(raw, "P"))
    }

    @Test fun largeTrailingUnbalancedJsonIsMasked() {
        val blob = "{\"output\": \"" + "x".repeat(300)
        val out = stabilizeStreamingMarkdown("done:\n$blob", "接收中")
        assertFalse(out.contains("output"))
        assertTrue(out.contains("接收中"))
        assertTrue(out.startsWith("done:"))
    }

    @Test fun balancedJsonIsKept() {
        val text = "result:\n{\"ok\": true, \"n\": 1}\ntail prose"
        assertEquals(text, stabilizeStreamingMarkdown(text, "P"))
    }

    @Test fun tinyTrailingObjectIsMaskedImmediately() {
        // Showing the raw payload prefix and yanking it back once a size threshold trips is
        // itself a visible jump, so masking starts with the blob's very first characters.
        val out = stabilizeStreamingMarkdown("quick:\n{\"a\": 1", "接收中")
        assertFalse(out.contains("{\"a\""))
        assertTrue(out.contains("接收中"))
        assertTrue(out.startsWith("quick:"))
    }

    @Test fun bracesInsideJsonStringsDoNotUnmask() {
        // Payload whose STRING VALUES are full of braces (code output). A naive brace count sees
        // depth hit zero inside the string and unmasks; string-aware scanning must keep the blob
        // masked until the real closing brace.
        val blob = "{\"output\": \"if (x) { return y; } else { z(); } map { it } close}}\", \"code\": \"fun a() { b() }"
        val out = stabilizeStreamingMarkdown("前文分析：\n$blob", "接收中")
        assertFalse(out.contains("output"))
        assertTrue(out.contains("接收中"))
    }

    @Test fun maskVerdictIsMonotoneAcrossStreamingPrefixes() {
        // The regression seen on a screen recording: the mask flipping on/off between 64ms
        // snapshots made the answer's rendered height oscillate at ~7Hz. Feed every prefix of a
        // hostile payload (braces and escaped quotes inside strings) and require at most ONE
        // masked->unmasked transition across the whole stream.
        val prose = "结论如下：\n\n"
        val blob = "{\"result\": \"for { a } while { b } \\\" quoted \\\" end}\", \"n\": 1, \"tail\": \"x { y }\"}"
        val full = prose + blob + "\n\n后续说明文字。"
        var transitions = 0
        var wasMasked = false
        for (end in prose.length + 2..full.length) {
            val masked = stabilizeStreamingMarkdown(full.substring(0, end), "P").contains("*P*")
            if (end > prose.length + 2 && masked != wasMasked) transitions++
            wasMasked = masked
        }
        assertTrue("mask flipped $transitions times across prefixes", transitions <= 1)
        // And the final complete text must be unmasked (blob closed, prose follows).
        assertEquals(false, stabilizeStreamingMarkdown(full, "P").contains("*P*"))
    }

    @Test fun maskedJsonInsideOpenFenceStillClosesTheFence() {
        val blob = "{\"data\": \"" + "y".repeat(300)
        val out = stabilizeStreamingMarkdown("look:\n```json\n$blob", "P")
        val fenceCount = Regex("(?m)^\\s*```").findAll(out).count()
        assertEquals(0, fenceCount % 2)
        assertFalse(out.contains("data"))
    }
}
