package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchHighlightTest {
    // The whole query first, then its words (2+ chars) so Markdown tokens still light up.
    @Test fun terms_are_phrase_then_words() {
        assertEquals(listOf("deploy script", "deploy", "script"), searchHighlightTerms("  deploy   script "))
        assertEquals(listOf("部署"), searchHighlightTerms("部署"))
        assertEquals(listOf("a b"), searchHighlightTerms("a b"))
        assertTrue(searchHighlightTerms("   ").isEmpty())
    }

    // Phrase wins over its words: no double marks, longest first.
    @Test fun ranges_do_not_overlap_and_prefer_the_phrase() {
        val terms = searchHighlightTerms("deploy script")
        val text = "deploy script then deploy again"
        assertEquals(listOf(0..12, 19..24), searchHighlightRangesFor(text, terms))
    }

    @Test fun ranges_are_empty_without_terms_or_text() {
        assertTrue(searchHighlightRangesFor("", listOf("x")).isEmpty())
        assertTrue(searchHighlightRangesFor("abc", emptyList()).isEmpty())
    }

    // Auto-expand only in the current turn, for the hit's own source, when the body has the query.
    @Test fun auto_expand_rules() {
        val ctx = ChatSearchContext(query = "gradle", currentMessageId = "m1", currentSource = SearchSource.THINKING)
        assertTrue(shouldAutoExpand(ctx, isCurrentTurn = true, source = SearchSource.THINKING, body = "run Gradle now"))
        assertFalse(shouldAutoExpand(ctx, isCurrentTurn = false, source = SearchSource.THINKING, body = "run gradle now"))
        assertFalse(shouldAutoExpand(ctx, isCurrentTurn = true, source = SearchSource.TOOL, body = "run gradle now"))
        assertFalse(shouldAutoExpand(ctx, isCurrentTurn = true, source = SearchSource.THINKING, body = "nothing here"))
        assertFalse(shouldAutoExpand(null, isCurrentTurn = true, source = SearchSource.THINKING, body = "gradle"))
    }
}
