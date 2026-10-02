package com.hermes.client.data.haptics

import org.junit.Assert.*
import org.junit.Test

class OutputHapticParametersTest {
    @Test fun missingCorruptOrFuturePreferencesPreserveBuiltInDefault() {
        listOf(null, "broken", "{\"schemaVersion\":2}").forEach {
            assertEquals(OutputHapticConfig(), OutputHapticParameters.decode(it))
        }
        assertEquals(OutputHapticType.SYSTEM_TICK, OutputHapticConfig().type)
        assertEquals(100, OutputHapticConfig().intervalMs)
    }
    @Test fun copiedSchemaRoundTripsAllValuesWithoutPersonalDataAndClampsOutOfRange() {
        val config = OutputHapticConfig(OutputHapticType.CUSTOM_PULSE, 75, 9, 84)
        val text = OutputHapticParameters.encode(config)
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertEquals(config, OutputHapticParameters.decode(text))
        assertEquals(OutputHapticConfig(OutputHapticType.CUSTOM_PULSE, 40, 30, 1),
            OutputHapticParameters.decode(OutputHapticParameters.encode(config.copy(intervalMs = -1, durationMs = 999, amplitude = 0))))
    }
}
