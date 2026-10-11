package com.hermes.client.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val VOICE_RECORDING_LIMIT_MS = 300_000L

/** Armed by the first nonempty audio read, stopped on every terminal path. */
internal class VoiceRecordingDeadline(
    private val scope: CoroutineScope,
    private val limitMs: Long = VOICE_RECORDING_LIMIT_MS,
    private val onLimit: () -> Unit,
) {
    private var timer: Job? = null
    private var ended = false
    @Synchronized fun audioReceived() {
        if (ended || timer != null) return
        timer = scope.launch { delay(limitMs); onLimit() }
    }
    @Synchronized fun stop() {
        ended = true
        timer?.cancel()
        timer = null
    }
}
