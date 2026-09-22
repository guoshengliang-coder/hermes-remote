package com.hermes.client.notifications.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushHintTest {
    private val valid = mapOf(
        "type" to "hermes.lifecycle",
        "eventId" to "evt-1",
        "event" to "run.completed",
        "state" to "completed",
        "deviceId" to "mac-1",
        "storedSessionId" to "stored-1",
        "runtimeSessionId" to "runtime-1",
        "profile" to "work",
        "occurredAt" to "2026-09-22T01:02:03Z",
    )

    @Test fun parsesACompleteHint() {
        val hint = PushHint.parse(valid)!!
        assertEquals("evt-1", hint.eventId)
        assertEquals("run.completed", hint.event)
        assertEquals("mac-1", hint.deviceId)
        assertEquals("stored-1", hint.storedSessionId)
        assertEquals("runtime-1", hint.runtimeSessionId)
        assertEquals("work", hint.profile)
    }

    @Test fun ignoresAnotherSendersMessage() {
        assertNull(PushHint.parse(valid + ("type" to "something.else")))
        assertNull(PushHint.parse(valid - "type"))
    }

    @Test fun rejectsAHintMissingAnIdentityField() {
        listOf("eventId", "event", "state", "deviceId", "storedSessionId", "occurredAt").forEach { key ->
            assertNull("missing $key", PushHint.parse(valid - key))
            assertNull("blank $key", PushHint.parse(valid + (key to "  ")))
        }
    }

    @Test fun rejectsEventsTheServerDoesNotPush() {
        assertNull(PushHint.parse(valid + ("event" to "run.started")))
        assertNull(PushHint.parse(valid + ("event" to "run.resumed")))
        assertNull(PushHint.parse(valid + ("event" to "run.brand_new")))
        listOf("run.waiting", "run.completed", "run.interrupted", "run.unknown").forEach {
            assertEquals(it, PushHint.parse(valid + ("event" to it))?.event)
        }
    }

    @Test fun runtimeSessionAndProfileAreOptional() {
        val hint = PushHint.parse(valid - "profile" + ("runtimeSessionId" to ""))!!
        assertEquals("", hint.runtimeSessionId)
        assertNull(hint.profile)
        assertNull(PushHint.parse(valid - "runtimeSessionId" + ("profile" to " "))!!.profile)
    }

    @Test fun mapsToAnInboxEnvelopeWithoutATitle() {
        val dto = PushHint.parse(valid)!!.toLifecycleEventDto()
        assertEquals("session.lifecycle", dto.type)
        assertEquals(1, dto.version)
        assertEquals("evt-1", dto.eventId)
        assertEquals("mac-1", dto.deviceId)
        assertEquals("work", dto.profile)
        assertEquals("runtime-1", dto.runtimeSessionId)
        assertEquals("stored-1", dto.storedSessionId)
        assertEquals("run.completed", dto.event)
        assertEquals("completed", dto.state)
        assertEquals("2026-09-22T01:02:03Z", dto.occurredAt)
        assertNull(dto.title)
    }
}
