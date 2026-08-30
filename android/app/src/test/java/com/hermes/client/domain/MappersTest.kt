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

    @Test fun image_generate_natural_language_path_becomes_remote_image() {
        val parsed = parseMessageContent(
            """
                已生成成功：一只戴眼镜的猫程序员。
                图片保存路径： /Users/bs/.hermes/cache/images/openai_codex_27d5be69.png
                图片模型：gpt-image-2-medium
            """.trimIndent(),
        )

        assertEquals("已生成成功：一只戴眼镜的猫程序员。\n图片模型：gpt-image-2-medium", parsed.text)
        assertEquals("/Users/bs/.hermes/cache/images/openai_codex_27d5be69.png", parsed.images.single().remotePath)
        assertEquals("image/png", parsed.images.single().mimeType)
    }

    @Test fun wrapped_labeled_path_is_joined_until_image_extension() {
        val parsed = parseMessageContent(
            """
                图片保存路径： /Users/bs/.hermes/cache/images/
                openai_codex_gpt-image-2-medium_
                20260830_192233_27d5be69.png
                图片模型：gpt-image-2-medium
            """.trimIndent(),
        )

        assertEquals("图片模型：gpt-image-2-medium", parsed.text)
        assertEquals(
            "/Users/bs/.hermes/cache/images/openai_codex_gpt-image-2-medium_20260830_192233_27d5be69.png",
            parsed.images.single().remotePath,
        )
    }

    @Test fun local_markdown_and_file_urls_become_remote_images() {
        val markdown = parseMessageContent("结果：\n![测试图](</Users/bs/output/photo one.webp>)")
        val fileUrl = parseMessageContent("Image saved to: file:///Users/bs/output/photo%20two.jpg")

        assertEquals("结果：\n测试图", markdown.text)
        assertEquals("/Users/bs/output/photo one.webp", markdown.images.single().remotePath)
        assertEquals("", fileUrl.text)
        assertEquals("/Users/bs/output/photo two.jpg", fileUrl.images.single().remotePath)
    }

    @Test fun ordinary_filesystem_example_is_not_misclassified_as_an_image() {
        val raw = "可以在 /Users/bs/example.png 上测试路径解析。"
        val parsed = parseMessageContent(raw)

        assertEquals(raw, parsed.text)
        assertEquals(0, parsed.images.size)
    }
}
