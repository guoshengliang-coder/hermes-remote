package com.hermes.client.ui.chat

import org.junit.Assert.*
import org.junit.Test

class LongPlainTextLayoutTest {
    @Test fun extremeLiteralParagraphHasBoundedNativeLayoutsWithoutLosingText() {
        val paragraph = "{".repeat(180_000) + "x"
        val blocks = markdownRenderBlocks(paragraph)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.length <= MAX_PLAIN_TEXT_BLOCK_CHARS })
        assertEquals(paragraph, blocks.joinToString(""))
    }

    @Test fun chunkBoundariesNeverSplitAnEmojiSurrogatePair() {
        val paragraph = "x".repeat(MAX_PLAIN_TEXT_BLOCK_CHARS - 1) + "😀" + "x".repeat(5_000)
        val blocks = markdownRenderBlocks(paragraph)
        assertEquals(paragraph, blocks.joinToString(""))
        assertTrue(blocks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test fun structuredMarkdownAndDocumentReferencesStayWhole() {
        val body = "x".repeat(10_000)
        for (text in listOf("```\n$body\n```", "- $body", "> $body", "**$body**",
            "    $body", "| $body |\n|---|", "[label][ref]\n\n[ref]: https://example.com")) {
            assertEquals(listOf(text), markdownRenderBlocks(text))
        }
    }

    @Test fun cutsCannotInventMarkdownListsOrSplitAnHtmlEntity() {
        val prefix = "x".repeat(MAX_PLAIN_TEXT_BLOCK_CHARS)
        for (paragraph in listOf(prefix + "1. ordinary prose", prefix + "&amp; tail")) {
            assertEquals(listOf(paragraph), markdownRenderBlocks(paragraph))
        }
    }

    @Test fun ordinaryParagraphsKeepTheirExistingBoundaries() {
        assertEquals(listOf("First paragraph", "Second paragraph"),
            markdownRenderBlocks("First paragraph\n\nSecond paragraph"))
    }
}
