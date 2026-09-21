package com.hermes.client.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The two ways Hermes asks this client a question, folded onto one set of cards.
 *
 * **Old protocol** (Hermes f159e581 and earlier): `approval.request` / `clarify.request` *events*,
 * answered by the `approval.respond` / `clarify.respond` RPCs.
 *
 * **New protocol** (`tui_gateway/server_requests.py`, e.g. 17b5df02): a server→client JSON-RPC
 * request `{jsonrpc, id:"srq-…", method:"approval"|"clarify"|…, params:{session_id, …}}`, which this
 * client answers by id through `request.answer` (it reports `expired`, a bare response frame does
 * not); a batch clarify locks answers one at a time through the
 * `clarify.lock` RPC; `request.cancel {id, method, reason}` withdraws a request; and unanswered
 * requests come back in `open_requests` on `session.resume`. Hermes only sends them on a
 * connection that said `client.capabilities {server_requests: true}` — otherwise approvals are
 * withdrawn and clarify returns nothing, silently.
 *
 * Which one applies is decided per card by what actually arrived, never by a version guess: a
 * request frame becomes the same event the old protocol would have sent, marked with
 * [SERVER_REQUEST_ID_KEY], and everything downstream (reducer, run phase, notifications,
 * persistence) keeps working on that one shape. The marker is what routes the answer back.
 */
object ServerRequests {
    const val APPROVAL = "approval"
    const val CLARIFY = "clarify"
    const val CAPABILITIES_METHOD = "client.capabilities"
    const val CLARIFY_LOCK_METHOD = "clarify.lock"
    /**
     * Answers an open request by id and says whether it was still open (`{status:"ok"|"expired"}`,
     * `tui_gateway/methods_prompt.py`). Preferred to a bare response frame, which upstream drops
     * without a word when the request is gone — timed out, interrupted, or answered on another surface
     * (which sends no `request.cancel`) — so the phone reported success for an answer nobody received.
     */
    const val ANSWER_METHOD = "request.answer"
    const val RESUME_METHOD = "session.resume"
    /**
     * Client-internal event: the ids a `session.resume` reported as still open for one session. Never
     * on the wire; see HermesGatewayClient.onResumeAnswered.
     */
    const val OPEN_SNAPSHOT_EVENT = "hr.open_requests"
    const val CANCEL_EVENT = "request.cancel"
    const val APPROVAL_EVENT = "approval.request"
    const val CLARIFY_EVENT = "clarify.request"

    /**
     * JSON-RPC "method not found". Upstream reads any error response as "no answer", and names this
     * one as how a client says it has no handler (`contracts/liveness.py`, `server._emit_approval_request`),
     * so the agent moves on immediately instead of waiting out the request's deadline.
     */
    const val METHOD_NOT_FOUND = -32601

    /**
     * Client-internal payload key carrying the server request id. Namespaced so it can never
     * collide with a field upstream adds; it never leaves the phone.
     */
    const val SERVER_REQUEST_ID_KEY = "hr_server_request_id"

    /** `client.capabilities` params: this connection answers server→client requests. */
    fun capabilityParams(): JsonObject = buildJsonObject { put("server_requests", true) }

    /** The methods this client answers; anything else gets [METHOD_NOT_FOUND]. */
    val HANDLED: Set<String> = setOf(APPROVAL, CLARIFY)

    /**
     * The event a server request is folded into, or null when this client has no card for it.
     *
     * For clarify the frame id also becomes `request_id`: it is what `clarify.lock` expects, and
     * it is where the card has always kept its request id. For approval the params' own
     * `request_id` is the approval queue's id, a different thing, so it is left alone.
     */
    fun toEvent(id: JsonPrimitive, method: String, params: JsonObject): ServerEvent? {
        val requestId = id.contentOrNull ?: return null
        val payload = when (method) {
            APPROVAL -> JsonObject(params + (SERVER_REQUEST_ID_KEY to JsonPrimitive(requestId)))
            CLARIFY -> JsonObject(
                params + mapOf(
                    "request_id" to JsonPrimitive(requestId),
                    SERVER_REQUEST_ID_KEY to JsonPrimitive(requestId),
                ),
            )
            else -> return null
        }
        val type = if (method == APPROVAL) APPROVAL_EVENT else CLARIFY_EVENT
        return ServerEvent.from(buildJsonObject {
            put("type", type)
            params["session_id"]?.let { put("session_id", it) }
            put("payload", payload)
        })
    }

    /**
     * `open_requests` from a `session.resume` answer, re-delivered as if each had just arrived — how
     * upstream's own clients restore a question that was asked while no socket was attached. A
     * batch clarify's snapshot carries the answers already locked, which the card restores as ✓.
     */
    fun openRequestEvents(result: JsonObject?): List<ServerEvent> {
        val entries = result?.get("open_requests") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry["id"] as? JsonPrimitive ?: return@mapNotNull null
            val method = (entry["method"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val params = entry["params"] as? JsonObject ?: JsonObject(emptyMap())
            toEvent(id, method, params)
        }
    }

    /**
     * [OPEN_SNAPSHOT_EVENT] for a resume answer: the live session it covers and the request ids to keep —
     * those it lists, plus [alsoOpen], the requests that arrived while the resume was in flight and
     * may postdate upstream's snapshot.
     */
    fun openSnapshotEvent(result: JsonObject, alsoOpen: Set<String> = emptySet()): ServerEvent? {
        val sessionId = (result["session_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val ids = ((result["open_requests"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull } + alsoOpen).distinct()
        return ServerEvent(
            type = OPEN_SNAPSHOT_EVENT,
            sessionId = sessionId,
            payload = buildJsonObject {
                put("session_id", sessionId)
                put("ids", JsonArray(ids.map(::JsonPrimitive)))
            },
        )
    }

    /** The server request id a card was raised by, or null for an old-protocol event. */
    fun idOf(payload: JsonObject): String? =
        (payload[SERVER_REQUEST_ID_KEY] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
}
