package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.Session
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.sessions.SessionSubline
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-420dpi")
class ChatRoleLayoutTest {
    @get:Rule val compose = createComposeRule()
    private fun verify(dark: Boolean, scale: Float, project: Boolean) {
        compose.setContent { InChinese { HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Surface { Column {
                    ChatTopBar(title = "本周工作安排", roleName = "聊天角色名称很长也不能抢标题",
                        actionsVisible = true, creatingNewChat = false, refreshingConversation = false,
                        promptsLabel = "我的提问", onBack = {}, onNewChat = {}, onSearch = {},
                        onPrompts = {}, onRefresh = {}, onShare = {}, onArchive = {}, subtitle = if (project) {
                            { WorkspaceSubtitle("hermes-remote", "main", true, {}, compact = true) }
                        } else null)
                    SessionSubline(Session(id = "s", title = "会话", model = "gpt-6.2-sol", provider = null,
                        messageCount = 1, profile = "work", cwd = "/u/project", gitRepoRoot = "/u/project"),
                        pinned = true, hasDraft = true, modifier = Modifier.padding(16.dp))
                } }
            }
        } } }
        val title = compose.onNodeWithText("本周工作安排").fetchSemanticsNode().boundsInRoot
        val role = compose.onNodeWithText("聊天角色名称很长也不能抢标题").fetchSemanticsNode().boundsInRoot
        assertTrue("role must be below the title", role.top >= title.bottom)
        val action = compose.onNodeWithContentDescription("新建对话").fetchSemanticsNode().boundsInRoot
        assertTrue("role cannot displace the action", role.right <= action.left)
        compose.onNodeWithText("work").assertIsDisplayed()
        compose.onAllNodesWithText(" · ").assertCountEquals(if (project) 3 else 2)
        if (project) {
            val projectBounds = compose.onNodeWithText("hermes-remote").fetchSemanticsNode().boundsInRoot
            // A semantic node can exist with zero width; keep at least a readable label fragment.
            assertTrue("long roles must leave readable project text", projectBounds.width >= 18 * compose.density.density)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("hermes-remote").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("the project must show a real character, not only ellipsis", layouts.single().getLineEnd(0, visibleEnd = true) > 0)
            val branchLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("main").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(branchLayouts) }
            assertTrue("the branch must show a real character, not only ellipsis", branchLayouts.single().getLineEnd(0, visibleEnd = true) > 0)
        }
        compose.onRoot().captureRoboImage("screenshots/hg261262-${if (dark) "dark" else "light"}-$scale-${if (project) "project" else "plain"}.png")
    }
    @Test fun light_without_directory() = verify(false, 1f, false)
    @Test fun dark_large_text_without_directory() = verify(true, 1.3f, false)
    @Test fun light_with_directory() = verify(false, 1f, true)
    @Test fun dark_large_text_with_directory() = verify(true, 1.3f, true)
}
