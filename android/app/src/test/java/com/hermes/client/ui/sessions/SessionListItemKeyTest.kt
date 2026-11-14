package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionListItemKeyTest {
    private val session = Session("s", "title", null, null, 1, "p", deviceId = "mac-a")

    @Test fun mutableMetadataDoesNotChangeListIdentity() {
        assertEquals(sessionListItemKey(session), sessionListItemKey(session.copy(
            title = "renamed", model = "new model", lastActive = 123L, messageCount = 42,
            runtimeRevision = 3, cwd = "/another/project",
        )))
    }

    @Test fun theSameIdOnAnotherMacOrProfileIsADifferentRow() {
        assertNotEquals(sessionListItemKey(session), sessionListItemKey(session.copy(deviceId = "mac-b")))
        assertNotEquals(sessionListItemKey(session), sessionListItemKey(session.copy(profile = "other")))
        assertNotEquals(sessionListItemKey(session), sessionListItemKey(session.copy(id = "other")))
        assertNotEquals(sessionListItemKey(session), sessionListItemKey(session.copy(deviceId = null)))
    }

    @Test fun defaultProfileAliasesShareOneIdentity() {
        val key = sessionListItemKey(session.copy(profile = "default"))
        assertEquals(key, sessionListItemKey(session.copy(profile = null)))
        assertEquals(key, sessionListItemKey(session.copy(profile = "")))
    }

    @Test fun separatorsInOpaqueIdentifiersCannotAliasAnotherRow() {
        assertNotEquals(
            sessionListItemKey(session.copy(profile = "p:x", id = "y")),
            sessionListItemKey(session.copy(profile = "p", id = "x:y")),
        )
        assertNotEquals(
            sessionListItemKey(session.copy(deviceId = "mac/p", profile = "q")),
            sessionListItemKey(session.copy(deviceId = "mac", profile = "p/q")),
        )
    }
}
