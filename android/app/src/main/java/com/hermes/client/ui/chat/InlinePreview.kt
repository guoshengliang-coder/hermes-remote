package com.hermes.client.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.error.artifactDownloadError
import com.hermes.client.data.repository.InlinePreviewTooLargeException
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.PREVIEW_DEFAULT_HEIGHT
import com.hermes.client.domain.PREVIEW_MAX_HEIGHT
import com.hermes.client.domain.PreviewDirective
import com.hermes.client.domain.PreviewSegment
import com.hermes.client.domain.PreviewTarget
import com.hermes.client.domain.segmentPreviewDirectives
import com.hermes.client.domain.resolvePreviewTarget
import com.hermes.client.ui.components.FileIconKind
import com.hermes.client.ui.components.attachmentIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Inline previews: upstream Hermes' `::preview{file="…"}` cards (docs/DESIGN.md §5.23,
 * docs/HERMES_CONTRACT.md §4). The grammar lives in domain/PreviewDirectives.kt; this file is
 * everything between "the answer names an HTML file" and "the chart is on screen".
 *
 * The one place this app executes HTML from a conversation. Everything below exists to keep that
 * page in a box: its own WebView per card, an opaque origin (CSP `sandbox allow-scripts`, as
 * upstream's iframe omits `allow-same-origin`), no network at all, no files, no storage, no
 * navigation, no pop-ups or dialogs, and no JavaScript bridge — the app reads the page's height
 * with `evaluateJavascript`, so the page has no way to call into the app.
 */

/** Upstream's remote preview ceiling (`REMOTE_HTML_PREVIEW_MAX_BYTES`), HR-PREVIEW-002 above it. */
internal const val INLINE_PREVIEW_MAX_BYTES = 16L * 1024L * 1024L

private const val PREVIEW_HOST = "preview.hermes.invalid"
private const val INLINE_MIN_HEIGHT = 48
// A page that never finishes loading in this long is reported instead of spinning forever.
private const val PREVIEW_LOAD_TIMEOUT_MS = 10_000L
// Content drawn by scripts after `load` (animation frames, timers) settles within a few seconds;
// measuring on this schedule and then stopping keeps an idle chat from polling forever.
private val MEASURE_SCHEDULE_MS = longArrayOf(0, 60, 160, 320, 600, 1_000, 1_600, 2_600, 4_000)

/** One rendered block of an assistant answer: Markdown prose, or a preview card. */
internal sealed interface AssistantDisplayBlock {
    data class Prose(val content: String) : AssistantDisplayBlock
    data class Preview(val directive: PreviewDirective) : AssistantDisplayBlock
}

/**
 * [markdownRenderBlocks] with each `::preview` lifted into a block of its own. Rendering, search
 * and output haptics all index blocks through this one function, so a block's number — and the
 * viewport key built from it — means the same thing to all three.
 */
internal fun assistantDisplayBlocks(text: String): List<AssistantDisplayBlock> =
    markdownRenderBlocks(text).flatMap { block ->
        segmentPreviewDirectives(block)?.map { segment ->
            when (segment) {
                is PreviewSegment.Prose -> AssistantDisplayBlock.Prose(segment.text)
                is PreviewSegment.Preview -> AssistantDisplayBlock.Preview(segment.directive)
            }
        } ?: listOf(AssistantDisplayBlock.Prose(block))
    }

internal sealed interface PreviewDocument {
    data object Loading : PreviewDocument
    data class Ready(val html: String) : PreviewDocument
    data class Failed(val error: AppError) : PreviewDocument
}

/** Fetch failures keep the shared download codes; only the frame's own limit gets a new one. */
internal fun inlinePreviewLoadError(error: Throwable): AppError = when (error) {
    is InlinePreviewTooLargeException -> AppError(
        AppErrorCode.PREVIEW_TOO_LARGE,
        retryable = false,
        technicalCause = error.message,
        stage = "inline_preview",
    )
    else -> artifactDownloadError(error, stage = "inline_preview")
}

/**
 * What a conversation screen lends its preview cards: the file fetch, the folder relative paths
 * resolve against, and the existing open/share/full-screen paths.
 *
 * Documents are fetched once per screen and shared by every card naming the same file (one
 * production session previews the same report seven times). A failure is kept too, until the
 * person asks to retry, so scrolling past a deleted file does not ask the Mac for it again.
 */
internal class InlinePreviewHost(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> String,
    private val cwd: () -> String?,
    val open: (ChatFile) -> Unit,
    val share: (ChatFile) -> Unit,
    val openFullscreen: (String) -> Unit,
    val openLink: (String) -> Unit,
    private val maxCachedChars: Long = 24L * 1024L * 1024L,
) {
    private val documents = mutableStateMapOf<String, PreviewDocument>()
    private val renderFailures = mutableStateMapOf<String, AppError>()
    private val readyChars = LinkedHashMap<String, Int>()
    private val heights = HashMap<String, Int>()

    /** Read during composition: a folder that arrives later re-resolves relative paths. */
    fun workingDirectory(): String? = cwd()
    fun document(path: String): PreviewDocument? = documents[path]
    fun request(path: String) { if (documents[path] == null) load(path) }
    fun retry(path: String) = load(path)
    fun renderFailure(path: String): AppError? = renderFailures[path]
    fun renderFailed(path: String, cause: String) {
        renderFailures[path] = AppError(AppErrorCode.PREVIEW_RENDER_FAILED, retryable = true,
            technicalCause = cause, stage = "inline_preview_render")
    }
    fun retryRender(path: String) { renderFailures.remove(path) }
    fun height(key: String): Int? = heights[key]
    fun rememberHeight(key: String, height: Int) { heights[key] = height }

    private fun load(path: String) {
        readyChars.remove(path)
        documents[path] = PreviewDocument.Loading
        scope.launch {
            val next = try {
                PreviewDocument.Ready(fetch(path))
            } catch (error: CancellationException) {
                documents.remove(path)
                throw error
            } catch (error: Throwable) {
                PreviewDocument.Failed(inlinePreviewLoadError(error))
            }
            documents[path] = next
            if (next is PreviewDocument.Ready) keep(path, next.html.length)
        }
    }

    // Oldest pages go first once the screen holds more than [maxCachedChars]; a card whose page
    // was dropped simply fetches it again the next time it is composed.
    private fun keep(path: String, chars: Int) {
        readyChars[path] = chars
        var total = readyChars.values.sumOf { it.toLong() }
        val iterator = readyChars.entries.iterator()
        while (total > maxCachedChars && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == path) continue
            total -= eldest.value
            iterator.remove()
            documents.remove(eldest.key)
        }
    }
}

