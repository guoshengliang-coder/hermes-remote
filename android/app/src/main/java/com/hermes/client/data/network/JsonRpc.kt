package com.hermes.client.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RpcRequest(val id: Long, val method: String, val params: JsonObject) {
    fun encode(json: Json): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}

data class RpcError(val code: Int, val message: String)

sealed interface RpcInbound
data class RpcResult(val id: Long, val result: JsonElement) : RpcInbound
data class RpcErrorReply(val id: Long, val error: RpcError) : RpcInbound
data class RpcEvent(val event: ServerEvent) : RpcInbound

/**
 * A server→client JSON-RPC request: Hermes asking this client a question and waiting for a response
 * frame that carries the same [id] (`tui_gateway/server_requests.py`, newer than f159e581).
 *
 * [id] is kept as the exact JSON primitive it arrived as — upstream mints `"srq-<12 hex>"` strings —
 * because the answer must echo it verbatim; turning it into a number or re-quoting it would address
 * nothing, and upstream drops a response for an id it does not have without saying so.
 */
data class RpcServerRequest(val id: JsonPrimitive, val method: String, val params: JsonObject) : RpcInbound

/**
 * A frame that could not be read as any of the above. Before server→client requests existed the
 * parser assumed every non-event carried a numeric id, and a string id threw out of the WebSocket
 * listener — which OkHttp turns into a dead socket. Anything unreadable is now named and dropped.
 */
data class RpcUnreadable(val reason: String) : RpcInbound

fun parseInbound(json: Json, line: String): RpcInbound {
    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        ?: return RpcUnreadable("not a JSON object")
    val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (method == "event") {
        val params = obj.objOrEmpty("params")
        return RpcEvent(ServerEvent.from(params))
    }
    val rawId = obj["id"] as? JsonPrimitive
    if (method != null) {
        // A request needs an id to be answerable; one without is a notification we do not know.
        val id = rawId?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?: return RpcUnreadable("notification $method")
        return RpcServerRequest(id, method, obj.objOrEmpty("params"))
    }
    // Every id this client mints is a Long, so a response with anything else cannot be ours.
    val id = rawId?.longOrNull ?: -1L
    obj["error"]?.let { element ->
        val e = element as? JsonObject ?: JsonObject(emptyMap())
        return RpcErrorReply(
            id,
            RpcError(
                code = (e["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                message = (e["message"] as? JsonPrimitive)?.content ?: "error",
            ),
        )
    }
    return RpcResult(id, obj["result"] ?: JsonPrimitive("null"))
}

/** The response frame that answers server request [id] with [result]. */
fun encodeServerResponse(json: Json, id: JsonPrimitive, result: JsonObject): String =
    json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    })

/** The error frame that answers server request [id]: upstream reads any error as "no answer". */
fun encodeServerError(json: Json, id: JsonPrimitive, code: Int, message: String): String =
    json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    })
