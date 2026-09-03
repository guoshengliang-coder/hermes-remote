package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExportTest {
    private val stamp = 1_756_000_000_000L  // fixed instant so assertions stay deterministic

    private fun msg(role: Role, text: String, images: List<ChatImage> = emptyList()) =
        ChatMessage(id = "m-${text.hashCode()}", role = role, text = text, images = images)

    // ---- Markdown document ----

    @Test fun markdown_carries_title_meta_and_role_sections() {
        val md = transcriptMarkdown(
            title = "读书笔记整理",
            messages = listOf(msg(Role.USER, "帮我合并"), msg(Role.ASSISTANT, "好的")),
            language = AppLanguage.ZH,
            exportedAtMillis = stamp,
            model = "claude-fable-5",
        )
        assertTrue(md.startsWith("# 读书笔记整理"))
        assertTrue("meta line must name the model", md.contains("claude-fable-5"))
        assertTrue(md.contains("Hermes GO"))
        assertTrue(md.contains("## 你"))
        assertTrue(md.contains("## 助手"))
        assertTrue(md.contains("帮我合并"))
    }

    // The whole point of the file export over plain text: Markdown structure survives verbatim.
    @Test fun markdown_preserves_code_fences_and_tables() {
        val body = "见下表：\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```kotlin\nval x = 1\n```"
        val md = transcriptMarkdown(null, listOf(msg(Role.ASSISTANT, body)), AppLanguage.ZH, stamp)
        assertTrue(md.contains("| a | b |"))
        assertTrue(md.contains("```kotlin"))
        assertTrue(md.contains("val x = 1"))
    }

    @Test fun markdown_labels_system_and_error_turns() {
        val messages = listOf(
            msg(Role.SYSTEM, "已切换模型"),
            ChatMessage(id = "e1", role = Role.SYSTEM, text = "连接中断", isError = true),
        )
        val md = transcriptMarkdown(null, messages, AppLanguage.ZH, stamp)
        assertTrue(md.contains("## 系统"))
        assertTrue(md.contains("## 错误"))
    }

    @Test fun markdown_is_empty_when_nothing_to_export() {
        assertEquals("", transcriptMarkdown("t", emptyList(), AppLanguage.ZH, stamp))
        assertEquals("", transcriptMarkdown("t", listOf(msg(Role.USER, "   ")), AppLanguage.ZH, stamp))
    }

    @Test fun markdown_falls_back_to_a_generic_heading_without_a_title() {
        val md = transcriptMarkdown("  ", listOf(msg(Role.USER, "hi")), AppLanguage.EN, stamp)
        assertTrue(md.startsWith("# Chat transcript"))
    }

    // ---- File name sanitising ----

    @Test fun file_name_strips_path_and_reserved_characters() {
        val name = transcriptFileBaseName("""a/b\c:d*e?f"g<h>i|j""", stamp)
        listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|").forEach {
            assertFalse("$it must not survive in $name", name.contains(it))
        }
    }

    @Test fun file_name_collapses_whitespace_and_newlines() {
        val name = transcriptFileBaseName("读书  笔记\n整理\t十月", stamp)
        assertFalse(name.contains("\n"))
        assertFalse(name.contains("\t"))
        assertFalse("collapsed to single spaces", name.contains("  "))
    }

    @Test fun file_name_is_bounded_and_never_empty() {
        val long = transcriptFileBaseName("标".repeat(500), stamp)
        assertTrue("kept short enough for any filesystem", long.length <= 70)
        val blank = transcriptFileBaseName("   ", stamp)
        assertTrue(blank.startsWith("HermesGO-"))
        assertTrue(transcriptFileBaseName(null, stamp).startsWith("HermesGO-"))
    }

    // ---- Image height budget (strategy A) ----

    @Test fun short_conversation_fits_the_image_budget() {
        val messages = List(4) { msg(Role.USER, "短消息 $it") }
        assertTrue(transcriptImageFitsBudget(messages, density = 3f))
    }

    @Test fun long_conversation_is_refused_before_rendering() {
        val messages = List(60) { msg(Role.ASSISTANT, "长回复。".repeat(120)) }
        assertFalse(
            "an over-budget transcript must be refused, not rendered into a broken capture",
            transcriptImageFitsBudget(messages, density = 3f),
        )
    }

    // dp→px scales with density, so the same transcript can fit on one device and not another.
    @Test fun budget_scales_with_screen_density() {
        val messages = List(22) { msg(Role.ASSISTANT, "中等长度的一段回复内容。".repeat(8)) }
        val estimate = estimateTranscriptImageHeightDp(messages)
        assertTrue("fixture must straddle the budget", estimate * 1f <= TRANSCRIPT_IMAGE_MAX_HEIGHT_PX)
        assertTrue(transcriptImageFitsBudget(messages, density = 1f))
        assertFalse(transcriptImageFitsBudget(messages, density = 4f))
    }

    @Test fun estimate_counts_hard_line_breaks_and_images() {
        val plain = listOf(msg(Role.ASSISTANT, "a".repeat(100)))
        val withBreaks = listOf(msg(Role.ASSISTANT, "a".repeat(100) + "\n\n\n\n"))
        val withImage = listOf(msg(Role.ASSISTANT, "a".repeat(100), listOf(ChatImage(id = "i1"))))
        assertTrue(estimateTranscriptImageHeightDp(withBreaks) > estimateTranscriptImageHeightDp(plain))
        assertTrue(estimateTranscriptImageHeightDp(withImage) > estimateTranscriptImageHeightDp(plain))
        assertEquals(0, estimateTranscriptImageHeightDp(emptyList()))
    }
}
