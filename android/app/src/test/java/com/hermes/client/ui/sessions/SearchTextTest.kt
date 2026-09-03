package com.hermes.client.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test fun highlightRanges_finds_every_case_insensitive_occurrence() {
        assertEquals(listOf(0..4, 6..10, 13..17), highlightRanges("Build build, BUILD!", "build"))
        assertEquals(listOf(4..5), highlightRanges("先跑打包脚本再部署", "脚本"))
    }

    @Test fun highlightRanges_is_empty_for_blank_query_or_no_match() {
        assertTrue(highlightRanges("anything", "  ").isEmpty())
        assertTrue(highlightRanges("anything", "zzz").isEmpty())
        assertTrue(highlightRanges("", "a").isEmpty())
    }

    @Test fun highlightRanges_does_not_overlap() {
        assertEquals(listOf(0..1, 2..3), highlightRanges("aaaa", "aa"))
    }

    // The match sits inside the window with ellipses on the cut sides.
    @Test fun centerSnippet_keeps_the_match_visible() {
        val raw = "x".repeat(200) + "部署" + "y".repeat(200)
        val s = centerSnippet(raw, "部署", context = 10)
        assertEquals("…" + "x".repeat(10) + "部署" + "y".repeat(10) + "…", s)
    }

    @Test fun centerSnippet_at_the_head_has_no_leading_ellipsis() {
        assertEquals("部署 then more…", centerSnippet("部署 then more text follows here", "部署", context = 10))
    }

    @Test fun centerSnippet_without_match_keeps_the_head() {
        val s = centerSnippet("a".repeat(100), "zz", context = 10)
        assertEquals("a".repeat(22) + "…", s)
    }

    @Test fun centerSnippet_collapses_whitespace_and_handles_null() {
        assertEquals("one two three", centerSnippet("one\n\n two   three", "two"))
        assertEquals("", centerSnippet(null, "x"))
    }

    // The gateway may already have put an ellipsis at the cut: don't double it.
    @Test fun centerSnippet_does_not_double_existing_ellipsis() {
        val s = centerSnippet("…" + "x".repeat(50) + "hit" + "y".repeat(50) + "…", "hit", context = 5)
        assertEquals("…xxxxxhityyyyy…", s)
    }

    @Test fun titleMatches_uses_title_or_project_label_same_rule_for_all_rows() {
        assertTrue(titleMatches("Fix APK signing", null, "sign"))
        assertTrue(titleMatches("Untitled", "hermes-remote", "remote"))
        assertFalse(titleMatches("Untitled", null, "remote"))
        assertFalse(titleMatches("Anything", "any", " "))
    }
}
