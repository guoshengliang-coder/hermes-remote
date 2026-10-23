package com.hermes.client.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermes.client.ui.workspace.*
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceLayoutSettingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cancelDoesNotChangeTheModeAndSaveUsesTheChoice() {
        var mode: WorkspaceMode? = null
        var resets = 0
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                Surface { WorkspaceLayoutSetting(WorkspacePreference(), { mode = it }, { resets++ }) }
            }
        } }
        compose.onNodeWithText("大屏布局").performClick()
        compose.onNodeWithText("始终单栏").performClick()
        compose.onNodeWithText("恢复默认栏宽").performClick()
        compose.onNodeWithText("取消").performClick()
        assertNull(mode)
        assertEquals(0, resets)
        compose.onNodeWithText("大屏布局").performClick()
        compose.onNodeWithText("始终单栏").performClick()
        compose.onNodeWithText("恢复默认栏宽").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals(WorkspaceMode.SINGLE, mode)
        assertEquals(1, resets)
    }
}
