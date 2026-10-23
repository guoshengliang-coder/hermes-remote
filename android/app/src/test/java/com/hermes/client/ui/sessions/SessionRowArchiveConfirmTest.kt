package com.hermes.client.ui.sessions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.hermes.client.domain.Session
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Archiving from the session list's long-press menu ASKS FIRST (docs/DESIGN.md §5.2, HG-5).
 *
 * This exists because it silently did not. The confirmation dialog and its `confirmingArchive`
 * flag were both written in `SessionsScreen.kt`, but nothing ever set the flag: the menu item
 * called `onArchive()` directly. So the list archived on a single tap while the chat page's same
 * action — documented as behaving identically — showed the dialog. Nothing failed, because the
 * only tests near this row measured its height.
 *
 * The assertion is deliberately in two halves. "The dialog is on screen" alone would still pass if
 * the archive had ALSO already happened behind it, which is exactly the bug: what matters is that
 * nothing was archived until the second tap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SessionRowArchiveConfirmTest {
    @get:Rule val compose = createComposeRule()

    private val session = Session(
        id = "s1", title = "查看昨天公司数据", model = "claude-opus-5", provider = null,
        messageCount = 3, profile = "personal", cwd = null, gitRepoRoot = null, gitBranch = null,
    )

    private var archived = 0
    private var deleted = 0

    private fun showRow() {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    SessionRow(
                        session = session, isPinned = false, defaultProjectPath = null,
                        onMoveToProject = {}, onOpen = {}, onTogglePin = {}, onRename = {},
                        onArchive = { archived++ }, onDelete = { deleted++ },
                    )
                }
            }
        }
        compose.onNodeWithText(session.title).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    @Test fun archive_asks_before_it_archives() {
        showRow()
        compose.onNodeWithText("归档会话").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("归档这个对话？").assertExists()
        assertEquals("nothing may be archived until the dialog is confirmed", 0, archived)

        compose.onNodeWithText("归档").performClick()
        compose.waitForIdle()
        assertEquals(1, archived)
    }

    @Test fun cancelling_the_confirm_archives_nothing() {
        showRow()
        compose.onNodeWithText("归档会话").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertEquals(0, archived)
    }

    /**
     * The irreversible action next to it, for contrast: delete has always confirmed, and pinning
     * this says so — if a later change routed 删除会话 straight to `onDelete` the way archive was
     * routed, that would look like a copy of the fix rather than a new bug.
     */
    @Test fun delete_asks_before_it_deletes() {
        showRow()
        compose.onNodeWithText("删除会话").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("删除会话？").assertExists()
        assertEquals(0, deleted)
    }
}
