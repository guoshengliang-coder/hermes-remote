# Connector control connection recovery

HG-199 adds opt-in local maintenance admission via `HERMES_GO_MAINTENANCE_DIRECTORY`. Desktop
explicitly prepares an owner-only 0700 mailbox; 0600 `request.json` pauses new HTTP/uploads/WS/chat,
while admitted operations finish and retain cancellation/ACK handling. A private `ack.json` reports
run/source identity, exact request hash, PID/instance, freshness and active count. Deletion/expiry is
never a release; unsafe configured records fail closed. `HR-HERMESUPDATE-005` is bilingual and
non-retryable. No env configured preserves existing installations. ACK is not proof of Hermes
CLI/cron/task idleness. This source is not yet published or enabled by the production Desktop adapter.
See `docs/HERMES_UPGRADE.md`; `hermes-maintenance*.test.ts` uses disposable files and actual loopback
Connector processes. No Gateway/protocol version or production credentials are changed.

HG-232 adds pooled HTTP(S) returns and up to four ranges of one opaque file reference, sharing the
existing aggregate upload budget. Old Gateways/Android clients remain serial. See
`../docs/ATTACHMENT_PERFORMANCE.md` for negotiation, compatibility and the local benchmark.

## Independent attachment downloads (HG-222)

`HEAD /api/files` reads original metadata without a payload. Its legacy GET still uses
`MAX_FILE_BYTES` (default 100 MiB). POST `/api/files/downloads` prepares an opaque, short-lived
reference under the same filesystem policy. New Android clients request validated byte ranges
through the Gateway; the Mac returns binary bytes over a separate outbound HTTP(S) request.
No local listening port, extra phone client, cloud file staging or upstream Hermes change is needed.
The control socket carries only instructions/cancellation, not the file body.

`MAX_DOWNLOAD_BYTES` is a separate safety ceiling (default 8 GiB). `FILE_DOWNLOAD_BYTES_PER_SECOND`
(default 4 MiB/s, maximum 128 MiB/s) paces the independent stream. One active download per Mac is
admitted; the control heartbeat remains unchanged. Both components must support the channel before
Android enables it. Non-loopback plain-HTTP Gateway origins retain legacy transport but do not
advertise independent downloads. See `../docs/ARTIFACT_CHANNEL.md` for compatibility and verification.

The Connector opens the outbound WebSocket to Gateway. See the root README for the topology and
`docs/ENVIRONMENT.md` for configuration. Hermes credentials remain on the Mac.

## Connector 0.1.16 — HG-222 independent downloads (allocated, not published)

Allocated with Gateway 0.4.34 and Android 0.1.174 for the independent attachment channel.
The next signed managed pair is planned as 0.4.11, retaining Node 22.23.2 and the immutable
Hermes Server archive. On 2026-10-21 the owner approved a new one-publication exception to
use the existing trusted M4 signing key, retaining both independent verifiers, full public
readback and paired-index gates. It does not authorize creating, rotating or transferring a key.
Deploy the compatible Gateway and streaming edge routes before this component and Android.
Publication and installation must be verified separately; this version allocation changes neither.

## Connector 0.1.15 — HG-219 heartbeat diagnostics

Release allocation for the metadata-only diagnostics merged in PR #599. Managed manifests advance
from 0.4.9 to 0.4.10 with the existing Node 22.23.2 and immutable Hermes Server archive. This is a
Connector-only publication; Gateway remains at 0.4.33/schema18 by the owner's 2026-10-20 decision.
The owner authorized reuse of the existing trusted local signing key for this publication only,
with both independent signature verifiers, full public readback and paired-index gates retained.
The signing exception does not apply to later releases. No heartbeat deadline or reconnect policy
changes; the underlying M4 outage remains unresolved. Verify installation on M4 before collecting
the next natural recurrence. Publication/install evidence is recorded separately after the gates.

## Generated files in temporary directories (HG-217, Connector 0.1.14)

`GET /api/files` retains the existing `FILES_ROOT` grant and additionally permits files in
`/tmp`, `/private/tmp`, and the Connector process's `os.tmpdir()` (including their canonical
aliases). This lets an existing desktop conversation's generated report download through the
same Android/Web file reference without moving or regenerating it. The file must still exist.

The temporary grant requires a known effective user ID, an ordinary file owned by that user,
and exactly one hard link. Its resolved target must stay within the matching temporary root.
Intermediate directories must belong to the same user and have no group/other write permission;
the shared temporary root may instead be root-owned with the sticky bit. The opener validates
the opened file's device/inode, type and ownership and rechecks the temporary path/directories.
It rejects path traversal and symlink escapes. A symlink out of `FILES_ROOT` is still refused,
even if it points into a temporary directory. This is read-only; upload/cache roots do not change.

The existing download size cap, acknowledged streaming, filename headers and error responses
remain compatible. Refusal uses HTTP 403 (`HR-FILE-003` in Android), a removed file uses 404
(`HR-FILE-005`), and an oversized file uses 413 (`HR-FILE-004`). There is no new client protocol,
error code or Android version requirement. Publish/install the updated Connector separately;
a source merge alone does not change an installed Mac.

`artifact-file.test.ts` covers the filesystem policy, including deterministic replacement races.
`artifact-download.integration.test.ts` runs a real Connector against a loopback relay to verify
historical `/tmp` and canonical paths, the process temporary directory, exact streamed bytes,
existing root behavior and unchanged rejection responses. See the HG-217 smoke steps in
`docs/SMOKE_TEST.md` for actual Android open/share verification after an authorized rollout.

## Account-mode Hermes health and control readiness

