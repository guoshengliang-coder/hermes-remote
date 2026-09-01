package com.hermes.client.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarifyParseTest {
    private fun payload(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test fun openEndedSingleQuestion() {
        val req = parseClarifyRequest(payload("""{"request_id":"r1","question":"部署到哪个区域？"}"""))
        assertEquals("r1", req.requestId)
        assertFalse(req.isBatch)
        val q = req.currentQuestion!!
        assertEquals("部署到哪个区域？", q.question)
        assertTrue(q.choices.isEmpty())
        assertFalse(q.multiSelect)
    }

    @Test fun singleQuestionWithChoices() {
        val req = parseClarifyRequest(
            payload("""{"request_id":"r2","question":"发布方式？","choices":["滚动 (Recommended)","蓝绿",null,""]}"""),
        )
        val q = req.currentQuestion!!
        assertEquals(listOf("滚动 (Recommended)", "蓝绿"), q.choices) // null/blank dropped
        assertFalse(q.multiSelect)
    }

    @Test fun multiSelectFlagParses() {
        val req = parseClarifyRequest(
            payload("""{"request_id":"r3","question":"备份哪些？","choices":["A","B"],"multi_select":true}"""),
        )
        assertTrue(req.currentQuestion!!.multiSelect)
    }

    @Test fun batchQuestionsWinOverSingleFields() {
        val req = parseClarifyRequest(
            payload(
                """{"request_id":"r4","question":"ignored","questions":[
                  {"qid":"q0","question":"数据库？","choices":["PG","MySQL"]},
                  {"qid":"q1","question":"存储？","multi_select":true,"choices":["MinIO","OSS"]},
                  {"qid":"q2","question":"备注"}
                ]}""",
            ),
        )
        assertTrue(req.isBatch)
        assertEquals(3, req.questions.size)
        assertEquals("q0", req.currentQuestion!!.qid)
        assertTrue(req.questions[1].multiSelect)
        assertTrue(req.questions[2].choices.isEmpty())
    }

    @Test fun lockedAnswersAdvanceCurrentQuestion() {
        val req = parseClarifyRequest(
            payload(
                """{"request_id":"r5","questions":[
                  {"qid":"q0","question":"一"},{"qid":"q1","question":"二"}
                ],"answers":{"q0":"甲"}}""",
            ),
        )
        assertEquals("q1", req.currentQuestion!!.qid)
        assertEquals("甲", req.lockedAnswers["q0"])
        // answering the last question empties the queue
        val done = req.copy(lockedAnswers = req.lockedAnswers + ("q1" to "乙"))
        assertNull(done.currentQuestion)
    }

    @Test fun malformedBatchEntriesAreSkipped() {
        val req = parseClarifyRequest(
            payload("""{"request_id":"r6","questions":[{"qid":"q0"},{"qid":"q1","question":"有效"}]}"""),
        )
        assertEquals(1, req.questions.size)
        assertEquals("有效", req.currentQuestion!!.question)
    }
}
