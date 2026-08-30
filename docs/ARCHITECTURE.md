# Architecture

## Trust boundaries

1. Android authenticates to the HK Gateway with an app credential.
2. The macOS Connector authenticates independently and registers a device ID.
3. The Connector initiates the connection, so the Mac requires no inbound firewall rule.
4. The Gateway routes opaque structured events and does not need access to Mac files.
5. Hermes remains reachable only on the Mac's private interface and is never exposed publicly.

## MVP lifecycle

1. Connector sends `hello(role=connector, deviceId=mac-mini)`.
2. Android sends `hello(role=app)` and receives device status.
3. Android sends a `command` with a unique request ID.
4. Gateway records the requesting app and forwards the command to the Mac.
5. Connector calls local Hermes and emits `accepted`, `delta`, and `complete` events.
6. Gateway forwards events only to the app that owns the request.

## Production follow-ups

- Add connection-level rate limiting at the Nginx edge.
- Replace static MVP credentials with short-lived, device-bound tokens.
- Add replay protection, request limits, persisted sessions, and push notifications.
- Define Hermes-specific event normalization for assistant text, tool calls, tool results, files, and errors.

## Android compatibility facade

The selected Android base already implements the Hermes REST and `/api/ws` JSON-RPC surfaces. To retain its sessions and management features, the production Gateway will expose compatible public routes and tunnel them through the Connector. The public app credential is validated in Hong Kong and is never forwarded to the Mac; the Connector adds the separate localhost Hermes credential.

Production exposes only `mrlgs.net:443` to Android and the Mac Connector. Nginx terminates the public
connection and routes `/api/*` plus `/v1/connect` to the TLS Gateway on `127.0.0.1:8444`; all other
paths go to the TLS release server on `127.0.0.1:9443`. Port 8444 remains temporarily public only for
old-client compatibility and can be closed after migration.

### REST flow

1. Android calls a normal Hermes `/api/*` route with `X-Hermes-Session-Token: <APP_TOKEN>`.
2. Gateway validates and removes the public credential, limits the request body, and sends a tunnel request to the registered Mac.
3. Connector logs into the local Hermes dashboard when needed, maintains the in-memory Cookie session, and forwards the request.
4. Only selected response headers and the response body return to Android.

### WebSocket flow

1. Android opens `/api/ws` on the public Gateway and sends `X-Hermes-Session-Token` in the
   WebSocket upgrade header. Password-gated mode uses only a short-lived, single-use query ticket.
2. Gateway validates the token and opens a logical socket through the Connector.
3. Connector uses its local Cookie session to mint a single-use Hermes WS Ticket.
4. Raw JSON-RPC frames, including `gateway.ready` and streaming events, travel bidirectionally without being reinterpreted by the relay.

The relay applies request-size, pending-request, WebSocket-count, and timeout limits. The edge preserves
WebSocket upgrades and never redirects APK or Relay traffic to a non-standard public port.