internal val LocalInlinePreviewHost = staticCompositionLocalOf<InlinePreviewHost?> { null }

/** The app's live theme handed to the page under upstream's names (`var(--foreground)` …). */
internal data class PreviewTheme(
    val dark: Boolean,
    val foreground: Color,
    val mutedForeground: Color,
    val accent: Color,
    val border: Color,
    val card: Color,
)

private fun Color.css(): String = String.format(
    Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
    (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(), alpha,
)

/**
 * Upstream's theme prelude: the tokens, the colour scheme, the system font, no body margin and a
 * transparent background, so a widget-shaped page reads as part of the chat. The page's own styles
 * come later and override all of it, so a full page keeps its own design.
 */
internal fun previewThemePrelude(theme: PreviewTheme): String =
    "<style>:root{color-scheme:${if (theme.dark) "dark" else "light"};" +
        "--foreground:${theme.foreground.css()};--muted-foreground:${theme.mutedForeground.css()};" +
        "--accent:${theme.accent.css()};--border:${theme.border.css()};--card:${theme.card.css()}}" +
        "html,body{margin:0;padding:0;background:transparent;color:var(--foreground,inherit);" +
        "font-family:system-ui,sans-serif}</style>"

/**
 * Upstream's `window.hermes` with nowhere to deliver to. Widgets may send a prompt back as a hidden
 * turn on the desktop (`data-hermes-send`, `hermes.send()`); the phone does not take those yet, so
 * every send resolves `undelivered` — the answer upstream itself gives when no composer takes it —
 * instead of the widget throwing on a missing object.
 */
private const val INTENT_STUB = "<script>(function(){function send(p){return Promise.resolve(" +
    "typeof p===\"string\"&&p.trim()?{ok:false,error:\"undelivered\"}:{ok:false,error:\"invalid\"})}" +
    "window.hermes={send:send,maxLength:500};addEventListener(\"click\",function(e){var el=e.target&&" +
    "e.target.closest?e.target.closest(\"[data-hermes-send]\"):null;if(el)send(el.getAttribute(" +
    "\"data-hermes-send\")||\"\")},true)})()</script>"

private val LEADING_DOCTYPE = Regex("^\\uFEFF?\\s*(?:<!--[\\s\\S]*?-->\\s*)*<!doctype[^>]*>", RegexOption.IGNORE_CASE)

/**
 * The page as the frame loads it. Upstream hands the page to an `srcdoc` iframe, which parses in
 * standards mode whatever comes first; a top-level document only does that when its doctype comes
 * first, so the author's doctype is kept in front (or one supplied) and the prelude follows it.
 */
internal fun inlinePreviewDocument(html: String, prelude: String): String {
    val doctype = LEADING_DOCTYPE.find(html)
    val head = doctype?.value ?: "<!DOCTYPE html>"
    val rest = if (doctype == null) html else html.substring(doctype.range.last + 1)
    return head + prelude + INTENT_STUB + rest
}

/**
 * Served with the page. `sandbox allow-scripts` gives it an opaque origin, like upstream's iframe:
 * scripts run, but there are no cookies, storage, forms, pop-ups or dialogs. Nothing may be fetched;
 * the page has only what it carries inline. `shouldInterceptRequest` refuses every other request
 * and `blockNetworkLoads` stands behind both.
 */
internal const val PREVIEW_CONTENT_SECURITY_POLICY = "sandbox allow-scripts; default-src 'none'; " +
    "script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data: blob:; " +
    "font-src data:; media-src data: blob:; connect-src 'none'; form-action 'none'; base-uri 'none'"

/**
 * Fits a page wider than the frame and returns its height in CSS px (= dp here). The natural width
 * is read once, before any zoom, so the result never depends on how a WebView version reports
 * geometry under zoom; a page that only grows wide after `load` is caught on a later pass.
 */
internal const val PREVIEW_MEASURE_SCRIPT = "(function(){try{var d=document.documentElement," +
    "b=document.body;if(!d||!b)return -1;if(!window.__hermesPreviewZoom){var vw=d.clientWidth," +
    "k=b.children,l=Infinity,r=0;for(var i=0;i<k.length;i++){var x=k[i].getBoundingClientRect();" +
    "if(x.width===0&&x.height===0)continue;if(x.left<l)l=x.left;if(x.right>r)r=x.right}" +
    "var cw=Math.max(r>l?r-l:0,d.scrollWidth);if(vw>0&&cw>vw+4){window.__hermesPreviewZoom=vw/cw;" +
    "b.style.setProperty(\"zoom\",String(vw/cw),\"important\")}}var z=window.__hermesPreviewZoom||1;" +
    // A page that scrolls its body instead of the document reports its height on the body.
    "return Math.ceil(Math.max(d.scrollHeight,b.scrollHeight*z))}catch(e){return -1}})()"

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.isolateForPreview(interactive: Boolean, textZoomPercent: Int) {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        blockNetworkImage = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        textZoom = textZoomPercent
        // Layout width is the frame's own width; pinch-zoom only where the page is interactive.
        useWideViewPort = false
        loadWithOverviewMode = false
        setSupportZoom(interactive)
        builtInZoomControls = interactive
        displayZoomControls = false
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    isFocusable = interactive
    isFocusableInTouchMode = interactive
    isVerticalScrollBarEnabled = interactive
    isHorizontalScrollBarEnabled = interactive
    overScrollMode = if (interactive) View.OVER_SCROLL_IF_CONTENT_SCROLLS else View.OVER_SCROLL_NEVER
    webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }
}

