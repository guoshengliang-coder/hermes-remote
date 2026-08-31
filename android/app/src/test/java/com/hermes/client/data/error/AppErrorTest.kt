package com.hermes.client.data.error

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {
    @Test fun diagnosticsKeepTheCodeAndRedactSecrets() {
        val diagnostic = AppError(
            code = AppErrorCode.CONNECTION_FAILED,
            retryable = true,
            stage = "gateway_ready",
            technicalCause = "token=secret cookie:abc ticket=xyz url?signature=hidden&safe=1",
        ).sanitizedDiagnostic()

        assertTrue(diagnostic.contains("HR-CONN-002"))
        assertTrue(diagnostic.contains("gateway_ready"))
        assertTrue(diagnostic.contains("<redacted>"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("abc"))
        assertFalse(diagnostic.contains("hidden"))
    }
}
