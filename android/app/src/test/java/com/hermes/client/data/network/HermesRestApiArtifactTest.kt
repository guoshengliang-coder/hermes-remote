package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class HermesRestApiArtifactTest {
    @get:Rule val serverRule = MockWebServerRule()

    @Before fun setUp() { DebugLog.setEnabled(true); DebugLog.clear() }
    @After fun tearDown() { DebugLog.setEnabled(false); DebugLog.clear() }

    private fun api(server: MockWebServer) = HermesRestApi(
        testHttpClient(),
        Json { ignoreUnknownKeys = true },
    ) { GatewayConfig(server.url("/").toString().trimEnd('/'), "secret") }

    private fun apiAt(baseUrl: String) = HermesRestApi(
        testHttpClient(),
        Json { ignoreUnknownKeys = true },
    ) { GatewayConfig(baseUrl, "secret") }

    private fun restLines(): List<String> =
        DebugLog.entries.value.filter { it.category == "rest" }.map { it.message }

    /** A port nothing is listening on, so the connect attempt is refused instead of hanging. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

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

    @Test fun ordinary_file_upload_streams_a_file_larger_than_the_old_six_mib_limit() = runTest {
        val source = File.createTempFile("upload-large", ".pdf")
        try {
            source.outputStream().use { output ->
                val block = ByteArray(64 * 1024) { 37 }
                repeat(7 * 1024 * 1024 / block.size) { output.write(block) }
            }
            serverRule.server.enqueue(MockResponse.Builder().code(201).body(
                """{"path":"/tmp/uploaded.pdf","name":"report.pdf","size":${source.length()}}""",
            ).build())

            val result = api(serverRule.server).uploadArtifact(source, "report.pdf", "application/pdf")

            assertEquals(source.length(), result.sizeBytes)
            val request = serverRule.server.takeRequest()
            assertEquals(source.length().toString(), request.headers["Content-Length"])
            assertEquals(source.length(), request.body?.size?.toLong())
            assertEquals("/api/files/upload?name=report.pdf", request.target)
        } finally {
            source.delete()
        }
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

    /**
     * HG-161: the attachment upload was the only REST call in this file that wrote nothing to the
     * diagnostics. A send that died inside it left a single `send(…) failed: timeout` line and no
     * way to tell which call produced it — the report could not even establish whether the images
     * reached the wire. An upload outcome speaks now, success or failure.
     */
    @Test fun a_successful_upload_writes_its_outcome_line() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(201).body(
            """{"path":"/Users/bs/.hermes/images/upload_20260930_103918_1.png","name":"1000091452.jpg","size":3}""",
        ).build())

        api(serverRule.server).uploadArtifact("abc".toByteArray(), "1000091452.jpg", "image/jpeg")

        val line = restLines().single { it.contains("POST /api/files/upload") }
        assertTrue("expected the upload outcome, got $line", line.contains("← 201"))
        assertTrue(
            "the outcome line must name what was stored, got $line",
            line.contains("upload_20260930_103918_1.png"),
        )
    }

    @Test fun a_refused_upload_writes_the_status_and_the_server_body() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().code(503).body("""{"error":"connector_disconnected"}""").build(),
        )

        try {
            api(serverRule.server).uploadArtifact("abc".toByteArray(), "1000091452.jpg", "image/jpeg")
            fail("expected HermesApiException")
        } catch (error: HermesApiException) {
            assertEquals(503, error.code)
        }

        val line = restLines().single { it.contains("POST /api/files/upload") }
        assertTrue("expected the refusal, got $line", line.contains("← 503"))
    }

    /**
     * The other half of HG-161: a transport failure has to be attributable. Before this line a
     * timed-out upload and a timed-out submit read exactly alike, which is why the report could not
     * say whether the client's 10s default killed the body write, the connect, or nothing at all.
     */
    @Test fun a_transport_failure_names_the_exception_and_the_bytes() = runTest {
        val api = apiAt("http://127.0.0.1:${closedPort()}")

        try {
            api.uploadArtifact("abc".toByteArray(), "1000091452.jpg", "image/jpeg")
            fail("expected a transport failure")
        } catch (error: Exception) {
            assertFalse(
                "a refused connect is not a Hermes answer and must not be reported as one",
                error is HermesApiException,
            )
        }

        val line = restLines().single { it.contains("POST /api/files/upload") }
        assertTrue("the failure line must mark the outcome, got $line", line.contains("✗"))
        assertTrue("the failure line must carry the byte count, got $line", line.contains("3b"))
    }
    @Test fun metadata_uses_only_head_and_reads_original_size_and_smaller_mac_limit() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200)
            .addHeader("Content-Length", "132120576")
            .addHeader("X-Hermes-Max-File-Bytes", "52428800").build())
        val info = api(serverRule.server).artifactMetadata("/tmp/视频.mp4")!!
        assertEquals(132120576L, info.sizeBytes)
        assertEquals(52428800L, info.maxBytes)
        val request = serverRule.server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("secret", request.headers["X-Hermes-Session-Token"])
        assertTrue(request.target!!.startsWith("/api/files?path="))
        assertEquals(1, serverRule.server.requestCount)
    }

    @Test fun old_connector_does_not_trigger_a_get_fallback() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(405).build())
        assertEquals(null, api(serverRule.server).artifactMetadata("/tmp/video.mp4"))
        assertEquals("HEAD", serverRule.server.takeRequest().method)
        assertEquals(1, serverRule.server.requestCount)
    }

    @Test fun metadata_never_increases_the_app_limit() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "0")
            .addHeader("X-Hermes-Max-File-Bytes", "9999999999").build())
        val info = api(serverRule.server).artifactMetadata("/tmp/empty.txt")!!
        assertEquals(0L, info.sizeBytes)
        assertEquals(com.hermes.client.domain.ARTIFACT_DOWNLOAD_LIMIT_BYTES, info.maxBytes)
    }

    @Test fun chunked_oversize_is_rejected_and_partial_file_is_removed() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200)
            .chunkedBody("x".repeat(1025), 64).build())
        val target = File.createTempFile("artifact-chunked", ".part").apply { delete() }
        try {
            api(serverRule.server).downloadArtifact("/tmp/video.mp4", target, maxBytes = 1024)
            fail("expected size rejection")
        } catch (error: HermesApiException) {
            assertEquals(413, error.code)
        }
        assertFalse(target.exists())
    }

    @Test fun exact_limit_download_still_succeeds() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("x".repeat(1024)).build())
        val target = File.createTempFile("artifact-boundary", ".part").apply { delete() }
        try {
            api(serverRule.server).downloadArtifact("/tmp/video.mp4", target, maxBytes = 1024)
            assertEquals(1024L, target.length())
        } finally { target.delete() }
    }

    @Test fun metadata_and_download_keep_the_conversations_explicit_mac_and_account_bearer() = runTest {
        val manager = io.mockk.mockk<com.hermes.client.data.auth.AccountSessionManager>(relaxed = true)
        io.mockk.coEvery { manager.connection("original-mac") } returns com.hermes.client.data.auth.AccountConnection(
            serverRule.server.url("/").toString().trimEnd('/'), "fixture-bearer", "original-mac",
            com.hermes.client.data.auth.AccountDeviceRouteMode.EXPLICIT_DEVICE,
        )
        val api = HermesRestApi(testHttpClient(), Json { ignoreUnknownKeys = true }, manager, testHttpClient()) {
            GatewayConfig("https://unrelated-legacy.invalid", "legacy-secret")
        }
        serverRule.server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "3").build())
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("mp4").build())
        assertEquals(3L, api.artifactMetadata("/tmp/video.mp4", "original-mac")!!.sizeBytes)
        val target = File.createTempFile("artifact-device", ".mp4").apply { delete() }
        try {
            api.downloadArtifact("/tmp/video.mp4", target, deviceId = "original-mac")
            repeat(2) {
                val request = serverRule.server.takeRequest()
                assertTrue(request.target!!.startsWith("/v2/devices/original-mac/api/files?"))
                assertEquals("Bearer fixture-bearer", request.headers["Authorization"])
                assertEquals(null, request.headers["X-Hermes-Session-Token"])
            }
        } finally { target.delete() }
    }

}
