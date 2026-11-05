package com.hermes.client.ui.chat

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.InlinePreviewTooLargeException
import com.hermes.client.data.repository.decodeHtml
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.PreviewDirective
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InlinePreviewTest {
    private val directive = "::preview{file=\"/Users/bs/.hermes/cache/scratch/retention-chart.html\"}"
    private val answer = "**结论：留存并不是瓶颈。**\n\n$directive\n\n加权留存（按注册批次）："

    @Test fun answerBlocksPutTheCardBetweenItsParagraphs() {
        assertEquals(listOf(
            AssistantDisplayBlock.Prose("**结论：留存并不是瓶颈。**"),
            AssistantDisplayBlock.Preview(PreviewDirective("/Users/bs/.hermes/cache/scratch/retention-chart.html")),
            AssistantDisplayBlock.Prose("加权留存（按注册批次）："),
        ), assistantDisplayBlocks(answer))
        // Without a directive the blocks are exactly the Markdown blocks they always were.
        val plain = "第一段\n\n第二段"
        assertEquals(markdownRenderBlocks(plain).map { AssistantDisplayBlock.Prose(it) }, assistantDisplayBlocks(plain))
    }

    @Test fun searchNeverCountsThePathBehindACard() {
        val messages = listOf(ChatMessage(id = "a1", role = Role.ASSISTANT, text = answer))
        assertEquals(0, searchHits(messages, "retention").size)
        assertEquals(1, searchHits(messages, "瓶颈").size)
    }

    @Test fun readAloudSkipsTheDirective() {
        val spoken = speechText(answer)
        assertFalse(spoken.contains("preview"))
        assertFalse(spoken.contains("/Users"))
        assertTrue(spoken.contains("加权留存"))
    }

    /** Output haptics keys must name the same blocks AssistantTurn renders. */
    @Test fun outputHapticBlocksSkipTheCardButKeepItsIndex() {
        val message = ChatMessage(id = "a1", role = Role.ASSISTANT, text = answer)
        assertEquals(listOf("a1:markdown:0", "a1:markdown:2"), finalOutputHapticBlocks(message, "placeholder").map { it.first })
        val presentation = outputHapticPresentation(message, "placeholder")
        assertEquals("a1:markdown:2", presentation.tailKey)
        assertEquals(listOf("a1:markdown:0", "a1:markdown:2"), presentation.blockKeys)
        assertFalse(presentation.text.contains("preview"))
    }

    @Test fun documentKeepsTheAuthorsDoctypeFirst() {
        val framed = inlinePreviewDocument("<!DOCTYPE html>\n<html><body><p>x</p></body></html>", "<style>p{}</style>")
        assertTrue(framed.startsWith("<!DOCTYPE html><style>p{}</style><script>"))
        assertTrue(framed.endsWith("\n<html><body><p>x</p></body></html>"))
        // A BOM or a comment ahead of the doctype stays ahead of it.
        val bom = Char(0xFEFF)
        assertTrue(inlinePreviewDocument("$bom<!-- note --><!doctype html><p>x</p>", "<style></style>")
            .startsWith("$bom<!-- note --><!doctype html><style></style>"))
    }

    @Test fun documentWithoutADoctypeStillParsesInStandardsMode() {
        val framed = inlinePreviewDocument("<div>chart</div>", "<style></style>")
        assertTrue(framed.startsWith("<!DOCTYPE html><style></style><script>"))
        assertTrue(framed.endsWith("<div>chart</div>"))
    }

    @Test fun widgetsCanStillCallHermesSendAndAreToldItWasNotDelivered() {
        val framed = inlinePreviewDocument("<p>x</p>", "")
        assertTrue(framed.contains("window.hermes={send:send,maxLength:500}"))
        assertTrue(framed.contains("error:\"undelivered\""))
        assertTrue(framed.contains("[data-hermes-send]"))
    }

    @Test fun themePreludeHandsTheAppThemeToThePageUnderUpstreamsNames() {
        val prelude = previewThemePrelude(PreviewTheme(
            dark = true,
            foreground = Color(0xFFEEEEEE),
            mutedForeground = Color(0xFF999999),
            accent = Color(0xFFCC7755),
            border = Color(0x33FFFFFF),
            card = Color(0xFF202020),
        ))
        assertTrue(prelude.contains("color-scheme:dark"))
        assertTrue(prelude.contains("--foreground:rgba(238,238,238,1.000)"))
        assertTrue(prelude.contains("--muted-foreground:rgba(153,153,153,1.000)"))
        assertTrue(prelude.contains("--accent:rgba(204,119,85,1.000)"))
        assertTrue(prelude.contains("--border:rgba(255,255,255,0.200)"))
        assertTrue(prelude.contains("--card:rgba(32,32,32,1.000)"))
        assertTrue(prelude.contains("background:transparent"))
    }

    @Test fun pagePolicyIsAnOpaqueOriginWithNoNetwork() {
        val policy = PREVIEW_CONTENT_SECURITY_POLICY
        assertTrue(policy.startsWith("sandbox allow-scripts;"))
        assertFalse(policy.contains("allow-same-origin"))
        assertFalse(policy.contains("allow-popups"))
        assertFalse(policy.contains("allow-forms"))
        assertTrue(policy.contains("default-src 'none'"))
        assertTrue(policy.contains("connect-src 'none'"))
        assertFalse(policy.contains("http"))
    }

    @Test fun loadFailuresKeepTheDownloadCodesAndTheFrameLimitHasItsOwn() {
        val tooLarge = inlinePreviewLoadError(InlinePreviewTooLargeException(INLINE_PREVIEW_MAX_BYTES))
        assertEquals(AppErrorCode.PREVIEW_TOO_LARGE, tooLarge.code)
        assertFalse(tooLarge.retryable)
        assertEquals(AppErrorCode.ARTIFACT_MISSING, inlinePreviewLoadError(HermesApiException(404, "not found")).code)
        assertEquals(AppErrorCode.ARTIFACT_FORBIDDEN, inlinePreviewLoadError(HermesApiException(403, "forbidden")).code)
        val transfer = inlinePreviewLoadError(java.io.IOException("reset"))
        assertEquals(AppErrorCode.ARTIFACT_DOWNLOAD_FAILED, transfer.code)
        assertTrue(transfer.retryable)
        assertTrue(transfer.localizedMessage(AppLanguage.ZH).contains("HR-FILE-006"))
    }

    @Test fun htmlBytesDecodeWithTheirByteOrderMark() {
        assertEquals("<p>中</p>", decodeHtml("<p>中</p>".toByteArray(Charsets.UTF_8)))
        assertEquals("<p>x</p>", decodeHtml(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<p>x</p>".toByteArray()))
        assertEquals("<p>x</p>", decodeHtml(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<p>x</p>".toByteArray(Charsets.UTF_16LE)))
    }

    private fun host(scope: TestScope, fetch: suspend (String) -> String, maxCachedChars: Long = 1_000_000) = InlinePreviewHost(
        scope = scope,
        fetch = fetch,
        cwd = { null },
        open = {},
        share = {},
        openFullscreen = {},
        openLink = {},
        maxCachedChars = maxCachedChars,
    )

    @Test fun cardsNamingTheSameFileShareOneFetch() = runTest(StandardTestDispatcher()) {
        var fetches = 0
        val host = host(this, { fetches++; "<p>$it</p>" })
        host.request("/a.html")
        host.request("/a.html")
        assertEquals(PreviewDocument.Loading, host.document("/a.html"))
        advanceUntilIdle()
        assertEquals(PreviewDocument.Ready("<p>/a.html</p>"), host.document("/a.html"))
        host.request("/a.html")
        advanceUntilIdle()
        assertEquals(1, fetches)
    }

    @Test fun aFailureIsKeptUntilThePersonRetries() = runTest(StandardTestDispatcher()) {
        var fail = true
        var fetches = 0
        val host = host(this, { fetches++; if (fail) throw HermesApiException(404, "gone") else "<p>ok</p>" })
        host.request("/gone.html")
        advanceUntilIdle()
        val failed = host.document("/gone.html") as PreviewDocument.Failed
        assertEquals(AppErrorCode.ARTIFACT_MISSING, failed.error.code)
        // Scrolling past the card again must not ask the Mac again.
        host.request("/gone.html")
        advanceUntilIdle()
        assertEquals(1, fetches)
        fail = false
        host.retry("/gone.html")
        advanceUntilIdle()
        assertEquals(PreviewDocument.Ready("<p>ok</p>"), host.document("/gone.html"))
    }

    @Test fun oldestPagesAreDroppedPastTheBudget() = runTest(StandardTestDispatcher()) {
        val host = host(this, { "x".repeat(60) }, maxCachedChars = 100)
        host.request("/1.html"); advanceUntilIdle()
        host.request("/2.html"); advanceUntilIdle()
        assertNull(host.document("/1.html"))
        assertTrue(host.document("/2.html") is PreviewDocument.Ready)
    }

    @Test fun aRenderFailureIsReportedWithItsOwnRetryableCode() = runTest(StandardTestDispatcher()) {
        val host = host(this, { "<p>x</p>" })
        host.renderFailed("/a.html", "renderer crashed")
        val failure = host.renderFailure("/a.html")!!
        assertEquals(AppErrorCode.PREVIEW_RENDER_FAILED, failure.code)
        assertTrue(failure.retryable)
        assertTrue(failure.localizedMessage(AppLanguage.EN).contains("HR-PREVIEW-001"))
        host.retryRender("/a.html")
        assertNull(host.renderFailure("/a.html"))
    }
}
