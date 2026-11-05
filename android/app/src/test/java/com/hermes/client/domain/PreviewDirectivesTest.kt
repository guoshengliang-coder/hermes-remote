package com.hermes.client.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `::preview{file="…"}` grammar, mirrored from upstream's transcript-directives.ts. */
class PreviewDirectivesTest {
    private fun preview(file: String, height: Int? = null) = PreviewSegment.Preview(PreviewDirective(file, height))
    private fun prose(text: String) = PreviewSegment.Prose(text)
    private fun directives(text: String) = previewDirectives(text)

    @Test fun aDirectiveOnItsOwnBecomesOneCard() {
        assertEquals(listOf(preview("/Users/bs/a.html")), segmentPreviewDirectives("::preview{file=\"/Users/bs/a.html\"}"))
    }

    /** The answer from the 2026-11-05 report, shaped as production stored it. */
    @Test fun theReportedAnswerKeepsItsParagraphsAroundTheCard() {
        val text = "**结论：留存并不是瓶颈。**\n\n" +
            "::preview{file=\"/Users/bs/.hermes/cache/scratch/retention-chart.html\"}\n\n" +
            "加权留存（按注册批次）："
        assertEquals(listOf(
            prose("**结论：留存并不是瓶颈。**"),
            preview("/Users/bs/.hermes/cache/scratch/retention-chart.html"),
            prose("加权留存（按注册批次）："),
        ), segmentPreviewDirectives(text))
    }

    @Test fun aDirectiveInsideASentenceSplitsTheSentenceAroundIt() {
        assertEquals(
            listOf(prose("见下图"), preview("a.html"), prose("然后继续")),
            segmentPreviewDirectives("见下图 ::preview{file=\"a.html\"} 然后继续"),
        )
        assertEquals(
            listOf(prose("第一行"), preview("a.html"), prose("第二行")),
            segmentPreviewDirectives("第一行\n::preview{file=\"a.html\"}\n第二行"),
        )
    }

    @Test fun severalDirectivesKeepTheirOrder() {
        assertEquals(
            listOf(preview("a.html"), prose("中间"), preview("b.html")),
            segmentPreviewDirectives("::preview{file=\"a.html\"}\n\n中间\n\n::preview{file=\"b.html\"}"),
        )
    }

    /** Production row 4 of 2026-11-05: the assistant explaining the syntax in inline code. */
    @Test fun codeShowsTheSyntaxInsteadOfUsingIt() {
        assertNull(segmentPreviewDirectives("也就是 `::preview{file=\"a.html\"}` 渲染出来的内容"))
        assertNull(segmentPreviewDirectives("```\n::preview{file=\"a.html\"}\n```"))
        assertNull(segmentPreviewDirectives("    ::preview{file=\"a.html\"}"))
        // A code span inside the attributes is not a path anyone meant.
        assertNull(segmentPreviewDirectives("::preview{file=\"`a.html`\"}"))
    }

    @Test fun onlyTheClaimedNameAtAWordStartIsADirective() {
        assertNull(segmentPreviewDirectives("std::preview{file=\"a.html\"}"))
        assertNull(segmentPreviewDirectives("下图：::preview{file=\"a.html\"}"))
        assertNull(segmentPreviewDirectives("::chart{file=\"a.html\"}"))
        assertNull(segmentPreviewDirectives("::Preview{file=\"a.html\"}"))
        // JavaScript's \s, which upstream's word start means, includes the ideographic space.
        assertEquals(1, directives("下图${Char(0x3000)}::preview{file=\"a.html\"}").size)
    }

    @Test fun halfADirectiveStaysText() {
        assertNull(segmentPreviewDirectives("::preview{file=\"a.html\""))
        assertNull(segmentPreviewDirectives("::preview{file=\"a.html\" title=\"${"x".repeat(2100)}\"}"))
        // Nothing to draw: the words stay where the author put them rather than vanishing.
        assertNull(segmentPreviewDirectives("用 ::preview 显示"))
        assertNull(segmentPreviewDirectives("::preview{height=\"300\"}"))
    }

