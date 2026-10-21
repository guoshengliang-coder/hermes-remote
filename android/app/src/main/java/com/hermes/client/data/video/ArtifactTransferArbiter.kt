package com.hermes.client.data.video

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One request per originating Mac. Playback preempts only its own download's current range. */
class ArtifactTransferArbiter {
    private class Lane {
        val gate = Mutex()
        val lock = Any()
        val viewers = MutableStateFlow(0)
        var download: Job? = null
    }
    private val lanes = mutableMapOf<String, Lane>()
    private fun lane(owner: String) = synchronized(lanes) { lanes.getOrPut(owner) { Lane() } }
    private class PlaybackPriority : CancellationException("playback priority")

    fun playbackLease(owner: String): AutoCloseable {
        val lane = lane(owner)
        synchronized(lane.lock) {
            lane.viewers.value++
            lane.download?.cancel(PlaybackPriority())
        }
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) synchronized(lane.lock) { lane.viewers.value-- } }
    }

    suspend fun <T> playback(owner: String, action: suspend () -> T): T = lane(owner).gate.withLock { action() }

    suspend fun download(owner: String, onYield: () -> Unit, waiting: (Boolean) -> Unit, action: suspend () -> Unit) {
        val lane = lane(owner)
        while (true) {
            currentCoroutineContext().ensureActive()
            waiting(lane.viewers.value > 0)
            lane.viewers.first { it == 0 }
            waiting(false)
            var yielded = false
            coroutineScope {
                val transfer = async(start = CoroutineStart.LAZY) {
                    lane.gate.withLock {
                        val job = currentCoroutineContext()[Job]
                        synchronized(lane.lock) {
                            if (lane.viewers.value > 0) throw PlaybackPriority()
                            lane.download = job
                        }
                        try { action() }
                        catch (error: Exception) { currentCoroutineContext().ensureActive(); throw error }
                        finally { synchronized(lane.lock) { lane.download = null } }
                    }
                }
                transfer.start()
                try { transfer.await() }
                catch (error: CancellationException) {
                    // Parent cancellation is a user Pause/Cancel, never an invitation to resume.
                    currentCoroutineContext().ensureActive()
                    if (error !is PlaybackPriority) throw error
                    yielded = true
                }
            }
            if (!yielded) return
            onYield()
        }
    }
}
