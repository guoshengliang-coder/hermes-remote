# Independent attachment downloads (HG-222)

Approved in the dispatch conversation on 2026-10-21. This supersedes the original HG-222
100 MiB hard-block interaction in ARTIFACT_DOWNLOAD.md. No phone VPN or Mac inbound port is needed.

## Transport and compatibility

The existing authenticated control socket carries only preparation/cancellation instructions.
Each file response uses a separate outbound HTTP(S) PUT from the Mac to the configured Gateway
origin. Gateway streams its body into the waiting, authenticated Android GET with backpressure;
it never stages the complete file. This is a narrowly scoped reverse HTTP channel, not a general
port-forwarding service. It ships with the Connector component; Desktop needs no additional tool.

POST `/api/files/downloads` with `{ "path": "<original reference>" }` is Connector-owned and
returns `{artifactId,sizeBytes,etag,contentType,maxBytes,quickOpenBytes}`. Gateway applies the
same existing account/device or legacy authorization before forwarding. Paths occur only in
this authenticated request body, never in public download URLs or diagnostic logs. The Mac
reuses openArtifactFile's FILES_ROOT, temporary-directory and symlink/ownership policy.
An opaque reference lasts 10 minutes, is bounded in memory and is cleared on control disconnect.
Re-preparing after expiry/restart yields a new reference; the source version must still match.

GET `/api/files/downloads/<artifactId>` accepts a single explicit `Range: bytes=start-end`
and mandatory strong `If-Match` source version. Gateway creates a short-lived, single-use
return capability, passes it to the authenticated Mac over the control socket and accepts the
response only at PUT `/api/file-channel/<transferId>`, Authorization Bearer capability.
The URL and origin are derived locally from GATEWAY_URL; no arbitrary callback URL is accepted.
The capability is never given to Android, persisted or logged. A return request cannot select
another device or file. Its lifetime is the pending request; completion/cancellation consumes it.
Browser cookie routes remain unavailable for this API until separately integrated.

Gateway binds each transfer to the original control socket, revalidates account/device access
before accepting data and while it runs, stops on control disconnect, and limits one active
transfer per Mac and a bounded global count. Internal request/response buffering is bounded;
client cancellation and idle timeout terminate Mac reading. Separate connections still share
physical bandwidth. Mac sends paced binary blocks with a configurable upload rate.

Connector checks size/type/device/inode/mtime/ctime on the opened descriptor. A changed version
returns 412; malformed/unsatisfiable ranges return 416 with `Content-Range: bytes */size`.
It rechecks the source before releasing the final block so a changed file cannot appear complete.
Clients verify 206 offsets, length, ETag and final size; 200 is never appended to existing bytes.
The ETag is a file-generation identifier, not a cryptographic content digest.

Legacy GET `/api/files?path=...` and thumbnails retain MAX_FILE_BYTES (default 100 MiB).
New downloads have an independent MAX_DOWNLOAD_BYTES safety ceiling (default 8 GiB), not an
Android quick-open limit. There is no upstream Hermes or release-server change. Old peers are
detected explicitly and small legacy downloads remain available; no silent large control transfer.

## Android and design

100 MiB is an interaction threshold: a small file can transparently finish downloading before
opening/sharing; an undownloaded large file exposes Download first. All completed files can open
or share. No streaming media player is added. Explicit downloads are durable app-private files,
not the evictable 512 MiB chat cache. Tasks and partial files survive process loss; no bearer token
is serialized. Resume reauthenticates against the original account, Gateway and Mac and checks
the file generation. Account changes cannot silently reroute an old task.

Cards show size/type, progress, Pause/Resume/Cancel, failure recovery and Downloaded. Background
completion posts a notification without unexpectedly opening another app. Foreground open/share
only continues while the requesting screen remains resumed. Android 14+ uses a user-initiated
data-transfer job; older Android uses a foreground dataSync service. OS stop/force-stop is respected;
checkpointed bytes remain available. Disk space is checked before and during transfer.

## Test impact and release gates

Regression coverage must include legacy behavior, Range/If-Match errors, reference expiry,
cross-device return rejection, cancellation, slow consumers/backpressure, control disconnect,
source mutation and bounded-memory large transfer; Android resume after process loss, 200/206/416,
account changes, space shortage, coalescing, threshold boundaries and finished-file sharing.
Update stale hard-block UI tests and inspect L1 screenshots plus available L2/L3 layers.
Cellular/home NAT, HONOR background policy and original MP4 playback remain physical smoke gates.

Deploy compatible Gateway/edge routes, then the Connector component, then Android. Nginx must
disable request/response buffering on the return/download routes and use progress idle timeouts.
Return bodies are sized Range responses, not unrestricted file uploads. Rollback retains old GET.
Gateway, Connector and Android are required release artifacts; merge/version/publication remain
separate gates. No production rollout is authorized by this source implementation.

## Local verification — 2026-10-21

- Android full unit baseline: 2,593 tests, zero failures; subsequent final focused suite: 48 tests,
  zero failures, including source/owner checks, Range validation, crash checkpoints and pause/cancel
  IOException regression. A final metadata capability case is also run separately.
- assembleDebug/assembleDebugAndroidTest pass; final lintDebug passes in a fresh single-worker
  invocation. An earlier combined invocation hit Kotlin FIR analysis failures in test source sets;
  no lint rule was disabled to bypass them.
- L1: light/dark and Chinese/English enlarged screenshots were recorded and inspected directly.
- L3: Pixel 9 API 36 passed 3 instrumentation tests. The real UIDT job downloaded a 4 MiB fixture,
  paused after a committed prefix, resumed from that prefix, completed while the Activity was
  stopped and posted completion notification. Component touches cover large-file Download,
  Pause/Resume and completed Open/Share, plus legacy limit compatibility. The first run exposed
  IOException overwriting Pause; the regression test and final device run verify the fix.
- L2 unavailable: no physical phone attached. API 36 does not certify targetSdk 37 platform gates.
  Original HONOR MP4 playback, actual cellular/NAT and every available OEM ROM remain smoke gates.
- Debug signing certificate matches SIGNING.md (SHA-256
  06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5).
- npm run build passes. Default Node suites: Protocol 18/18, Connector 150/150,
  Gateway 222 passed + 32 opt-in skips, release-server 37/37. Scripts: 426 passed,
  1 skipped, 2 unchanged legacy-recovery decrypt failures on this host's LibreSSL 3.3.6.
  Those sources were not altered. Explicit network tests additionally exercise the real 1 GiB
  transfer; see SMOKE_TEST.md for observed memory and mock-command latency, not production claims.
- No production service was restarted, no Android version allocated and no distributable APK
  published. Both source merge and the three required artifact releases remain separate gates.
