package com.hermes.client.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

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

/**
 * The voice-input composer bar (HG-146): keyboard toggle on the left, the hold-to-talk pill in
 * the middle, and the same add button the keyboard bar shows on the right — same 48dp slot, so the
 * control does not move when the bar switches mode. While a run is generating the right control
 * becomes stop, exactly as it does on the keyboard bar.
 */
@Composable
internal fun VoiceComposerBar(
    language: AppLanguage,
    holdLabel: String,
    holdEnabled: Boolean,
    keyboardEnabled: Boolean,
    sessionWritable: Boolean,
    isGenerating: Boolean,
    onKeyboard: () -> Unit,
    onDown: () -> Unit,
    onZone: (VoiceReleaseAction) -> Unit,
    onRelease: (VoiceReleaseAction) -> Unit,
    onAdd: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onKeyboard, enabled = keyboardEnabled) {
            Icon(
                Icons.Rounded.Keyboard,
                contentDescription = localized(language, "切换键盘输入", "Switch to keyboard"),
                modifier = Modifier.size(24.dp),
            )
        }
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            VoiceHoldButton(
                label = holdLabel,
                enabled = holdEnabled,
                onDown = onDown,
                onZone = onZone,
                onRelease = onRelease,
            )
        }
        if (isGenerating && sessionWritable) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            ) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = localized(language, "停止", "Stop"),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        } else {
            IconButton(onClick = onAdd, enabled = sessionWritable) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = localized(language, "添加内容", "Add content"),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
