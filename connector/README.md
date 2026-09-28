# Connector control connection recovery

The Connector opens the outbound WebSocket to Gateway. See the root README for the topology and
`docs/ENVIRONMENT.md` for configuration. Hermes credentials remain on the Mac.

## Reconnect backoff (HG-147)

A WebSocket upgrade, authentication response, or a briefly usable connection does not reset the
retry budget. A connection earns a reset only after it has been **routing-ready for at least 30
seconds**, with a solicited pong received after readiness and no older than one configured
`CONTROL_HEARTBEAT_MS` interval at disconnection. Elapsed times use a monotonic clock. An account
binding waiting for activation is not routing-ready.

Unstable connections use exponential backoff with equal jitter: successive waits are 1–2, 2–4,
4–8, 8–16, and then 15–30 seconds (upper bounds exclusive). Jitter remains active at the cap.
A stable connection resets the next wait to 1–2 seconds. Failure before authentication, an
authenticated socket that immediately closes, and a socket that is merely open but silent all
retain the accumulated backoff.

This changes reconnection pacing, not the heartbeat interval, heartbeat timeout, handshake timeout,
wire protocol, selected endpoint, or handling/replay of task commands. A failed network path may
remain unavailable. After several failures, recovery can wait up to 30 seconds for the next retry;
this is the tradeoff for limiting reconnect churn. Alternate routes are a separate validation task.

`relay.disconnected` logs mode, close code, authenticated state, connection lifetime and shutdown
state. `relay.reconnect_scheduled` logs the retry number, chosen delay, whether stability was earned,
ready duration and time since the last eligible pong. These diagnostic fields contain no endpoint,
close-reason text, credentials or message bodies. Existing desktop-readable connection messages
remain available. No new user-visible error or error code is introduced.

## Verification

```sh
npm run build -w @hermes-remote/protocol
npm run test -w @hermes-remote/connector
npm run build
npm test
```

`control-reconnect.test.ts` uses an injected clock to cover unstable ready connections, unready
sockets, stale/missing pongs, the stability threshold, per-attempt isolation, and bounded jitter.
`control-reconnect.integration.test.ts` launches the real Connector against a loopback WebSocket
server in both legacy and account modes: repeated ready-then-close cycles must increase the actual
time between connection attempts. The old open-time reset fails those regression cases.

After a separately authorized Connector release, verify with a disposable test binding:

1. Repeatedly close the control socket shortly after readiness; the reconnect log must progress to
   longer waits, including when the transport accepted authentication.
2. Let readiness and bidirectional heartbeats remain healthy for more than 30 seconds, then close;
   the next retry should return to the initial range.
3. Drop traffic after readiness; a silent connection must retain backoff, and the existing heartbeat
   timeout must still force reconnection.
4. Confirm existing Mac sessions remain on their originating device and no task command is replayed
   by the reconnect policy. This change makes no promise of uninterrupted sessions during outage.

Production packet loss, actual Mac sleep/wake and the 24-hour route comparison require runtime
observation; loopback and fake-clock tests do not replace those checks.
