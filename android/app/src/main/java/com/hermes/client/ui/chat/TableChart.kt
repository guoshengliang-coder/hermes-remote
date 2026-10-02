package com.hermes.client.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID

internal fun chartKey(scope: List<String>, identity: String, raw: String): String {
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    return hash(org.json.JSONArray(scope + identity).toString()) + "." + hash(raw)
}

internal class ChartPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("table_charts", Context.MODE_PRIVATE)
    fun preference(): String = prefs.getString("presentation", "table").takeIf { it in listOf("table", "chart", "auto") } ?: "table"
    fun read(key: String): JSONObject? = prefs.getString(key, null)?.let { JSONObject(it) }
    /** Disk writes are called on IO. A failed commit is a user-visible failure, not a silent save. */
    fun save(key: String, state: JSONObject) {
        require(state.toString().length <= 32_000)
        val edit = prefs.edit().putString(key, state.toString())
        val keys = prefs.all.keys.filter { it != "presentation" && it != key }
        if (keys.size >= 500) keys.take(keys.size - 499).forEach(edit::remove)
        check(edit.commit())
    }
    fun setPreference(value: String) { require(value in listOf("table", "chart", "auto")); check(prefs.edit().putString("presentation", value).commit()) }
}

internal class TableChartController(
    val key: String, val raw: String, val prompt: String, val source: String,
    val preferences: ChartPreferences,
) {
    var state: JSONObject? = null
    var view by mutableStateOf("table")
    var activated by mutableStateOf(false)
    var eligible by mutableStateOf(true)
    var reason by mutableStateOf("")
    var failure by mutableStateOf<AppError?>(null)
    var rendererGeneration by mutableIntStateOf(0)
    var contentHeight by mutableIntStateOf(780)
    var releaseRendererFocus: (() -> Unit)? = null
    var cachedModel: JSONObject? = null // In-memory only; source cells never enter preferences.
    var preference = "table"
    init {
        try { state = preferences.read(key); preference = preferences.preference(); view = state?.optString("view", "table") ?: "table"; activated = true }
        catch (_: Exception) { failure = AppError(AppErrorCode.CHART_SETTINGS_FAILED, true) }
    }
    fun choose(value: String) {
        if (value == "table") releaseRendererFocus?.invoke()
        view = value
        if (value == "chart") activated = true
        state = (state ?: JSONObject()).put("view", value)
    }
    fun retry() { failure = null; rendererGeneration++; activated = true; view = "chart" }
}

internal class TableChartScope(
    private val context: Context,
    val namespace: List<String>,
    val messages: () -> List<ChatMessage>,
    val active: (String, String) -> Unit,
    val fullscreenKey: () -> String?,
    val prepare: (String) -> Unit,
) {
    private val controllers = mutableMapOf<String, TableChartController>()
    private val preferences = ChartPreferences(context)
    fun get(raw: String, identity: String, savedKey: String? = null): TableChartController {
        val key = savedKey ?: chartKey(namespace, identity, raw)
        return controllers.getOrPut(key) {
            val rows = messages(); val messageId = identity.substringBefore(":markdown:")
            val index = rows.indexOfFirst { it.id == messageId }
            val prompt = rows.take(index.coerceAtLeast(0)).lastOrNull { it.role == Role.USER && it.displayKind == null }?.text.orEmpty()
            val sourceText = rows.getOrNull(index)?.text.orEmpty().lineSequence().filter { it.contains(Regex("来源|数据源|source", RegexOption.IGNORE_CASE)) && !it.trim().startsWith("|") }.joinToString(" ").take(500)
            TableChartController(key, raw, prompt, listOfNotNull("${namespace.lastOrNull().orEmpty()} · $identity", sourceText.takeIf { it.isNotBlank() }).joinToString(" · "), preferences)
        }
    }
    fun complete(identity: String): Boolean = messages().firstOrNull { it.id == identity.substringBefore(":markdown:") }?.isStreaming != true
}
internal val LocalTableChartScope = staticCompositionLocalOf<TableChartScope?> { null }

