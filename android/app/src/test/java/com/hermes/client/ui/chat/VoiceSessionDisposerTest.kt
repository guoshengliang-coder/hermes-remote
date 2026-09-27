package com.hermes.client.ui.chat

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real HG-144 root cause. The disposer used to key its DisposableEffect on the session
 * itself, so `beginVoice`'s `voiceSession = session` ran the previous `onDispose` one
 * recomposition later — reading the new value and cancelling the session it was created to
 * guard. Every voice attempt died ~18ms in; the logs of 2026-09-27 20:22 show exactly
 * `start → endpoint ready (7ms) → cancelled` on every press. These tests pin both halves:
 * assigning a session must not cancel it, and leaving the conversation must.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSessionDisposerTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        DebugLog.detachStore()
        DebugLog.setTokenToRedact(null)
        DebugLog.setEnabled(true)
        DebugLog.clear()
    }

    @After fun tearDown() {
        DebugLog.setEnabled(false)
        DebugLog.clear()
        DebugLog.detachStore()
    }

    private fun newSession(): DoubaoVoiceSession = DoubaoVoiceSession(
        context = ApplicationProvider.getApplicationContext(),
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        endpoint = { awaitCancellation() },
        endpointTimeoutMs = 60_000,
        onEvent = {},
    )

    private fun cancelled(): Boolean = DebugLog.entries.value.any { it.message == "cancelled" }

    @Test fun assigningSession_doesNotCancelIt() {
        var session by mutableStateOf<DoubaoVoiceSession?>(null)
        compose.setContent {
            Surface { rememberVoiceSessionDisposer(sessionId = "s1", voiceSession = session) }
        }
        compose.runOnIdle { session = newSession() }
        // Several frames pass — under the old key-on-session effect this is exactly where the
        // cancelled line appeared (~18ms after every press).
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertFalse("session was cancelled by its own assignment", cancelled())
    }

    @Test fun leavingConversation_cancelsLiveSession() {
        var session by mutableStateOf<DoubaoVoiceSession?>(null)
        var sessionId by mutableStateOf("s1")
        compose.setContent {
            Surface { rememberVoiceSessionDisposer(sessionId = sessionId, voiceSession = session) }
        }
        compose.runOnIdle { session = newSession() }
        compose.waitForIdle()
        assertFalse(cancelled())
        compose.runOnIdle { sessionId = "s2" } // conversation replaced — leave the old one
        compose.waitForIdle()
        assertTrue("session survived a conversation change", cancelled())
    }

    @Test fun clearingSession_doesNotCancelTheNextOne() {
        var session by mutableStateOf<DoubaoVoiceSession?>(null)
        compose.setContent {
            Surface { rememberVoiceSessionDisposer(sessionId = "s1", voiceSession = session) }
        }
        val first = newSession()
        compose.runOnIdle { session = first }
        compose.waitForIdle()
        compose.runOnIdle { session = null } // releaseVoice's cancel path clears the reference
        compose.waitForIdle()
        assertFalse(cancelled()) // clearing a reference is not the disposer's business
        val second = newSession()
        compose.runOnIdle { session = second }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertFalse(cancelled())
    }
}
