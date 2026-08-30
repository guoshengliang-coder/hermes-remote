package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCardsTest {
    @Test fun commandPayloadParses() {
        val meta = parseToolPayloadMeta(
            """{"output": "ok\ndone", "exit_code": 0, "command": "nginx -t", "duration_ms": 412, "cwd": "/root"}""",
        )
        assertEquals("nginx -t", meta?.command)
        assertEquals(0, meta?.exitCode)
        assertEquals(412L, meta?.durationMs)
        assertEquals("ok\ndone", meta?.outputBody)
    }

    @Test fun failureExitCodeParses() {
        val meta = parseToolPayloadMeta("""{"command": "systemctl restart x", "exit_code": 1, "output": "failed"}""")
        assertEquals(1, meta?.exitCode)
    }

    @Test fun plainTextPayloadIsNotCommandShaped() {
        assertNull(parseToolPayloadMeta("just a normal sentence"))
        assertNull(parseToolPayloadMeta("""{"unrelated": true, "shape": [1, 2]}"""))
    }

    @Test fun durationFormats() {
        assertEquals("412ms", formatToolDuration(412))
        assertEquals("1.2s", formatToolDuration(1234))
    }

    @Test fun explicitDiffLanguageDetected() {
        assertTrue(looksLikeDiff("+a\n-b", "diff"))
        assertTrue(looksLikeDiff("+a\n-b", "patch"))
    }

    @Test fun heuristicDiffDetected() {
        val diff = "  context line;\n- proxy_ssl_verify_depth 2;\n+ proxy_ssl_verify_depth 4;\n  more context;"
        assertTrue(looksLikeDiff(diff, null))
    }

    @Test fun markdownListIsNotADiff() {
        val list = "- first bullet\n- second bullet\n- third bullet"
        assertFalse(looksLikeDiff(list, null))
        val prose = "some text\nmore text\neven more"
        assertFalse(looksLikeDiff(prose, null))
    }

    @Test fun diffLinesClassified() {
        val lines = parseDiffLines("--- a/f\n+++ b/f\n@@ -1 +1 @@\n context\n+added\n-removed")
        assertEquals(DiffLineKind.HUNK, lines[0].kind)
        assertEquals(DiffLineKind.HUNK, lines[1].kind)
        assertEquals(DiffLineKind.HUNK, lines[2].kind)
        assertEquals(DiffLineKind.CONTEXT, lines[3].kind)
        assertEquals(DiffLineKind.ADD, lines[4].kind)
        assertEquals(DiffLineKind.DEL, lines[5].kind)
    }
}
