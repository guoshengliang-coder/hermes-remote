package com.hermes.client.ui.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.domain.Session
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.InChinese
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
            InChinese {
                HermesTheme(darkTheme = false) {
                    SessionSubline(session(repo = "/u/hermes-remote"), pinned = true)
                }
            }
        }
        compose.onNodeWithContentDescription("已置顶").assertIsDisplayed()
        compose.onNodeWithText("hermes-remote").assertIsDisplayed()
        compose.onNodeWithText("gpt-5.6-terra").assertIsDisplayed()
    }

    @Test fun unpinned_subline_has_no_pin() {
        compose.setContent {
            InChinese { HermesTheme(darkTheme = false) { SessionSubline(session(repo = "/u/hermes-remote")) } }
        }
        compose.onNodeWithContentDescription("已置顶").assertDoesNotExist()
        compose.onNodeWithText("hermes-remote").assertIsDisplayed()
    }

    @Test fun pinned_without_any_subline_content_still_shows_the_pin() {
        compose.setContent {
            InChinese { HermesTheme(darkTheme = false) { SessionSubline(session(model = null), pinned = true) } }
        }
        compose.onNodeWithContentDescription("已置顶").assertIsDisplayed()
    }

    // HG-41: the draft marker shares the pin's slot — never the row's trailing dot column, where a
    // neutral mark is read as unread (DESIGN.md §5.2).
    @Test fun draft_subline_carries_the_word_before_the_project_and_model() {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    SessionSubline(session(repo = "/u/hermes-remote"), hasDraft = true)
                }
            }
        }
        compose.onNodeWithText("草稿").assertIsDisplayed()
        compose.onNodeWithContentDescription("有未发送的草稿").assertIsDisplayed()
        compose.onNodeWithText("hermes-remote").assertIsDisplayed()
        compose.onNodeWithText("gpt-5.6-terra").assertIsDisplayed()
    }

    @Test fun draft_and_pin_coexist() {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    SessionSubline(session(repo = "/u/hermes-remote"), pinned = true, hasDraft = true)
                }
            }
        }
        compose.onNodeWithContentDescription("已置顶").assertIsDisplayed()
        compose.onNodeWithText("草稿").assertIsDisplayed()
    }

    // The early return used to be `parts.isEmpty && !pinned`; a default-project chat with no model
    // has no parts, so the marker would have been swallowed entirely.
    @Test fun draft_without_any_subline_content_still_shows_the_word() {
        compose.setContent {
            InChinese { HermesTheme(darkTheme = false) { SessionSubline(session(model = null), hasDraft = true) } }
        }
        compose.onNodeWithText("草稿").assertIsDisplayed()
    }

    @Test fun no_draft_no_word() {
        compose.setContent {
            InChinese { HermesTheme(darkTheme = false) { SessionSubline(session(repo = "/u/hermes-remote")) } }
        }
        compose.onNodeWithText("草稿").assertDoesNotExist()
    }
}
