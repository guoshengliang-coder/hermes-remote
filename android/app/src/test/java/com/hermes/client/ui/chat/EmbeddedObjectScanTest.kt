package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedObjectScanTest {
    private fun organize(text: String) = ChatMessage("a", Role.ASSISTANT, text).organizedForDisplay()

    // HG-215: this cached transcript produced an actual input-dispatch ANR on API 37.
    // A generous wall-clock ceiling catches quadratic rescanning, not small timing differences.
    @Test(timeout = 3_000) fun unclosedBraceHistoryIsPreservedWithoutRescanningEverySuffix() {
        val raw = "Unclosed object probe\n\n" + "{".repeat(180_000) + "x"
        assertEquals(raw, organize(raw).text)
    }

    @Test(timeout = 3_000) fun unclosedJsonPrefixesDoNotRescanEverySuffix() {
        val raw = "Prose\n\n" + "{\"nested\":".repeat(20_000) + "x"
        assertEquals(raw, organize(raw).text)
    }

    @Test(timeout = 3_000) fun invalidBalancedNestingHasABoundedParseBudget() {
        val raw = "Prose\n\n" + "{\"nested\":".repeat(20_000) + "x" + "}".repeat(20_000)
        assertEquals(raw, organize(raw).text)
    }

    @Test fun malformedMultilineStringDoesNotHideALaterCompleteToolObject() {
        val result = organize("{\"broken\":\"unterminated\n{\"output\":\"result\"}\n\nanswer")
        assertEquals("result", result.tools.single().output)
        assertEquals("answer", result.text)
    }

    @Test fun innerCompletePayloadSurvivesAnUnclosedOuterObject() {
        val result = organize("prefix { broken {\"output\":\"result\"} suffix")
        assertEquals("result", result.tools.single().output)
    }

    @Test fun bracesAndEscapedQuotesInsideJsonStringsDoNotEndTheObject() {
        val result = organize("before\n\n{\"output\":\"a } { \\\"quoted\\\" value\"}\n\nafter")
        assertEquals("a } { \"quoted\" value", result.tools.single().output)
        assertEquals("after", result.text)
    }

    @Test fun ordinaryCodeObjectsAndAdjacentToolPayloadsKeepTheirMeaning() {
        val code = "code {\"key\":{\"ordinary\":1}}"
        assertEquals(code, organize(code).text)
        val result = organize("{\"output\":\"one\"} {\"output\":\"two\"}\n\nanswer")
        assertEquals(listOf("one", "two"), result.tools.map { it.output })
        assertEquals("answer", result.text)
    }
}
