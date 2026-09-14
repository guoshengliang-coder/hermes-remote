package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
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

    // The one invariant the focused mark rests on (HG-45): the counter and the renderer have to
    // agree on what "the k-th occurrence" is. They did not before — this searched for the whole
    // query while the renderer also marked each word of it, so a two-word query drew more marks
    // than there were hits, and the k-th hit pointed at the wrong one.
    @Test fun hit_count_equals_the_number_of_marks_the_renderer_draws() {
        val text = "deploy script, then deploy the script again"
        listOf("deploy script", "deploy", "错误 deploy").forEach { query ->
            val marks = searchHighlightRangesFor(text, searchHighlightTerms(query)).size
            val hits = searchHits(listOf(ChatMessage(id = "m", role = Role.ASSISTANT, text = text)), query)
            assertEquals("query=$query", marks, hits.size)
            assertEquals("query=$query", (0 until marks).toList(), hits.map { it.occurrence })
        }
    }
}
