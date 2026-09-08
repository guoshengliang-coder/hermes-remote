package com.hermes.client.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic capture is on by default in debug builds, which is what every APK handed to a tester
 * currently is. A stall like HG-27 is only diagnosable if capture was already running when it
 * happened, and requiring the user to have switched it on beforehand loses the first occurrence
 * every time. Release builds keep the off-by-default privacy decision (DESIGN.md §5.15).
 */
class DebugLoggingDefaultTest {
    @Test fun a_debug_build_captures_until_told_otherwise() {
        assertTrue(resolveDebugLogging(stored = null, default = true))
    }

    @Test fun a_release_build_stays_silent_until_asked() {
        assertFalse(resolveDebugLogging(stored = null, default = false))
    }

    /**
     * Both directions matter. Turning capture off in a debug build has to survive a restart —
     * re-enabling it from the default would be a privacy bug, and an easy one to write.
     */
    @Test fun an_explicit_choice_outranks_the_default_in_both_directions() {
        assertFalse("off must stay off in a debug build", resolveDebugLogging(stored = false, default = true))
        assertTrue("on must stay on in a release build", resolveDebugLogging(stored = true, default = false))
    }
}
