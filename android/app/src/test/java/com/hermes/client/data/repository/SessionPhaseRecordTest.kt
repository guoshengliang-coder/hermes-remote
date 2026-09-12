package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disk format for HG-31's run-state snapshot. Everything here is a pure function, so the
 * bounds and the failure modes are testable without Android.
 */
class SessionPhaseRecordTest {

    private fun record(id: String, phase: String = "COMPLETED_UNREAD", lastEventAt: Long = 0L) =
        SessionPhaseRecord(sessionId = id, phase = phase, lastEventAt = lastEventAt)

    @Test fun aRecordSurvivesARoundTrip() {
        val original = SessionPhaseRecord(
            sessionId = "s1",
            profile = "personal",
            deviceId = "mac-mini",
            phase = "WAITING_CLARIFICATION",
            phaseBeforeReconnect = "THINKING",
            occurredAt = 111L,
            lastEventAt = 222L,
            lastTerminalAt = 333L,
            runStartedAt = 444L,
            todoDone = 2,
            todoTotal = 5,
            clarify = PersistedClarify(
                requestId = "clr-1",
                questions = listOf(PersistedQuestion("q0", "要用哪种发布方式？", listOf("滚动", "蓝绿"), false)),
                lockedAnswers = mapOf("q0" to "滚动"),
            ),
        )
        assertEquals(listOf(original), decodePhaseRecords(encodePhaseRecords(listOf(original))))
    }

    @Test fun unknownFieldsDoNotDropTheRecord() {
        val raw = """[{"v":1,"sessionId":"s1","phase":"COMPLETED_UNREAD","somethingNewer":42}]"""
        assertEquals(listOf("s1"), decodePhaseRecords(raw).map { it.sessionId })
    }

    @Test fun corruptPayloadDecodesToNothing() {
        assertEquals(emptyList<SessionPhaseRecord>(), decodePhaseRecords("{not json"))
        assertEquals(emptyList<SessionPhaseRecord>(), decodePhaseRecords(null))
    }

    @Test fun aRecordFromAnotherFormatVersionIsDropped() {
        val raw = """[{"v":99,"sessionId":"s1","phase":"COMPLETED_UNREAD"}]"""
        assertEquals(emptyList<SessionPhaseRecord>(), decodePhaseRecords(raw))
    }

    @Test fun recordsWithoutAnIdentityOrPhaseAreDropped() {
        val raw = """[{"v":1,"sessionId":"","phase":"COMPLETED_UNREAD"},{"v":1,"sessionId":"s2","phase":""}]"""
        assertEquals(emptyList<SessionPhaseRecord>(), decodePhaseRecords(raw))
    }

    @Test fun onlyTheMostRecentRunsAreKept() {
        val many = (1..MAX_PERSISTED_RUNTIMES + 20).map { record("s$it", lastEventAt = it.toLong()) }
        val kept = decodePhaseRecords(encodePhaseRecords(many))
        assertEquals(MAX_PERSISTED_RUNTIMES, kept.size)
        // The newest survive; the oldest are the ones dropped.
        assertEquals("s${MAX_PERSISTED_RUNTIMES + 20}", kept.first().sessionId)
        assertTrue(kept.none { it.sessionId == "s1" })
    }

    @Test fun aPathologicallyLargeRecordIsSkippedWithoutLosingTheOthers() {
        val huge = record("huge", lastEventAt = 99L).copy(
            phase = "WAITING_CLARIFICATION",
            clarify = PersistedClarify(
                requestId = "clr",
                questions = List(400) { PersistedQuestion("q$it", "x".repeat(200)) },
            ),
        )
        val kept = decodePhaseRecords(encodePhaseRecords(listOf(huge, record("small", lastEventAt = 1L))))
        assertEquals(listOf("small"), kept.map { it.sessionId })
    }

    @Test fun theWholePayloadStaysUnderItsBudget() {
        val chunky = (1..MAX_PERSISTED_RUNTIMES).map {
            record("s$it", phase = "WAITING_CLARIFICATION", lastEventAt = it.toLong()).copy(
                clarify = PersistedClarify(
                    requestId = "clr-$it",
                    questions = List(6) { q -> PersistedQuestion("q$q", "y".repeat(300)) },
                ),
            )
        }
        val encoded = encodePhaseRecords(chunky)
        assertTrue("payload was ${encoded.length}", encoded.length <= MAX_PAYLOAD_CHARS)
        assertTrue(decodePhaseRecords(encoded).isNotEmpty())
    }
}
