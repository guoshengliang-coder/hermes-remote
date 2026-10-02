package com.hermes.client.ui.chat

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TableChartTest {
    @Test fun historyStateHasEveryRoutingBoundaryAndContentFingerprint() {
        val scope = listOf("https://gateway", "account", "mac", "profile", "session")
        val key = chartKey(scope, "h1:table:12", "raw")
        scope.indices.forEach { index -> assertNotEquals(key, chartKey(scope.mapIndexed { i, v -> if (i == index) "$v-x" else v }, "h1:table:12", "raw")) }
        assertNotEquals(key, chartKey(scope, "h2:table:12", "raw"))
        assertNotEquals(key, chartKey(scope, "h1:table:13", "raw"))
        assertNotEquals(key, chartKey(scope, "h1:table:12", "changed"))
        val prefs = ChartPreferences(ApplicationProvider.getApplicationContext<Context>())
        prefs.save(key, JSONObject().put("view", "chart").put("from", "2026-09-01"))
        val reopened = TableChartController(key, "raw", "", "source", prefs)
        assertEquals("chart", reopened.view)
        assertEquals("2026-09-01", reopened.state!!.getString("from"))
        assertNull(prefs.read(chartKey(scope, "h1:table:12", "changed")))
    }
    @Test fun tableSwitchReleasesAndroidViewFocusBeforeRemovingTheRenderer() {
        val controller = TableChartController("focus", "raw", "", "", ChartPreferences(ApplicationProvider.getApplicationContext()))
        controller.choose("chart")
        var released = false
        controller.releaseRendererFocus = { assertEquals("chart", controller.view); released = true }
        controller.choose("table")
        assertTrue(released)
        assertEquals("table", controller.view)
    }
    @Test fun isolatedRendererRefusesAllNonPackagedResources() {
        TableChartIsolation.FILES.forEach { file -> assertEquals(file, TableChartIsolation.file("${TableChartIsolation.ORIGIN}/$file")) }
        listOf("http://chart.hermes.invalid/chart.html", "https://example.com/chart.html", "file:///chart.html", "content://chart.html", "javascript:alert(1)", "${TableChartIsolation.ORIGIN}/../engine.js", "${TableChartIsolation.ORIGIN}/%65ngine.js", "${TableChartIsolation.ORIGIN}/engine.js?token=secret", "https://user:secret@chart.hermes.invalid/chart.html", "${TableChartIsolation.ORIGIN}/arbitrary.html").forEach { assertNull(it, TableChartIsolation.file(it)) }
    }
    @Test fun localPresentationHasThreeChoicesAndDefaultsToTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("table_charts", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = ChartPreferences(context)
        assertEquals("table", prefs.preference())
        listOf("table", "chart", "auto").forEach { prefs.setPreference(it); assertEquals(it, ChartPreferences(context).preference()) }
    }
    @Test fun errorsAreBilingualAndDiagnosticsNeverContainCredentials() {
        listOf(AppErrorCode.CHART_RENDER_FAILED, AppErrorCode.CHART_SETTINGS_FAILED, AppErrorCode.CHART_RANGE_EXCEEDED).forEach { code ->
            val error = AppError(code, code != AppErrorCode.CHART_RANGE_EXCEEDED, "token=secret password=hidden")
            assertTrue(error.localizedMessage(AppLanguage.ZH).contains(code.value))
            assertTrue(error.localizedMessage(AppLanguage.EN).contains(code.value))
            assertNotEquals(error.localizedMessage(AppLanguage.ZH), error.localizedMessage(AppLanguage.EN))
            assertFalse(error.sanitizedDiagnostic().contains("secret"))
            assertFalse(error.sanitizedDiagnostic().contains("hidden"))
            assertEquals(code, AppErrorCode.fromValue(code.value))
        }
    }
}
