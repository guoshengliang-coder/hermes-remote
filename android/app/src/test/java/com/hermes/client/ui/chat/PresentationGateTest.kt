package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gate that decides whether a transcript is shown or held behind the chat skeleton.
 *
 * The bug this pins down: it used to key on "is a request running", so a transcript already in
 * hand was masked at alpha 0 with the skeleton over it until the network answered. The in-memory
 * cache therefore saved layout work but not the wait, and the disk cache would have saved nothing
 * at all (docs/DESIGN.md §5.4 rule 4).
 */
class PresentationGateTest {

    @Test fun `a cold open is masked until something exists to draw`() {
        assertEquals(
            false,
            immediatePresentationDecision(isGenerating = false, historyLoading = true, historyLoaded = false),
        )
    }

    /** The regression: a refresh behind a cached transcript must not re-raise the skeleton. */
    @Test fun `a refresh with a transcript in hand is not a cold open`() {
        assertNull(
            "content exists, so the layout settles and reveals instead of masking",
            immediatePresentationDecision(isGenerating = false, historyLoading = true, historyLoaded = true),
        )
    }

    @Test fun `a settled transcript reveals after the layout stops moving`() {
        assertNull(
            immediatePresentationDecision(isGenerating = false, historyLoading = false, historyLoaded = true),
        )
    }

    @Test fun `a live run is never masked`() {
        assertEquals(
            true,
            immediatePresentationDecision(isGenerating = true, historyLoading = true, historyLoaded = false),
        )
    }

    /** An error or a brand-new session: nothing is coming, so do not sit behind a skeleton. */
    @Test fun `nothing loading and nothing loaded reveals the empty ground`() {
        assertEquals(
            true,
            immediatePresentationDecision(isGenerating = false, historyLoading = false, historyLoaded = false),
        )
    }
}
