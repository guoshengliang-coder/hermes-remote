package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.SessionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun hermes_image_path_on_following_inline_code_line_becomes_remote_image() {
        val markdownHardBreak = "  "
        val parsed = parseMessageContent(
            """
                已生成并复核成功：云海上的玻璃温室、发光植物和戴红围巾的小狐狸。

                图片路径：${markdownHardBreak}
                `/Users/bs/.hermes/cache/images/openai_codex_gpt-image-2-medium_20260830_212159_f6fe9be9.png`

                图片模型：gpt-image-2-medium${markdownHardBreak}
                对话模型：gpt-5.6-sol
            """.trimIndent(),
        )

        assertEquals(
            "已生成并复核成功：云海上的玻璃温室、发光植物和戴红围巾的小狐狸。\n\n" +
                "图片模型：gpt-image-2-medium  \n对话模型：gpt-5.6-sol",
            parsed.text,
        )
        assertEquals(
            "/Users/bs/.hermes/cache/images/openai_codex_gpt-image-2-medium_20260830_212159_f6fe9be9.png",
            parsed.images.single().remotePath,
        )
    }

    @Test fun labeled_fenced_image_path_becomes_remote_image_without_leaking_fence() {
        val parsed = parseMessageContent(
            """
                Generated image path:
                ```text
                /Users/bs/output/generated fox.webp
                ```
                Done.
            """.trimIndent(),
        )

        assertEquals("Done.", parsed.text)
        assertEquals("/Users/bs/output/generated fox.webp", parsed.images.single().remotePath)
    }

    @Test fun hermes_generated_markdown_path_becomes_downloadable_file() {
        val parsed = parseMessageContent(
            """
                已生成:`/Users/bs/hermes-文生图与安卓图片显示-会话整理-20260830.md`(7.3KB)

                **内容结构**:
                1. **需求背景** — 三个问题的来源
            """.trimIndent(),
        )

        assertEquals("**内容结构**:\n1. **需求背景** — 三个问题的来源", parsed.text)
        val file = parsed.files.single()
        assertEquals("hermes-文生图与安卓图片显示-会话整理-20260830.md", file.name)
        assertEquals("text/markdown", file.mimeType)
        assertEquals(7_475L, file.sizeBytes)
        assertEquals("/Users/bs/hermes-文生图与安卓图片显示-会话整理-20260830.md", file.remotePath)
    }

    @Test fun labeled_file_path_on_following_fenced_line_becomes_downloadable_file() {
        val parsed = parseMessageContent(
            """
                File saved to:
                ```text
                /Users/bs/output/final report.pdf
                ```
                Ready.
            """.trimIndent(),
        )

        assertEquals("Ready.", parsed.text)
        assertEquals("final report.pdf", parsed.files.single().name)
        assertEquals("application/pdf", parsed.files.single().mimeType)
    }

    @Test fun generic_generated_image_path_remains_an_image_not_a_file() {
        val parsed = parseMessageContent("已生成：`/Users/bs/output/generated.png`")

        assertEquals("", parsed.text)
        assertEquals("/Users/bs/output/generated.png", parsed.images.single().remotePath)
        assertEquals(0, parsed.files.size)
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

    @Test fun hermes_media_markdown_tag_becomes_downloadable_file() {
        val parsed = parseMessageContent(
            """
                现在直接发 md 原文件：

                MEDIA:/Users/bs/hermes-文生图与安卓图片显示-会话整理-20260830.md

                这次应该能打开。
            """.trimIndent(),
        )

        assertEquals("现在直接发 md 原文件：\n\n这次应该能打开。", parsed.text)
        assertEquals(0, parsed.images.size)
        assertEquals("hermes-文生图与安卓图片显示-会话整理-20260830.md", parsed.files.single().name)
        assertEquals("text/markdown", parsed.files.single().mimeType)
        assertEquals(
            "/Users/bs/hermes-文生图与安卓图片显示-会话整理-20260830.md",
            parsed.files.single().remotePath,
        )
    }

    @Test fun media_keyword_explanation_stays_while_real_directive_becomes_file() {
        val parsed = parseMessageContent(
            """
                桌面会话会提取，`MEDIA:` 标签和扩展名都在支持范围内。

                MEDIA:/Users/bs/hermes-文生图与安卓图片显示-会话整理-20260830.md

                文件会作为附件推送到客户端。
            """.trimIndent(),
        )

        assertEquals(
            "桌面会话会提取，`MEDIA:` 标签和扩展名都在支持范围内。\n\n文件会作为附件推送到客户端。",
            parsed.text,
        )
        assertEquals("text/markdown", parsed.files.single().mimeType)
    }

    @Test fun media_protocol_routes_multiple_quoted_and_spaced_paths_by_kind() {
        val parsed = parseMessageContent(
            """
                结果如下：
                **MEDIA:`/Users/bs/output/戴眼镜的猫 01.png`**
                [[as_document]] MEDIA:"/Users/bs/output/季度 报告.pdf"（7.3 KB）
            """.trimIndent(),
        )

        assertEquals("结果如下：", parsed.text)
        assertEquals("/Users/bs/output/戴眼镜的猫 01.png", parsed.images.single().remotePath)
        assertEquals("/Users/bs/output/季度 报告.pdf", parsed.files.single().remotePath)
    }

    @Test fun adjacent_media_tags_are_extracted_independently() {
        val parsed = parseMessageContent(
            "MEDIA:/Users/bs/a.pngMEDIA:/Users/bs/b.csv",
        )

        assertEquals("", parsed.text)
        assertEquals("/Users/bs/a.png", parsed.images.single().remotePath)
        assertEquals("/Users/bs/b.csv", parsed.files.single().remotePath)
    }

    @Test fun media_examples_in_fenced_code_and_blockquotes_remain_visible() {
        val raw = """
            示例：
            ```text
            MEDIA:/Users/bs/example.pdf
            ```
            > MEDIA:/Users/bs/quoted.png
        """.trimIndent()
        val parsed = parseMessageContent(raw)

        assertEquals(raw, parsed.text)
        assertEquals(0, parsed.images.size)
        assertEquals(0, parsed.files.size)
    }

    @Test fun incomplete_or_unknown_media_tag_is_not_silently_removed() {
        val raw = "`MEDIA:` 标签示例；MEDIA:/Users/bs/source.py"
        val parsed = parseMessageContent(raw)

        assertEquals(raw, parsed.text)
        assertEquals(0, parsed.images.size)
        assertEquals(0, parsed.files.size)
    }

    @Test fun local_markdown_file_link_becomes_downloadable_card() {
        val parsed = parseMessageContent("下载：[会话整理](</Users/bs/output/会话 整理.md>)")

        assertEquals("下载：会话整理", parsed.text)
        assertEquals("/Users/bs/output/会话 整理.md", parsed.files.single().remotePath)
    }

    // Regression for HG-4. Hermes' messages table carries `timestamp REAL NOT NULL` (Unix seconds)
    // and the API passes the row through verbatim; the client modelled only `created_at`, a field
    // upstream never emits. Every message loaded from history therefore came back timeless, so the
    // 我的提问 list showed times on recent prompts and nothing on older ones — backwards from what
    // is useful. See docs/HERMES_CONTRACT.md §1b.
    @Test fun `message time comes from Hermes' own timestamp column`() {
        val fromUpstream = MessageDto(id = 1, role = "user", content = "hi", timestamp = 1_788_000_000.5)
        assertEquals(1_788_000_000_500L, fromUpstream.toDomain().timestamp)

        // The ISO fallback still parses, and never overrides a real upstream value.
        val isoOnly = MessageDto(id = 2, role = "user", content = "hi", createdAt = "2026-09-05T01:20:00Z")
        assertEquals(1_788_571_200_000L, isoOnly.toDomain().timestamp)
        val both = MessageDto(id = 3, role = "user", content = "hi", timestamp = 1_788_000_000.0, createdAt = "2026-09-05T01:20:00Z")
        assertEquals(1_788_000_000_000L, both.toDomain().timestamp)

        // Neither present, and a zero stamp, both mean "unknown" — not 1970.
        assertNull(MessageDto(id = 4, role = "user", content = "hi").toDomain().timestamp)
        assertNull(MessageDto(id = 5, role = "user", content = "hi", timestamp = 0.0).toDomain().timestamp)
    }

    // Regression for HG-23. A page fetched into the transcript carried Docusaurus' own header
    // markup, in which `/docs/img/logo.png` is a root-relative URL on that site — not a path on
    // the Mac. The old rule ("starts with / and ends in a delivery extension") turned it into an
    // attachment, and opening it asked the Connector for a file that had never been on the Mac:
    // refused with 403 and reported as "this file is not inside the directory the Mac allows".
    // Confirmed against the production Connector log — `status=403 reason=forbidden ext=.png
    // len=18`, and 18 is exactly the length of /docs/img/logo.png.
    @Test fun `a website's root-relative url never becomes an attachment`() {
        val quotedPage = parseMessageContent("[![Hermes Agent](/docs/img/logo.png)\n\n**Hermes Agent**")

        assertEquals(0, quotedPage.images.size)
        assertEquals(0, quotedPage.files.size)

        val webLink = parseMessageContent("见 [说明](/assets/guide.pdf)")
        assertEquals(0, webLink.files.size)
    }

    // The other half of the same rule: a real delivery must still become a card. These are roots
    // Hermes actually writes to, and losing any of them would cost a working attachment.
    @Test fun `markdown links to real mac paths still become cards`() {
        listOf(
            "/Users/bs/output/report.pdf",
            "/tmp/report.pdf",
            "/private/var/folders/xx/T/report.pdf",
            "/Volumes/Data/report.pdf",
            "/opt/hermes/report.pdf",
        ).forEach { path ->
            val parsed = parseMessageContent("下载：[报告]($path)")
            assertEquals(path, parsed.files.single().remotePath)
            assertEquals("下载：报告", parsed.text)
        }

        val image = parseMessageContent("![图](</Users/bs/output/shot.png>)")
        assertEquals("/Users/bs/output/shot.png", image.images.single().remotePath)
    }

    // The guard is deliberately scoped to Markdown links. MEDIA: and @file: are explicit delivery
    // instructions rather than prose that happens to contain a link, so a path arriving through
    // them is taken at its word and the Connector stays the authority on whether it is readable.
    @Test fun `explicit delivery grammars are not filtered by the mac-path guard`() {
        val media = parseMessageContent("MEDIA:/srv/exports/report.pdf")
        assertEquals("/srv/exports/report.pdf", media.files.single().remotePath)

        val directive = parseMessageContent("@file:/srv/exports/report.pdf")
        assertEquals("/srv/exports/report.pdf", directive.files.single().remotePath)
    }
}