@Composable
internal fun rememberTableChart(raw: String, identity: String, savedKey: String? = null): TableChartController? {
    val scope = LocalTableChartScope.current ?: return null
    val complete = scope.complete(identity)
    return if (complete) remember(scope, raw, identity, savedKey) { scope.get(raw, identity, savedKey) } else null
}

@Composable
internal fun TableChartToggle(controller: TableChartController, modifier: Modifier = Modifier) {
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    DisableSelection {
        Row(modifier) {
            listOf("table" to localized(language, "表格", "Table"), "chart" to localized(language, "图表", "Chart")).forEach { (value, label) ->
                TextButton(onClick = {
                    controller.choose(value)
                    scope.launch { try { withContext(Dispatchers.IO) { synchronized(controller) { controller.preferences.save(controller.key, controller.state!!) } } } catch (_: Exception) { controller.failure = AppError(AppErrorCode.CHART_SETTINGS_FAILED, true) } }
                }, enabled = value != "chart" || controller.eligible, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                    Text(label, color = if (controller.view == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

internal object TableChartIsolation {
    const val ORIGIN = "https://chart.hermes.invalid"
    val FILES = setOf("chart.html", "engine.js", "chart.js", "chart.css")
    fun file(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "chart.hermes.invalid" || uri.port != -1 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        return uri.rawPath?.removePrefix("/")?.takeIf { it in FILES }
    }
}

/** Only the packaged four files can load; the bridge accepts state or a draft request, never RPC. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OfflineTableChart(controller: TableChartController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = LocalTableChartScope.current
    val coroutineScope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminanceCompat() < .5f
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val latestFontScale by rememberUpdatedState(fontScale)
    val latestDark by rememberUpdatedState(dark)
    val nonce = remember(controller, controller.rendererGeneration) { UUID.randomUUID().toString() }
    var loaded by remember(controller, controller.rendererGeneration) { mutableStateOf(false) }
    var web by remember(controller, controller.rendererGeneration) { mutableStateOf<WebView?>(null) }
    val latestLanguage by rememberUpdatedState(language)
    val latestScope by rememberUpdatedState(scope)
    val releaseFocus: () -> Unit = remember(nonce) { { web?.clearFocus() } }
    val mounted = remember(nonce) { java.util.concurrent.atomic.AtomicBoolean(true) }
    fun error(code: AppErrorCode = AppErrorCode.CHART_RENDER_FAILED) { if (code == AppErrorCode.CHART_RENDER_FAILED) controller.releaseRendererFocus?.invoke(); controller.failure = AppError(code, code != AppErrorCode.CHART_RANGE_EXCEEDED); if (code == AppErrorCode.CHART_RENDER_FAILED) controller.view = "table" }
    fun init(view: WebView) {
        val config = JSONObject().put("protocol", 1).put("type", "init").put("nonce", nonce).put("raw", controller.raw).put("prompt", controller.prompt).put("source", controller.source)
            .put("language", if (latestLanguage == AppLanguage.EN) "en" else "zh").put("theme", if (latestDark) "dark" else "light").put("preference", controller.preference).put("fontScale", latestFontScale)
        controller.state?.let { config.put("state", it) }; controller.cachedModel?.let { config.put("model", it) }
        view.evaluateJavascript("window.HermesChartReceive($config)", null)
    }
    LaunchedEffect(nonce) { kotlinx.coroutines.delay(10_000); if (!loaded) error() }
    LaunchedEffect(controller.view, language, dark) {
        web?.let { view -> if (loaded) {
            view.evaluateJavascript("window.HermesChartReceive(${JSONObject().put("protocol", 1).put("type", "view").put("nonce", nonce).put("view", controller.view)})", null)
        } }
    }
    LaunchedEffect(language, dark, fontScale) { web?.let { if (loaded) init(it) } }
    DisposableEffect(controller, nonce) { onDispose { mounted.set(false); if (controller.releaseRendererFocus === releaseFocus) controller.releaseRendererFocus = null; web?.let { it.removeJavascriptInterface("HermesChartBridge"); it.stopLoading(); it.destroy() }; web = null } }
    key(nonce) {
        AndroidView(modifier = modifier, factory = {
            WebView(context).apply {
                web = this
                controller.releaseRendererFocus = releaseFocus
                val isolatedView = this
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                isLongClickable = false
                webChromeClient = object : android.webkit.WebChromeClient() { override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() } }
                addJavascriptInterface(object {
                    @JavascriptInterface fun post(payload: String) {
                        if (payload.length > 4_000_000) { isolatedView.post { error() }; return }
                        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return
                        if (data.optInt("protocol") != 1 || data.optString("nonce") != nonce) return
                        isolatedView.post {
                            if (!mounted.get()) return@post
                            when (data.optString("type")) {
                                "state" -> {
                                    val next = data.optJSONObject("state") ?: return@post
                                    if (next.optString("view") !in listOf("table", "chart")) return@post
                                    controller.state = next
                                    controller.view = next.optString("view")
                                    controller.eligible = data.optBoolean("eligible", false)
                                    controller.reason = data.optString("reason").take(500)
                                    data.optJSONObject("model")?.let { controller.cachedModel = it }
                                    loaded = true
                                    coroutineScope.launch { try { withContext(Dispatchers.IO) { synchronized(controller) { controller.preferences.save(controller.key, controller.state ?: next) } } } catch (_: Exception) { error(AppErrorCode.CHART_SETTINGS_FAILED) } }
                                }
                                "height" -> controller.contentHeight = data.optInt("height", 780).coerceIn(320, 2048)
                                "error" -> error()
                                "range" -> error(AppErrorCode.CHART_RANGE_EXCEEDED)
                                "query" -> {
                                    val range = data.optString("range").trim().takeIf { it.isNotEmpty() && it.length <= 500 } ?: return@post
                                    val columns = controller.cachedModel?.optJSONArray("headers")?.let { a -> (0 until a.length()).joinToString("、") { a.optString(it) } }.orEmpty()
                                    val question = localized(latestLanguage, "请从原表来源重新取数：${controller.source}。原表列：$columns。所需范围：$range。沿用原指标口径与单位，注明来源和实际覆盖范围，不推算缺失数据。", "Please fetch fresh data from the original source of ${controller.source}. Columns: $columns. Requested range: $range. Keep metric definitions and units; state the source and actual coverage. Do not extrapolate missing data.")
                                    controller.releaseRendererFocus?.invoke()
                                    latestScope?.prepare?.invoke(question)
                                }
                            }
                        }
                    }
                }, "HermesChartBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val file = TableChartIsolation.file(request.url.toString())
                        if (file == null || request.method != "GET" || request.isForMainFrame && file != "chart.html") return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        val mime = if (file.endsWith("html")) "text/html" else if (file.endsWith("css")) "text/css" else "text/javascript"
                        return runCatching { WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; img-src 'none'; base-uri 'none'; form-action 'none'"), context.assets.open("table-chart/$file")) }.getOrElse { view.post { error() }; WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) }
                    }
                    override fun onPageFinished(view: WebView, url: String) { if (TableChartIsolation.file(url) == "chart.html") init(view) }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: android.webkit.WebResourceError) { error() }
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean { error(); return true }
                }
                loadUrl("${TableChartIsolation.ORIGIN}/chart.html")
            }
        })
    }
}
private fun androidx.compose.ui.graphics.Color.luminanceCompat(): Float = .2126f * red + .7152f * green + .0722f * blue

@Composable
internal fun TableChartNotice(controller: TableChartController) {
    val language = LocalAppLanguage.current
    DisableSelection {
        controller.failure?.let { failure ->
            Column(Modifier.padding(12.dp)) {
                Text(failure.localizedMessage(language), style = MaterialTheme.typography.bodySmall)
                if (failure.retryable) TextButton(onClick = controller::retry) { Text(localized(language, "重试", "Retry")) }
            }
        }
        if (!controller.eligible && controller.reason.isNotBlank()) Text(controller.reason, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
