package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
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

    @Test fun todosParseFromPayload() {
        val meta = parseToolPayloadMeta(
            """{"todos": [
                {"id": "1", "content": "检查配置", "status": "completed"},
                {"id": "2", "content": "重载服务", "status": "in_progress"},
                {"id": "3", "content": "验证健康", "status": "pending"},
                {"id": "4", "content": "回滚预案", "status": "cancelled"}
            ]}""",
        )
        assertEquals(4, meta?.todos?.size)
        assertEquals("completed", meta?.todos?.get(0)?.status)
        val (done, total) = todoProgress(meta!!.todos)
        assertEquals(1, done)
        assertEquals(3, total) // cancelled excluded from total
    }

    @Test fun consecutiveToolsCollapseIntoTimeline() {
        fun tool(id: String) = com.hermes.client.domain.ToolCall(
            id = id, name = "Bash", status = com.hermes.client.domain.ToolStatus.DONE,
        )
        val todoTool = com.hermes.client.domain.ToolCall(
            id = "todo", name = "TodoWrite", status = com.hermes.client.domain.ToolStatus.DONE,
            todos = listOf(com.hermes.client.domain.TodoItem("x", "pending")),
        )
        // 3 consecutive -> one timeline
        val g1 = groupToolsForDisplay(listOf(tool("a"), tool("b"), tool("c")))
        assertEquals(1, g1.size)
        assertTrue(g1[0] is ToolDisplayGroup.Timeline)
        // 2 consecutive -> singles
        val g2 = groupToolsForDisplay(listOf(tool("a"), tool("b")))
        assertEquals(2, g2.size)
        assertTrue(g2.all { it is ToolDisplayGroup.Single })
        // todo breaks the run: 3 + todo + 1 -> timeline, single(todo), single
        val g3 = groupToolsForDisplay(listOf(tool("a"), tool("b"), tool("c"), todoTool, tool("d")))
        assertEquals(3, g3.size)
        assertTrue(g3[0] is ToolDisplayGroup.Timeline)
        assertTrue(g3[1] is ToolDisplayGroup.Single)
        assertTrue(g3[2] is ToolDisplayGroup.Single)
    }

    @Test fun revealPacingIsBoundedBothWays() {
        // Small backlog: floor keeps the reveal moving.
        assertEquals(108, nextRevealCount(current = 100, target = 110))
        // Medium backlog: proportional catch-up, capped per tick.
        assertEquals(164, nextRevealCount(current = 100, target = 500))
        // Huge backlog (reconnect replay): one fast-forward hop, then pace the recent tail.
        assertEquals(4250, nextRevealCount(current = 100, target = 5000))
        // Never overshoots; target shrink (defensive) clamps down.
        assertEquals(110, nextRevealCount(current = 109, target = 110))
        assertEquals(50, nextRevealCount(current = 100, target = 50))
    }

    @Test fun revealConvergesAfterStreamStops() {
        var revealed = 0
        val target = 900
        var ticks = 0
        while (revealed < target && ticks < 100) {
            revealed = nextRevealCount(revealed, target)
            ticks++
        }
        assertEquals(target, revealed)
        assertTrue("took $ticks ticks", ticks <= 30)
    }

    @Test fun surrogatePairsAreNeverSplit() {
        val text = "ab\uD83D\uDE00cd" // ab😀cd
        assertEquals(2, surrogateSafeCut(text, 3)) // cutting inside the pair backs off
        assertEquals(4, surrogateSafeCut(text, 4))
        assertEquals(0, surrogateSafeCut(text, 0))
        assertEquals(text.length, surrogateSafeCut(text, 99))
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

    // ---- running-status line ------------------------------------------------

    private fun streamingMsg(
        text: String = "",
        thinking: String = "",
        tools: List<ToolCall> = emptyList(),
    ) = ChatMessage(id = "m1", role = Role.ASSISTANT, text = text, thinking = thinking, tools = tools, isStreaming = true)

    @Test fun runningToolWinsOverEverything() {
        val msg = streamingMsg(
            text = "some text already streamed",
            thinking = "planning...",
            tools = listOf(
                ToolCall("t1", "bash", ToolStatus.DONE, command = "ls"),
                ToolCall("t2", "bash", ToolStatus.RUNNING, command = "npm test"),
            ),
        )
        assertEquals(RunningStatus.Tool("npm test"), runningStatusFor(msg))
    }

    @Test fun runningToolFallsBackToNameAndFirstLine() {
        val msg = streamingMsg(tools = listOf(ToolCall("t", "web_search", ToolStatus.RUNNING)))
        assertEquals(RunningStatus.Tool("web_search"), runningStatusFor(msg))
        val multi = streamingMsg(tools = listOf(ToolCall("t", "bash", ToolStatus.RUNNING, command = "line1\nline2")))
        assertEquals(RunningStatus.Tool("line1"), runningStatusFor(multi))
    }

    @Test fun thinkingPreviewShowsTailOfLastLine() {
        val msg = streamingMsg(thinking = "first line\n这是一段相当长的思考内容需要截断只保留最后二十四个字符用于单行预览显示")
        val status = runningStatusFor(msg)
        assertTrue(status is RunningStatus.Thinking)
        val preview = (status as RunningStatus.Thinking).preview
        assertTrue(preview.startsWith("…"))
        assertTrue(preview.endsWith("预览显示"))
        assertTrue(preview.length <= 25)
    }

    @Test fun shortThinkingLineIsNotTruncated() {
        val msg = streamingMsg(thinking = "查资料中")
        assertEquals(RunningStatus.Thinking("查资料中"), runningStatusFor(msg))
    }

    @Test fun textPresentDowngradesThinkingToGenerating() {
        val msg = streamingMsg(text = "answer body", thinking = "old reasoning")
        assertEquals(RunningStatus.Generating, runningStatusFor(msg))
    }

    @Test fun doneToolsWithoutActivityMeanGenerating() {
        val msg = streamingMsg(text = "body", tools = listOf(ToolCall("t", "bash", ToolStatus.DONE, command = "ls")))
        assertEquals(RunningStatus.Generating, runningStatusFor(msg))
    }

    @Test fun elapsedTimeFormats() {
        assertEquals("0秒", formatElapsedTime(-100, zh = true))
        assertEquals("12秒", formatElapsedTime(12_400, zh = true))
        assertEquals("12s", formatElapsedTime(12_400, zh = false))
        assertEquals("1分24秒", formatElapsedTime(84_000, zh = true))
        assertEquals("1m24s", formatElapsedTime(84_000, zh = false))
    }
}
