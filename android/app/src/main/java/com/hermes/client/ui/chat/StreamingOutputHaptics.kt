package com.hermes.client.ui.chat

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
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

    fun observe(id: String?, text: String, eligible: Boolean, nowMs: Long): Boolean {
        val sameStream = id != null && id == streamId
        val appended = sameStream && text.length > previous.length && text.startsWith(previous) &&
            text.substring(previous.length).any { !it.isWhitespace() }
        val pulse = appended && eligible && wasEligible &&
            (lastPulse == null || nowMs - lastPulse!! >= intervalMs)
        streamId = id
        previous = text
        wasEligible = eligible
        if (pulse) lastPulse = nowMs
        return pulse
    }
}

internal fun outputHapticConstant(sdk: Int): Int = when {
    // All three former constants select texture tick in AOSP, which may be silent on
    // phones that cannot produce that very soft effect. Request the ordinary system
    // tick instead; View still owns system settings, intensity and device adaptation.
    sdk >= 34 -> HapticFeedbackConstants.SEGMENT_TICK
    else -> HapticFeedbackConstants.CONTEXT_CLICK
}

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

@Composable
internal fun StreamingOutputHaptics(
    sessionId: String,
    source: ChatMessage?,
    displayed: ChatMessage?,
    enabled: Boolean,
    generating: Boolean,
    viewport: ChatViewportController,
    toolDataPlaceholder: String,
) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    val policy = remember(sessionId) { OutputHapticPolicy() }
    val paintedProse = remember(sessionId) { ParsedOutputHapticPresentation() }
    val presentation = remember(displayed?.id, displayed?.text, displayed?.isError, toolDataPlaceholder) {
        outputHapticPresentation(displayed, toolDataPlaceholder)
    }
    val latestPresentation by rememberUpdatedState(presentation)
    val allowed = enabled && generating && source?.isStreaming == true &&
        source.tools.none { it.status == ToolStatus.RUNNING }
    val latestAllowed by rememberUpdatedState(allowed)
    DisposableEffect(lifecycle, policy) {
        lifecycle.addObserver(policy)
        onDispose { lifecycle.removeObserver(policy) }
    }
    DisposableEffect(policy, allowed, window.isWindowFocused) {
        policy.resetEligibility()
        onDispose { policy.resetEligibility() }
    }
    val activeId = source?.takeIf { it.isStreaming }?.id
    LaunchedEffect(sessionId, activeId) {
        // Prime on entry/new turns with what is already painted. No historical output is replayed.
        policy.observe(activeId, paintedProse.read(latestPresentation, viewport).text, false, SystemClock.uptimeMillis())
        if (activeId == null) return@LaunchedEffect
        var loggedGate: String? = null
        var loggedGateAt: Long? = null
        var loggedAcceptance: Boolean? = null
        while (isActive) {
            // Wait for placement of the paced display snapshot, not for a WebSocket packet.
            withFrameNanos { }
            val target = latestPresentation
            val current = paintedProse.read(target, viewport)
            val eligible = latestAllowed && lifecycle.currentState == Lifecycle.State.RESUMED &&
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
            if (policy.observe(activeId, current.text, eligible, now)) {
                // No ignore-setting flags, VIBRATE permission, waveform, or strong fallback.
                val effect = outputHapticConstant(Build.VERSION.SDK_INT)
                val accepted = view.performHapticFeedback(effect)
                if (accepted != loggedAcceptance) {
                    DebugLog.log("haptics") {
                        "output request effect=$effect accepted=$accepted viewEnabled=${view.isHapticFeedbackEnabled}"
                    }
                    loggedAcceptance = accepted
                }
            }
            delay(64L)
        }
    }
}
