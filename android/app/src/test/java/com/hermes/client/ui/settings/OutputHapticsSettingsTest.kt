package com.hermes.client.ui.settings

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutputHapticsSettingsTest {
    @Test fun waitsForReadAndPropagatesExplicitOffImmediately() = runTest {
        val preferences = MutableStateFlow(true)
        val settings = OutputHapticsSettings(preferences, { preferences.value = it }, backgroundScope)
        assertFalse(settings.state.value.feedbackEnabled)
        runCurrent()
        assertTrue(settings.state.value.feedbackEnabled)
        settings.setEnabled(false)
        assertFalse(settings.state.value.feedbackEnabled)
        runCurrent()
        assertFalse(settings.state.value.enabled)
        assertNull(settings.state.value.error)
        settings.setEnabled(true)
        runCurrent()
        assertTrue(settings.state.value.feedbackEnabled)
    }

    @Test fun failedSaveKeepsStoredChoiceAndRetriesTheRequestedChoice() = runTest {
        val preferences = MutableStateFlow(true)
        var fail = true
        val writes = mutableListOf<Boolean>()
        val settings = OutputHapticsSettings(preferences, {
            writes += it
            if (fail) throw java.io.IOException("token=secret")
            preferences.value = it
        }, backgroundScope)
        runCurrent()
        settings.setEnabled(false)
        runCurrent()
        val state = settings.state.value
        assertTrue(state.enabled)
        assertFalse(state.feedbackEnabled)
        val error = state.error!!
        assertEquals(AppErrorCode.OUTPUT_HAPTICS_SETTINGS_FAILED, error.code)
        assertEquals(error.code, AppErrorCode.fromValue("HR-STORE-002"))
        assertEquals("无法读取或保存输出触感设置，请重试。", error.localizedSummary(AppLanguage.ZH))
        assertEquals("Couldn't read or save output haptics settings. Retry.", error.localizedSummary(AppLanguage.EN))
        assertTrue(error.retryable)
        assertFalse(error.sanitizedDiagnostic().contains("secret"))
        fail = false
        settings.retry()
        runCurrent()
        assertEquals(listOf(false, false), writes)
        assertFalse(preferences.value)
        assertNull(settings.state.value.error)
    }

    @Test fun readFailureStopsFeedbackAndRetryResubscribes() = runTest {
        var fail = true
        var subscriptions = 0
        val preferences = flow {
            subscriptions++
            if (fail) throw java.io.IOException("password=hidden")
            emit(true)
        }
        val settings = OutputHapticsSettings(preferences, {}, backgroundScope)
        runCurrent()
        assertFalse(settings.state.value.loaded)
        assertFalse(settings.state.value.feedbackEnabled)
        assertFalse(settings.state.value.error!!.sanitizedDiagnostic().contains("hidden"))
        fail = false
        settings.retry()
        runCurrent()
        assertEquals(2, subscriptions)
        assertTrue(settings.state.value.feedbackEnabled)
    }
}
