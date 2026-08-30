package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownStabilizerTest {
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

    @Test fun smallTrailingObjectIsNotMasked() {
        val text = "quick:\n{\"a\": 1"
        assertEquals(text, stabilizeStreamingMarkdown(text, "P"))
    }

    @Test fun maskedJsonInsideOpenFenceStillClosesTheFence() {
        val blob = "{\"data\": \"" + "y".repeat(300)
        val out = stabilizeStreamingMarkdown("look:\n```json\n$blob", "P")
        val fenceCount = Regex("(?m)^\\s*```").findAll(out).count()
        assertEquals(0, fenceCount % 2)
        assertFalse(out.contains("data"))
    }
}
