package com.hermes.client.ui.chat

import com.hermes.client.domain.*
import org.junit.Assert.*
import org.junit.Test

class ConversationArtifactsTest {
    @Test fun flattenedToolPayloadIsNotAResultAfterHistoryParsing() {
        val message = com.hermes.client.data.network.MessageDto(id = 1, role = "assistant",
            content = "检查结果\n<untrusted_tool_result source=\"run\">\nMEDIA:/tmp/process.pdf\n</untrusted_tool_result>\n完成").toDomain().organizedForDisplay()
        assertTrue(conversationArtifacts(listOf(message)).isEmpty())
    }
    @Test fun plainMessagesKeepExistingReconciliationIdentity() {
        val plain = ChatMessage("a", Role.ASSISTANT, "ordinary reply")
        assertEquals(plain, plain.organizedForDisplay())
    }
    private fun file(id: String, path: String) = ChatMessage(id, Role.ASSISTANT, "",
        files = listOf(ChatFile(path, path.substringAfterLast('/'), remotePath = path)))

    @Test fun excludesInputsProcessMediaAndStreaming() {
        val input = file("input", "/tmp/input.pdf").copy(role = Role.USER)
        val process = ChatMessage("tool", Role.ASSISTANT, "", tools = listOf(ToolCall("t", "run", ToolStatus.DONE, "MEDIA:/tmp/process.html")))
            .organizedForDisplay().organizedForDisplay()
        assertTrue(process.files.isNotEmpty())
        val artifacts = conversationArtifacts(listOf(input, process, file("ref", "/tmp/input.pdf"),
            file("result", "/tmp/result.md"), file("partial", "/tmp/partial.png").copy(isStreaming = true),
            file("note", "/tmp/note.pdf").copy(role = Role.SYSTEM)))
        assertEquals(listOf("/tmp/result.md"), artifacts.map { it.reference })
    }

    @Test fun samePathUsesLatestDeliveryAndSameNameGetsCollisionSafeLabel() {
        val artifacts = conversationArtifacts(listOf(file("old", "/a/report.md"), file("other", "/b/report.md"),
            file("reserved", "/c/report (2).md"), file("latest", "/a/report.md").copy(timestamp = 200)))
        assertEquals(listOf("latest", "reserved", "other"), artifacts.map { it.messageId })
        assertEquals(listOf("report.md", "report (2).md", "report (3).md"), artifacts.map { it.displayName })
        assertEquals("report.md", artifacts.last().file?.name)
        assertNull(artifacts.last().timestamp)
    }

    @Test fun directDeliveryAfterToolOutputOfSameFileIsRetainedWhenTurnsMerge() {
        val process = ChatMessage("early", Role.ASSISTANT, "", tools = listOf(ToolCall("t", "run", ToolStatus.DONE, "MEDIA:/tmp/result.md")))
        val delivered = ChatMessage("last", Role.ASSISTANT, "MEDIA:/tmp/result.md")
        val merged = listOf(process, delivered).organizedConversationTurns()
        assertEquals(1, merged.size)
        val artifact = conversationArtifacts(merged).single()
        assertEquals("/tmp/result.md", artifact.reference)
        assertEquals("early", artifact.messageId)
    }

    @Test fun supportsGeneratedImagesAndLocalMarkdownFilesButNotQuotedCodeExamples() {
        val delivered = ChatMessage("a", Role.ASSISTANT,
            "[网页](/tmp/page.html)\n\n![图](/tmp/chart.png)\n\n```md\n[示例](/tmp/example.pdf)\n```").organizedForDisplay()
        assertEquals(setOf("/tmp/page.html", "/tmp/chart.png"), conversationArtifacts(listOf(delivered)).map { it.reference }.toSet())
    }
}
