package com.hermes.client.ui.chat

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The external-link glyph is injected per AST node, so the interesting behaviour is "which nodes
 * count as a link". Roborazzi goldens only record here, they never fail a build, so this is the
 * test that actually holds the rule.
 */
class MarkdownLinkIconTest {
    private fun iconCount(markdown: String): Int {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        var count = 0
        fun walk(node: ASTNode) {
            if (shouldPrefixLinkIcon(node)) count++
            node.children.forEach(::walk)
        }
        walk(tree)
        return count
    }

    @Test fun inlineLinkGetsOneIcon() {
        assertEquals(1, iconCount("见 [文档](https://example.com) 一节。"))
    }

    @Test fun bareUrlGetsOneIcon() {
        assertEquals(1, iconCount("见 https://example.com/docs 一节。"))
    }

    @Test fun angleAutolinkGetsOneIcon() {
        assertEquals(1, iconCount("见 <https://example.com/docs> 一节。"))
    }

    @Test fun referenceLinkGetsOneIcon() {
        assertEquals(1, iconCount("见 [文档][doc]。\n\n[doc]: https://example.com"))
    }

    /** Regression: the label is itself an autolink token, which must not add a second glyph. */
    @Test fun urlLabelledLinkGetsExactlyOneIcon() {
        assertEquals(1, iconCount("[https://a.example](https://b.example)"))
    }

    @Test fun severalLinksEachGetOne() {
        assertEquals(3, iconCount("见 [A](https://a.example)、[B](https://b.example) 和 [C](https://c.example)。"))
    }

    @Test fun nonLinkContentGetsNone() {
        assertEquals(0, iconCount("**加粗**、`code`、*斜体* 与普通正文。"))
    }

    /** A fenced code block that happens to contain a URL must stay untouched. */
    @Test fun codeFenceUrlGetsNone() {
        assertEquals(0, iconCount("```\ncurl https://example.com\n```"))
    }
}
