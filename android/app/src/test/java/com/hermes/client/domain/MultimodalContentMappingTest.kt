package com.hermes.client.domain

import com.hermes.client.data.network.MessagesDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A turn sent with attachments comes back from Hermes with `content` as an ARRAY of content blocks
 * rather than a string. The DTO modelled only the string, and kotlinx does not skip a row it cannot
 * read — it abandons the document. So one such turn did not lose that turn, it made the whole
 * transcript unreadable, and the chat screen printed 无法加载历史消息（HR-RPC-001） for a
 * conversation whose other fifty rows were fine (HG-64):
 *
 *   history(20260918_204034_16def7) failed: Unexpected JSON token at offset 13298:
 *   Expected beginning of the string, but got [ at path: $.messages[4].content
 *
 * The same failure silently disabled the finished-run self-heal, which reads the transcript before
 * retiring a stale phase, so those conversations also kept showing 正在运行中 (HG-61).
 */
class MultimodalContentMappingTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Shape taken from the row named in HG-65's log, with the sibling rows that used to die with it.
    private val payload = """
        {"messages":[
          {"id":1,"role":"user","content":"第一句，普通字符串"},
          {"id":2,"role":"assistant","content":null,"tool_calls":null},
          {"id":3,"role":"user","content":[
            {"type":"text","text":"体检报告，归档"},
            {"type":"image_url","image_url":{"url":"/Users/bs/Documents/report.png"}}
          ]},
          {"id":4,"role":"assistant","content":"最终回答"}
        ]}
    """.trimIndent()

    @Test fun oneContentBlockRowNoLongerTakesTheWholeTranscriptDownWithIt() {
        val messages = json.decodeFromString(MessagesDto.serializer(), payload).messages

        assertEquals(4, messages.size)
        assertEquals("第一句，普通字符串", messages[0].content)
        assertNull(messages[1].content)
        assertEquals("最终回答", messages[3].content)
    }

    @Test fun textBlocksAreJoinedAndAPathBearingBlockKeepsTheGrammarTheRendererKnows() {
        val messages = json.decodeFromString(MessagesDto.serializer(), payload).messages

        assertEquals("体检报告，归档\n@image:/Users/bs/Documents/report.png", messages[2].content)
    }

    /**
     * Upstream owns this shape and we cannot version-negotiate it, so an unfamiliar block is skipped
     * rather than thrown on — the cost of guessing wrong has to stay "one block missing", never
     * "the transcript is gone".
     */
    @Test fun unknownBlockTypesAreSkippedRatherThanThrownOn() {
        val exotic = """
            {"messages":[{"id":9,"role":"user","content":[
              {"type":"text","text":"看这个"},
              {"type":"something_we_have_never_seen","payload":{"nested":true}},
              {"type":"image","source":{"kind":"base64","data":"AAAA"}},
              {"type":"text","text":"谢谢"}
            ]}]}
        """.trimIndent()

        val messages = json.decodeFromString(MessagesDto.serializer(), exotic).messages

        assertEquals("看这个\n谢谢", messages[0].content)
    }

    @Test fun anAttachmentOnlyTurnFlattensToNothingRatherThanAnEmptyStringOfPunctuation() {
        val captionless = """
            {"messages":[{"id":10,"role":"user","content":[
              {"type":"image","data":"AAAA"}
            ]}]}
        """.trimIndent()

        val messages = json.decodeFromString(MessagesDto.serializer(), captionless).messages

        assertNull(messages[0].content)
    }

    /**
     * The configuration the app actually parses with (`di/AppModule.provideJson`). `coerceInputValues`
     * in particular changes how a defaulted property behaves, and a tolerance that only holds under
     * the test's own Json would be worth nothing on a phone.
     */
    @Test fun theProductionJsonConfigurationReadsItTheSameWay() {
        val production = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        val messages = production.decodeFromString(MessagesDto.serializer(), payload).messages

        assertEquals(4, messages.size)
        assertEquals("体检报告，归档\n@image:/Users/bs/Documents/report.png", messages[2].content)
        assertNull(messages[1].content)
    }

    @Test fun flattenedRowsStillMapIntoTheDomainTheWayStringRowsDo() {
        val domain = json.decodeFromString(MessagesDto.serializer(), payload).messages.map { it.toDomain() }

        assertEquals(4, domain.size)
        assertTrue(domain[2].text.contains("体检报告，归档"))
    }
}
