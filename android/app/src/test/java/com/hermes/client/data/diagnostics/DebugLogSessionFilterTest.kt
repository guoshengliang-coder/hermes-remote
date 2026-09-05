package com.hermes.client.data.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** docs/DESIGN.md §5.15: a report carries exactly the conversation that misbehaved. */
class DebugLogSessionFilterTest {
    @Before fun setUp() { DebugLog.detachStore(); DebugLog.setEnabled(true); DebugLog.clear() }
    @After fun tearDown() { DebugLog.setEnabled(false); DebugLog.clear() }

    @Test fun sessionIdsAreCollectedMostRecentFirst() {
        DebugLog.log("phase", "s=20260905_102612_6d5fd4 IDLE→SUBMITTING cause=prompt")
        DebugLog.log("ws", "event message.start session=20260905_163901_c12694")
        DebugLog.log("phase", "s=20260905_102612_6d5fd4 SUBMITTING→THINKING cause=event:message.start")
        DebugLog.log("ws", "opening socket (gen=3)")

        assertEquals(listOf("20260905_102612_6d5fd4", "20260905_163901_c12694"), DebugLog.sessionIdsIn(DebugLog.entries.value))
    }

    @Test fun exportWithASessionKeepsOnlyThatSessionsLines() {
        DebugLog.log("phase", "s=aaa111 IDLE→SUBMITTING cause=prompt")
        DebugLog.log("ws", "opening socket (gen=3)")
        DebugLog.log("phase", "s=bbb222 IDLE→SUBMITTING cause=prompt")
        DebugLog.log("history", "reconcile s=aaa111 rejected: assistantTurns 0<1")

        val text = DebugLog.export("aaa111")
        assertTrue(text.contains("session aaa111"))
        assertTrue(text.contains("cause=prompt"))
        assertTrue(text.contains("rejected: assistantTurns 0<1"))
        assertFalse(text.contains("bbb222"))
        assertFalse(text.contains("opening socket"))
        assertEquals(2, text.lines().count { it.contains("aaa111") && !it.startsWith("Hermes diagnostic log") })
    }

    @Test fun anIdThatIsAPrefixOfAnotherDoesNotMatchIt() {
        DebugLog.log("phase", "s=abc123456 IDLE→SUBMITTING cause=prompt")
        assertFalse(DebugLog.mentionsSession("s=abc123456 IDLE→SUBMITTING", "abc123"))
        assertTrue(DebugLog.mentionsSession("s=abc123456 IDLE→SUBMITTING", "abc123456"))
    }
}
