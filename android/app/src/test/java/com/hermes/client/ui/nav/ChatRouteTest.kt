package com.hermes.client.ui.nav

import com.hermes.client.domain.Session
import com.hermes.client.ui.chat.ChatLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pure-JVM: the encoder is injected so android.net.Uri is not needed. */
class ChatRouteTest {
    private val encode: (String) -> String = { "<$it>" }

    private fun session(id: String, title: String) = Session(
        id = id, title = title, model = null, provider = null,
        messageCount = 1, profile = "personal", workspace = "No workspace", source = "tui",
    )

    // A search hit carries profile, title, and the query; every value goes through the encoder.
    @Test fun search_hit_route_carries_query() {
        val route = chatRoute(ChatLaunch.searchHit("s1", profile = "personal", title = "发布 流程", query = "部署 gradle"), encode)
        assertEquals("chat/<s1>?profile=<personal>&title=<发布 流程>&q=<部署 gradle>", route)
    }

    // Without a query the route is unchanged from before (no dangling q=).
    @Test fun existing_session_without_query_has_no_q_param() {
        val route = chatRoute(ChatLaunch.existing(session("s1", "Title")), encode)
        assertEquals("chat/<s1>?profile=<personal>&title=<Title>", route)
        assertFalse(route.contains("q="))
    }

    // A blank query is dropped rather than encoded.
    @Test fun blank_query_is_dropped() {
        val route = chatRoute(ChatLaunch.existing(session("s1", "Title"), initialQuery = "   "), encode)
        assertFalse(route.contains("q="))
    }

    // New-session flag keeps its position before the query.
    @Test fun new_session_flag_precedes_query() {
        val route = chatRoute(ChatLaunch.new("n1").copy(initialQuery = "x"), encode)
        assertEquals("chat/<n1>?new=true&q=<x>", route)
    }
}
