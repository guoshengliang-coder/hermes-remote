package com.hermes.client.data.repository

import com.hermes.client.data.auth.AccountRoutingContext
import com.hermes.client.data.auth.AccountSessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftDefaultModelStoreTest {
    @Test fun transient_failure_retries_and_same_owner_reuses_the_result() = runTest {
        val config = mockk<ConfigRepository>()
        val account = mockk<AccountSessionManager>()
        every { account.session } returns MutableStateFlow(null)
        every { account.transportRouteEpoch } returns MutableStateFlow(0L)
        every { account.routingContext() } returns AccountRoutingContext("account", "mac")
        var attempts = 0
        coEvery { config.getDraftConfig("work", any()) } coAnswers {
            if (++attempts == 1) throw IOException("temporary")
            buildJsonObject { put("model", "model-a") }
        }
        val store = DraftDefaultModelStore(config, account, mockk(), backgroundScope) { testScheduler.currentTime }

        assertEquals("model-a", store.read("work"))
        assertEquals("model-a", store.read("work"))
        coVerify(exactly = 2) { config.getDraftConfig("work", any()) }

        every { account.routingContext() } returns AccountRoutingContext("account", "another-mac")
        coEvery { config.getDraftConfig("work", any()) } returns buildJsonObject { put("model", "model-b") }
        assertEquals("model-b", store.read("work"))
    }

    @Test fun a_pending_role_does_not_block_other_roles_and_deduplicates_reads() = runTest {
        val config = mockk<ConfigRepository>()
        val account = mockk<AccountSessionManager>()
        every { account.session } returns MutableStateFlow(null)
        every { account.routingContext() } returns AccountRoutingContext("account", "mac")
        val slowConfig = kotlinx.coroutines.CompletableDeferred<kotlinx.serialization.json.JsonObject>()
        coEvery { config.getDraftConfig("slow", any()) } coAnswers { slowConfig.await() }
        coEvery { config.getDraftConfig("default", any()) } returns buildJsonObject { put("model", "default-model") }
        val store = DraftDefaultModelStore(config, account, mockk(), backgroundScope) { testScheduler.currentTime }
        val first = async { store.read("slow") }
        val second = async { store.read("slow", force = true) }

        assertEquals("default-model", store.read("default"))
        org.junit.Assert.assertFalse(first.isCompleted)
        slowConfig.complete(buildJsonObject { put("model", "slow-model") })
        assertEquals("slow-model", first.await())
        assertEquals("slow-model", second.await())
        coVerify(exactly = 1) { config.getDraftConfig("slow", any()) }
    }
}
