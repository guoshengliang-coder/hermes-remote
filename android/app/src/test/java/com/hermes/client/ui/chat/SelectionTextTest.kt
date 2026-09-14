package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTextTest {
    @Test fun fenced_code_is_kept_verbatim_unlike_speech_text() {
        val out = readableText("before\n```kotlin\n    val x = 1\n```\nafter")
        assertEquals("before\n    val x = 1\nafter", out)
        // The TTS transform throws the same block away; the selection view must not.
        assertFalse(speechText("before\n```kotlin\n    val x = 1\n```\nafter").contains("val x = 1"))
    }

    @Test fun markdown_inside_a_code_block_is_not_rewritten() {
        val src = "```\n# not a heading\n- not a bullet\n**not bold**\n```"
        assertEquals("# not a heading\n- not a bullet\n**not bold**", readableText(src))
    }

    @Test fun image_keeps_its_alt_without_a_stray_bang() {
        assertEquals("chart", readableText("![chart](https://example.com/a.png)"))
        // The regression this ordering exists for: link-first matching leaves "!".
        assertFalse(readableText("![chart](https://example.com/a.png)").contains("!"))
    }

    @Test fun table_keeps_rows_and_drops_the_alignment_row() {
        val out = readableText("| a | b |\n| --- | :-: |\n| 1 | 2 |")
        assertEquals("| a | b |\n| 1 | 2 |", out)
    }

    @Test fun list_markers_normalise_and_indentation_survives() {
        assertEquals("• one\n  • nested\n2. two", readableText("- one\n  * nested\n2. two"))
    }

    @Test fun task_boxes_become_glyphs() {
        assertEquals("☐ todo\n☑ done", readableText("- [ ] todo\n- [x] done"))
    }

    @Test fun headings_quotes_and_rules() {
        assertEquals("Title", readableText("### Title"))
        assertEquals("quoted", readableText("> quoted"))
        assertEquals("above\nbelow", readableText("above\n---\nbelow"))
    }

    @Test fun blank_lines_are_kept_so_paragraphs_stay_apart() {
        assertEquals("one\n\ntwo", readableText("one\n\ntwo"))
    }

    @Test fun inline_spans_are_unwrapped() {
        assertEquals("Hello world", readableText("**Hello** _world_"))
        assertEquals("run ls now", readableText("run `ls` now"))
        assertEquals("click here", readableText("[click here](https://example.com)"))
        assertEquals("gone", readableText("~~gone~~"))
    }

    @Test fun snake_case_and_apostrophes_are_left_alone() {
        assertEquals("call read_page, it's fine", readableText("call read_page, it's fine"))
    }

    @Test fun plain_text_unchanged_and_empty_is_empty() {
        assertEquals("just words", readableText("just words"))
        assertEquals("", readableText(""))
        assertEquals("", readableText("   \n  "))
    }

    @Test fun unterminated_fence_while_streaming_still_yields_the_code() {
        val out = readableText("here:\n```py\nprint(1)")
        assertTrue(out.contains("print(1)"))
    }
}
