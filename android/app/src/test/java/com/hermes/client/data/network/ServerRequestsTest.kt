package com.hermes.client.data.network

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.phaseAfterWithdrawal
import com.hermes.client.ui.chat.ChatUiState
import com.hermes.client.ui.chat.reduce
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both ways Hermes asks the phone a question, parsed and folded onto the same cards. Frame shapes
 * are copied from upstream: `tui_gateway/server_requests.py` (`ServerRequest.frame`, `snapshot`,
 * `request.cancel`) and `tui_gateway/contracts/server_requests.py` for the new protocol, the
 * `approval.request` / `clarify.request` events of f159e581 for the old one.
 */
class ServerRequestsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(line: String): RpcServerRequest = parseInbound(json, line) as RpcServerRequest

    private fun event(line: String): ServerEvent = (parseInbound(json, line) as RpcEvent).event

    private fun fold(state: ChatUiState, line: String): ChatUiState {
        val msg = request(line)
        return state.reduce(ServerRequests.toEvent(msg.id, msg.method, msg.params)!!)
    }

    private val approvalFrame = """{"jsonrpc":"2.0","id":"srq-0123456789ab","method":"approval","params":{"session_id":"live-1","request_id":"q-7","command":"rm -rf build","description":"delete build output","choices":["once","session","always","deny"],"allow_permanent":true}}"""
    private val singleClarifyFrame = """{"jsonrpc":"2.0","id":"srq-aaaaaaaaaaaa","method":"clarify","params":{"session_id":"live-1","question":"Which region?","choices":["hk","sg"]}}"""
    private val batchClarifyFrame = """{"jsonrpc":"2.0","id":"srq-bbbbbbbbbbbb","method":"clarify","params":{"session_id":"live-1","questions":[{"qid":"q0","question":"DB?","choices":["pg","mysql"],"multi_select":false},{"qid":"q1","question":"Notes?","choices":null,"multi_select":false}]}}"""

    @Test fun a_string_id_request_is_a_server_request_not_a_crash() {
        val msg = request(approvalFrame)
        assertEquals("srq-0123456789ab", msg.id.content)
        assertTrue("the id must be echoed as the string it arrived as", msg.id.isString)
        assertEquals("approval", msg.method)
        assertEquals("live-1", (msg.params["session_id"] as JsonPrimitive).content)
    }

    @Test fun a_frame_that_is_neither_event_request_nor_response_is_dropped_not_thrown() {
        assertTrue(parseInbound(json, """{"jsonrpc":"2.0","method":"something.new","params":{}}""") is RpcUnreadable)
        assertTrue(parseInbound(json, "[1,2]") is RpcUnreadable)
        // A response whose id this client could never have minted is harmless, not fatal.
        assertTrue(parseInbound(json, """{"jsonrpc":"2.0","id":"x","result":{}}""") is RpcResult)
    }

    @Test fun response_frames_echo_the_id_and_carry_result_or_error() {
        val ok = json.parseToJsonElement(
            encodeServerResponse(json, JsonPrimitive("srq-1"), JsonObject(mapOf("choice" to JsonPrimitive("once")))),
        ).jsonObject
        assertEquals(setOf("jsonrpc", "id", "result"), ok.keys)
        assertEquals("srq-1", (ok["id"] as JsonPrimitive).content)
        assertTrue((ok["id"] as JsonPrimitive).isString)
        assertFalse("a response has no method, or upstream would dispatch it as a call", "method" in ok)
        val err = json.parseToJsonElement(encodeServerError(json, JsonPrimitive("srq-2"), -32601, "no handler")).jsonObject
        assertEquals("-32601", err["error"]!!.jsonObject["code"].toString())
        assertFalse("method" in err)
    }

    @Test fun an_approval_request_becomes_the_approval_card_marked_with_its_request_id() {
        val state = fold(ChatUiState(), approvalFrame)
        val card = state.pendingApproval!!
        assertEquals("rm -rf build", card.command)
        assertEquals("delete build output", card.description)
        assertTrue(card.allowPermanent)
        assertEquals("srq-0123456789ab", card.serverRequestId)
    }

    @Test fun the_mapped_event_resolves_to_the_same_session_as_an_event_would() {
        val msg = request(approvalFrame)
        val mapped = ServerRequests.toEvent(msg.id, msg.method, msg.params)!!
        assertEquals("approval.request", mapped.type)
        assertEquals("live-1", mapped.sessionId)
    }

    @Test fun a_single_clarify_uses_the_frame_id_as_its_request_id() {
        val card = fold(ChatUiState(), singleClarifyFrame).pendingClarify!!
        assertEquals("srq-aaaaaaaaaaaa", card.requestId)
        assertTrue(card.serverRequest)
        assertEquals(1, card.questions.size)
        assertEquals("", card.questions.single().qid)
        assertEquals(listOf("hk", "sg"), card.questions.single().choices)
    }

    @Test fun a_batch_clarify_keeps_its_question_ids() {
        val card = fold(ChatUiState(), batchClarifyFrame).pendingClarify!!
        assertEquals(listOf("q0", "q1"), card.questions.map { it.qid })
        assertEquals("q0", card.currentQuestion?.qid)
        assertTrue(card.serverRequest)
    }

    @Test fun unknown_methods_have_no_card() {
        val sudo = request("""{"jsonrpc":"2.0","id":"srq-cccccccccccc","method":"sudo","params":{"session_id":"live-1","command":"apt"}}""")
        assertNull(ServerRequests.toEvent(sudo.id, sudo.method, sudo.params))
        assertFalse(sudo.method in ServerRequests.HANDLED)
    }

    @Test fun old_protocol_events_stay_unmarked() {
        val approval = event("""{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"live-1","payload":{"command":"ls","allow_permanent":true}}}""")
        val clarify = event("""{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"live-1","payload":{"request_id":"ab12cd34","question":"Q?","choices":["a"]}}}""")
        val state = ChatUiState().reduce(approval).reduce(clarify)
        assertNull(state.pendingApproval!!.serverRequestId)
        assertFalse(state.pendingClarify!!.serverRequest)
        assertEquals("ab12cd34", state.pendingClarify!!.requestId)
    }

    @Test fun request_cancel_tears_down_only_the_card_it_names() {
        val both = fold(fold(ChatUiState(), approvalFrame), batchClarifyFrame)
        val cancelApproval = event("""{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"live-1","payload":{"id":"srq-0123456789ab","method":"approval","reason":"timeout"}}}""")
        val afterApproval = both.reduce(cancelApproval)
        assertNull(afterApproval.pendingApproval)
        assertNotNull(afterApproval.pendingClarify)
        val cancelOther = event("""{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"live-1","payload":{"id":"srq-ffffffffffff","method":"sudo","reason":"interrupted"}}}""")
        assertEquals(afterApproval, afterApproval.reduce(cancelOther))
        val cancelClarify = event("""{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"live-1","payload":{"id":"srq-bbbbbbbbbbbb","method":"clarify","reason":"resolved"}}}""")
        assertNull(afterApproval.reduce(cancelClarify).pendingClarify)
    }

    @Test fun request_cancel_never_touches_an_old_protocol_card_with_a_colliding_id() {
        val clarify = event("""{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"live-1","payload":{"request_id":"ab12cd34","question":"Q?"}}}""")
        val cancel = event("""{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"live-1","payload":{"id":"ab12cd34","method":"clarify","reason":"timeout"}}}""")
        assertNotNull(ChatUiState().reduce(clarify).reduce(cancel).pendingClarify)
    }

    @Test fun open_requests_restore_cards_and_locked_batch_answers() {
        val resume = json.parseToJsonElement(
            """{"session_id":"live-1","open_requests":[
                {"id":"srq-0123456789ab","method":"approval","params":{"session_id":"live-1","request_id":"q-7","command":"rm -rf build","choices":["once","deny"]}},
                {"id":"srq-bbbbbbbbbbbb","method":"clarify","params":{"session_id":"live-1","questions":[{"qid":"q0","question":"DB?"},{"qid":"q1","question":"Notes?"}],"answers":{"q0":"pg"}}},
                {"id":"srq-dddddddddddd","method":"secret","params":{"session_id":"live-1","env_var":"X","prompt":"?"}}
            ]}""",
        ).jsonObject
        val events = ServerRequests.openRequestEvents(resume)
        assertEquals("a method with no card is not re-delivered", 2, events.size)
        val state = events.fold(ChatUiState()) { acc, e -> acc.reduce(e) }
        assertEquals("srq-0123456789ab", state.pendingApproval!!.serverRequestId)
        val clarify = state.pendingClarify!!
        assertEquals(mapOf("q0" to "pg"), clarify.lockedAnswers)
        assertEquals("q1", clarify.currentQuestion?.qid)
    }

    @Test fun an_old_resume_answer_has_no_open_requests() {
        assertTrue(ServerRequests.openRequestEvents(json.parseToJsonElement("""{"session_id":"x"}""").jsonObject).isEmpty())
        assertTrue(ServerRequests.openRequestEvents(null).isEmpty())
    }

    @Test fun a_withdrawn_last_card_stops_waiting_but_another_card_keeps_the_wait() {
        assertEquals(SessionRunPhase.THINKING, phaseAfterWithdrawal(SessionRunPhase.WAITING_APPROVAL, ChatUiState()))
        assertEquals(SessionRunPhase.THINKING, phaseAfterWithdrawal(SessionRunPhase.WAITING_CLARIFICATION, ChatUiState()))
        val stillAsking = fold(ChatUiState(), singleClarifyFrame)
        assertEquals(
            SessionRunPhase.WAITING_CLARIFICATION,
            phaseAfterWithdrawal(SessionRunPhase.WAITING_APPROVAL, stillAsking),
        )
        assertEquals(SessionRunPhase.IDLE, phaseAfterWithdrawal(SessionRunPhase.IDLE, ChatUiState()))
    }
}