    @Test fun attributesFollowUpstream() {
        assertEquals(PreviewDirective("a.html", 480), directives("::preview{file='a.html' height=\"480\"}").single())
        assertEquals(120, directives("::preview{file=\"a.html\" height=\"50\"}").single().initialHeight)
        assertEquals(1200, directives("::preview{FILE=\"a.html\" HEIGHT=\"5000\"}").single().initialHeight)
        assertNull(directives("::preview{file=\"a.html\" height=\"tall\"}").single().initialHeight)
    }

    @Test fun aCardIsNeverLiftedOutOfTheMiddleOfOtherMarkup() {
        assertNull(segmentPreviewDirectives("**::preview{file=\"a.html\"}**"))
        assertNull(segmentPreviewDirectives("[::preview{file=\"a.html\"}](https://example.com)"))
        assertNull(segmentPreviewDirectives("# ::preview{file=\"a.html\"}"))
        assertNull(segmentPreviewDirectives("| a |\n|---|\n| ::preview{file=\"a.html\"} |"))
    }

    @Test fun markupBesideTheDirectiveStaysProse() {
        assertEquals(
            listOf(prose("**图：**"), preview("a.html")),
            segmentPreviewDirectives("**图：** ::preview{file=\"a.html\"}"),
        )
    }

    @Test fun aDirectiveInAListIsDrawnAfterTheList() {
        assertEquals(
            listOf(prose("- 第一项\n- 第二项"), preview("a.html"), prose("后文")),
            segmentPreviewDirectives("- 第一项\n  ::preview{file=\"a.html\"}\n- 第二项\n\n后文"),
        )
        val quoted = segmentPreviewDirectives("> 引用\n> ::preview{file=\"a.html\"}").orEmpty()
        assertEquals(preview("a.html"), quoted.last())
        assertTrue(quoted.none { it is PreviewSegment.Prose && it.text.contains("::preview") })
    }

    @Test fun messagesWithoutADirectiveNeverReachTheParser() {
        assertNull(segmentPreviewDirectives("普通的回答，提到了 preview 这个词"))
        assertEquals(emptyList<PreviewDirective>(), previewDirectives("no directive here"))
    }

    @Test fun readAloudAndSearchTextDropTheDirectiveButNotTheSyntaxShownInCode() {
        assertEquals("前文\n\n后文", withoutPreviewDirectives("前文\n\n::preview{file=\"a.html\"}\n\n后文"))
        val shown = "写法：`::preview{file=\"a.html\"}`"
        assertEquals(shown, withoutPreviewDirectives(shown))
    }

    @Test fun targetsResolveTheWayUpstreamResolvesThem() {
        assertEquals(PreviewTarget.File("/Users/bs/a.html", true), resolvePreviewTarget("/Users/bs/a.html", null))
        assertEquals(PreviewTarget.File("/Users/bs/a.html", true), resolvePreviewTarget(" `/Users/bs/a.html` ", null))
        assertEquals(PreviewTarget.File("/Users/bs/a b.html", true), resolvePreviewTarget("file:///Users/bs/a%20b.html", null))
        assertEquals(PreviewTarget.File("/Users/bs/proj/out/a.html", true), resolvePreviewTarget("out/a.html", "/Users/bs/proj"))
        assertEquals(PreviewTarget.File("/Users/bs/a.html", true), resolvePreviewTarget("./../a.html", "/Users/bs/proj"))
        assertEquals(PreviewTarget.File("/a.html", true), resolvePreviewTarget("../../../a.html", "/Users"))
        // No folder known: left for the Mac to refuse, never guessed.
        assertEquals(PreviewTarget.File("a.html", true), resolvePreviewTarget("a.html", null))
        assertEquals(PreviewTarget.File("/x/page.XHTML", true), resolvePreviewTarget("/x/page.XHTML", null))
        assertEquals(PreviewTarget.File("/Users/bs/report.pdf", false), resolvePreviewTarget("/Users/bs/report.pdf", null))
        assertEquals(PreviewTarget.Web("https://example.com/a.html"), resolvePreviewTarget("https://example.com/a.html", null))
        assertNull(resolvePreviewTarget("  ", null))
        assertEquals("retention-chart.html", PreviewTarget.File("/Users/bs/.hermes/cache/scratch/retention-chart.html", true).name)
    }
}
