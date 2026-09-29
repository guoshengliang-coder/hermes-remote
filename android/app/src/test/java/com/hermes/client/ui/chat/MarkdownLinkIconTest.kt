package com.hermes.client.ui.chat

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Link glyphs are injected per AST node. These tests hold the one-icon-per-link rule and select
 * the GitHub mark only for a GitHub destination; screenshots cover the rendered shape and spacing.
 */
class MarkdownLinkIconTest {
    private fun iconKinds(markdown: String): List<MarkdownLinkIconKind> {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        val kinds = mutableListOf<MarkdownLinkIconKind>()
        fun walk(node: ASTNode) {
            linkIconKind(node, markdown)?.let(kinds::add)
            node.children.forEach(::walk)
        }
        walk(tree)
        return kinds
    }

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

    @Test fun githubPullRequestUsesGitHubMark() {
        assertEquals(
            listOf(MarkdownLinkIconKind.GITHUB),
            iconKinds("见 [PR #465](https://github.com/example/project/pull/465)。"),
        )
    }

    @Test fun bareGitHubUrlUsesGitHubMark() {
        assertEquals(
            listOf(MarkdownLinkIconKind.GITHUB),
            iconKinds("见 https://github.com/example/project/pull/465。"),
        )
    }

    @Test fun unrelatedAndLookalikeHostsKeepExternalLinkIcon() {
        assertEquals(
            listOf(MarkdownLinkIconKind.EXTERNAL, MarkdownLinkIconKind.EXTERNAL),
            iconKinds("[文档](https://example.com) 与 [伪装](https://github.com.evil.example/pull/465)"),
        )
    }

    @Test fun mixedLinksGetOneIconEach() {
        assertEquals(
            listOf(MarkdownLinkIconKind.GITHUB, MarkdownLinkIconKind.EXTERNAL),
            iconKinds("[PR](https://github.com/example/project/pull/465) 与 [说明](https://example.com)"),
        )
    }
}
