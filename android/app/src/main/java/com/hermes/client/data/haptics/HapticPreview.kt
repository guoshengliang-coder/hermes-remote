package com.hermes.client.data.haptics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A finite, explicit audition; never a producer for chat output. */
class HapticPreview(
    private val scope: CoroutineScope,
    private val request: (OutputHapticConfig) -> HapticRequestResult,
    private val cancel: () -> Unit,
    private val failure: (HapticRequestResult) -> Unit,
) {
    private var job: Job? = null
    private var generation = 0L
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()

    fun start(parameters: OutputHapticConfig, rhythm: Boolean) {
        stop()
        val ownGeneration = generation
        val config = parameters.normalized()
        mutableRunning.value = true
        job = scope.launch {
            try {
                var elapsed = 0
                do {
                    val result = request(config)
                    if (result != HapticRequestResult.REQUESTED) { failure(result); break }
                    if (!rhythm) { delay(config.durationMs.toLong()); break }
                    delay(config.intervalMs.toLong())
                    elapsed += config.intervalMs
                } while (elapsed < 1000)
            } finally {
                if (generation == ownGeneration) {
                    cancel()
                    mutableRunning.value = false
                }
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        cancel()
        mutableRunning.value = false
    }
}
