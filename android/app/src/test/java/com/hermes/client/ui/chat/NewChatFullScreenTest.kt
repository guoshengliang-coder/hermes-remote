package com.hermes.client.ui.chat

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class NewChatFullScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun verify(dark: Boolean, scale: Float) {
        val fixture = ChatViewModelTest()
        val vm = fixture.localDraftUiFixture()
        var role by mutableStateOf("default")
        var sent: FirstTurn? = null
        try {
            compose.setContent { InChinese { HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                    com.hermes.client.ui.components.LocalRoleLabels provides mapOf("default" to "助手", "work" to "工作角色")) {
                    ChatScreen("draft-ui", sessionProfile = role, isNewSession = true, vm = vm,
                        localDraft = true, greetingHourOfDay = 10, onDraftSend = { sent = it }, draftRoleSwitch = {
                            TextButton(onClick = { role = "work" }) { Text("切换角色") }
                        })
                }
            } } }
            compose.onNodeWithContentDescription("切换语音输入").assertExists()
            compose.onNodeWithContentDescription("添加内容").assertExists()
            compose.onNodeWithText("默认项目").assertExists()
            compose.onNodeWithText("test-default").assertExists()
            compose.onNode(hasSetTextAction()).performTextInput("角色切换不能丢失草稿")
            compose.onNodeWithText("切换角色").performClick()
            compose.onNodeWithText("角色切换不能丢失草稿").assertExists()
            compose.onAllNodesWithText("test-work").assertCountEquals(2)
            compose.onNodeWithContentDescription("发送").performClick()
            assertEquals("角色切换不能丢失草稿", sent?.text)
            assertEquals("work", role)
            compose.onRoot().captureRoboImage("screenshots/hg260-new-chat-${if (dark) "dark" else "light"}-$scale.png")
        } finally { fixture.tearDown() }
    }
    @Test fun light_keeps_the_full_composer_and_role_draft() = verify(false, 1f)
    @Test fun dark_large_text_keeps_the_full_composer_and_role_draft() = verify(true, 1.3f)
}
