package com.hermes.client.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.components.LocalRoleLabels
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class NewRoleDraftTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingKeepsTextAndFirstSendUsesChosenId() {
        var sent: Pair<String, String>? = null
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalRoleLabels provides mapOf("default" to "助手", "work" to "工作")) {
                        NewProfileChat(listOf(ProfileDto("default", true), ProfileDto("work")), ConnectionState.Connected,
                            false, {}) { owner, turn -> sent = owner to turn!!.text }
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("这一份草稿")
        compose.onNodeWithText("切换角色").performClick()
        compose.onNodeWithText("工作").performClick()
        assertNull(sent)
        compose.onNodeWithText("这一份草稿").assertExists()
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals("work" to "这一份草稿", sent)
    }
}
