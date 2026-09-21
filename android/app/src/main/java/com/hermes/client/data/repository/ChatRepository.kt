package com.hermes.client.data.repository

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.ServerRequests
import com.hermes.client.ui.chat.ApprovalChoice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

data class AttachedImage(
    val path: String,
    val width: Int? = null,
    val height: Int? = null,
)

data class AttachedFile(
    val name: String,
    val path: String?,
    val refText: String,
)

data class BackgroundProcess(
    val id: String,
    val command: String,
    val running: Boolean,
    val exitCode: Int? = null,
    val outputTail: String = "",
)

/** Result of `session.create`: the durable id plus the working directory the gateway resolved. */
data class CreatedSession(val id: String, val cwd: String?)

/** Workspace facts the gateway reports for a session (from `session.workspace.move` / `session.info`). */
data class WorkspaceInfo(val cwd: String?, val branch: String?, val gitRepoRoot: String?)

/** A "@" completion item: [text] is inserted, [display] shown, [meta] is a hint. */
data class PathItem(val text: String, val display: String, val meta: String)

enum class SessionAccessState {
    AVAILABLE,
    OWNED_BY_REQUESTER,
    OWNED_ELSEWHERE,
    UNKNOWN,
}

data class SessionAccess(
    val state: SessionAccessState,
    val running: Boolean?,
    val writable: Boolean?,
    val ownerSurface: String?,
)

class ChatRepository(private val client: HermesGatewayClient) {
    /**
     * Identifies this client to Hermes on `session.create` and `session.resume`.
     *
     * Hermes stores whatever the caller passes (`_resolve_session_source` returns an explicit value
     * unchanged) and derives the agent's platform — and therefore its system-prompt capability
     * block — from it. Sending nothing made Hermes fall back to its environment guess, `tui`, which
     * is indistinguishable from a real terminal and whose prompt block states there is no
     * attachment channel and that `MEDIA:` tags are not intercepted. Both claims are false here:
     * the app renders `MEDIA:` as a downloadable file card. The agent was faithfully obeying a
     * prompt that did not describe this client.
     *
     * The matching capability text lives in the Mac's `~/.hermes/config.yaml` under
     * `platform_hints.hermes_remote` — Hermes' supported config override, so no Hermes source is
     * patched and an upgrade cannot clobber it. See docs/HERMES_CONTRACT.md.
     *
     * Sent on resume as well, so sessions stored before this shipped also get the right platform.
     */
    private val clientSource = "hermes_remote"

    val events: SharedFlow<ServerEvent> get() = client.events
    val connectionState: StateFlow<ConnectionState> get() = client.connectionState

    /** See [com.hermes.client.data.network.HermesGatewayClient.consecutiveDroppedConnections]. */
    val consecutiveDroppedConnections: Int get() = client.consecutiveDroppedConnections

    fun connect() = client.connect()
    fun disconnect() = client.close("chat repository disconnect")

    /** Force an immediate reconnect, skipping the backoff wait (user tapped "Retry"). */
    fun reconnect() = client.reconnectNow()

    /**
     * Creates a new session. [profile] MUST be the active profile: the gateway binds the session to
     * a per-profile state.db at creation time, and with no profile it defaults to the gateway's
     * launch profile. Messages then persist under that default profile, but the session list is
     * scoped to the active profile — so a chat created (and messaged) under the wrong profile is
     * invisible in both the app and the desktop. Same per-profile rule as [resume].
     *
     * [cwd] is the project folder the session should work in. Only an explicit cwd is persisted as
     * the session's workspace; omitted, the session lands in the gateway's launch directory (the
     * default project). The returned [CreatedSession.cwd] is the folder the gateway actually
     * resolved, so a caller can detect a requested folder that no longer exists on the Mac.
     */
    suspend fun createSession(profile: String? = null, cwd: String? = null): CreatedSession {
        val result = client.call("session.create", buildJsonObject {
            put("source", clientSource)
            if (!profile.isNullOrBlank()) put("profile", profile)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        })
        val obj = result.jsonObject
        // session.create returns an ephemeral in-memory `session_id` plus the durable
        // `stored_session_id`. Navigation must use the durable id: ChatViewModel.open()
        // resumes it into a fresh live handle before the first prompt. Passing the live
        // id back to session.resume fails for a zero-message session because no DB row
        // exists yet (`session not found`). Older gateways may omit stored_session_id.
        val id = obj["stored_session_id"]?.jsonPrimitive?.contentOrNull
            ?: obj["session_id"]?.jsonPrimitive?.contentOrNull
            ?: error("session.create returned no id")
        val resolvedCwd = (obj["info"] as? JsonObject)?.get("cwd")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        return CreatedSession(id = id, cwd = resolvedCwd)
    }

