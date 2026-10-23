package com.hermes.client.ui.models

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import com.hermes.client.ui.createHermesComposeRule
import com.hermes.client.data.sync.*
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class AccountModelSyncPanelTest {
    @get:Rule val compose=createHermesComposeRule()
    private val state=MutableStateFlow(AccountModelPreferenceState(ownerKey="owner-a",accountLabel="a@example.test",useCloud=true,enabled=true,ready=true))
    private val sync=mockk<AccountModelPreferences>()
    private val imported=java.util.concurrent.CopyOnWriteArrayList<String>()
    private val data=LegacyModelImport(listOf(LegacyModelPreference("p","m",true,"high")),1)
    private fun mount(fail:Boolean=false) {
        every{sync.state}returns state
        coEvery{sync.importLegacy(any(),any(),any())}coAnswers{imported+=thirdArg<String>();Unit}
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                AccountModelSyncPanel(sync,{if(fail)throw java.io.IOException("synthetic");data})
            }
        } }
    }
    @Test fun import_requires_explicit_confirmation_bound_to_the_displayed_owner() {
        mount();assertTrue(imported.isEmpty())
        compose.onNodeWithText("导入本机偏好").performClick();compose.waitForIdle()
        assertTrue(imported.isEmpty())
        compose.onNodeWithText("a@example.test",substring=true).assertExists()
        compose.onAllNodesWithText("确认导入").onLast().performClick()
        compose.waitUntil { imported.size==1 }
        Assert.assertEquals(listOf("owner-a"),imported)
    }
    @Test fun cancel_and_account_change_cannot_import_an_old_confirmation_into_a_new_owner() {
        mount();compose.onNodeWithText("导入本机偏好").performClick();compose.waitForIdle()
        compose.onNodeWithText("取消").performClick();assertTrue(imported.isEmpty())
        compose.onNodeWithText("导入本机偏好").performClick();compose.waitForIdle()
        state.value=state.value.copy(ownerKey="owner-b",accountLabel="b@example.test")
        compose.waitForIdle();compose.onAllNodesWithText("确认导入").assertCountEquals(0)
        assertTrue(imported.isEmpty())
    }
    @Test fun local_source_failure_is_structured_and_does_not_import() {
        mount(fail=true);compose.onNodeWithText("导入本机偏好").performClick();compose.waitForIdle()
        compose.onNodeWithText("HR-SYNC-017",substring=true).assertExists()
        assertTrue(imported.isEmpty())
    }
}
