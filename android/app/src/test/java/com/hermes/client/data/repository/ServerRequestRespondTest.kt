package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.ServerEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a clarify answer and a resume's open requests travel on each Hermes protocol. */
class ServerRequestRespondTest {
    private val client = mockk<HermesGatewayClient>(relaxed = true)
    private val repo = ChatRepository(client)

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
        coVerify(exactly = 0) { client.respondToServerRequest(any(), any()) }
    }

    @Test fun a_lock_on_an_ended_request_reports_expired() = runTest {
        coEvery { client.call("clarify.lock", any()) } returns Json.parseToJsonElement("""{"status":"expired"}""")
        assertEquals("expired", repo.respondClarify("s1", "srq-aa", "x", questionId = "q1", serverRequest = true))
    }

    @Test fun a_single_answer_is_the_response_frame_answer() = runTest {
        val result = slot<JsonObject>()
        coEvery { client.respondToServerRequest("srq-bb", capture(result)) } returns Unit
        assertEquals("ok", repo.respondClarify("s1", "srq-bb", "蓝绿切换", serverRequest = true))
        assertEquals(mapOf("answer" to JsonPrimitive("蓝绿切换")), result.captured)
        coVerify(exactly = 0) { client.call(any(), any()) }
    }

    @Test fun a_skip_or_cancel_all_is_an_empty_answer_frame() = runTest {
        val result = slot<JsonObject>()
        coEvery { client.respondToServerRequest("srq-cc", capture(result)) } returns Unit
        repo.respondClarify("s1", "srq-cc", "", serverRequest = true)
        assertEquals(mapOf("answer" to JsonPrimitive("")), result.captured)
    }

    @Test fun an_event_clarify_keeps_using_clarify_respond() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("clarify.respond", capture(params)) } returns
            Json.parseToJsonElement("""{"status":"ok"}""")
        assertEquals("ok", repo.respondClarify("s1", "clr-1", "a", questionId = "q0"))
        assertEquals("clr-1", (params.captured["request_id"] as JsonPrimitive).content)
        assertEquals("q0", (params.captured["question_id"] as JsonPrimitive).content)
        coVerify(exactly = 0) { client.respondToServerRequest(any(), any()) }
    }

    @Test fun resume_redelivers_open_requests_including_locked_batch_answers() = runTest {
        val redelivered = slot<List<ServerEvent>>()
        every { client.redeliver(capture(redelivered)) } returns Unit
        coEvery { client.call("session.resume", any()) } returns Json.parseToJsonElement(
            """
            {"session_id":"live-1","open_requests":[
              {"id":"srq-111111111111","method":"approval","params":{"session_id":"live-1","request_id":"q-1","command":"rm -rf build","choices":["once","deny"]}},
              {"id":"srq-222222222222","method":"clarify","params":{"session_id":"live-1","questions":[{"qid":"q0","question":"A?"},{"qid":"q1","question":"B?"}],"answers":{"q0":"yes"}}}
            ]}
            """.trimIndent(),
        )
        assertEquals("live-1", repo.resume("stored-1"))
        val events = redelivered.captured
        assertEquals(listOf("approval.request", "clarify.request"), events.map { it.type })
        assertTrue(events.all { it.sessionId == "live-1" })
        assertEquals("srq-222222222222", (events[1].payload["request_id"] as JsonPrimitive).content)
        assertEquals("yes", events[1].payload["answers"]!!.jsonObject["q0"].let { (it as JsonPrimitive).content })
    }

    @Test fun a_resume_without_open_requests_redelivers_nothing() = runTest {
        coEvery { client.call("session.resume", any()) } returns Json.parseToJsonElement("""{"session_id":"live-1"}""")
        repo.resume("stored-1")
        verify(exactly = 0) { client.redeliver(any()) }
    }
}
