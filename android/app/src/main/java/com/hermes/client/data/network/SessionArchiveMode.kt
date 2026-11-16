package com.hermes.client.data.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

enum class SessionArchiveMode { LEGACY, AUTHORITY, UNKNOWN }
data class ArchiveBackendSnapshot(val generation: Int, val mode: SessionArchiveMode)

/** Called only for the current socket's completed gateway.ready handshake. */
fun sessionArchiveMode(payload: JsonElement?): SessionArchiveMode {
    val obj = payload as? JsonObject ?: return SessionArchiveMode.UNKNOWN
    val value = obj["session_authority"] ?: return SessionArchiveMode.LEGACY
    val flag = value as? JsonPrimitive ?: return SessionArchiveMode.UNKNOWN
    if (flag.isString) return SessionArchiveMode.UNKNOWN
    return when (flag.booleanOrNull) {
        true -> SessionArchiveMode.AUTHORITY
        false -> SessionArchiveMode.LEGACY
        null -> SessionArchiveMode.UNKNOWN
    }
}
