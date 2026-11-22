package com.hermes.client.ui.chat

import com.hermes.client.share.PendingShareStore
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FirstTurnRouteTest {
    @Test fun onlyTheCreatedConversationConsumesItsFirstTurnOnce() {
        val store = PendingShareStore()
        val turn = FirstTurn("草稿", listOf(PendingAttachment("a", byteArrayOf(1), "image/png")))
        store.putTurn("account", "mac", "work", "s", turn)
        assertNull(store.takeTurn("other", "mac", "work", "s"))
        assertNull(store.takeTurn("account", "other", "work", "s"))
        assertNull(store.takeTurn("account", "mac", "other", "s"))
        assertNull(store.takeTurn("account", "mac", "work", "other"))
        assertSame(turn, store.takeTurn("account", "mac", "work", "s"))
        assertNull(store.takeTurn("account", "mac", "work", "s"))
    }
}
