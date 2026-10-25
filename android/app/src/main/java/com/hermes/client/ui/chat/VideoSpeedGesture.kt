package com.hermes.client.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/** The picture alone owns this gesture; sibling controls consume their own touches.
 * A second pointer, consumed drag, bounds exit or composition cancellation always releases speed.
 */
internal fun Modifier.videoSpeedGesture(
    playing: Boolean,
    label: String,
    tap: () -> Unit,
    hold: (Boolean) -> Unit,
): Modifier = semantics { onClick(label) { tap(); true } }.pointerInput(playing) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var cancelled = false
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var up = false
            while (!up && !cancelled) {
                val event = awaitPointerEvent()
                val change = event.changes.singleOrNull()
                cancelled = change == null || change.id != down.id || change.isConsumed ||
                    change.isOutOfBounds(size, extendedTouchPadding) ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                up = !cancelled && change?.pressed == false
            }
            up
        }
        if (released == true) tap()
        else if (!cancelled) {
            // A paused long press is intentionally consumed without toggling Play or controls.
            if (playing) hold(true)
            try {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.singleOrNull()
                    pressed = change != null && change.id == down.id && change.pressed &&
                        !change.isConsumed && !change.isOutOfBounds(size, extendedTouchPadding)
                    event.changes.forEach { it.consume() }
                }
            } finally { hold(false) }
        }
    }
}