    /**
     * Re-homes a stored session into another project folder (`session.workspace.move`). Targets
     * the durable session key, so it works for sessions with no live agent; a live agent follows
     * through the runtime path. The gateway refuses while the session is mid-turn (4009) and when
     * the folder does not exist on the Mac (4017) — see [com.hermes.client.ui.sessions.workspaceMoveError].
     */
    suspend fun moveWorkspace(sessionKey: String, cwd: String, profile: String? = null): WorkspaceInfo {
        val result = client.call("session.workspace.move", buildJsonObject {
            put("session_key", sessionKey)
            put("cwd", cwd)
            if (!profile.isNullOrBlank()) put("profile", profile)
        })
        val obj = result.jsonObject
        return WorkspaceInfo(
            cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: cwd,
            branch = obj["branch"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
            gitRepoRoot = obj["git_repo_root"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
        )
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
            put("source", clientSource)
            // The app reads history through the chunked REST endpoint. Repeating every historical
            // base64 image in this control-plane answer can exceed the relay frame before the
            // returned live handle reaches us (HG-69).
            put("inline_images", false)
            if (!profile.isNullOrBlank()) put("profile", profile)
        })
        val obj = result as? JsonObject
        // A question asked while no socket of ours was attached is not replayed as a frame; newer
        // Hermes returns it here instead and expects the client to re-deliver it (an older one
        // has no such field, and this is then empty).
        ServerRequests.openRequestEvents(obj).takeIf { it.isNotEmpty() }?.let(client::redeliver)
        return obj?.get("session_id")?.jsonPrimitive?.content
    }

