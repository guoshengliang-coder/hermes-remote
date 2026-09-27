package com.hermes.client.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceHoldButton(
    label: String,
    enabled: Boolean,
    onDown: () -> Unit,
    onZone: (VoiceReleaseAction) -> Unit,
    onRelease: (VoiceReleaseAction) -> Unit,
) {
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("voice-hold").pointerInput(enabled, threshold) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                onDown()
                var action = VoiceReleaseAction.SEND
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            onRelease(action)
                            break
                        }
                        val next = voiceReleaseAction(change.position.x, change.position.y, size.width.toFloat(), threshold)
                        if (next != action) {
                            action = next
                            onZone(action)
                        }
                        change.consume()
                    }
                } finally {
                    if (!released) onRelease(VoiceReleaseAction.CANCEL)
                }
            }
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
