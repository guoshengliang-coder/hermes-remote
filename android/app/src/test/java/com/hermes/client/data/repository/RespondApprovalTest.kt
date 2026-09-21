package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.ui.chat.ApprovalChoice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RespondApprovalTest {
    private val client = mockk<HermesGatewayClient>(relaxed = true)
    private val repo = ChatRepository(client) // match the real ctor; add other relaxed deps if needed

    @Test fun once_sends_choice_once() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.ONCE)
        assertEquals("s1", params.captured["session_id"]!!.jsonPrimitive.content)
        assertEquals("once", params.captured["choice"]!!.jsonPrimitive.content)
    }

    @Test fun deny_sends_choice_deny() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.DENY)
        assertEquals("deny", params.captured["choice"]!!.jsonPrimitive.content)
    }

    /**
     * Upstream never read `approved` (f159e581 resolves by `choice` alone), and a newer Hermes
     * validates params against a contract that forbids unknown keys — `approved` alone turned the
     * whole call into a 4000.
     */
    @Test fun respond_sends_only_keys_the_upstream_contract_declares() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.SESSION)
        assertEquals(setOf("session_id", "choice"), params.captured.keys)
        assertNull(params.captured["approved"])
    }

    @Test fun a_server_request_approval_answers_that_request_by_id() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("request.answer", capture(params)) } returns
            kotlinx.serialization.json.Json.parseToJsonElement("""{"status":"ok"}""")
        assertEquals("ok", repo.respondApproval("s1", ApprovalChoice.ALWAYS, serverRequestId = "srq-0123456789ab"))
        assertEquals(JsonPrimitive("srq-0123456789ab"), params.captured["id"])
        assertEquals(mapOf("choice" to JsonPrimitive("always")), params.captured["result"])
        coVerify(exactly = 0) { client.call("approval.respond", any()) }
    }
}