    /**
     * Read-only cross-process ownership and run-state inspection. This does not resume, activate,
     * or acquire the session. Callers must fail open when an older Hermes does not implement it;
     * `prompt.submit`'s 4090 remains the compatibility backstop.
     */
    suspend fun sessionAccess(
        sessionId: String,
        profile: String? = null,
        liveSessionId: String? = null,
    ): SessionAccess {
        val result = client.call("session.access", buildJsonObject {
            put("session_id", sessionId)
            if (!profile.isNullOrBlank()) put("profile", profile)
            if (!liveSessionId.isNullOrBlank()) put("live_session_id", liveSessionId)
        }).jsonObject
        val state = when (result["state"]?.jsonPrimitive?.contentOrNull) {
            "available" -> SessionAccessState.AVAILABLE
            "owned_by_requester" -> SessionAccessState.OWNED_BY_REQUESTER
            "owned_elsewhere" -> SessionAccessState.OWNED_ELSEWHERE
            else -> SessionAccessState.UNKNOWN
        }
        return SessionAccess(
            state = state,
            running = result["running"]?.jsonPrimitive?.booleanOrNull,
            writable = result["writable"]?.jsonPrimitive?.booleanOrNull,
            ownerSurface = result["owner_surface"]?.jsonPrimitive?.contentOrNull,
        )
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

    /**
     * Current reasoning effort for [sessionId] (`config.get {key:"reasoning"}` — the same RPC
     * the Hermes desktop client uses). Returns e.g. "medium", "none" (thinking off), or "" when
     * the provider default applies.
     */
    suspend fun reasoningGet(sessionId: String): String? {
        val result = client.call("config.get", buildJsonObject {
            put("key", "reasoning")
            put("session_id", sessionId)
        })
        return result.jsonObject["value"]?.jsonPrimitive?.content
    }

    /**
     * Session-scoped reasoning-effort override (`config.set {key:"reasoning"}` without a scope —
     * deliberately never `scope:"global"`, mirroring the desktop picker which never rewrites the
     * profile default).
     */
    suspend fun reasoningSet(sessionId: String, value: String) {
        client.call("config.set", buildJsonObject {
            put("key", "reasoning")
            put("session_id", sessionId)
            put("value", value)
        })
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

    suspend fun attachImagePath(sessionId: String, path: String): AttachedImage {
        val obj = client.call("image.attach", buildJsonObject {
            put("session_id", sessionId)
            put("path", path)
        }).jsonObject
        return AttachedImage(
            path = obj["path"]?.jsonPrimitive?.content ?: path,
            width = obj["width"]?.jsonPrimitive?.intOrNull,
            height = obj["height"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** Render a remotely-selected PDF into Hermes vision pages for the next prompt. */
    suspend fun attachPdfBytes(sessionId: String, dataBase64: String, filename: String) {
        client.call("pdf.attach", buildJsonObject {
            put("session_id", sessionId)
            put("content_base64", dataBase64)
            put("filename", filename)
        })
    }

    suspend fun attachPdfPath(sessionId: String, path: String) {
        client.call("pdf.attach", buildJsonObject {
            put("session_id", sessionId)
            put("path", path)
        })
    }

    /** Stage a non-image client file and return the @file reference required by prompt.submit. */
    suspend fun attachFileBytes(
        sessionId: String,
        dataBase64: String,
        mimeType: String,
        filename: String,
    ): AttachedFile {
        val result = client.call("file.attach", buildJsonObject {
            put("session_id", sessionId)
            put("name", filename)
            put("data_url", "data:$mimeType;base64,$dataBase64")
        }).jsonObject
        return AttachedFile(
            name = result["name"]?.jsonPrimitive?.contentOrNull ?: filename,
            path = result["path"]?.jsonPrimitive?.contentOrNull,
            refText = result["ref_text"]?.jsonPrimitive?.contentOrNull
                ?: error("file.attach returned no ref_text"),
        )
    }

    suspend fun attachFilePath(sessionId: String, path: String, filename: String): AttachedFile {
        val result = client.call("file.attach", buildJsonObject {
            put("session_id", sessionId)
            put("path", path)
            put("name", filename)
        }).jsonObject
        return AttachedFile(
            name = result["name"]?.jsonPrimitive?.contentOrNull ?: filename,
            path = result["path"]?.jsonPrimitive?.contentOrNull ?: path,
            refText = result["ref_text"]?.jsonPrimitive?.contentOrNull
                ?: error("file.attach returned no ref_text"),
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

    /**
     * Answer an approval. [serverRequestId] is set when the card came from a server→client request
     * (newer Hermes): the answer is then the response frame `{choice}` for that exact request, which
     * cannot land on a different approval. Otherwise it is the older `approval.respond` RPC.
     */
    suspend fun respondApproval(sessionId: String, choice: ApprovalChoice, serverRequestId: String? = null) {
        if (!serverRequestId.isNullOrBlank()) {
            client.respondToServerRequest(serverRequestId, buildJsonObject { put("choice", choice.wire) })
            return
        }
        client.call("approval.respond", buildJsonObject {
            put("session_id", sessionId)
            // Only `choice`: upstream never read an `approved` flag (f159e581 included), and newer
            // Hermes rejects any key its contract does not declare with 4000.
            put("choice", choice.wire)
        })
    }

    /**
     * Returns the server's status string: "ok" when the pending request was released with this
     * answer, "expired" when the request was already gone server-side (timeout, interrupt, or a
     * concurrent release) — the agent never sees an answer delivered onto an expired request.
     *
     * [serverRequest] marks a card raised by a server→client `clarify` request (newer Hermes), for
     * which `clarify.respond` no longer exists: one batch answer is locked with `clarify.lock`
     * (which still says `ok`/`expired`, and whose last lock resolves the request), while a single
     * answer, a skip or a cancel-all is the response frame `{answer}`. That frame gets no reply, so
     * it reports `ok`; a request that had already ended is withdrawn with `request.cancel` instead.
     */
    suspend fun respondClarify(
        sessionId: String,
        requestId: String,
        answer: String,
        questionId: String? = null,
        serverRequest: Boolean = false,
    ): String {
        if (serverRequest) {
            if (!questionId.isNullOrEmpty()) {
                val locked = client.call(ServerRequests.CLARIFY_LOCK_METHOD, buildJsonObject {
                    put("request_id", requestId)
                    put("question_id", questionId)
                    put("answer", answer)
                })
                return (locked as? JsonObject)?.get("status")?.let { (it as? JsonPrimitive)?.content }.orEmpty()
            }
            client.respondToServerRequest(requestId, buildJsonObject { put("answer", answer) })
            return "ok"
        }
        val result = client.call("clarify.respond", buildJsonObject {
            put("session_id", sessionId)
            put("request_id", requestId)
            put("answer", answer)
            // Batch clarifies lock one answer at a time; the server keys them by qid.
            if (!questionId.isNullOrEmpty()) put("question_id", questionId)
        })
        return (result as? JsonObject)?.get("status")?.let { (it as? JsonPrimitive)?.content }.orEmpty()
    }
}
