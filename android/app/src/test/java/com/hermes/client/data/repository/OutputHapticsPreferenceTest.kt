package com.hermes.client.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputHapticsPreferenceTest {
    @Test fun defaultsOnAndExplicitChoicePersistsAcrossStoreRecreation() = runBlocking {
        val directory = Files.createTempDirectory("output-haptics-pref").toFile()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val first = SettingsStore(context)
        assertTrue(first.outputHaptics.first())
        assertEquals(com.hermes.client.data.haptics.OutputHapticConfig(), first.outputHapticConfig.first())
        first.setOutputHaptics(false)
        assertFalse(SettingsStore(context).outputHaptics.first())
        val parameters = com.hermes.client.data.haptics.OutputHapticConfig(com.hermes.client.data.haptics.OutputHapticType.CUSTOM_PULSE, 75, 9, 84)
        first.setOutputHapticConfig(parameters)
        assertEquals(parameters, SettingsStore(context).outputHapticConfig.first())
        assertFalse(SettingsStore(context).outputHaptics.first())
        first.setOutputHaptics(true)
        assertTrue(SettingsStore(context).outputHaptics.first())
    }
}
