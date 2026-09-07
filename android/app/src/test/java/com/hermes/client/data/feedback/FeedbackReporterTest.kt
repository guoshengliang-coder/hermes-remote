package com.hermes.client.data.feedback

import android.app.Activity
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReporterTest {

    // ── Error mapping ────────────────────────────────────────────────────────────────────────

    @Test fun an_unconfigured_build_maps_to_the_unavailable_code() {
        val error = feedbackErrorFor("not_initialized", retryable = false, stage = "feedback_open")
        assertEquals(AppErrorCode.FEEDBACK_UNAVAILABLE, error.code)
        assertFalse(error.retryable)
    }

    @Test fun rejected_credentials_are_not_retryable_however_the_sdk_reported_them() {
        // A revoked or mistyped token cannot be fixed by trying again, so the offer of a retry is
        // suppressed here even if the SDK were to call it retryable.
        listOf("http_401", "http_403").forEach { code ->
            val error = feedbackErrorFor(code, retryable = true, stage = "feedback_open")
            assertEquals(AppErrorCode.FEEDBACK_REJECTED, error.code)
            assertFalse("$code must not offer a retry", error.retryable)
        }
    }

    @Test fun rate_limiting_is_retryable() {
        val error = feedbackErrorFor("http_429", retryable = true, stage = "feedback_open")
        assertEquals(AppErrorCode.FEEDBACK_RATE_LIMITED, error.code)
        assertTrue(error.retryable)
    }

    @Test fun any_other_code_takes_its_retryability_from_the_sdk() {
        // The point of reading the SDK's own flag: a code we have never seen — a new server code,
        // say — still gets the right recovery action without a local table to update.
        val transient = feedbackErrorFor("network_error", retryable = true, stage = "feedback_open")
        assertEquals(AppErrorCode.FEEDBACK_SUBMIT_FAILED, transient.code)
        assertTrue(transient.retryable)

        val permanent = feedbackErrorFor("some_unknown_server_code", retryable = false, stage = "feedback_open")
        assertEquals(AppErrorCode.FEEDBACK_SUBMIT_FAILED, permanent.code)
        assertFalse(permanent.retryable)
    }

    @Test fun a_bare_http_status_is_never_matched_as_a_code() {
        // MissionGo reports "http_401", never "401". A mapping written against the number would
        // silently fall through to the generic failure.
        val error = feedbackErrorFor("401", retryable = false, stage = "feedback_open")
        assertEquals(AppErrorCode.FEEDBACK_SUBMIT_FAILED, error.code)
    }

    @Test fun the_sdk_code_is_kept_as_a_redacted_technical_cause() {
        val diagnostic = feedbackErrorFor("http_429", retryable = true, stage = "feedback_open").sanitizedDiagnostic()
        assertTrue(diagnostic.contains("HR-FEEDBACK-004"))
        assertTrue(diagnostic.contains("feedback_open"))
        assertTrue(diagnostic.contains("http_429"))
    }

    @Test fun every_feedback_code_has_bilingual_copy_and_keeps_its_code() {
        val codes = listOf(
            AppErrorCode.FEEDBACK_UNAVAILABLE to "HR-FEEDBACK-001",
            AppErrorCode.FEEDBACK_SUBMIT_FAILED to "HR-FEEDBACK-002",
            AppErrorCode.FEEDBACK_REJECTED to "HR-FEEDBACK-003",
            AppErrorCode.FEEDBACK_RATE_LIMITED to "HR-FEEDBACK-004",
        )
        codes.forEach { (code, value) ->
            assertEquals(value, code.value)
            val zh = com.hermes.client.data.error.AppError(code, retryable = false).localizedMessage(AppLanguage.ZH)
            val en = com.hermes.client.data.error.AppError(code, retryable = false).localizedMessage(AppLanguage.EN)
            assertTrue("$value zh must carry the code", zh.endsWith("($value)"))
            assertTrue("$value en must carry the code", en.endsWith("($value)"))
            assertTrue("$value zh must be Chinese", zh.any { it.code in 0x4E00..0x9FFF })
            assertFalse("$value en must not be Chinese", en.any { it.code in 0x4E00..0x9FFF })
        }
    }

    // ── Crash report trimming ────────────────────────────────────────────────────────────────

    @Test fun a_short_crash_report_is_untouched() {
        val report = "Hermes GO — crash report\napp: x\n\njava.lang.IllegalStateException: boom"
        assertEquals(report, trimCrashReport(report))
    }

    @Test fun an_over_long_crash_report_keeps_both_ends() {
        // The tail is the stack trace and the head is the device/version identity; a plain take()
        // would drop exactly the part that says where the process died.
        val head = "Hermes GO — crash report\napp: com.hermes.remote 0.1.100 (101)\n"
        val middle = "x".repeat(60_000)
        val tail = "\njava.lang.IllegalStateException: boom\n\tat com.hermes.Foo.bar(Foo.kt:42)"
        val trimmed = trimCrashReport(head + middle + tail)

        assertTrue("must fit the server limit", trimmed.length <= FEEDBACK_DESCRIPTION_LIMIT)
        assertTrue("head must survive", trimmed.startsWith("Hermes GO — crash report"))
        assertTrue("stack trace must survive", trimmed.endsWith("at com.hermes.Foo.bar(Foo.kt:42)"))
        assertTrue("the cut must be visible", trimmed.contains("trimmed"))
    }

    @Test fun trimming_is_stable_at_exactly_the_limit() {
        val report = "y".repeat(FEEDBACK_DESCRIPTION_LIMIT)
        assertEquals(FEEDBACK_DESCRIPTION_LIMIT, trimCrashReport(report).length)
        assertEquals(report, trimCrashReport(report))
    }

    // ── The unavailable stand-in ─────────────────────────────────────────────────────────────

    @Test fun the_unavailable_reporter_never_throws_and_reports_the_reason() {
        val reporter = UnavailableFeedbackReporter
        assertFalse(reporter.isAvailable)
        // Recording calls come from dozens of places; none of them may need a guard.
        reporter.setScreen("chat")
        reporter.setContext("gateway", mapOf("state" to "ready"))
        reporter.setAppearance(FeedbackAppearance.Dark)
        assertFalse("queueing must fail loudly enough for the caller to fall back", reporter.enqueue(FeedbackPrefill()))

        var outcome: FeedbackOutcome? = null
        reporter.open(activity = mockk<Activity>(relaxed = true), prefill = FeedbackPrefill()) { outcome = it }
        assertEquals(
            AppErrorCode.FEEDBACK_UNAVAILABLE,
            (outcome as FeedbackOutcome.Failed).error.code,
        )
    }

    // ── Appearance mirroring ─────────────────────────────────────────────────────────────────

    @Test fun the_in_app_theme_maps_onto_the_editor_appearance() {
        assertEquals(FeedbackAppearance.FollowSystem, ThemeMode.SYSTEM.toFeedbackAppearance())
        assertEquals(FeedbackAppearance.Light, ThemeMode.LIGHT.toFeedbackAppearance())
        // The case that made this necessary: dark chosen in-app while the system is light.
        assertEquals(FeedbackAppearance.Dark, ThemeMode.DARK.toFeedbackAppearance())
    }
}
