package com.hermes.client.update

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UpdateHttpClientTest {
    @get:Rule
    val originRule = MockWebServerRule()

    @get:Rule
    val targetRule = MockWebServerRule()

    private val certificate =
        "06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5"
    private val emptyIndex =
        """{"schemaVersion":1,"channel":"internal","latestVersionCode":0,"generatedAt":"2026-08-30T00:00:00Z","versions":[]}"""

    @Test
    fun `isolated update request sends no gateway credentials`() = runTest {
        val server = originRule.server
        server.enqueue(MockResponse.Builder().code(200).body(emptyIndex).build())

        fetchUpdateIndex(
            createUpdateHttpClient(),
            server.url("/index.json").toString(),
            UpdateManifestParser(Json, certificate),
        )

        val request = server.takeRequest()
        assertNull(request.headers["x-hermes-session-token"])
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `index response is bounded before it is buffered or parsed`() = runTest {
        val server = originRule.server
        val limit = emptyIndex.toByteArray().size.toLong()
        server.enqueue(MockResponse.Builder().code(200).body(emptyIndex + " ".repeat(64)).build())
        val failure = runCatching {
            fetchUpdateIndex(createUpdateHttpClient(), server.url("/index.json").toString(), UpdateManifestParser(Json, certificate), limit)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)

        server.enqueue(MockResponse.Builder().code(200).body(emptyIndex).build())
        assertEquals(
            0,
            fetchUpdateIndex(createUpdateHttpClient(), server.url("/index.json").toString(), UpdateManifestParser(Json, certificate), limit).latestVersionCode,
        )
    }

    @Test
    fun `isolated update client does not follow cross origin redirects`() {
        val origin = originRule.server
        val target = targetRule.server
        origin.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", target.url("/stolen"))
                .build(),
        )

        createUpdateHttpClient().newCall(
            Request.Builder().url(origin.url("/index.json")).build(),
        ).execute().use { response ->
            assertEquals(302, response.code)
        }

        assertEquals(1, origin.requestCount)
        assertEquals(0, target.requestCount)
    }
}
