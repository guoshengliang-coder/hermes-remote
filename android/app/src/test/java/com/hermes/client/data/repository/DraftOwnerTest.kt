package com.hermes.client.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftOwnerTest {
    @Test fun sameConversationAndLateSavesRemainIsolatedAcrossRelayAndAccountChanges() = runBlocking {
        val store = DraftStore(ApplicationProvider.getApplicationContext<Context>())
        store.clearAll()
        val accountA = store.forOwner("relay-a/account-a")
        accountA.save("mac/profile/session", "A 的草稿", 1L)
        val accountB = store.forOwner("relay-a/account-b")
        assertNull(accountB.read("mac/profile/session"))
        assertTrue(store.tokens.first().isEmpty())
        accountB.save("mac/profile/session", "B 的草稿", 2L)
        accountA.save("mac/profile/session", "A 离开后的延迟保存", 3L)
        assertEquals("B 的草稿", accountB.read("mac/profile/session"))
        assertEquals(setOf("mac/profile/session"), store.tokens.first())
        val anotherRelay = store.forOwner("relay-b/account-b")
        assertNull(anotherRelay.read("mac/profile/session"))
        assertTrue(store.tokens.first().isEmpty())
        assertEquals("A 离开后的延迟保存", accountA.read("mac/profile/session"))
        accountA.clear("mac/profile/session")
        assertNull(accountA.read("mac/profile/session"))
        assertEquals("B 的草稿", accountB.read("mac/profile/session"))
        store.clearAll()
    }

    @Test fun upgradeClaimsOnlyUnownedDraftsWithoutDroppingTheirText() {
        val old = decodeDrafts("""[{"v":1,"token":"p/s","text":"升级前的草稿"}]""").single()
        assertEquals(DRAFT_RECORD_VERSION, old.v)
        val other = DraftRecord(token = "p/s", text = "其他账号", owner = "other")
        val claimed = claimLegacyDrafts(listOf(old, other), "current")
        assertEquals("current", claimed.first().owner)
        assertEquals("升级前的草稿", claimed.first().text)
        assertEquals(other, claimed.last())
        assertEquals(claimed, claimLegacyDrafts(claimed, "next"))
        assertEquals(claimed, decodeDrafts(encodeDrafts(claimed)))
    }
}
