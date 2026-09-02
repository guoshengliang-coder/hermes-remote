package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnJumpTest {
    private fun user(id: String, text: String, ts: Long? = null, displayKind: String? = null) =
        ChatMessage(id = id, role = Role.USER, text = text, timestamp = ts, displayKind = displayKind)

    private fun assistant(id: String, text: String = "answer") =
        ChatMessage(id = id, role = Role.ASSISTANT, text = text)

    private fun system(id: String, text: String = "notice") =
        ChatMessage(id = id, role = Role.SYSTEM, text = text)

    // u0 a1 a2 | u3 a4 | u5 a6 a7 a8
    private val threeGroups = listOf(
        user("u0", "第一个问题"), assistant("a1"), assistant("a2"),
        user("u3", "第二个问题"), assistant("a4"),
        user("u5", "第三个问题"), assistant("a6"), assistant("a7"), assistant("a8"),
    )

    // ---- grouping -----------------------------------------------------------------------

    @Test fun groups_start_at_each_user_prompt() {
        val groups = turnGroups(threeGroups)
        assertEquals(listOf(TurnGroup(0, 0), TurnGroup(3, 3), TurnGroup(5, 5)), groups)
        assertEquals(0, groupIndexOf(groups, 2))
        assertEquals(1, groupIndexOf(groups, 3))
        assertEquals(2, groupIndexOf(groups, 8))
    }

    @Test fun content_before_the_first_prompt_forms_a_leading_group_without_prompt() {
        val messages = listOf(assistant("greet"), system("s"), user("u", "第一个问题"), assistant("a"))
        val groups = turnGroups(messages)
        assertEquals(listOf(TurnGroup(0, null), TurnGroup(2, 2)), groups)
        assertEquals(0, groups[0].anchorIndex)
        assertEquals(0, groupIndexOf(groups, 1))
    }

    @Test fun timeline_notes_and_system_rows_never_open_a_group() {
        val messages = listOf(
            user("u0", "问题"),
            assistant("a1"),
            user("note", "[System: model changed]", displayKind = "model_switch"),
            system("err"),
            assistant("a2"),
        )
        assertEquals(listOf(TurnGroup(0, 0)), turnGroups(messages))
        assertFalse(messages[2].isPromptTurn())
        assertTrue(messages[0].isPromptTurn())
    }

    @Test fun empty_transcript_has_no_groups() {
        assertTrue(turnGroups(emptyList()).isEmpty())
    }

    // ---- summary ------------------------------------------------------------------------

    @Test fun summary_is_the_first_non_blank_line_with_whitespace_collapsed() {
        val msg = user("u", "\n\n  把 gateway   的路由\t拆开  \n第二行不显示")
        assertEquals("把 gateway 的路由 拆开", promptSummary(msg, AppLanguage.ZH))
    }

    @Test fun attachment_only_prompt_names_the_attachment() {
        val file = user("f", "").copy(files = listOf(ChatFile(id = "1", name = "report.pdf")))
        assertEquals("文件：report.pdf", promptSummary(file, AppLanguage.ZH))
        assertEquals("File: report.pdf", promptSummary(file, AppLanguage.EN))
        val one = user("i", " ").copy(images = listOf(ChatImage(id = "1")))
        assertEquals("图片", promptSummary(one, AppLanguage.ZH))
        val two = one.copy(images = listOf(ChatImage(id = "1"), ChatImage(id = "2")))
        assertEquals("图片 ×2", promptSummary(two, AppLanguage.ZH))
        assertEquals("2 images", promptSummary(two, AppLanguage.EN))
        assertEquals("（空消息）", promptSummary(user("e", ""), AppLanguage.ZH))
    }

    // ---- pill visibility ----------------------------------------------------------------

    private val groups = turnGroups(threeGroups)

    @Test fun pill_hidden_while_following_the_live_tail() {
        assertNull(turnPillFor(groups, topVisibleMessageIndex = 6, visibleMessageRange = 6..8, atBottom = true))
    }

    @Test fun pill_hidden_while_the_group_prompt_is_on_screen() {
        // Prompt u3 partially visible at the top edge.
        assertNull(turnPillFor(groups, topVisibleMessageIndex = 3, visibleMessageRange = 3..4, atBottom = false))
        // The NEXT group's prompt being on screen does not count: the reader is still inside
        // group 2's answer, whose own prompt (u3) has scrolled off, so the pill names group 2.
        assertEquals(
            TurnPillTarget(groupIndex = 1, showList = false),
            turnPillFor(groups, topVisibleMessageIndex = 4, visibleMessageRange = 4..5, atBottom = false),
        )
    }

    @Test fun pill_names_the_group_under_the_top_edge_once_its_prompt_scrolled_off() {
        val target = turnPillFor(groups, topVisibleMessageIndex = 7, visibleMessageRange = 7..8, atBottom = false)
        assertEquals(TurnPillTarget(groupIndex = 2, showList = false), target)
        val previous = turnPillFor(groups, topVisibleMessageIndex = 4, visibleMessageRange = 4..4, atBottom = false)
        assertEquals(TurnPillTarget(groupIndex = 1, showList = false), previous)
    }

    @Test fun list_segment_appears_from_the_third_group_from_the_end() {
        val deep = turnPillFor(groups, topVisibleMessageIndex = 1, visibleMessageRange = 1..2, atBottom = false)
        assertEquals(TurnPillTarget(groupIndex = 0, showList = true), deep)
        assertEquals(2, TURN_PILL_LIST_DEPTH)
    }

    @Test fun leading_group_is_jumpable_to_the_top() {
        val messages = listOf(assistant("g", "long greeting"), assistant("g2"), user("u", "问题"), assistant("a"))
        val g = turnGroups(messages)
        val target = turnPillFor(g, topVisibleMessageIndex = 1, visibleMessageRange = 1..2, atBottom = false)
        assertEquals(TurnPillTarget(groupIndex = 0, showList = false), target)
        assertEquals(0, g[0].anchorIndex)
    }

    @Test fun empty_groups_or_no_visible_turns_hide_the_pill() {
        assertNull(turnPillFor(emptyList(), 0, 0..0, atBottom = false))
        assertNull(turnPillFor(groups, topVisibleMessageIndex = -1, visibleMessageRange = IntRange.EMPTY, atBottom = false))
    }

    // ---- list index mapping -------------------------------------------------------------

    @Test fun list_index_mapping_skips_the_bottom_edge_slot_and_reverses() {
        val n = threeGroups.size
        assertEquals(1, messageListIndex(n, n - 1))
        assertEquals(n, messageListIndex(n, 0))
        assertNull(listMessageIndex(n, 0))
        for (i in 0 until n) assertEquals(i, listMessageIndex(n, messageListIndex(n, i)))
    }

    // ---- prompt list rows ---------------------------------------------------------------

    @Test fun prompt_rows_mark_the_current_group_and_carry_times() {
        val messages = listOf(
            assistant("greet"),
            user("u1", "第一个问题", ts = 1_000L), assistant("a1"),
            user("u2", "第二个问题\n第二行", ts = 2_000L), assistant("a2"),
        )
        val g = turnGroups(messages)
        val rows = promptRows(g, messages, currentGroupIndex = 1, language = AppLanguage.ZH) { "t$it" }
        assertEquals(3, rows.size)
        assertEquals(PromptRow(0, "会话开始", time = null, isCurrent = false, isLeading = true), rows[0])
        assertEquals(PromptRow(1, "第一个问题", time = "t1000", isCurrent = true, isLeading = false), rows[1])
        assertEquals(PromptRow(2, "第二个问题", time = "t2000", isCurrent = false, isLeading = false), rows[2])
    }

    @Test fun prompt_rows_without_leading_content_start_at_the_first_prompt() {
        val rows = promptRows(groups, threeGroups, currentGroupIndex = 2, language = AppLanguage.EN) { "t" }
        assertEquals(listOf("第一个问题", "第二个问题", "第三个问题"), rows.map { it.label })
        assertEquals(listOf(false, false, true), rows.map { it.isCurrent })
        assertTrue(rows.none { it.isLeading })
        // No timestamps in these fixtures: rows carry no time rather than a bogus one.
        assertTrue(rows.all { it.time == null })
    }
}
