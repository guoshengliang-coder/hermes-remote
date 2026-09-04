package com.hermes.client.di

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ping cadence is bounded by something that lives outside this module: the edge proxy closes an
 * idle upstream at `proxy_read_timeout 75s` (deploy/hermes-edge.nginx.conf.template), and OkHttp
 * only sends a ping once per interval. Raise the interval past that ceiling and every idle socket is
 * dropped by nginx instead — a failure that reproduces only through the real edge, never against the
 * local dev stack. This pins the relationship so the next change to either side has to notice it.
 */
class GatewayPingIntervalTest {
    private val edgeProxyReadTimeoutSeconds = 75L

    @Test fun pingCadenceStaysUnderTheEdgeProxyIdleTimeout() {
        assertTrue(
            "ping interval ${GATEWAY_PING_INTERVAL_SECONDS}s must stay well under the edge " +
                "proxy's ${edgeProxyReadTimeoutSeconds}s idle timeout",
            GATEWAY_PING_INTERVAL_SECONDS <= edgeProxyReadTimeoutSeconds * 2 / 3,
        )
    }

    @Test fun pingCadenceLeavesRoomForADelayedPongOnASleepingDevice() {
        // The app holds no wake lock, so a backgrounded ping/pong round trip can be batched by the
        // OS. The old 20s deadline killed healthy sockets; anything that short is a regression.
        assertTrue(GATEWAY_PING_INTERVAL_SECONDS >= 30L)
    }
}
