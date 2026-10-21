package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.domain.ArtifactDownloadDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.File

class ArtifactRangeApiTest {
    @get:Rule val server = MockWebServerRule()
    private fun api() = HermesRestApi(testHttpClient(), Json { ignoreUnknownKeys = true }) {
        GatewayConfig(server.server.url("/").toString().trimEnd('/'), "fixture-secret")
    }
    private val descriptor = ArtifactDownloadDescriptor("a".repeat(64), 6, "\"${"b".repeat(64)}\"", "video/mp4", 1024)

    @Test fun metadata_enables_large_files_only_when_both_peers_support_the_channel() = runBlocking {
        val api = api()
        for (enabled in listOf(true, false)) {
            val response = MockResponse.Builder().code(200).addHeader("Content-Length", 126L * 1024 * 1024)
                .addHeader("x-hermes-max-file-bytes", 100L * 1024 * 1024)
                .addHeader("x-hermes-max-download-bytes", 8L * 1024 * 1024 * 1024)
            if (enabled) response.addHeader("x-hermes-file-channel", "1")
            server.server.enqueue(response.build())
            val metadata = api.artifactMetadata("/tmp/video.mp4")!!
            assertEquals(enabled, metadata.independentDownloads)
            assertEquals(if (enabled) 8L * 1024 * 1024 * 1024 else 100L * 1024 * 1024, metadata.maxBytes)
            assertEquals("HEAD", server.server.takeRequest().method)
        }
    }

    @Test fun resumed_range_appends_only_an_exact_matching_206() = runBlocking {
        val target = File.createTempFile("artifact-resume", ".part").apply { writeText("abc") }
        try {
            server.server.enqueue(MockResponse.Builder().code(206).addHeader("ETag", descriptor.etag)
                .addHeader("Content-Range", "bytes 3-5/6").body("def").build())
            val api = api()
            api.downloadArtifactRange(descriptor, target, "mac-a", api.artifactOwnerKey("mac-a")!!)
            assertEquals("abcdef", target.readText())
            val request = server.server.takeRequest()
            assertEquals("bytes=3-5", request.headers["Range"])
            assertEquals(descriptor.etag, request.headers["If-Match"])
            assertFalse(request.target!!.contains("/tmp/"))
        } finally { target.delete() }
    }

    @Test fun an_ignored_range_or_wrong_generation_never_corrupts_a_partial() = runBlocking {
        for ((status, etag, range) in listOf(Triple(200, descriptor.etag, "bytes 3-5/6"),
            Triple(206, "\"other\"", "bytes 3-5/6"), Triple(206, descriptor.etag, "bytes 0-2/6"),
            Triple(416, descriptor.etag, "bytes */6"))) {
            val target = File.createTempFile("artifact-refused", ".part").apply { writeText("abc") }
            try {
                server.server.enqueue(MockResponse.Builder().code(status).addHeader("ETag", etag)
                    .addHeader("Content-Range", range).body("xyz").build())
                val api = api()
                try { api.downloadArtifactRange(descriptor, target, null, api.artifactOwnerKey(null)!!); fail("must reject") }
                catch (_: HermesApiException) { }
                assertEquals("abc", target.readText())
            } finally { target.delete() }
        }
    }

    @Test fun preparation_requires_both_sides_and_never_places_the_original_path_in_the_url() = runBlocking {
        val api = api()
        val json = Json.encodeToString(ArtifactDownloadDescriptor.serializer(), descriptor)
        server.server.enqueue(MockResponse.Builder().code(200).body(json).build())
        try { api.prepareArtifactDownload("/tmp/private video.mp4", null, api.artifactOwnerKey(null)!!); fail("missing capability") }
        catch (error: HermesApiException) { assertEquals("HR-FILE-012", error.errorCode) }
        val request = server.server.takeRequest()
        assertEquals("/api/files/downloads", request.target)
        assertTrue(request.body!!.utf8().contains("/tmp/private video.mp4"))
        server.server.enqueue(MockResponse.Builder().code(200).addHeader("x-hermes-file-channel", "1").body(json).build())
        assertEquals(descriptor, api.prepareArtifactDownload("/tmp/a", null, api.artifactOwnerKey(null)!!))
    }

    @Test fun source_missing_is_distinguished_from_an_old_connector() = runBlocking {
        val api = api()
        server.server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"code":"HR-FILE-005"}}""").build())
        try { api.prepareArtifactDownload("/tmp/a", null, api.artifactOwnerKey(null)!!); fail() }
        catch (error: HermesApiException) { assertEquals("HR-FILE-005", error.errorCode) }
        server.server.enqueue(MockResponse.Builder().code(404).body("""{"error":"not_found"}""").build())
        try { api.prepareArtifactDownload("/tmp/a", null, api.artifactOwnerKey(null)!!); fail() }
        catch (error: HermesApiException) { assertEquals("HR-FILE-012", error.errorCode) }
    }

    @Test fun changed_account_or_device_scope_is_rejected_before_any_request() = runBlocking {
        val api = api()
        val target = File.createTempFile("artifact-owner", ".part").apply { writeText("abc") }
        try {
            try { api.downloadArtifactRange(descriptor, target, "mac-b", api.artifactOwnerKey("mac-a")!!); fail() }
            catch (error: HermesApiException) { assertEquals(401, error.code) }
            assertEquals(0, server.server.requestCount)
        } finally { target.delete() }
    }
}
