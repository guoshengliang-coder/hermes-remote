package com.hermes.client.ui.models

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
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.favKey
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
 * Goldens for the model selector (docs/DESIGN.md §5.17, Stitch 基线-模型选择 / 暗夜).
 *
 * The sheet renders in its own window, where `onRoot()` cannot reach it, so these compose
 * [ModelSheetHeader] + [ModelSelectorContent] directly — the same split `ThemeSheetContent` makes
 * for the card page's theme sheet. What is NOT captured here is therefore the `ModalBottomSheet`
 * chrome itself (scrim, grab handle, corner), which §5.8 owns globally and no mock overrides.
 *
 * Goldens live under app/screenshots/. Not part of the release gate — pixel noise must never
 * block a release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ModelSelectorScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private val providers = listOf(
        ModelProviderDto(
            slug = "openai", name = "OpenAI & Codex Subscription", isCurrent = true,
            models = listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-6-astra"),
        ),
        ModelProviderDto(
            slug = "anthropic", name = "Anthropic", isCurrent = false,
            models = listOf("claude-3.7-sonnet", "claude-3.5-haiku"),
        ),
        ModelProviderDto(
            slug = "deepseek", name = "DeepSeek", isCurrent = false,
            models = listOf("deepseek-v4-pro", "deepseek-v4-flash"),
        ),
    )

    private val favorites = setOf(
        favKey("openai", "gpt-5.6-sol"),
        favKey("openai", "gpt-5.6-terra"),
        favKey("deepseek", "deepseek-v4-pro"),
        favKey("deepseek", "deepseek-v4-flash"),
    )

    private val recents = listOf(
        ModelRecent("openai", "gpt-5.6-sol", "OpenAI"),
        ModelRecent("deepseek", "deepseek-v4-pro", "DeepSeek"),
        ModelRecent("openai", "gpt-5.6-terra", "OpenAI"),
    )

    private fun summary(scope: String, restore: Boolean = false) = CurrentModelSummary(
        model = "gpt-5.6-sol", provider = "OpenAI & Codex Subscription",
        badgeText = "当前使用", scopeText = scope, showRestore = restore,
    )

    private fun snap(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        // RunSpinner is an infinite animation, so a golden containing one is only stable with the
        // clock held still — the house pattern from ScreenshotTest.
        manualClock: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        if (manualClock) compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    LocalDensity provides Density(
                        density.density,
                        fontScale ?: density.fontScale,
                    ),
                ) {
                    Surface { Column(Modifier) { content() } }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    private fun sheet(
        groups: List<ModelGroup>,
        pendingKey: String? = null,
        recentModels: List<ModelRecent> = recents,
        listLoading: Boolean = false,
        listError: Boolean = false,
        refreshing: Boolean = false,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        ModelSheetHeader(refreshing = refreshing, onRefresh = {}, onDismiss = {})
        ModelSelectorContent(
            groups = groups,
            onToggleFavorite = { _, _ -> },
            onSelect = { _, _ -> },
            onToggleGroup = {},
            pendingKey = pendingKey,
            error = null,
            currentSummary = summary("此对话覆盖", restore = true),
            onRestoreDefault = {},
            recents = recentModels,
            reasoningEffort = "high",
            onSelectReasoning = {},
            listLoading = listLoading,
            listError = listError,
            onRetryLoad = {},
        )
    }

    private fun groups(expanded: Set<String>? = setOf("openai"), favs: Set<String> = favorites) =
        modelSelectorGroups(
            providers = providers, favorites = favs,
            currentProvider = "openai", currentModel = "gpt-5.6-sol",
            expandedGroups = expanded,
        )

    @Test fun modelSelector() = snap("model-select", content = sheet(groups()))

    @Test fun modelSelectorDark() =
        snap("model-select-dark", darkTheme = true, content = sheet(groups()))

    /**
     * The one state the mock actually draws: a switch in flight. The target row takes the
     * highlight and 「切换中…」, the model that was in force steps back to 「前次生效」.
     */
    @Test fun modelSelectorSwitching() = snap(
        "model-select-switching", manualClock = true,
        content = sheet(groups(expanded = setOf("openai", "deepseek")), pendingKey = favKey("deepseek", "deepseek-v4-pro")),
    )

    @Test fun modelSelectorSwitchingDark() = snap(
        "model-select-switching-dark", darkTheme = true, manualClock = true,
        content = sheet(groups(expanded = setOf("openai", "deepseek")), pendingKey = favKey("deepseek", "deepseek-v4-pro")),
    )

    /** No favourites card, no quick-switch row: the first-run shape, which the mock never shows. */
    @Test fun modelSelectorBare() = snap(
        "model-select-bare",
        content = sheet(groups(favs = emptySet()), recentModels = emptyList()),
    )

    /** HR-RPC-003 with a retry. */
    @Test fun modelSelectorCatalogFailed() = snap(
        "model-select-failed",
        content = sheet(emptyList(), recentModels = emptyList(), listError = true),
    )

    /** The title bar mid-refresh: the working ring replaces the refresh glyph in place. */
    @Test fun modelSelectorRefreshing() = snap(
        "model-select-refreshing", manualClock = true,
        content = sheet(groups(), refreshing = true),
    )

    /** 360dp is the narrow screen the input bar is sized against; 1.3 is the large-type step. */
    @Test fun modelSelectorLargeType() =
        snap("model-select-fontscale-1_3", fontScale = 1.3f, content = sheet(groups()))

    /**
     * HG-32: every collapsed card's 「N 项」 and chevron on one right-aligned column. The fixture
     * deliberately mixes a long ellipsised name with a short one and 1- with 2-digit counts —
     * the previous fixture's two near-equal names hid the drift entirely. ModelSelectorLayoutTest
     * asserts the same thing in numbers; this is the version a person can see.
     */
    @Test fun modelSelectorCollapsedCards() = snap(
        "model-select-collapsed",
        content = {
            ModelSelectorContent(
                groups = modelSelectorGroups(
                    providers = listOf(
                        ModelProviderDto(slug = "copilot", name = "GitHub Copilot", isCurrent = false, models = List(17) { "c$it" }),
                        ModelProviderDto(slug = "deepseek", name = "DeepSeek", isCurrent = false, models = List(3) { "d$it" }),
                        ModelProviderDto(slug = "proxy", name = "devops-ai-proxy.yiyuan.internal.example", isCurrent = false, models = List(48) { "p$it" }),
                        ModelProviderDto(slug = "opencode", name = "OpenCode Free", isCurrent = false, models = List(7) { "o$it" }),
                    ),
                    favorites = emptySet(), currentProvider = null, currentModel = null,
                    expandedGroups = emptySet(),
                ),
                onToggleFavorite = { _, _ -> }, onSelect = { _, _ -> }, onToggleGroup = {},
                pendingKey = null, error = null,
            )
        },
    )
}
