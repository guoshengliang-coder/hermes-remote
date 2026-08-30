package com.hermes.client.data.repository

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.ui.chat.ApprovalChoice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class AttachedImage(
    val path: String,
    val width: Int? = null,
    val height: Int? = null,
)

data class BackgroundProcess(
    val id: String,
    val command: String,
    val running: Boolean,
    val exitCode: Int? = null,
    val outputTail: String = "",
)

/** A "@" completion item: [text] is inserted, [display] shown, [meta] is a hint. */
data class PathItem(val text: String, val display: String, val meta: String)

class ChatRepository(private val client: HermesGatewayClient) {
    val events: SharedFlow<ServerEvent> get() = client.events
    val connectionState: StateFlow<ConnectionState> get() = client.connectionState

    fun connect() = client.connect()
    fun disconnect() = client.close()

    /** Force an immediate reconnect, skipping the backoff wait (user tapped "Retry"). */
    fun reconnect() = client.reconnectNow()

    /**
     * Creates a new session. [profile] MUST be the active profile: the gateway binds the session to
     * a per-profile state.db at creation time, and with no profile it defaults to the gateway's
     * launch profile. Messages then persist under that default profile, but the session list is
     * scoped to the active profile — so a chat created (and messaged) under the wrong profile is
     * invisible in both the app and the desktop. Same per-profile rule as [resume].
     */
    suspend fun createSession(profile: String? = null): String {
        val result = client.call("session.create", buildJsonObject {
            if (!profile.isNullOrBlank()) put("profile", profile)
        })
        val obj = result.jsonObject
        // session.create returns an ephemeral in-memory `session_id` plus the durable
        // `stored_session_id`. Navigation must use the durable id: ChatViewModel.open()
        // resumes it into a fresh live handle before the first prompt. Passing the live
        // id back to session.resume fails for a zero-message session because no DB row
        // exists yet (`session not found`). Older gateways may omit stored_session_id.
        return obj["stored_session_id"]?.jsonPrimitive?.contentOrNull
            ?: obj["session_id"]?.jsonPrimitive?.contentOrNull
            ?: error("session.create returned no id")
    }

    /**
     * Resumes a session. The gateway accepts the stored (REST) id but returns a NEW short
     * live handle in `session_id` — callers MUST use that returned id for subsequent
     * submit/interrupt and for filtering streamed events. Returns null if not present.
     *
     * [profile] MUST be the active profile: the gateway resolves session.resume against a
     * per-profile state.db, and without it a session that lives in a non-default profile is
     * reported "session not found" (4007) — which then fails the next prompt.submit too. Once
     * resume succeeds, the live handle it returns is profile-independent (resolved in-memory),
     * so only resume needs the profile.
     */
    suspend fun resume(sessionId: String, profile: String? = null): String? {
        val result = client.call("session.resume", buildJsonObject {
            put("session_id", sessionId)
            if (!profile.isNullOrBlank()) put("profile", profile)
        })
        return result.jsonObject["session_id"]?.jsonPrimitive?.content
    }

    suspend fun submit(sessionId: String, text: String) {
        client.call("prompt.submit", buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
        })
    }

    /**
     * Execute a slash command (e.g. "/help", "/model …"). Returns the command's text output
     * (the gateway's `output` field) so callers can surface the result — a `/model` switch, for
     * instance, reports success or an error like "Could not resolve credentials for …" here. A
     * transport/worker failure (e.g. "slash worker closed pipe") throws instead.
     */
    suspend fun slashExec(sessionId: String, command: String): String? {
        val result = client.call("slash.exec", buildJsonObject {
            put("session_id", sessionId)
            put("command", command)
        })
        return result.jsonObject["output"]?.jsonPrimitive?.content
    }

    /** "@" path/mention completions (complete.path → {items:[{text,display,meta}]}). */
    suspend fun completePath(sessionId: String, word: String): List<PathItem> {
        val result = client.call("complete.path", buildJsonObject {
            put("session_id", sessionId)
            put("word", word)
        })
        val items = result.jsonObject["items"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val text = o["text"]?.jsonPrimitive?.content ?: return@mapNotNull null
            PathItem(
                text = text,
                display = o["display"]?.jsonPrimitive?.content ?: text,
                meta = o["meta"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    /** Fetch the slash-command catalog for the composer palette ("pairs" = [[name, desc], …]). */
    suspend fun commandsCatalog(): List<Pair<String, String>> {
        val result = client.call("commands.catalog", buildJsonObject {})
        val arr = result.jsonObject["pairs"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { el ->
            val pair = runCatching { el.jsonArray }.getOrNull() ?: return@mapNotNull null
            val name = pair.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
            val desc = pair.getOrNull(1)?.jsonPrimitive?.content ?: ""
            name to desc
        }
    }

    suspend fun interrupt(sessionId: String) {
        client.call("session.interrupt", buildJsonObject { put("session_id", sessionId) })
    }

    /** Attach an image (base64 data) to the session; included with the next prompt. */
    suspend fun attachImageBytes(sessionId: String, dataBase64: String, mimeType: String): AttachedImage {
        val result = client.call("image.attach_bytes", buildJsonObject {
            put("session_id", sessionId)
            // Current Hermes uses content_base64; `data` was a legacy alias.
            put("content_base64", dataBase64)
            put("mime_type", mimeType)
        })
        val obj = result.jsonObject
        return AttachedImage(
            path = obj["path"]?.jsonPrimitive?.content
                ?: error("image.attach_bytes returned no path"),
            width = obj["width"]?.jsonPrimitive?.intOrNull,
            height = obj["height"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** Session-scoped background processes, matching Hermes Desktop's process status source. */
    suspend fun listProcesses(sessionId: String): List<BackgroundProcess> {
        val result = client.call("process.list", buildJsonObject { put("session_id", sessionId) })
        val items = result.jsonObject["processes"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = listOf("session_id", "process_id", "id")
                .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull }
                ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val exitCode = obj["exit_code"]?.jsonPrimitive?.intOrNull
            BackgroundProcess(
                id = id,
                command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                running = status.equals("running", true) || (status.isBlank() && exitCode == null),
                exitCode = exitCode,
                outputTail = obj["output_tail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    suspend fun respondApproval(sessionId: String, choice: ApprovalChoice) {
        client.call("approval.respond", buildJsonObject {
            put("session_id", sessionId)
            put("choice", choice.wire)
            put("approved", choice != ApprovalChoice.DENY)
        })
    }

    suspend fun respondClarify(sessionId: String, requestId: String, answer: String) {
        client.call("clarify.respond", buildJsonObject {
            put("session_id", sessionId)
            put("request_id", requestId)
            put("answer", answer)
        })
    }
}
