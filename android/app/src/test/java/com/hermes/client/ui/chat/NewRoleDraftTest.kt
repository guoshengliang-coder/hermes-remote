package com.hermes.client.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.components.LocalRoleLabels
import com.hermes.client.ui.components.RolePickerSheet
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class NewRoleDraftTest {
    @get:Rule val compose = createComposeRule()
    @Test fun filter_toggles_multiple_roles_without_dismissing_and_restores_all() {
        var selected by mutableStateOf(setOf("default", "work"))
        var dismissals = 0
        compose.setContent { InChinese { HermesTheme(darkTheme = false) {
            CompositionLocalProvider(LocalRoleLabels provides mapOf("default" to "助手", "work" to "工作")) {
                RolePickerSheet(listOf("default", "work"), "筛选角色", selected, multiple = true,
                    onAll = { selected = setOf("default", "work") },
                    onPick = { selected = if (it in selected) selected - it else selected + it },
                    onDismiss = { dismissals++ })
            }
        } } }
        compose.onNodeWithText("工作").performClick()
        assertEquals(setOf("default"), selected)
        compose.onNodeWithText("助手").performClick()
        assertEquals(emptySet<String>(), selected)
        compose.onNodeWithText("全部角色").performClick()
        assertEquals(setOf("default", "work"), selected)
        assertEquals(0, dismissals)
    }
}
