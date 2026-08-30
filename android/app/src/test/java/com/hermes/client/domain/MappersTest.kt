package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.SessionDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    @Test fun session_dto_maps_to_domain() {
        val s = SessionDto(sessionId = "s1", title = "Hi", model = "opus", messageCount = 2).toDomain()
        assertEquals("s1", s.id)
        assertEquals("Hi", s.title)
        assertEquals(2, s.messageCount)
    }

    @Test fun message_dto_maps_role_and_text() {
        val m = MessageDto(id = 1, role = "assistant", content = "hello").toDomain()
        assertEquals(Role.ASSISTANT, m.role)
        assertEquals("hello", m.text)
        assertEquals(false, m.isStreaming)
    }

    @Test fun image_directives_become_hidden_image_references() {
        val m = MessageDto(
            id = 2,
            role = "user",
            content = "请看这张图\n@image:/Users/me/photo one.png\n@image:\"/tmp/second.jpg\"",
        ).toDomain()

        assertEquals("请看这张图", m.text)
        assertEquals(2, m.images.size)
        assertEquals("/Users/me/photo one.png", m.images[0].remotePath)
        assertEquals("/tmp/second.jpg", m.images[1].remotePath)
    }

    @Test fun attachment_placeholder_is_not_rendered_when_image_exists() {
        val parsed = parseMessageContent(
            "[User attached image: screenshot.png]\n@image:`/tmp/screenshot.png`",
        )
        assertEquals("", parsed.text)
        assertEquals("/tmp/screenshot.png", parsed.images.single().remotePath)
    }

    @Test fun file_directives_become_hidden_downloadable_references() {
        val parsed = parseMessageContent(
            "报告已生成\n@file:`/Users/me/report final.pdf`\n[User attached file: report final.pdf]",
        )

        assertEquals("报告已生成", parsed.text)
        assertEquals("report final.pdf", parsed.files.single().name)
        assertEquals("application/pdf", parsed.files.single().mimeType)
        assertEquals("/Users/me/report final.pdf", parsed.files.single().remotePath)
    }

    @Test fun https_markdown_images_become_image_cards_without_leaking_markup() {
        val parsed = parseMessageContent("图如下：\n![架构图](https://cdn.example.com/diagram.png)")

        assertEquals("图如下：\n架构图", parsed.text)
        assertEquals("https://cdn.example.com/diagram.png", parsed.images.single().sourceUrl)
    }
}
