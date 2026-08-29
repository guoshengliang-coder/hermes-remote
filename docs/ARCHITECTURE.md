# Architecture

## Trust boundaries

1. Android authenticates to the HK Gateway with an app credential.
2. The macOS Connector authenticates independently and registers a device ID.
3. The Connector initiates the connection, so the Mac requires no inbound firewall rule.
4. The Gateway routes opaque structured events and does not need access to Mac files.
5. Hermes remains bound to localhost on the Mac.

## MVP lifecycle

1. Connector sends `hello(role=connector, deviceId=mac-mini)`.
2. Android sends `hello(role=app)` and receives device status.
3. Android sends a `command` with a unique request ID.
4. Gateway records the requesting app and forwards the command to the Mac.
5. Connector calls local Hermes and emits `accepted`, `delta`, and `complete` events.
6. Gateway forwards events only to the app that owns the request.

## Production follow-ups

- Put Caddy or Nginx in front of the Gateway for TLS and rate limiting.
- Replace static MVP credentials with short-lived, device-bound tokens.
- Add replay protection, request limits, persisted sessions, and push notifications.
- Define Hermes-specific event normalization for assistant text, tool calls, tool results, files, and errors.

## Android compatibility facade

The selected Android base already implements the Hermes REST and `/api/ws` JSON-RPC surfaces. To retain its sessions and management features, the production Gateway will expose compatible public routes and tunnel them through the Connector. The public app credential is validated in Hong Kong and is never forwarded to the Mac; the Connector adds the separate localhost Hermes credential.

The first HK deployment terminates TLS directly in the Node.js Gateway on port `8444` because ports `80`, `443`, and `8443` are already occupied. The Gateway supports certificate and private-key paths through environment variables; the dedicated service user must receive narrowly scoped read access to those files.

### REST flow

1. Android calls a normal Hermes `/api/*` route with `X-Hermes-Session-Token: <APP_TOKEN>`.
2. Gateway validates and removes the public credential, limits the request body, and sends a tunnel request to the registered Mac.
3. Connector logs into the local Hermes dashboard when needed, maintains the in-memory Cookie session, and forwards the request.
4. Only selected response headers and the response body return to Android.

### WebSocket flow

1. Android opens `/api/ws?token=<APP_TOKEN>` on the public Gateway.
2. Gateway validates the token and opens a logical socket through the Connector.
3. Connector uses its local Cookie session to mint a single-use Hermes WS Ticket.
4. Raw JSON-RPC frames, including `gateway.ready` and streaming events, travel bidirectionally without being reinterpreted by the relay.

The relay applies request-size, pending-request, WebSocket-count, and timeout limits. Production deployment should additionally restrict the cloud security group to port `8444`, add connection-level rate limiting, and rotate both relay credentials after installation.
