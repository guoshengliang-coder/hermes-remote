package com.hermes.client.ui.startup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.client.BuildConfig
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural contract of the startup gate (DESIGN.md §5.11). The clock is driven manually: the
 * progress highlight is an infinite transition, which never lets an auto-advancing clock go idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class StartupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = mutableStateOf<StartupUiState>(StartupUiState.Hidden)
    private var retries = 0

    private fun show(initial: StartupUiState, dark: Boolean = false) {
        state.value = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                StartupScreen(state = state.value, onRetry = { retries++ }, onOpenConnectionSettings = {})
            }
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private val progressBar = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun statusStaysHiddenUntilTheRevealDelay() {
        show(StartupUiState.Loading(StartupReason.COLD_START, StartupPhase.NETWORK))
        advance(STATUS_REVEAL_DELAY_MS - 100)
        compose.onNodeWithText("正在连接").assertDoesNotExist()
        compose.onNode(progressBar).assertDoesNotExist()

        advance(500)
        compose.onNodeWithText("正在连接").assertIsDisplayed()
        compose.onNode(progressBar).assertIsDisplayed()
    }

    @Test fun readyBeforeTheRevealDelayNeverShowsStatus() {
        show(StartupUiState.Loading(StartupReason.COLD_START, StartupPhase.CONFIGURATION))
        advance(300)
        state.value = StartupUiState.Loading(StartupReason.COLD_START, StartupPhase.READY)
        advance(1_500)
        compose.onNodeWithText("连接就绪").assertDoesNotExist()
        compose.onNodeWithText("正在连接").assertDoesNotExist()
    }

    @Test fun earlyPhasesKeepOneLineAndLaterPhasesChangeIt() {
        show(StartupUiState.Loading(StartupReason.COLD_START, StartupPhase.NETWORK))
        advance(1_200)
        compose.onNodeWithText("正在连接").assertIsDisplayed()

        state.value = StartupUiState.Loading(StartupReason.COLD_START, StartupPhase.AUTHENTICATION)
        advance(400)
        compose.onAllNodesWithText("正在连接").onFirst().assertIsDisplayed()
        compose.onNodeWithText("正在验证连接凭据").assertDoesNotExist()

        state.value = StartupUiState.Loading(StartupReason.COLD_START, StartupPhase.INITIAL_DATA)
        advance(400)
        compose.onAllNodesWithText("正在准备会话").onFirst().assertIsDisplayed()
    }

    @Test fun failureShowsCodeOnItsOwnLineHidesProgressAndRetries() {
        show(StartupUiState.Failed(StartupReason.COLD_START, StartupFailure.CONNECTOR_OFFLINE))
        advance(600)
        compose.onNodeWithText("Mac 端当前离线，请确认 Hermes Go Desktop 正在运行。").assertIsDisplayed()
        compose.onNodeWithText("HR-CONN-005").assertIsDisplayed()
        compose.onNode(progressBar).assertDoesNotExist()

        compose.onNodeWithText("重新连接").performClick()
        assertEquals(1, retries)
    }

    @Test fun versionLineNamesVersionAndChannel() {
        show(StartupUiState.Loading(StartupReason.COLD_START, StartupPhase.NETWORK), dark = true)
        advance(600)
        compose.onNodeWithText("${BuildConfig.VERSION_NAME} · DEBUG").assertIsDisplayed()
    }

    @Test fun hiddenStateRendersNothing() {
        show(StartupUiState.Hidden)
        advance(600)
        compose.onNodeWithText("HERMES GO").assertDoesNotExist()
    }

    // DESIGN.md §5.11: a warm reconnect recovers silently. The conversation keeps the screen with
    // the content it already committed; throwing the gate over it on every self-healing socket
    // blip read as "the app went back to the launch screen".
    @Test fun warmReconnectRecoversWithoutCoveringTheScreen() {
        show(StartupUiState.Loading(StartupReason.CONNECTION_RECOVERY, StartupPhase.CONNECTION))
        advance(STATUS_REVEAL_DELAY_MS + 900)
        compose.onNodeWithText("HERMES GO").assertDoesNotExist()
        compose.onNodeWithText("正在恢复连接").assertDoesNotExist()
    }

    // …but a recovery that actually FAILED still owns the screen: it carries the error code and
    // the only two actions that can fix it.
    @Test fun warmReconnectFailureStillOwnsTheScreen() {
        show(StartupUiState.Failed(StartupReason.CONNECTION_RECOVERY, StartupFailure.CONNECTION_FAILED))
        advance(600)
        compose.onNodeWithText("HERMES GO").assertIsDisplayed()
        compose.onNodeWithText("HR-CONN-002").assertIsDisplayed()
        compose.onNodeWithText("重新连接").assertIsDisplayed()
    }
}