private class PreviewWebViewClient(
    private val token: String,
    private val document: ByteArray,
    private val interactive: Boolean,
    private val onLoaded: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onLink: (String) -> Unit,
) : WebViewClient() {
    private fun isDocument(url: android.net.Uri) =
        url.scheme == "https" && url.host == PREVIEW_HOST && url.path == "/$token" && url.query == null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        // Nothing navigates inside the frame. A link the person taps in full screen opens in the
        // browser, exactly like a link in the chat; anything else is dropped.
        if (interactive && request.hasGesture() && request.isForMainFrame && url.scheme in setOf("http", "https") && url.host != PREVIEW_HOST) {
            view.post { onLink(url.toString()) }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        if (request.isForMainFrame && request.method == "GET" && isDocument(request.url)) {
            return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf(
                "Content-Security-Policy" to PREVIEW_CONTENT_SECURITY_POLICY,
                "Cache-Control" to "no-store",
                "Referrer-Policy" to "no-referrer",
                "X-Content-Type-Options" to "nosniff",
            ), ByteArrayInputStream(document))
        }
        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (isDocument(android.net.Uri.parse(url))) onLoaded()
    }

    // A sibling image the page could not have is not the page failing; only the document counts.
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onFailure("document error ${error.errorCode}")
    }

    // Answering true keeps the app alive when the shared renderer dies; the card then shows
    // HR-PREVIEW-001 and its Retry builds a fresh WebView.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onFailure(if (detail.didCrash()) "renderer crashed" else "renderer stopped by the system")
        return true
    }
}

