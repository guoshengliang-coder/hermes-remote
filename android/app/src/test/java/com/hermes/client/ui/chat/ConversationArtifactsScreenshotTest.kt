package com.hermes.client.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.*
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class ConversationArtifactsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    /** Today at [hour]:[minute], so the footer always renders a bare "16:16" in any timezone and
     *  on any date the suite runs. */
    private fun todayAt(hour: Int, minute: Int): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.LocalDate.now(zone).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    private fun snap(dark: Boolean, language: AppLanguage, scale: Float) {
        val artifacts = conversationArtifacts(listOf(
            ChatMessage("one", Role.ASSISTANT, "", timestamp = todayAt(15, 2),
                files = listOf(ChatFile("a", "会话产物需求调研与验收方案.md", "text/markdown", 2048, remotePath = "/tmp/report.md"))),
            ChatMessage("two", Role.ASSISTANT, "", timestamp = todayAt(16, 16),
                files = listOf(ChatFile("b", "会话产物需求调研与验收方案.md", "text/markdown", 3072, remotePath = "/tmp/other.md"))),
        ))
        var opened = 0
        var source: String? = null
        compose.setContent { HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, scale)) {
                Surface {
                    ConversationArtifactsContent(artifacts, OlderHistoryUiState(), rememberLazyListState(), {}, { _, _ -> },
                        { opened++ }, {}, { source = it.messageId }, {})
                }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithText(artifacts.first().displayName).performClick()
        compose.onAllNodesWithText(if (language == AppLanguage.EN) "View context" else "查看上下文")[0].performClick()
        assertEquals(1, opened)
        assertEquals("two", source)
        compose.onRoot().captureRoboImage("screenshots/artifacts-$dark-${language.name}-$scale.png")
    }
    @Test fun light() = snap(false, AppLanguage.ZH, 1f)
    @Test fun darkLargeFont() = snap(true, AppLanguage.ZH, 1.3f)
    @Test fun englishLargeFont() = snap(false, AppLanguage.EN, 1.3f)
}
