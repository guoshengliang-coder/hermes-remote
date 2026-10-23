package com.hermes.client.data.video

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class ArtifactTransferArbiterTest {
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
}