@Composable
private fun rememberPreviewTheme(): PreviewTheme {
    val colors = MaterialTheme.colorScheme
    return PreviewTheme(
        dark = colors.background.luminance() < 0.5f,
        foreground = colors.onSurface,
        mutedForeground = colors.onSurfaceVariant,
        accent = colors.primary,
        border = colors.outlineVariant,
        card = colors.surfaceContainerLow,
    )
}

/**
 * One isolated page. Inline it never takes focus or touches (the card above it does); in full
 * screen it is interactive and pinch-zoomable. Either way it is fitted to the frame's width.
 */
@Composable
private fun PreviewFrame(
    html: String,
    interactive: Boolean,
    onHeight: (Int) -> Unit,
    onFailure: (String) -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = rememberPreviewTheme()
    val textZoom = (LocalDensity.current.fontScale * 100).roundToInt()
    // A new token per page and theme: the frame is rebuilt when the app switches light/dark.
    val token = remember(html, theme, textZoom) { UUID.randomUUID().toString() }
    val document = remember(token) { inlinePreviewDocument(html, previewThemePrelude(theme)).toByteArray(Charsets.UTF_8) }
    var loaded by remember(token) { mutableStateOf(false) }
    var web by remember(token) { mutableStateOf<WebView?>(null) }
    val latestOnHeight by rememberUpdatedState(onHeight)
    val latestOnFailure by rememberUpdatedState(onFailure)
    val latestOnLink by rememberUpdatedState(onLink)
    LaunchedEffect(token) {
        delay(PREVIEW_LOAD_TIMEOUT_MS)
        if (!loaded) latestOnFailure("load timed out")
    }
    LaunchedEffect(token, loaded) {
        if (!loaded) return@LaunchedEffect
        var elapsed = 0L
        for (at in MEASURE_SCHEDULE_MS) {
            delay(at - elapsed)
            elapsed = at
            val view = web ?: return@LaunchedEffect
            view.evaluateJavascript(PREVIEW_MEASURE_SCRIPT) { result ->
                result?.toDoubleOrNull()?.takeIf { it > 0 }?.let { latestOnHeight(ceil(it).toInt()) }
            }
        }
    }
    key(token) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    web = this
                    isolateForPreview(interactive, textZoom)
                    webViewClient = PreviewWebViewClient(token, document, interactive,
                        onLoaded = { loaded = true },
                        onFailure = { latestOnFailure(it) },
                        onLink = { latestOnLink(it) })
                    loadUrl("https://$PREVIEW_HOST/$token")
                }
            },
            onRelease = { view ->
                if (web === view) web = null
                view.stopLoading()
                view.destroy()
            },
            modifier = modifier,
        )
    }
}

