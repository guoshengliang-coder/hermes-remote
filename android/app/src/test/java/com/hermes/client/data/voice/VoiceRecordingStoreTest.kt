package com.hermes.client.data.voice

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VoiceRecordingStoreTest {
    private val root = Files.createTempDirectory("voice-store-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun interruptedAndCompleteAudioRecoverOnlyInExactScope() {
        val store = VoiceRecordingStore(root)
        val r = store.begin("account/mac/profile/chat")
        store.append(r.id, byteArrayOf(1, 2)); store.append(r.id, byteArrayOf(3, 4))
        assertTrue(VoiceRecordingStore(root).list("another-account/mac/profile/chat").isEmpty())
        assertEquals(4L, VoiceRecordingStore(root).list(r.scope).single().bytes)
        assertFalse(VoiceRecordingStore(root).list(r.scope).single().complete)
        store.seal(r.id, true, true)
        assertTrue(VoiceRecordingStore(root).list(r.scope).single().complete)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.audio(r.id).readBytes())
    }
    @Test fun expiryRemovesAudioAndPreventsReplay() {
        var now = 10L
        val store = VoiceRecordingStore(root) { now }
        val r = store.begin("one"); store.append(r.id, byteArrayOf(1, 2)); store.seal(r.id, true, false)
        now += VoiceRecordingStore.RETENTION_MS
        assertTrue(runCatching { store.load(r.id) }.isFailure)
        assertTrue(store.list("one").isEmpty())
        assertFalse(store.audio(r.id).exists())
    }
    @Test fun noOverwriteAndReservedCapacityAcrossConcurrentRecordings() {
        val store = VoiceRecordingStore(root)
        val r = store.begin("one"); store.append(r.id, byteArrayOf(1, 2))
        assertTrue(runCatching { store.begin("one") }.isFailure)
        store.begin("two"); store.begin("three")
        assertTrue(runCatching { store.begin("four") }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2), store.audio(r.id).readBytes())
    }
    @Test fun logoutInvalidatesOldWriterBeforeDeletingFiles() {
        val store = VoiceRecordingStore(root); val r = store.begin("one")
        store.append(r.id, byteArrayOf(1, 2)); store.clear()
        assertTrue(runCatching { store.append(r.id, byteArrayOf(3, 4)) }.isFailure)
        assertTrue(VoiceRecordingStore(root).list("one").isEmpty())
        assertFalse(store.audio(r.id).exists())
    }
    @Test fun durableTextHandoffDeletesAudioAndAllowsAnotherRecording() {
        val store = VoiceRecordingStore(root); val r = store.begin("one")
        store.append(r.id, byteArrayOf(1, 2)); store.seal(r.id, true, false)
        store.handoff(r.id, "final speech")
        assertFalse(store.audio(r.id).exists())
        assertEquals("final speech", VoiceRecordingStore(root).list("one").single().text)
        assertNotEquals(r.id, store.begin("one").id)
    }
    @Test fun cancellingRemovesOnlyOneRecordingAndCannotEscapeThePrivateDirectory() {
        val store = VoiceRecordingStore(root); val r = store.begin("one"); val other = store.begin("two")
        store.append(r.id, byteArrayOf(1, 2)); store.append(other.id, byteArrayOf(3, 4))
        store.remove(r.id)
        assertTrue(store.list("one").isEmpty()); assertEquals(1, store.list("two").size)
        assertTrue(runCatching { store.audio("../other") }.isFailure)
    }
    @Test fun anotherViewCannotConsumeOrHandOffALiveRecording() {
        val writer = VoiceRecordingStore(root); val r = writer.begin("live")
        writer.append(r.id, byteArrayOf(1, 2))
        val other = VoiceRecordingStore(root)
        assertTrue(runCatching { other.load(r.id) }.isFailure)
        assertTrue(runCatching { other.handoff(r.id, "prefix only") }.isFailure)
        writer.append(r.id, byteArrayOf(3, 4)); writer.seal(r.id, true, false)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), other.audio(r.id).readBytes())
    }
    @Test fun truncatedAudioCannotKeepItsCompleteFlag() {
        val store = VoiceRecordingStore(root); val r = store.begin("one")
        store.append(r.id, byteArrayOf(1, 2, 3, 4, 5, 6)); store.seal(r.id, true, false)
        store.audio(r.id).writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(store.load(r.id).complete)
        assertFalse(store.list("one").single().complete)
    }
    @Test fun emptyStoppedJournalDoesNotBlockANewRecordingFor24Hours() {
        val store = VoiceRecordingStore(root); val r = store.begin("one")
        store.seal(r.id, false, false)
        assertTrue(store.list("one").isEmpty())
        assertNotEquals(r.id, store.begin("one").id)
    }

}
