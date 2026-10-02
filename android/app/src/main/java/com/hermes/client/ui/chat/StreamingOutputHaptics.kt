package com.hermes.client.ui.chat

import android.os.Build
import android.os.SystemClock
import com.hermes.client.data.haptics.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.ToolStatus
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Consumes every display snapshot, including suppressed ones. There is no pending pulse queue. */
internal class OutputHapticPolicy(private val intervalMs: Long = 100L) : DefaultLifecycleObserver {
    private var streamId: String? = null
    private var previous = ""
    private var wasEligible = false
    private var lastPulse: Long? = null

    // Background frame clocks may stop entirely: reset at the event boundary, even when
    // there was no suppressed display snapshot to observe before returning.
    fun resetEligibility() { wasEligible = false }
    override fun onPause(owner: LifecycleOwner) { resetEligibility() }
    override fun onResume(owner: LifecycleOwner) { resetEligibility() }

    fun observe(id: String?, text: String, eligible: Boolean, nowMs: Long, minimumIntervalMs: Long = intervalMs): Boolean {
        val sameStream = id != null && id == streamId
        val appended = sameStream && text.length > previous.length && text.startsWith(previous) &&
            text.substring(previous.length).any { !it.isWhitespace() }
        val pulse = appended && eligible && wasEligible &&
            (lastPulse == null || nowMs - lastPulse!! >= minimumIntervalMs)
        streamId = id
        previous = text
        wasEligible = eligible
        if (pulse) lastPulse = nowMs
        return pulse
    }
}

internal fun outputHapticConstant(sdk: Int): Int = systemHapticEffect(OutputHapticType.SYSTEM_TICK, sdk)

internal data class OutputHapticPresentation(
    val text: String,
    val tailKey: String?,
    val blockKeys: List<String> = emptyList(),
)

/** The receiving-tool placeholder is presentation chrome, not assistant prose. */
internal fun outputHapticPresentation(message: ChatMessage?, placeholder: String): OutputHapticPresentation {
    if (message == null || message.isError) return OutputHapticPresentation("", null)
    val blocks = markdownRenderBlocks(message.text)
    val last = blocks.indexOfLast { it.trim() != "*$placeholder*" && it.isNotBlank() }
    val prose = message.text.removeSuffix("*$placeholder*").trimEnd()
    return OutputHapticPresentation(
        readableText(prose),
        last.takeIf { it >= 0 }?.let { "${message.id}:markdown:$it" },
        blocks.indices.filter { blocks[it].trim() != "*$placeholder*" && blocks[it].isNotBlank() }
            .map { "${message.id}:markdown:$it" },
    )
}

/** Feedback follows the content the Markdown renderer actually published, which may lag
 * the next input snapshot indefinitely during continuous output. Requiring exact equality
 * with that newer input starved all pulses even though parsed prose was visibly growing.
 */
internal class ParsedOutputHapticPresentation {
    private var previousBlocks = emptyList<Pair<String, String>>()
    private var previous = OutputHapticPresentation("", null)

    fun read(target: OutputHapticPresentation, viewport: ChatViewportController): OutputHapticPresentation {
        val painted = target.blockKeys.mapNotNull { key ->
            viewport.parsedOutputContent(key)?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        // Do not re-run the full prose extractor on every polling frame while output is paused.
        if (painted == previousBlocks) return previous
        previousBlocks = painted
        previous = OutputHapticPresentation(
            readableText(painted.joinToString("\n\n") { it.second }),
            painted.lastOrNull()?.first,
        )
        return previous
    }
}

/** A natural completion may still have paced/parsed output. Never admit historical changes. */
internal class OutputHapticRun {
    var observedLive = false
        private set
    private var finished = false
    var discarded = false
        private set

    fun allows(source: ChatMessage?, generating: Boolean): Boolean {
        if (source == null) return false
        if (source.isError || source.interrupted) discarded = true
        if (discarded) return false
        if (source.isStreaming && generating) {
            observedLive = true
            finished = false
        }
        return observedLive && !finished && (generating || !source.isStreaming)
    }