/**
 * A `::preview` block of an assistant answer: a live card for HTML, the ordinary file card for
 * anything else (upstream's fallback too), a link for a web address.
 */
@Composable
internal fun InlinePreviewBlock(directive: PreviewDirective, anchorKey: String, streaming: Boolean) {
    val host = LocalInlinePreviewHost.current
    val cwd = host?.workingDirectory()
    val target = remember(directive.file, cwd) { resolvePreviewTarget(directive.file, cwd) } ?: return
    if (host == null) {
        // Only conversation screens lend a host; elsewhere the file is named, not dropped.
        val name = when (target) {
            is PreviewTarget.File -> target.name
            is PreviewTarget.Web -> target.url
        }
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    when (target) {
        is PreviewTarget.Web -> AssistantMarkdownBlock(
            content = "[${target.url.substringAfterLast('/').ifBlank { target.url }.replace("]", "\\]")}](<${target.url}>)",
            anchorKey = anchorKey,
            onOpenTableFullscreen = {},
        )
        is PreviewTarget.File -> if (target.isHtml) {
            InlinePreviewCard(target, directive, anchorKey, streaming, host)
        } else {
            DisableSelection {
                ChatFileList(listOf(target.asChatFile()), host.open, host.share, Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

private fun PreviewTarget.File.asChatFile() = ChatFile(
    id = "preview-${path.hashCode()}",
    name = name,
    mimeType = if (isHtml) "text/html" else null,
    remotePath = path,
)

@Composable
private fun InlinePreviewCard(
    target: PreviewTarget.File,
    directive: PreviewDirective,
    anchorKey: String,
    streaming: Boolean,
    host: InlinePreviewHost,
) {
    val language = LocalAppLanguage.current
    val viewport = LocalChatViewportController.current
    DisposableEffect(viewport, anchorKey) { onDispose { viewport?.removeBlock(anchorKey) } }
    // Mid-stream the file is often still being written; upstream waits for the turn to settle too.
    LaunchedEffect(target.path, streaming) { if (!streaming) host.request(target.path) }
    val document = if (streaming) null else host.document(target.path)
    val renderFailure = host.renderFailure(target.path)
    val heightKey = "$anchorKey\u0000${target.path}"
    val file = remember(target.path) { target.asChatFile() }
    val ready = document as? PreviewDocument.Ready
    DisableSelection {
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
                .testTag("inline-preview-$anchorKey")
                .onGloballyPositioned {
                    viewport?.updateBlock(anchorKey, it.boundsInWindow(clipBounds = false))
                    viewport?.updateOutputTail(anchorKey, it.localToWindow(Offset(0f, it.size.height.toFloat())).y)
                },
        ) {
            Column {
                PreviewHeader(
                    name = target.name,
                    onShare = { host.share(file) },
                    onExpand = if (ready != null && renderFailure == null) ({ host.openFullscreen(target.path) }) else null,
                )
                val placeholder = host.height(heightKey) ?: directive.initialHeight ?: PREVIEW_DEFAULT_HEIGHT
                when {
                    renderFailure != null -> PreviewNotice(renderFailure) { host.retryRender(target.path) }
                    document is PreviewDocument.Failed -> PreviewNotice(document.error) { host.retry(target.path) }
                    ready != null -> {
                        var measured by remember(ready.html) { mutableStateOf(host.height(heightKey)) }
                        val description = localized(language, "网页预览：${target.name}", "Preview: ${target.name}")
                        Box(Modifier.fillMaxWidth().height((measured ?: placeholder).dp)) {
                            PreviewFrame(
                                html = ready.html,
                                interactive = false,
                                onHeight = { height ->
                                    val next = height.coerceIn(INLINE_MIN_HEIGHT, PREVIEW_MAX_HEIGHT)
                                    val current = measured
                                    if (current == null || abs(next - current) > 4) {
                                        measured = next
                                        host.rememberHeight(heightKey, next)
                                    }
                                },
                                onFailure = { host.renderFailed(target.path, it) },
                                onLink = {},
                                // Unmeasured, the page lays out in a 1dp-tall frame so its height
                                // is its content's, not the placeholder's; the placeholder covers it.
                                modifier = Modifier.fillMaxWidth().height((measured ?: 1).dp),
                            )
                            if (measured == null) PreviewPlaceholder(Modifier.fillMaxWidth().height(placeholder.dp))
                            // The card, not the page, takes touches: drags scroll the chat, a tap
                            // opens the page full screen where it is interactive.
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clickable(
                                        onClickLabel = localized(language, "全屏查看", "Open full screen"),
                                        role = Role.Button,
                                    ) { host.openFullscreen(target.path) }
                                    .semantics { contentDescription = description },
                            )
                        }
                    }
                    else -> PreviewPlaceholder(Modifier.fillMaxWidth().height(placeholder.dp))
                }
            }
        }
    }
}

@Composable
private fun PreviewHeader(name: String, onShare: () -> Unit, onExpand: (() -> Unit)?) {
    val language = LocalAppLanguage.current
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(attachmentIcon(FileIconKind.WEB), contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Share, contentDescription = localized(language, "分享文件", "Share file"), modifier = Modifier.size(16.dp), tint = tint)
        }
        if (onExpand != null) {
            IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.OpenInFull, contentDescription = localized(language, "全屏查看", "Open full screen"), modifier = Modifier.size(16.dp), tint = tint)
            }
        }
    }
}

