package com.hermes.client.data.haptics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class OutputHapticType { SYSTEM_TICK, SYSTEM_SOFT, KEYBOARD, CUSTOM_PULSE }

/** Device-local parameters. Interval is a minimum, never a timer that runs without new prose. */
@Serializable
data class OutputHapticConfig(
    val type: OutputHapticType = OutputHapticType.SYSTEM_TICK,
    val intervalMs: Int = 100,
    val durationMs: Int = 8,
    val amplitude: Int = 64,
) {
    fun normalized() = copy(
        intervalMs = intervalMs.coerceIn(40, 500),
        durationMs = durationMs.coerceIn(1, 30),
        amplitude = amplitude.coerceIn(1, 255),
    )
}

@Serializable
private data class HapticParameters(val schemaVersion: Int = 1, val config: OutputHapticConfig)

object OutputHapticParameters {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    fun encode(config: OutputHapticConfig): String = json.encodeToString(HapticParameters(config = config.normalized()))
    fun decode(stored: String?): OutputHapticConfig {
        if (stored == null) return OutputHapticConfig()
        return runCatching {
            val parameters = json.decodeFromString<HapticParameters>(stored)
            require(parameters.schemaVersion == 1)
            parameters.config.normalized()
        }.getOrDefault(OutputHapticConfig())
    }
}
