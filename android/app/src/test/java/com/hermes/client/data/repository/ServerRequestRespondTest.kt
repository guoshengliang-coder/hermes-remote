package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.ui.chat.ApprovalChoice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** How an answer travels on each Hermes protocol, and what it reports back. */
class ServerRequestRespondTest {
    private val client = mockk<HermesGatewayClient>(relaxed = true)
    private val repo = ChatRepository(client)

    private fun status(value: String) = Json.parseToJsonElement("""{"status":"$value"}""")

    @Test fun a_batch_answer_locks_one_question_with_clarify_lock() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("clarify.lock", capture(params)) } returns
            Json.parseToJsonElement("""{"status":"ok","remaining":["q2"]}""")
        val status = repo.respondClarify("s1", "srq-aa", "PostgreSQL", questionId = "q1", serverRequest = true)
        assertEquals("ok", status)
        // Exactly the contract's keys: upstream answers 4000 for anything it does not declare.
        assertEquals(
            mapOf(
                "request_id" to JsonPrimitive("srq-aa"),
                "question_id" to JsonPrimitive("q1"),
                "answer" to JsonPrimitive("PostgreSQL"),
            ),
            params.captured,
        )
    }

    @Test fun a_lock_on_an_ended_request_reports_expired() = runTest {
        coEvery { client.call("clarify.lock", any()) } returns status("expired")
        assertEquals("expired", repo.respondClarify("s1", "srq-aa", "x", questionId = "q1", serverRequest = true))
    }

    @Test fun a_single_answer_goes_through_request_answer() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("request.answer", capture(params)) } returns status("ok")
        assertEquals("ok", repo.respondClarify("s1", "srq-bb", "蓝绿切换", serverRequest = true))
        assertEquals(
            buildJsonObject {
                put("id", "srq-bb")
                put("result", buildJsonObject { put("answer", "蓝绿切换") })
            },
            params.captured,
        )
    }

    /**
     * Regression: a bare response frame for a request answered elsewhere (no request.cancel) or
     * timed out was dropped silently upstream, and the phone reported "ok". HR-CLARIFY-001 must fire.
     */
    @Test fun a_single_answer_to_a_request_no_longer_open_reports_expired() = runTest {
        coEvery { client.call("request.answer", any()) } returns status("expired")
        assertEquals("expired", repo.respondClarify("s1", "srq-bb", "a", serverRequest = true))
    }

    @Test fun a_skip_or_cancel_all_is_an_empty_answer_and_reports_expiry_too() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("request.answer", capture(params)) } returns status("expired")
        assertEquals("expired", repo.respondClarify("s1", "srq-cc", "", serverRequest = true))
        assertEquals(buildJsonObject { put("answer", "") }, params.captured["result"])
    }

    @Test fun a_server_request_approval_goes_through_request_answer_and_reports_expiry() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("request.answer", capture(params)) } returns status("expired")
        assertEquals("expired", repo.respondApproval("s1", ApprovalChoice.SESSION, serverRequestId = "srq-dd"))
        assertEquals(
            buildJsonObject {
                put("id", "srq-dd")
                put("result", buildJsonObject { put("choice", "session") })
            },
            params.captured,
        )
        coVerify(exactly = 0) { client.call("approval.respond", any()) }
    }

    @Test fun an_event_clarify_keeps_using_clarify_respond() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("clarify.respond", capture(params)) } returns status("ok")
        assertEquals("ok", repo.respondClarify("s1", "clr-1", "a", questionId = "q0"))
        assertEquals("clr-1", (params.captured["request_id"] as JsonPrimitive).content)
        assertEquals("q0", (params.captured["question_id"] as JsonPrimitive).content)
        coVerify(exactly = 0) { client.call("request.answer", any()) }
    }
}