@Composable
private fun PreviewPlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewNotice(error: AppError, onRetry: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(error.localizedMessage(language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error.retryable) TextButton(onClick = onRetry) { Text(localized(language, "重试", "Retry")) }
    }
}

private tailrec fun Context.previewActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.previewActivity()
    else -> null
}

/** The page full screen and interactive, with the table viewer's close / share / rotate chrome. */
@Composable
internal fun InlinePreviewFullscreenDialog(path: String, host: InlinePreviewHost, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    val activity = LocalContext.current.previewActivity()
    var ownsOrientation by rememberSaveable(path) { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val target = remember(path) { PreviewTarget.File(path, isHtml = true) }
    LaunchedEffect(path) { host.request(path) }
    val document = host.document(path)
    val renderFailure = host.renderFailure(path)
    fun close() {
        // Hand orientation back only when this viewer's rotate button took it (as the table does).
        if (ownsOrientation) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ownsOrientation = false
        }
        onDismiss()
    }
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogView = LocalView.current
        LaunchedEffect(Unit) {
            val window = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@LaunchedEffect
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, dialogView)
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).testTag("inline-preview-fullscreen")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { close() }) { Text(localized(language, "关闭", "Close")) }
                    Text(
                        target.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { host.share(target.asChatFile()) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Share, contentDescription = localized(language, "分享文件", "Share file"), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = {
                            if (ownsOrientation && isLandscape) {
                                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                ownsOrientation = false
                            } else if (!isLandscape) {
                                ownsOrientation = true
                                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Rounded.ScreenRotation, contentDescription = localized(language, "旋转屏幕", "Rotate screen"), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    renderFailure != null -> PreviewNotice(renderFailure) { host.retryRender(path) }
                    document is PreviewDocument.Failed -> PreviewNotice(document.error) { host.retry(path) }
                    document is PreviewDocument.Ready -> PreviewFrame(
                        html = document.html,
                        interactive = true,
                        onHeight = {},
                        onFailure = { host.renderFailed(path, it) },
                        onLink = host.openLink,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    else -> PreviewPlaceholder(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
