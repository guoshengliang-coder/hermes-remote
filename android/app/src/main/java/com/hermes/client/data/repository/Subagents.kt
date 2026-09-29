package com.hermes.client.data.repository

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A live child belongs to its parent conversation, not to the background-process roster. */
data class SubagentStatus(
    val id: String,
    val goal: String = "",
    val status: SubagentPhase = SubagentPhase.RUNNING,
    val currentTool: String = "",
    val lastTool: String = "",
    val progress: String = "",
    val result: String = "",
    val toolCount: Int = 0,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val active: Boolean get() = status == SubagentPhase.QUEUED || status == SubagentPhase.RUNNING
    val failure: AppError? get() = if (status == SubagentPhase.FAILED || status == SubagentPhase.INTERRUPTED) {
        AppError(code = AppErrorCode.SUBAGENT_UNFINISHED, retryable = true)
    } else null
}

enum class SubagentPhase { QUEUED, RUNNING, COMPLETED, FAILED, INTERRUPTED }

val SUBAGENT_STATUS_EVENTS = setOf("subagent.start", "subagent.progress", "subagent.tool", "subagent.complete")

private fun JsonObject.text(key: String): String =
    (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

private fun phase(raw: String, terminalEvent: Boolean = false): SubagentPhase = when (raw) {
    "queued" -> if (terminalEvent) SubagentPhase.FAILED else SubagentPhase.QUEUED
    "completed" -> SubagentPhase.COMPLETED
    "failed", "error", "timeout" -> SubagentPhase.FAILED
    "interrupted", "cancelled", "canceled" -> SubagentPhase.INTERRUPTED
    else -> if (terminalEvent) SubagentPhase.FAILED else SubagentPhase.RUNNING
}

/** Hermes sends at most the current roster; completed rows are kept locally until the next turn. */
internal fun parseSubagentSnapshot(result: JsonObject, now: Long): List<SubagentStatus> {
    val rows = result["subagents"] as? JsonArray
        ?: throw IllegalArgumentException("subagent.list has no subagents array")
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = row.text("subagent_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SubagentStatus(
            id = id,
            goal = row.text("goal"),
            status = phase(row.text("status")),
            lastTool = row.text("last_tool"),
            toolCount = row.number("tool_count") ?: 0,
            startedAt = (row["started_at"] as? JsonPrimitive)?.doubleOrNull?.times(1_000)?.toLong() ?: now,
            updatedAt = now,
        )
    }
}

/** Merge a roster without resurrecting a completed child or erasing an event newer than the request. */
fun reconcileSubagents(
    current: List<SubagentStatus>,
    snapshot: List<SubagentStatus>,
    requestedAt: Long,
): List<SubagentStatus> {
    val liveById = snapshot.associateBy { it.id }
    val kept = current.mapNotNull { old ->
        val fresh = liveById[old.id]
        when {
            !old.active -> old
            old.updatedAt > requestedAt -> old
            fresh == null -> null
            else -> old.copy(
                goal = fresh.goal.ifBlank { old.goal },
                status = fresh.status,
                lastTool = fresh.lastTool.ifBlank { old.lastTool },
                toolCount = maxOf(old.toolCount, fresh.toolCount),
                startedAt = if (old.startedAt > 0L) old.startedAt else fresh.startedAt,
            )
        }
    }
    return kept + snapshot.filter { fresh -> kept.none { it.id == fresh.id } && current.none { it.id == fresh.id } }
}

/** All subagent.* events are parent-session events; they must never alter the parent's run phase. */
fun foldSubagentEvent(current: List<SubagentStatus>, event: ServerEvent, now: Long): List<SubagentStatus> {
    if (event.type !in SUBAGENT_STATUS_EVENTS) return current
    val payload = event.payload
    val goal = payload.text("goal")
    val fallbackId = if (goal.isNotBlank()) {
        "${payload.text("parent_id").ifBlank { "root" }}:${payload.number("task_index") ?: 0}:$goal"
    } else ""
    val id = payload.text("subagent_id").ifBlank { fallbackId }.takeIf { it.isNotBlank() } ?: return current
    val old = current.firstOrNull { it.id == id }
        ?: current.firstOrNull { fallbackId.isNotBlank() && it.id == fallbackId }
    if (old != null && !old.active) return current
    val terminal = event.type == "subagent.complete"
    val status = if (terminal) phase(payload.text("status"), terminalEvent = true)
        else if (event.type == "subagent.start") phase(payload.text("status"))
        else SubagentPhase.RUNNING
    val tool = payload.text("tool_name")
    val progress = when (event.type) {
        "subagent.progress" -> payload.text("text").ifBlank { payload.text("preview") }
        "subagent.tool" -> payload.text("tool_preview").ifBlank { payload.text("text") }
        else -> ""
    }.replace(Regex("\\s+"), " ").take(180)
    val result = if (terminal) payload.text("summary").ifBlank {
        payload.text("text").ifBlank { payload.text("preview") }
    }.replace(Regex("\\s+"), " ").take(500) else ""
    val next = (old ?: SubagentStatus(id = id)).copy(
        id = id,
        goal = payload.text("goal").ifBlank { old?.goal.orEmpty() },
        status = status,
        currentTool = if (terminal) "" else tool.ifBlank { old?.currentTool.orEmpty() },
        lastTool = tool.ifBlank { old?.lastTool.orEmpty() },
        progress = progress.ifBlank { old?.progress.orEmpty() },
        result = result.ifBlank { old?.result.orEmpty() },
        toolCount = payload.number("tool_count") ?: old?.toolCount ?: 0,
        startedAt = old?.startedAt?.takeIf { it > 0L } ?: now,
        updatedAt = now,
    )
    return if (old == null) current + next else current.map { if (it.id == old.id) next else it }
}

fun List<SubagentStatus>.withoutCompletedSubagents(): List<SubagentStatus> = filter { it.active }
