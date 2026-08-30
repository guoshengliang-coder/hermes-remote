package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.File

class HermesRestApiArtifactTest {
    @get:Rule val serverRule = MockWebServerRule()

    private fun api(server: MockWebServer) = HermesRestApi(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
    ) { GatewayConfig(server.url("/").toString().trimEnd('/'), "secret") }

    @Test fun upload_sends_raw_bytes_and_reads_remote_path() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(201).body(
            """{"path":"/tmp/uploaded.txt","name":"notes.txt","size":3}""",
        ).build())

        val result = api(serverRule.server).uploadArtifact("abc".toByteArray(), "notes.txt", "text/plain")

        assertEquals("/tmp/uploaded.txt", result.path)
        val request = serverRule.server.takeRequest()
        assertEquals("/api/files/upload?name=notes.txt", request.target)
        assertEquals("secret", request.headers["X-Hermes-Session-Token"])
        assertEquals("abc", request.body?.utf8())
    }

    @Test fun download_streams_binary_response_to_destination() = runTest {
        val expected = ByteArray(256 * 1024) { (it % 251).toByte() }
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(expected)).build())
        val target = File.createTempFile("artifact", ".bin").apply { delete() }
        try {
            api(serverRule.server).downloadArtifact("/tmp/report.bin", target)
            assertArrayEquals(expected, target.readBytes())
            assertEquals("/api/files?path=%2Ftmp%2Freport.bin", serverRule.server.takeRequest().target)
        } finally {
            target.delete()
        }
    }

    @Test fun oversized_download_is_rejected_and_partial_file_is_removed() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().code(200).body(okio.Buffer().write(ByteArray(128 * 1024))).build(),
        )
        val target = File.createTempFile("artifact-oversized", ".bin").apply { delete() }

        try {
            api(serverRule.server).downloadArtifact("/tmp/large.bin", target, maxBytes = 64 * 1024L)
            fail("expected HermesApiException")
        } catch (error: HermesApiException) {
            assertEquals(413, error.code)
        }
        assertFalse(target.exists())
    }
}
