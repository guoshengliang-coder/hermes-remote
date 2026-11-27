package com.hermes.client.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UnknownNoticeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun committed_removal_survives_a_new_datastore_and_preserves_other_records() = runTest {
        val file = File(tmp.root, "unsent.preferences_pb")
        val firstJob = SupervisorJob()
        val first = UnsentStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file }))
        try {
            first.save(UnsentRecord(token = "mac/profile/session", messageId = "unknown", text = "original", deliveryUnconfirmed = true))
            first.save(UnsentRecord(token = "mac/profile/session", messageId = "failed", text = "keep"))
            first.save(UnsentRecord(token = "other-mac/profile/session", messageId = "unknown", text = "keep", deliveryUnconfirmed = true))
            first.removeUnknownNotice("mac/profile/session", "unknown")
            // A mistakenly addressed non-unknown row must never be removed by this operation.
            first.removeUnknownNotice("mac/profile/session", "failed")
        } finally { firstJob.cancelAndJoin() }
        val nextJob = SupervisorJob()
        try {
            val restored = UnsentStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(nextJob + Dispatchers.IO), produceFile = { file }))
            assertEquals(listOf("failed"), restored.readAll("mac/profile/session").map { it.messageId })
            assertEquals("unknown", restored.readAll("other-mac/profile/session").single().messageId)
        } finally { nextJob.cancelAndJoin() }
    }

    @Test fun legacy_creation_can_be_claimed_once_and_bound_without_replaying_a_prompt() = runTest {
        val job = SupervisorJob()
        val store = UnsentStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { File(tmp.root, "creation.preferences_pb") },
        ))
        try {
            val owner = PendingSendConversation("account", "mac", "default", "draft", null, localCreation = true)
            val draft = UnsentRecord(token = "draft", messageId = "turn", text = "keep", conversation = owner,
                code = "HR-SESS-022", retryable = false, deliveryUnconfirmed = true)
            store.saveForDelivery(draft)
            assertTrue(store.claimCreation(draft))
            assertFalse(store.claimCreation(draft))
            val formal = draft.copy(token = "formal", deliveryUnconfirmed = false,
                conversation = owner.copy(sessionId = "formal", localCreation = false))
            store.bindCreation(draft.token, formal)
            assertNull(store.read(draft.token))
            assertEquals(formal, store.read(formal.token))
            assertFalse(store.claimCreation(store.read(formal.token)!!))
            assertTrue(runCatching { store.bindCreation(draft.token, formal) }.isFailure)
            store.restoreCreation(draft)
            assertNull(store.read(draft.token))
            assertEquals(formal, store.read(formal.token))
        } finally { job.cancelAndJoin() }
    }

    @Test fun explicit_removal_propagates_a_failed_commit_instead_of_false_success() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("disk unavailable")
        }
        val store = UnsentStore(broken)
        try {
            store.removeUnknownNotice("session", "unknown")
            fail("a failed durable commit must be reported")
        } catch (_: IOException) { }
    }
}
