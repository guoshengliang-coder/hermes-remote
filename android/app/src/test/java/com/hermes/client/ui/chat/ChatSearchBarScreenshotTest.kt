package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the in-chat search bar (docs/DESIGN.md §5.4, Stitch 基线-聊天页/搜索 / 暗夜 / 无内容状态).
 *
 * The bar had no screenshot coverage at all before the 2026-09-12 pull, which is why the whole
 * counter-and-arrows group could move inside the field without a single test noticing. Four of the
 * five cases below exist because the bar is now a single row whose contents appear and disappear:
 * nothing typed (no counter, no arrows), hits, nothing found, and the same at fontScale 1.3 on a
 * 360dp screen — the one case where five controls and a text field have to share one line.
 *
 * Goldens live under app/screenshots/. Not part of the release gate — pixel noise must never block
 * a release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ChatSearchBarScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private fun snap(
        name: String,
        query: String,
        matchCount: Int,
        currentIndex: Int = 0,
        historyLoaded: Boolean = true,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
    ) {
        compose.setContent {
            HermesTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                ) {
                    Surface {
                        Column(Modifier) {
                            ChatSearchBar(
                                query = query,
                                onQueryChange = {},
                                matchCount = matchCount,
                                currentIndex = currentIndex,
                                historyLoaded = historyLoaded,
                                onPrevious = {},
                                onNext = {},
                                onClose = {},
                                onSearchAll = {},
                                requestFocus = false,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    @Test fun idle() = snap("chat-search-bar-idle", query = "", matchCount = 0)

    @Test fun hits() = snap("chat-search-bar-hits", query = "图", matchCount = 12, currentIndex = 3)

    @Test fun hitsDark() =
        snap("chat-search-bar-hits-dark", query = "图", matchCount = 12, currentIndex = 3, darkTheme = true)

    @Test fun noMatches() = snap("chat-search-bar-no-matches", query = "未命中的词", matchCount = 0)

    @Test fun loading() =
        snap("chat-search-bar-loading", query = "连接失败", matchCount = 0, historyLoaded = false)

    // The narrow-and-large case §5.4 calls out: 360dp with fontScale 1.3, where the field, the
    // clear button, the counter and both arrows have to survive on one line.
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
    fun largeFont() =
        snap("chat-search-bar-large-font", query = "连接失败", matchCount = 12, currentIndex = 3, fontScale = 1.3f)
}