The unauthenticated key proof still expires after 5 seconds. Once authenticated, a local Hermes
health probe has one 10-second budget, including Basic Auth login, retries and response-body reads;
Gateway waits up to 15 seconds. Closing the control socket or replacing its probe cancels that
probe's local HTTP work. A cancelled probe does not cancel a shared login still needed by another
request. Probes have a 64 KiB response limit.

The Connector prefers public `GET /api/health` (`ok: true`, optional `version`), then verifies any
configured credentials with protected `GET /api/profiles/active`. The public response alone cannot
prove credentials. A missing route (404/405) falls back to `/api/status` or `/api/sessions?limit=1`,
respectively; authentication errors and server failures do not fall back. Profile/session contents
remain on the Mac. This avoids the full status route's platform/MCP work on supported Hermes.

A failed or expired health probe records the authenticated Connector as online with Hermes
unreachable. Gateway retains the control socket and checks again 20 seconds after each completed
probe/write, with only one probe outstanding. New account-mode REST requests and WebSocket upgrades
receive the existing retryable HTTP 502 `HR-CONN-006`; existing tunnels still enforce account and
binding authorization without being closed solely by this health observation. A successful recheck
restores routing on the same socket. A pending binding still needs healthy verification to activate.

The existing v2 preflight messages are reused, so Gateway and Connector can be rolled out separately.
An older Connector can still take its original 60 seconds; Gateway ignores stale reply IDs. Only a
new Connector supplies the 10-second deadline and cancellation. A new Connector against an older
Gateway remains subject to that Gateway's original 5-second preflight cutoff. Deploy Connector
first, then Gateway, after separately authorized releases. Legacy mode and transport heartbeat
timeouts retain their existing behavior.

`connector.hermes_health` Gateway logs report initial health, state changes and probe timeouts with
`reachable`, `timedOut` and `durationMs`, without credentials or upstream response bodies. These
budgets are initial operating values: observe timeout rate and latency under production load before
tuning them. They do not repair event-loop starvation or packet loss.

## Web default-model editing (HG-166, Connector 0.1.13)

The Connector-owned `POST /api/hermes-remote/default-model/set` accepts a bounded model/provider
selection and optional explicit expensive-model confirmation for one profile. It supplies main
scope to local Hermes and returns only the applied model/provider or a confirmation flag; endpoint,
credential, cron and warning metadata stays on the Mac. Publish this signed Connector component
before Gateway 0.4.29 advertises `default-model-write`. The Web session picker separately uses
strict session-scoped `config.set model` through Gateway's `session-model-config` capability.

Verify the signed component manifest and public archive, then update the target Mac and check its
online control connection. A component candidate build alone does not establish publication or
installation. See `docs/DESKTOP_RELEASE_CHANNEL.md` and `docs/SMOKE_TEST.md` HG-166.

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

## Phone uploads (HG-145)

The default per-file limit is 50 MiB. The phone stages ordinary files on disk; Gateway accepts this
size only for `POST /api/files/upload` and sends one 256 KiB frame at a time per Mac. Connector
acknowledges each frame after writing it to a private `.part` file, then renames the file after the
last frame. Failed and cancelled streams remove the partial file. The upload request may run for up
to 10 minutes. Images retain their existing compression and attachment flow. PDFs are attached as
files with `file.attach`.

Roll out the new Connector before the Gateway route and the Android client. An older Connector does
not acknowledge upload start; Gateway detects that after 10 seconds, then preserves uploads up to
the former 6 MiB limit through the legacy request. Larger files receive `HR-FILE-010` until that
Mac updates. The capability decision is cached only for that control socket.

Test a near-limit upload against the actual Mac and monitor Connector memory and control socket
health before releasing the change. A configured `MAX_UPLOAD_BYTES` below 50 MiB lowers the
effective ceiling. The local 50 MiB frame and Connector tests peaked near 182 MiB each, including
their test fixtures; the Gateway container limit is 256 MiB, so production still needs observation.

## Verification

HG-219 adds metadata-only heartbeat diagnostics without changing the recovery policy. A local
timeout writes `relay.heartbeat_timeout`; that and `relay.disconnected` share one per-connection
`controlConnectionId`. Snapshots include last inbound message/ping/pong ages, the most recent
outgoing ping's local write state/times, `bufferedBytes`, and timer delay. A completed write is not
proof of remote delivery, and missing observations are omitted rather than reported as zero. No
frames, ping payloads, endpoints, credentials or raw write errors enter these records. Gateway's
existing heartbeat-timeout event has corresponding fields. The M4 incident and unresolved root
cause are recorded in `docs/HG_219_CONNECTION_DIAGNOSTICS.md`; production verification still needs
a separately authorized release and observation on the actual Mac/phone.

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

`connector-health.test.ts` covers the bounded lightweight probe, protected credential proof,
older-Hermes fallbacks, timeout, replacement and cancellation. `connector-health.integration.test.ts`
launches the real Connector to verify degraded/recovered health on one authenticated socket,
HTTP forwarding after recovery, and cancellation of a stalled upstream read on socket close.
Gateway's account-session and authorizer tests cover the 15-second deadline, stale results,
periodic recovery, authorization checks and new-request `HR-CONN-006` responses.

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

维护期间，已连接的 JSON-RPC 通道也拒绝新任务（Connector `-32002` / `HR-HERMESUPDATE-005`）。
既有任务的回答、审批和中断仍可继续；整个通道到实际关闭前一直计入 busy，不会因“当前没有请求”就
宣称已排空。不能解析或关联的帧不转发，保留通道以接收既有任务事件；正式升级适配器仍需让客户端
结束连接并独立确认全部 Hermes 写入者已经退出。
