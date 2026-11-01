package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessageDto
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.parseMessageContent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentFileHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val name = "协议书 report(1).pdf"
    private val remote = "/Users/test/uploads/e2d30d85-6ca8-4399-acd4-46b8c55abcde-$name"
    // A non-root workspace produces a relative alias that cannot be guessed by adding `/`.
    private val reference = "@file:`uploads/${remote.substringAfterLast('/')}`"
    private var owner: String? = "account:A#phone"

    private fun repository(rest: HermesRestApi): ChatFileRepository {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporary.root
        every { context.filesDir } returns temporary.root
        val accounts = mockk<com.hermes.client.data.auth.AccountSessionManager>()
        every { accounts.lifecycleCursorScope() } answers { owner }
        return ChatFileRepository(context, rest, accounts)
    }

    @Test fun real_history_mapping_and_cold_disk_reload_keep_original_name_and_local_bytes() = runTest {
        val rest = mockk<HermesRestApi>()
        rest.stubTranscript("session", "default", listOf(MessageDto(id = 1, role = "user", content = reference)))
        val files = repository(rest)
        val staged = temporary.newFile("send.pending").apply { writeText("%PDF-1.4 fixture") }
        val adopted = files.adoptSent(staged, remote, name, "mac")
        val sent = files.rememberSent(ChatFile("picked", name, "application/pdf", localPath = adopted, remotePath = remote),
            reference, "session", null, "mac")
        val disk = TranscriptStore(temporary.newFolder("transcripts"), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val sessions = SessionRepository(rest, this, transcripts = disk, files = files)
        repeat(2) {
            val restored = sessions.history("session", "default", "mac").single().files.single()
            assertEquals(name, restored.name)
            assertEquals(remote, restored.remotePath)
            assertEquals(sent.localPath, restored.localPath)
            assertEquals("%PDF-1.4 fixture", files.download(restored, "mac").readText())
        }
        runCurrent()
        // Both repositories are recreated: no in-memory alias/name survives this boundary.
        val cold = SessionRepository(rest, this, transcripts = disk, files = repository(rest))
        val restored = cold.diskHistory("session", "default", "mac")!!.single().files.single()
        assertEquals(name, restored.name)
        assertEquals(remote, restored.remotePath)
        assertEquals(sent.localPath, restored.localPath)
        assertEquals(name, cold.olderHistory("session", "default", "mac").messages.single().files.single().name)
        coVerify(exactly = 0) { rest.artifactMetadata(any(), any()) }
        coVerify(exactly = 0) { rest.downloadArtifact(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { rest.artifactOwnerKey(any()) }
    }

    @Test fun aliases_are_isolated_by_account_mac_profile_and_conversation_and_never_rewrite_assistant_files() = runTest {
        val rest = mockk<HermesRestApi>()
        val files = repository(rest)
        val staged = temporary.newFile("scope.pending").apply { writeText("bytes") }
        val path = files.adoptSent(staged, remote, name, "mac")
        files.rememberSent(ChatFile("sent", name, localPath = path, remotePath = remote), reference, "s1", "personal", "mac")
        val original = ChatMessage("history", Role.USER, "", files = parseMessageContent(reference).files)
        suspend fun restored(session: String = "s1", profile: String = "personal", device: String = "mac") =
            files.restoreSentFiles(listOf(original), session, profile, device).single().files.single()
        assertEquals(name, restored().name)
        assertNull(restored(session = "s2").localPath)
        assertNull(restored(profile = "work").localPath)
        assertNull(restored(device = "other-mac").localPath)
        owner = "account:B#phone"
        assertNull(restored().localPath)
        owner = null
        assertNull(restored().localPath)
        owner = "account:A#phone"
        val assistant = original.copy(role = Role.ASSISTANT)
        assertEquals(assistant, files.restoreSentFiles(listOf(assistant), "s1", "personal", "mac").single())
    }

    @Test fun evicted_bytes_keep_the_recorded_absolute_reference_and_never_claim_downloaded() = runTest {
        val rest = mockk<HermesRestApi>()
        val files = repository(rest)
        val staged = temporary.newFile("evict.pending").apply { writeText("fixture") }
        val path = files.adoptSent(staged, remote, name, "mac")
        val sent = files.rememberSent(ChatFile("sent", name, localPath = path, remotePath = remote), reference, "s1", null, "mac")
        File(sent.localPath!!).delete()
        val message = ChatMessage("h", Role.USER, "", files = parseMessageContent(reference).files)
        val restored = files.restoreSentFiles(listOf(message), "s1", null, "mac").single().files.single()
        assertEquals(name, restored.name)
        assertEquals(remote, restored.remotePath)
        assertNull(restored.localPath)
    }

    @Test fun old_root_workspace_cache_is_recovered_only_when_its_full_path_hash_exists() = runTest {
        val rest = mockk<HermesRestApi>()
        val files = repository(rest)
        val staged = temporary.newFile("legacy.pending").apply { writeText("fixture") }
        val path = files.adoptSent(staged, remote, remote.substringAfterLast('/'), "mac")
        val original = ChatMessage("h", Role.USER, "", files = parseMessageContent("@file:`${remote.drop(1)}`").files)
        val restored = files.restoreSentFiles(listOf(original), "s1", null, "mac").single().files.single()
        assertEquals(name, restored.name)
        assertEquals(remote, restored.remotePath)
        assertEquals(path, restored.localPath)
        val absent = original.copy(files = parseMessageContent("@file:unknown/report.pdf").files)
        assertEquals(absent, files.restoreSentFiles(listOf(absent), "s1", null, "mac").single())
    }
}
