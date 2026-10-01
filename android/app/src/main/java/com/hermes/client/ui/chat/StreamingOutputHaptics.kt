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
        val appended = sameStream && text.startsWith(previous) && text.length > previous.length &&
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
    sdk >= 34 -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    sdk >= 27 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
    else -> HapticFeedbackConstants.CLOCK_TICK
}

internal data class OutputHapticPresentation(val text: String, val tailKey: String?, val tailContent: String = "")

/** The receiving-tool placeholder is presentation chrome, not assistant prose. */
internal fun outputHapticPresentation(message: ChatMessage?, placeholder: String): OutputHapticPresentation {
    if (message == null || message.isError) return OutputHapticPresentation("", null)
    val blocks = markdownRenderBlocks(message.text)
    val last = blocks.indexOfLast { it.trim() != "*$placeholder*" && it.isNotBlank() }
    val prose = message.text.removeSuffix("*$placeholder*").trimEnd()
    return OutputHapticPresentation(
        readableText(prose),
        last.takeIf { it >= 0 }?.let { "${message.id}:markdown:$it" },
        last.takeIf { it >= 0 }?.let { withCjkEmphasisRepaired(blocks[it]) }.orEmpty(),
    )
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
        policy.observe(activeId, latestPresentation.text, false, SystemClock.uptimeMillis())
        if (activeId == null) return@LaunchedEffect
        while (isActive) {
            // Wait for placement of the paced display snapshot, not for a WebSocket packet.
            withFrameNanos { }
            val current = latestPresentation
            val eligible = latestAllowed && lifecycle.currentState == Lifecycle.State.RESUMED &&
                window.isWindowFocused && view.isShown && view.hasWindowFocus() &&
                current.tailKey?.let(viewport::isOutputTailVisible) == true
            // Async Markdown parsing can still be drawing the previous snapshot. Wait for its
            // actual content; suppressed snapshots must still be consumed to prevent replay.
            val parsed = current.tailKey?.let { viewport.isOutputSnapshotParsed(it, current.tailContent) } == true
            if ((!eligible || parsed) && policy.observe(activeId, current.text, eligible, SystemClock.uptimeMillis())) {
                // No ignore-setting flags, VIBRATE permission, waveform, or strong fallback.
                view.performHapticFeedback(outputHapticConstant(Build.VERSION.SDK_INT))
            }
            delay(64L)
        }
    }
}
