package com.hermes.client.data.video

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded parallel playback, exclusive downloads. Playback preempts its own Mac's download. */
class ArtifactTransferArbiter {
    private class Lane {
        val gate = Mutex()
        val slots = Semaphore(4)
        val lock = Any()
        val viewers = MutableStateFlow(0)
        var download: Job? = null
        var precache: Job? = null
        var readers = 0
        var downloads = 0
        val precacheGate = Mutex()
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

    suspend fun <T> playback(owner: String, action: suspend () -> T): T {
        val lane = lane(owner)
        val background = synchronized(lane.lock) {
            lane.readers++
            lane.precache?.also { it.cancel(PlaybackPriority()) }
        }
        return try {
            // Drain cancelled cache IO before a seek/new opaque reference uses the channel.
            background?.join()
            lane.slots.withPermit { action() }
        } finally { synchronized(lane.lock) { lane.readers-- } }
    }

    /** A single cancellable spare-slot request. Demand and queued downloads always preempt it. */
    suspend fun precache(owner: String, action: suspend () -> Unit): Boolean {
        val lane = lane(owner)
        return lane.precacheGate.withLock {
            var completed = false
            coroutineScope {
                val work = async(start = CoroutineStart.LAZY) { lane.slots.withPermit { action() }; completed = true }
                val admitted = synchronized(lane.lock) {
                    if (lane.readers == 0 && lane.downloads == 0) { lane.precache = work; true } else false
                }
                if (!admitted) work.cancel() else work.start()
                try { work.await() }
                catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); if (admitted && cancelled !is PlaybackPriority) throw cancelled }
                finally { synchronized(lane.lock) { if (lane.precache === work) lane.precache = null } }
            }
            completed
        }
    }

    suspend fun download(owner: String, onYield: () -> Unit, waiting: (Boolean) -> Unit, action: suspend () -> Unit) {
        val lane = lane(owner)
        synchronized(lane.lock) { lane.downloads++; lane.precache?.cancel(PlaybackPriority()) }
        try { while (true) {
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
                        var acquired = 0
                        try {
                            repeat(4) { lane.slots.acquire(); acquired++ }
                            action()
                        }
                        catch (error: Exception) { currentCoroutineContext().ensureActive(); throw error }
                        finally {
                            repeat(acquired) { lane.slots.release() }
                            synchronized(lane.lock) { lane.download = null }
                        }
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
        } } finally { synchronized(lane.lock) { lane.downloads-- } }
    }
}
