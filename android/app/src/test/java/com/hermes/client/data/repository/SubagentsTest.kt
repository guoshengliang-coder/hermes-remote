package com.hermes.client.data.repository

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentsTest {
    private fun event(type: String, id: String = "child-1", vararg fields: Pair<String, String>) =
        ServerEvent(type, "parent-session", buildJsonObject {
            put("subagent_id", id)
            fields.forEach { (key, value) -> put(key, value) }
        })

    @Test fun liveProgressAndTerminalResultStayVisibleUntilNextTurn() {
        var children = foldSubagentEvent(
            emptyList(), event("subagent.start", "child-1", "goal" to "审计代码"), 100,
        )
        children = foldSubagentEvent(children, event("subagent.tool", "child-1", "tool_name" to "read_file"), 110)
        children = foldSubagentEvent(children, event("subagent.progress", "child-1", "text" to "检查 3 个文件"), 120)
        assertEquals("审计代码", children.single().goal)
        assertEquals("read_file", children.single().currentTool)
        assertEquals("检查 3 个文件", children.single().progress)
        assertTrue(children.single().active)

        children = foldSubagentEvent(
            children, event("subagent.complete", "child-1", "status" to "completed", "summary" to "没有发现问题"), 130,
        )
        assertFalse(children.single().active)
        assertEquals("没有发现问题", children.single().result)
        assertEquals(1, children.size)
    }

    @Test fun snapshotHydratesRunningChildAndKeepsNewerEvent() {
        val roster = parseSubagentSnapshot(buildJsonObject {
            put("subagents", buildJsonArray {
                add(buildJsonObject {
                    put("subagent_id", "child-1")
                    put("goal", "审计代码")
                    put("status", "running")
                    put("last_tool", "read_file")
                    put("tool_count", 3)
                    put("started_at", 1720000000.5)
                })
            })
        }, now = 200)
        assertEquals(1720000000500L, roster.single().startedAt)
        assertEquals("read_file", roster.single().lastTool)

        val live = foldSubagentEvent(roster, event("subagent.tool", "child-1", "tool_name" to "run_tests"), 250)
        val reconciled = reconcileSubagents(live, roster, requestedAt = 220)
        assertEquals("run_tests", reconciled.single().currentTool)
        assertEquals(1, reconcileSubagents(reconciled, emptyList(), requestedAt = 220).size)
        assertTrue(reconcileSubagents(reconciled, emptyList(), requestedAt = 260).isEmpty())
    }

    @Test fun completionCannotBeResurrectedByLateProgressOrRoster() {
        val started = foldSubagentEvent(emptyList(), event("subagent.start", "child-1", "goal" to "审计代码"), 100)
        val completed = foldSubagentEvent(started, event("subagent.complete", "child-1", "status" to "completed", "summary" to "审计完成"), 200)
        val late = foldSubagentEvent(completed, event("subagent.progress", "child-1", "text" to "旧进度"), 210)
        assertEquals(SubagentPhase.COMPLETED, late.single().status)
        assertEquals("审计完成", late.single().result)
        assertEquals(late, reconcileSubagents(late, listOf(started.single()), requestedAt = 220))
        assertTrue(late.withoutCompletedSubagents().isEmpty())
    }

    @Test fun unknownCompletionStatusFailsClosed() {
        val ended = foldSubagentEvent(emptyList(), event("subagent.complete", "child-1", "status" to "running"), 100)
        assertFalse(ended.single().active)
        assertEquals(SubagentPhase.FAILED, ended.single().status)
    }

    @Test fun progressStartsQueuedChildAndFailureUsesRegisteredSafeCode() {
        val queued = foldSubagentEvent(
            emptyList(), event("subagent.start", "child-1", "status" to "queued"), 100,
        )
        val running = foldSubagentEvent(queued, event("subagent.progress", "child-1", "text" to "开始"), 110)
        assertEquals(SubagentPhase.RUNNING, running.single().status)
        val failed = foldSubagentEvent(running, event(
            "subagent.complete", "child-1", "status" to "timeout", "summary" to "token=secret",
        ), 120).single()
        assertEquals(AppErrorCode.SUBAGENT_UNFINISHED, failed.failure?.code)
        assertTrue(failed.failure?.retryable == true)
        assertFalse(failed.failure!!.sanitizedDiagnostic().contains("secret"))
    }

    @Test fun realIdReplacesProvisionalChildAndReasoningChunksDoNotChurnStatus() {
        val provisional = foldSubagentEvent(
            emptyList(), event("subagent.start", "", "goal" to "检查代码", "task_index" to "2"), 100,
        )
        val identified = foldSubagentEvent(
            provisional, event("subagent.tool", "child-2", "goal" to "检查代码", "task_index" to "2",
                "tool_name" to "read_file"), 110,
        )
        assertEquals(listOf("child-2"), identified.map { it.id })
        assertEquals(identified, foldSubagentEvent(
            identified, event("subagent.thinking", "child-2", "text" to "大量推理 token"), 120,
        ))
    }
}
