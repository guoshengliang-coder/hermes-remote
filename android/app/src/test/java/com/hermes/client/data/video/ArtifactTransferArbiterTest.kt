package com.hermes.client.data.video

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class ArtifactTransferArbiterTest {
    @Test fun playback_overlaps_four_ranges_but_download_waits_for_every_reader() = runBlocking {
        withTimeout(5_000) {
            val arbiter = ArtifactTransferArbiter()
            val started = kotlinx.coroutines.channels.Channel<Int>(4)
            val release = CompletableDeferred<Unit>()
            val reads = (0..3).map { index -> launch {
                arbiter.playback("mac") { started.send(index); release.await() }
            } }
            repeat(4) { started.receive() }
            var fifthStarted = false; var downloadStarted = false
            val fifth = launch { arbiter.playback("mac") { fifthStarted = true } }
            val download = launch { arbiter.download("mac", {}, {}) { downloadStarted = true } }
            yield()
            assertFalse(fifthStarted); assertFalse(downloadStarted)
            release.complete(Unit); reads.forEach { it.join() }; fifth.join(); download.join()
            assertTrue(fifthStarted); assertTrue(downloadStarted)
        }
    }

    @Test fun cancelling_a_download_waiting_for_slots_releases_its_partial_reservation() = runBlocking {
        withTimeout(5_000) {
            val arbiter = ArtifactTransferArbiter()
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val read = launch { arbiter.playback("mac") { started.complete(Unit); release.await() } }
            started.await()
            val download = launch { arbiter.download("mac", {}, {}) { fail("must wait for reader") } }
            yield(); download.cancelAndJoin()
            release.complete(Unit); read.join()
            arbiter.download("mac", {}, {}) { }
        }
    }

    @Test fun playback_preempts_a_range_then_download_resumes_only_after_last_viewer_leaves() = runBlocking {
        withTimeout(5_000) {
            val arbiter = ArtifactTransferArbiter()
            val started = CompletableDeferred<Unit>(); val rolledBack = CompletableDeferred<Unit>()
            var attempts = 0
            val job = launch { arbiter.download("mac", { rolledBack.complete(Unit) }, {}) {
                if (++attempts == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { throw java.io.IOException("socket closed by cancellation") }
                }
            } }
            started.await()
            val lease = arbiter.playbackLease("mac"); val second = arbiter.playbackLease("mac")
            rolledBack.await()
            arbiter.playback("mac") { assertEquals(1, attempts) }
            lease.close(); lease.close(); yield()
            assertEquals(1, attempts)
            second.close(); job.join()
            assertEquals(2, attempts)
        }
    }

    @Test fun user_pause_during_playback_wait_does_not_restart_and_other_Macs_are_independent() = runBlocking {
        withTimeout(5_000) {
            val arbiter = ArtifactTransferArbiter()
            val lease = arbiter.playbackLease("mac")
            val waiting = CompletableDeferred<Unit>()
            var attempts = 0
            val job = launch { arbiter.download("mac", {}, { if (it) waiting.complete(Unit) }) { attempts++ } }
            waiting.await()
            arbiter.download("other-mac", {}, {}) { attempts++ }
            job.cancelAndJoin(); lease.close(); yield()
            assertEquals(1, attempts)
        }
    }
    @Test fun foreground_read_cancels_and_drains_precache_before_demand_and_download_preempts_cache() = runBlocking {
        withTimeout(5_000) {
            val a = ArtifactTransferArbiter()
            val started = CompletableDeferred<Unit>(); var drained = false
            val prefetch = async { a.precache("mac") {
                started.complete(Unit)
                try { awaitCancellation() } finally { delayInCleanup(); drained = true }
            } }
            started.await()
            a.playback("mac") { assertTrue(drained) }
            assertFalse(prefetch.await())
            val again = CompletableDeferred<Unit>()
            val second = async { a.precache("mac") { again.complete(Unit); awaitCancellation() } }
            again.await()
            a.download("mac", {}, {}) { assertFalse(a.precache("mac") { fail() }) }
            assertFalse(second.await())
            assertTrue(a.precache("mac") {})
        }
    }
    private suspend fun delayInCleanup() = withContext(NonCancellable) { delay(10) }

    @Test fun queued_download_prevents_precache_and_user_cancel_releases_priority() = runBlocking {
        withTimeout(5_000) {
            val a = ArtifactTransferArbiter(); val lease = a.playbackLease("mac")
            val waiting = CompletableDeferred<Unit>()
            val d = launch { a.download("mac", {}, { if (it) waiting.complete(Unit) }) { fail() } }
            waiting.await()
            assertFalse(a.precache("mac") { fail() })
            assertTrue(a.precache("another-mac") {})
            d.cancelAndJoin(); lease.close()
            assertTrue(a.precache("mac") {})
            val running = CompletableDeferred<Unit>()
            val job = launch { a.precache("mac") { running.complete(Unit); awaitCancellation() } }
            running.await(); job.cancelAndJoin()
            assertTrue(a.precache("mac") {})
        }
    }

}
