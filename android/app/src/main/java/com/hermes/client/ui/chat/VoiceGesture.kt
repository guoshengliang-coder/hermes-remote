package com.hermes.client.ui.chat

enum class VoiceReleaseAction { SEND, CANCEL, EDIT }

/** The gesture can be steered back to send before the finger lifts. */
fun voiceReleaseAction(x: Float, y: Float, width: Float, upwardThreshold: Float): VoiceReleaseAction = when {
    y >= -upwardThreshold -> VoiceReleaseAction.SEND
    x < width * 0.35f -> VoiceReleaseAction.CANCEL
    x > width * 0.65f -> VoiceReleaseAction.EDIT
    else -> VoiceReleaseAction.SEND
}
