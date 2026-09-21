package com.hermes.client.data.network

import com.hermes.client.data.error.AppErrorCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Connector's contract report as it arrives on the phone. The payloads here are the shapes
 * `connector/src/hermes-contract.ts` produces (its tests pin that side), so a field renamed on one
 * side and not the other fails here rather than silently showing nothing.
 */
class HermesContractReportTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = json.decodeFromString<HermesContractReportDto>(body)

    private val breaking = """
        {"schema":1,"retryable":false,"hermesVersion":"0.22.0","minimumHermesVersion":"0.21.0",
         "versionBelowMinimum":false,"checkedAt":"2026-09-21T12:00:00.000Z","status":"breaking",
         "code":"HR-COMPAT-001","missing":[
           {"method":"GET","path":"/api/sessions/{id}/messages","tier":"required","feature":"history"},
           {"method":"POST","path":"/api/cron/jobs/{id}/trigger","tier":"optional","feature":"cron"}],
         "checkedPaths":41}
    """.trimIndent()

    @Test fun a_breaking_report_becomes_a_non_retryable_hr_compat_001() {
        val notice = parse(breaking).toNotice()!!
        assertEquals(HermesContractSeverity.BREAKING, notice.severity)
        assertEquals(AppErrorCode.HERMES_INCOMPATIBLE, notice.error.code)
        assertFalse(notice.error.retryable)
        assertEquals(listOf("history", "cron"), notice.features)
        assertEquals("0.22.0", notice.hermesVersion)
        val diagnostic = notice.error.sanitizedDiagnostic()
        assertTrue(diagnostic, diagnostic.contains("HR-COMPAT-001"))
        assertTrue(diagnostic, diagnostic.contains("GET /api/sessions/{id}/messages (required)"))
        assertTrue(diagnostic, diagnostic.contains("hermes=0.22.0"))
    }

    @Test fun a_degraded_report_names_its_own_code() {
        val missingOptional = parse(
            """{"schema":1,"status":"degraded","code":"HR-COMPAT-002","missing":[
               {"method":"PUT","path":"/api/skills/toggle","tier":"optional","feature":"skills"}]}""",
        ).toNotice()!!
        assertEquals(HermesContractSeverity.DEGRADED, missingOptional.severity)
        assertEquals(AppErrorCode.HERMES_FEATURES_MISSING, missingOptional.error.code)

        val tooOld = parse(
            """{"schema":1,"status":"degraded","code":"HR-COMPAT-003","hermesVersion":"0.20.1",
               "versionBelowMinimum":true,"missing":[]}""",
        ).toNotice()!!
        assertEquals(AppErrorCode.HERMES_BELOW_MINIMUM, tooOld.error.code)
        assertTrue(tooOld.features.isEmpty())
        assertTrue(tooOld.error.sanitizedDiagnostic().contains("belowMinimum=true"))
    }

    @Test fun compatible_unknown_and_foreign_schemas_show_nothing() {
        assertNull(parse("""{"schema":1,"status":"compatible","missing":[]}""").toNotice())
        // The check could not look. Never shown as a fault — that is the whole point of "unknown".
        assertNull(parse("""{"schema":1,"status":"unknown","reason":"openapi_unreachable"}""").toNotice())
        assertNull(parse("""{"schema":2,"status":"breaking","code":"HR-COMPAT-001"}""").toNotice())
        // An older Connector forwarded the path to Hermes, which answered with its own JSON.
        assertNull(parse("""{"detail":"Not Found"}""").toNotice())
    }

    @Test fun a_code_this_build_does_not_know_falls_back_to_its_severity() {
        val future = parse("""{"schema":1,"status":"breaking","code":"HR-COMPAT-099"}""").toNotice()!!
        assertEquals(AppErrorCode.HERMES_INCOMPATIBLE, future.error.code)
        // A registered code from another area is not a contract finding and is not borrowed.
        val foreign = parse("""{"schema":1,"status":"degraded","code":"HR-SESS-001"}""").toNotice()!!
        assertEquals(AppErrorCode.HERMES_FEATURES_MISSING, foreign.error.code)
    }

    @Test fun diagnostics_are_bounded_and_redacted() {
        val entries = (1..40).joinToString(",") {
            """{"method":"GET","path":"/api/x$it?token=secret$it","tier":"optional","feature":"cron"}"""
        }
        val notice = parse("""{"schema":1,"status":"degraded","code":"HR-COMPAT-002","missing":[$entries]}""")
            .toNotice()!!
        val diagnostic = notice.error.sanitizedDiagnostic()
        assertFalse(diagnostic, diagnostic.contains("secret"))
        assertTrue(diagnostic, diagnostic.contains("+28"))
        assertEquals(listOf("cron"), notice.features)
    }

    @Test fun the_report_path_matches_the_connector() {
        val source = sequenceOf(
            Path.of("../../connector/src/hermes-contract.ts"),
            Path.of("../connector/src/hermes-contract.ts"),
            Path.of("connector/src/hermes-contract.ts"),
        ).firstOrNull { it.isRegularFile() } ?: error("connector/src/hermes-contract.ts not found")
        val text = Files.readString(source)
        assertTrue(
            "CONTRACT_REPORT_PATH must equal $HERMES_CONTRACT_REPORT_PATH on both sides",
            text.contains("""CONTRACT_REPORT_PATH = "$HERMES_CONTRACT_REPORT_PATH""""),
        )
    }
}
