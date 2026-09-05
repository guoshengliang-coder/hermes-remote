package com.hermes.client.data.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashReporterTest {
    @Before fun setUp() {
        CrashReporter.resetBreadcrumbs()
        DebugLog.setTokenToRedact(null)
    }

    @After fun tearDown() {
        CrashReporter.resetBreadcrumbs()
        DebugLog.setTokenToRedact(null)
    }

    private fun report(
        breadcrumbs: List<String> = emptyList(),
        diagnostics: String? = null,
        trace: String = "java.lang.IllegalStateException: boom",
    ) = CrashReporter.composeReport(
        app = "com.hermes.client 0.1.91 (92)",
        android = "14 (API 34)",
        device = "vivo V2166BA",
        thread = "main",
        breadcrumbs = breadcrumbs,
        diagnostics = diagnostics,
        trace = trace,
    )

    @Test fun the_header_carries_the_version_and_device_context() {
        val text = report()
        assertTrue(text.startsWith("Hermes GO — crash report"))
        assertTrue(text.contains("app: com.hermes.client 0.1.91 (92)"))
        assertTrue(text.contains("android: 14 (API 34)"))
        assertTrue(text.contains("device: vivo V2166BA"))
        assertTrue(text.contains("thread: main"))
        assertTrue(text.trimEnd().endsWith("java.lang.IllegalStateException: boom"))
    }

    @Test fun the_diagnostic_log_is_attached_when_one_was_captured() {
        val text = report(diagnostics = "Hermes diagnostic log — 1 entries\nws opening socket\n")
        assertTrue(text.contains("diagnostic log:"))
        assertTrue(text.contains("ws opening socket"))
        // The trace still has the last word, so a reader scrolling to the bottom finds the crash.
        assertTrue(text.indexOf("ws opening socket") < text.indexOf("IllegalStateException"))
    }

    @Test fun empty_sections_are_omitted_rather_than_left_as_bare_headers() {
        val text = report(diagnostics = null)
        assertFalse("no diagnostic header without entries", text.contains("diagnostic log:"))
        assertFalse("no breadcrumb header without a trail", text.contains("breadcrumbs:"))
    }

    @Test fun a_blank_diagnostic_dump_counts_as_no_dump() {
        assertFalse(report(diagnostics = "   ").contains("diagnostic log:"))
    }

    @Test fun breadcrumbs_come_before_the_diagnostic_log_and_the_trace() {
        val text = report(
            breadcrumbs = listOf("2026-09-05T00:00:00Z [nav] open chat#42"),
            diagnostics = "Hermes diagnostic log — 1 entries\nws opening socket\n",
        )
        assertTrue(text.indexOf("breadcrumbs:") < text.indexOf("diagnostic log:"))
        assertTrue(text.indexOf("diagnostic log:") < text.indexOf("IllegalStateException"))
        assertTrue(text.contains("open chat#42"))
    }

    @Test fun the_trail_keeps_its_entries_in_order_with_a_category_and_a_timestamp() {
        CrashReporter.breadcrumb("nav", "open sessions")
        CrashReporter.breadcrumb("chat", "mount chat#42")

        val trail = CrashReporter.snapshotBreadcrumbs()
        assertEquals(2, trail.size)
        assertTrue(trail.first().contains("[nav] open sessions"))
        assertTrue(trail.last().contains("[chat] mount chat#42"))
        // Each line is prefixed with an ISO instant so the trail can be read against the trace.
        assertTrue(trail.first().startsWith("20"))
    }

    @Test fun the_trail_is_bounded_and_keeps_the_newest() {
        repeat(60) { CrashReporter.breadcrumb("nav", "step $it") }
        val trail = CrashReporter.snapshotBreadcrumbs()
        assertEquals(40, trail.size)
        assertTrue("the newest step must survive", trail.last().contains("step 59"))
        assertFalse("the oldest steps must be evicted", trail.any { it.endsWith("step 0") })
    }

    @Test fun a_registered_token_is_masked_in_the_trail() {
        DebugLog.setTokenToRedact("SECRET-TOKEN-123")
        CrashReporter.breadcrumb("nav", "open settings?token=SECRET-TOKEN-123")

        val text = report(breadcrumbs = CrashReporter.snapshotBreadcrumbs())
        assertFalse(text.contains("SECRET-TOKEN-123"))
        assertTrue(text.contains("***"))
    }

    @Test fun the_trace_is_always_present_even_with_nothing_else() {
        assertEquals(
            "Hermes GO — crash report\n" +
                "app: com.hermes.client 0.1.91 (92)\n" +
                "android: 14 (API 34)\n" +
                "device: vivo V2166BA\n" +
                "thread: main\n" +
                "\n" +
                "java.lang.IllegalStateException: boom",
            report(),
        )
    }
}