    fun finish() { finished = true }
}

/** Build final parser inputs once at completion, never on each growing display snapshot. */
internal fun finalOutputHapticBlocks(message: ChatMessage?, placeholder: String): List<Pair<String, String>> {
    if (message == null) return emptyList()
    return markdownRenderBlocks(message.text).mapIndexedNotNull { index, block ->
        if (block.trim() == "*$placeholder*" || block.isBlank()) null
        else "${message.id}:markdown:$index" to withCjkEmphasisRepaired(block)
    }
}

internal fun finalOutputIsPainted(blocks: List<Pair<String, String>>, viewport: ChatViewportController): Boolean =
    blocks.all { (key, content) -> viewport.parsedOutputContent(key) == content }

@Composable
internal fun StreamingOutputHaptics(
    sessionId: String,
    source: ChatMessage?,
    displayed: ChatMessage?,
    enabled: Boolean,
    generating: Boolean,
    config: OutputHapticConfig = OutputHapticConfig(),
    viewport: ChatViewportController,
    toolDataPlaceholder: String,
) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    val player = remember(view) { OutputHapticPlayer(AndroidOutputHapticDevice(view)) }
    val latestConfig by rememberUpdatedState(config.normalized())
    val policy = remember(sessionId) { OutputHapticPolicy() }
    val run = remember(sessionId, source?.id) { OutputHapticRun() }
    val latestSource by rememberUpdatedState(source)
    val latestGenerating by rememberUpdatedState(generating)
    val paintedProse = remember(sessionId) { ParsedOutputHapticPresentation() }
    val presentation = remember(displayed?.id, displayed?.text, displayed?.isError, toolDataPlaceholder) {
        outputHapticPresentation(displayed, toolDataPlaceholder)
    }
    val latestPresentation by rememberUpdatedState(presentation)
    val finalPresentation = remember(source?.id, source?.text, source?.isStreaming, source?.isError, toolDataPlaceholder) {
        finalOutputHapticBlocks(source?.takeIf { !it.isStreaming }?.organizedForDisplay(), toolDataPlaceholder)
    }
    val latestFinalPresentation by rememberUpdatedState(finalPresentation)
    val runAllowed = run.allows(source, generating)
    val allowed = enabled && runAllowed && source?.tools?.none { it.status == ToolStatus.RUNNING } == true
    val latestAllowed by rememberUpdatedState(allowed)
    DisposableEffect(lifecycle, policy) {
        val cancellation = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) { player.cancel() }
        }
        lifecycle.addObserver(cancellation)
        lifecycle.addObserver(policy)
        onDispose { lifecycle.removeObserver(policy); lifecycle.removeObserver(cancellation); player.cancel() }
    }
    DisposableEffect(policy, allowed, window.isWindowFocused, config) {
        policy.resetEligibility()
        player.cancel()
        onDispose { policy.resetEligibility(); player.cancel() }
    }
    val activeId = source?.id
    LaunchedEffect(sessionId, activeId, source?.isStreaming) {
        if (activeId == null || !run.observedLive) return@LaunchedEffect
        // Keep the policy baseline across network completion: only the parser/reveal changes.
        // New ids and eligibility transitions already establish a silent baseline in observe().
        var loggedGate: String? = null
        var loggedGateAt: Long? = null
        var loggedAcceptance: HapticRequestResult? = null
        while (isActive) {
            // Wait for placement of the paced display snapshot, not for a WebSocket packet.
            withFrameNanos { }
            val target = latestPresentation
            val current = paintedProse.read(target, viewport)
            val eligible = latestAllowed && run.allows(latestSource, latestGenerating) && lifecycle.currentState == Lifecycle.State.RESUMED &&
                window.isWindowFocused && view.isShown && view.hasWindowFocus() &&
                current.tailKey?.let(viewport::isOutputTailVisible) == true
            val now = SystemClock.uptimeMillis()
            val gate = when {
                !latestAllowed -> "disabled_or_not_outputting"
                lifecycle.currentState != Lifecycle.State.RESUMED -> "not_resumed"
                !window.isWindowFocused || !view.hasWindowFocus() -> "not_focused"
                !view.isShown -> "view_hidden"
                current.tailKey == null -> if (target.tailKey == null) "no_prose" else "waiting_for_markdown"
                !viewport.isOutputTailVisible(current.tailKey) -> "tail_offscreen"
                else -> "ready"
            }
            // Bound diagnostics too: async parse/layout may alternate each display snapshot.
            if (gate != loggedGate && (loggedGateAt == null || now - loggedGateAt!! >= 1000L)) {
                DebugLog.log("haptics") { "output gate=$gate sdk=${Build.VERSION.SDK_INT}" }
                loggedGate = gate
                loggedGateAt = now
            }
            if (!eligible) player.cancel()
            if (policy.observe(activeId, current.text, eligible, now, latestConfig.intervalMs.toLong())) {
                val result = player.request(latestConfig)
                if (result != loggedAcceptance) {
                    DebugLog.log("haptics") {
                        "output request type=${latestConfig.type} result=$result interval=${latestConfig.intervalMs}"
                    }
                    loggedAcceptance = result
                }
            }
            // Consume the final visible growth first, then retire this run. Waiting on exact
            // repaired parser inputs handles delayed Markdown, code, tables and CJK emphasis.
            if (run.discarded || (latestSource?.isStreaming == false && finalOutputIsPainted(latestFinalPresentation, viewport))) {
                run.finish()
                break
            }
            delay(64L)
        }
    }
}
