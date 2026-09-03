package com.hermes.client.ui.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.domain.Session
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pinned marker rides in the subline, never in ListItem's leading slot (DESIGN.md §5.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SessionSublineTest {
    @get:Rule
    val compose = createComposeRule()

    private fun session(model: String? = "gpt-5.6-terra", repo: String? = null) = Session(
        id = "s1", title = "查看机器性能负荷", model = model, provider = null, messageCount = 1,
        profile = "personal", cwd = repo, gitRepoRoot = repo, gitBranch = null,
    )

    @Test fun pinned_subline_carries_the_pin_before_the_project_and_model() {
        compose.setContent {
            HermesTheme(darkTheme = false) {
                SessionSubline(session(repo = "/u/hermes-remote"), pinned = true)
            }
        }
        compose.onNodeWithContentDescription("已置顶").assertIsDisplayed()
        compose.onNodeWithText("hermes-remote").assertIsDisplayed()
        compose.onNodeWithText("gpt-5.6-terra").assertIsDisplayed()
    }

    @Test fun unpinned_subline_has_no_pin() {
        compose.setContent {
            HermesTheme(darkTheme = false) { SessionSubline(session(repo = "/u/hermes-remote")) }
        }
        compose.onNodeWithContentDescription("已置顶").assertDoesNotExist()
        compose.onNodeWithText("hermes-remote").assertIsDisplayed()
    }

    @Test fun pinned_without_any_subline_content_still_shows_the_pin() {
        compose.setContent {
            HermesTheme(darkTheme = false) { SessionSubline(session(model = null), pinned = true) }
        }
        compose.onNodeWithContentDescription("已置顶").assertIsDisplayed()
    }
}
