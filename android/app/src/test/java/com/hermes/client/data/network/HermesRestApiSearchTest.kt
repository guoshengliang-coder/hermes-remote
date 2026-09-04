package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URLDecoder

class HermesRestApiSearchTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer) = HermesRestApi(testHttpClient(), json) {
        GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "secret")
    }

    // The enriched hit (title / archived / last_active / source / message_count) parses, and the
    // request carries the quoted CJK query plus the excluded sources.
    @Test fun search_parses_enriched_hit_and_sends_quoted_query_with_exclusions() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"results":[{"session_id":"s1","snippet":"…先跑打包脚本再部署…","role":"user","model":"opus",
                "title":"发布流程","archived":true,"last_active":1756900000.5,"source":"desktop","message_count":12,
                "lineage_root":"s0","is_active":false}]}"""
        ).build())

        val hits = api(serverRule.server).searchSessions("部署 gradle", profile = "personal", excludeSources = listOf("cron", "subagent"))
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals("s1", hit.sessionId)
        assertEquals("发布流程", hit.title)
        assertTrue(hit.archived)
        assertEquals(1756900000.5, hit.lastActive!!, 0.0)
        assertEquals("desktop", hit.source)
        assertEquals(12, hit.messageCount)

        val target = URLDecoder.decode(serverRule.server.takeRequest().target, "UTF-8")
        assertTrue(target, target.startsWith("/api/sessions/search?q=\"部署\" gradle&limit=30"))
        assertTrue(target, target.contains("exclude_sources=cron,subagent"))
        assertTrue(target, target.endsWith("profile=personal"))
    }

    // An older gateway that returns only session_id + snippet still parses (all enrichment optional).
    @Test fun search_parses_minimal_hit_and_omits_empty_exclusions() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"results":[{"session_id":"s2","snippet":"hello"}]}"""
        ).build())

        val hit = api(serverRule.server).searchSessions("hello").single()
        assertNull(hit.title)
        assertFalse(hit.archived)
        assertNull(hit.lastActive)
        assertEquals(0, hit.messageCount)

        val target = serverRule.server.takeRequest().target
        assertFalse(target, target.contains("exclude_sources"))
        assertFalse(target, target.contains("profile="))
    }
}
