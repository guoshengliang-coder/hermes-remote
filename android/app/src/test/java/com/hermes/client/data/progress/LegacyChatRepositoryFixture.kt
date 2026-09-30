package com.hermes.client.data.progress

import com.hermes.client.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk

/** Existing store tests exercise the pre-active_list compatibility path by default. */
internal fun legacyChatRepositoryFixture(): ChatRepository = mockk<ChatRepository>(relaxed = true).also { chat ->
    // Generic relaxed List mocks are not authoritative empty server snapshots.
    coEvery { chat.listProcesses(any()) } returns emptyList()
    coEvery { chat.listSubagents(any()) } returns emptyList()
    coEvery { chat.activeSessions(any()) } throws UnsupportedOperationException("old Hermes")
}
