# Architecture

## Trust boundaries

1. Android authenticates to the HK Gateway with an app credential.
2. The macOS Connector authenticates independently and registers a device ID.
3. The Connector initiates the connection, so the Mac requires no inbound firewall rule.
4. The Gateway routes opaque structured events and does not need access to Mac files.
5. Hermes remains reachable only on the Mac's private interface and is never exposed publicly.

## Planned account control plane (not implemented)

The account-mode I0 contract adds a provider-neutral Hermes GO account above the existing relay:

```text
Google identity -> Hermes GO account -> one active Desktop Connector -> one local Hermes
                                  \-> independent phone installation A
                                  \-> independent phone installation B
```

Google proves identity only. The Gateway/account service verifies the platform-specific provider
proof and issues its own short-lived access plus rotating refresh credentials. Connector
authentication is independent: the Mac proves possession of a binding-specific private key against a
short-lived Gateway challenge, so routine background reconnect does not depend on interactive Google
login.

The local account backend uses transactional PostgreSQL, separate from the existing legacy
lifecycle JSON file. It enforces one active Connector binding per account, per-installation phone
sessions/cursors, atomic replacement, and cross-account isolation. The existing App Token,
Connector Token, `/api/*`, `/api/ws`, and `/v1/connect` remain available throughout the compatibility
window. Account routes and Connector control messages are separately versioned under V2/capability
gates.

This plan does not modify Hermes. The local Hermes credential stays on the Mac, the Connector remains
outbound-only, and migration snapshots contain only Hermes GO/Connector-owned state. See
`ACCOUNT_MODE_API.md`, `ACCOUNT_MODE_SECURITY.md`, `ACCOUNT_MODE_MIGRATION.md`, and
`ACCOUNT_MODE_TEST_PLAN.md`.

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
4. Only selected response headers and the response body return to Android. Bodies are split into
   sequence-checked chunks; Gateway acknowledges a chunk only after writing it to the Android HTTP
   response, which provides end-to-end backpressure and keeps the shared Connector socket bounded.

### Attachment flow

1. Android reads a picker/camera URI with a hard limit. Still images above the direct-upload limit
   are resized and JPEG-compressed; unsupported executables and oversized non-image files are rejected.
2. Android uploads raw bytes to authenticated `POST /api/files/upload`; there is no data-URL wrapper.
   Gateway's existing request cap and Connector's 6 MiB upload cap bound the single-request MVP.
3. Connector stores the transient file with mode `0600` inside `UPLOAD_ROOT` (which must be within
   `FILES_ROOT`) and returns its Mac-visible path. Uploads expire after seven days by default and are
   also trimmed by count and total size.
4. Android attaches that path to the live Hermes session. Images render as thumbnails; output files
   render as cards and download only when opened or shared.
5. Authenticated `GET /api/files?path=...` resolves and opens files beneath `FILES_ROOT`, rejects
   traversal/symlink escapes, enforces the output limit, and streams bytes through the acknowledged
   response-chunk protocol. A large result therefore fails only its HTTP request instead of closing
   the Mac's shared control connection.

External Markdown images are fetched by a separate credential-free Android HTTP client. It permits
HTTPS only, refuses HTTPS-to-HTTP redirects and private/local address ranges, and caps image bytes.

### WebSocket flow

1. Android opens `/api/ws` on the public Gateway and sends `X-Hermes-Session-Token` in the
   WebSocket upgrade header. Password-gated mode uses only a short-lived, single-use query ticket.
2. Gateway validates the token and opens a logical socket through the Connector.
3. Connector uses its local Cookie session to mint a single-use Hermes WS Ticket.
4. Raw JSON-RPC frames, including `gateway.ready` and streaming events, travel bidirectionally without being reinterpreted by the relay.

## Read-only cross-client lifecycle observation

The Connector opens a second, independent local Hermes WebSocket and calls only the official
`session.active_list` JSON-RPC method. Hermes source and data files are not modified, and the
observer never calls `session.resume` or `session.activate`, so a Desktop/TUI-owned run keeps its
original transport.

The observer polls every two seconds while any session is `starting`, `working`, or `waiting`, and
backs off to twenty seconds while idle. It converts snapshots into sanitized lifecycle transitions
(`started`, `waiting`, `resumed`, and `completed`). Prompt text, assistant deltas, tool output,
commands, file paths, credentials, and approval payloads are deliberately excluded from this wire
message.

Each transition is written atomically to the Connector outbox before it is sent. The Relay stores it
durably and only then acknowledges it; reconnects therefore replay safely and deduplicate by stable
event ID. Android can consume the persisted inbox from `/api/mobile/events` even while the Mac is
offline, then mark events delivered or read through the matching `/ack` and `/read` routes.

Legacy Token mode stores one bounded JSON inbox. Account mode stores the sanitized event once in
PostgreSQL and atomically fans out independent receipt rows to every active phone installation.
Paging, delivered, and read state are therefore isolated per phone and account; no bearer token or
account identifier is forwarded to Hermes.

This read-only API exposes a generic `waiting` status, not the approval ID or approval choices owned
by another client connection. Consequently, a PC-started approval can be notified as “needs
attention”, but cannot be approved from Android without a future official Hermes cross-client API.
If an older Hermes version does not expose `session.active_list`, only this observer enters a long
retry cycle; chat, files, and the main Connector tunnel continue normally.

## Android notification monitoring

Android consumes the Relay lifecycle inbox independently of the foreground chat WebSocket. One
cursor is advanced only after the notification reducer has accepted a batch and the Relay has
acknowledged delivery, so process death replays an event instead of silently losing it. Stable
notification IDs make that replay update the existing notification rather than create a duplicate.
The same reducer updates the process-wide session runtime store, so a task started by another
client appears as running, waiting for attention, or completed/unread in the session list. These
observed runs are tagged as external and therefore never trigger the phone-owned high-frequency
background policy by themselves.

The default **Smart** strategy adapts to the current state:

- While the app is visible, an in-process poll runs every two seconds. No foreground service or
  permanent status notification is kept alive.
- When a run started by this phone remains active after the app goes into the background, the
  foreground service polls every three seconds and keeps the existing live event socket available.
- When no phone-started run is active, the service and socket are stopped. Android `JobScheduler`
  performs a network-constrained periodic inbox check (the platform minimum is about 15 minutes).

Users can explicitly choose **Real-time** (keep the foreground service in the background) or
**Power saving** (always use the periodic background check). Notification visibility on the lock
screen and heads-up presentation remain subject to Android's notification permission and the
user's channel settings.

FCM is intentionally not required by this phase. A future FCM data message can be a low-power
wakeup hint for the idle state, but the device must still fetch the durable Relay inbox by cursor;
FCM must never be the source of truth. Adding it requires a Firebase project and per-installation
token registration, neither of which belongs in source control.

The relay applies request-size, pending-request, WebSocket-count, and timeout limits. The edge preserves
WebSocket upgrades and never redirects APK or Relay traffic to a non-standard public port.
