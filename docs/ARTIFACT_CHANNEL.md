# Independent attachment downloads (HG-222)

Approved in the dispatch conversation on 2026-10-21. This supersedes the original HG-222
100 MiB hard-block interaction in ARTIFACT_DOWNLOAD.md. No phone VPN or Mac inbound port is needed.

## Transport and compatibility

Release allocation: Gateway 0.4.34, Connector 0.1.16 (planned managed pair 0.4.11),
Android 0.1.174/code 175. Allocation is not publication. Production currently runs Gateway
0.4.33/schema 18, while this main branch declares schema 20. R5-F8 admits exactly one
schema step, so the current bundle must not be sent to the routine or single-step production
entrypoint. Resolve and verify the migration path before deployment; do not lower the manifest's
declared schema or bypass backup/admission checks. On 2026-10-21 the owner approved the
bounded, proof-gated two-step path described in DEPLOYMENT.md and a one-publication exception
to use the existing trusted M4 signing key. The key is not generated, rotated or transferred;
independent signature verification, full public readback and paired-index gates still apply.

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
opening/sharing; an undownloaded large non-video file exposes Download first. All completed files can open
or share. The approved video follow-up adds original-quality in-app playback below and above 100 MiB
within the existing safety ceiling; explicit Download remains available and external sharing still
requires a completed file. Explicit downloads are durable app-private files,
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

## Publication — 2026-10-21

The owner separately approved publication, the bounded schema 18→20 path and a one-publication
exception to use M4's existing trusted signing identity in place. Source PR #604, version PR #605
and migration-gate PR #606 are merged. All three distributed binaries use source
`8fbb9ebdd59b5810c26c3266a1e745b96a6124ec`: Gateway **0.4.34**, Connector **0.1.16** in signed managed
pair **0.4.11**, and Android **0.1.174 (175)**. Desktop App and upstream Hermes were not upgraded.

The official publishers completed their package, signature, immutable-file and index gates. A
second full public download verified every Desktop file and both signed envelopes; the Connector
BUILD-IDENTITY matches the source above. Android's full public APK is 33,652,799 bytes, SHA-256
`d85ae19de5b7da0264edb2bce23690a99aff648a357083cb9cfcf3510b2301ac`; its package/version and canonical
debug certificate match the index. The public URL is
`https://mrlgs.net/releases/Hermes-Remote-0.1.174-debug.apk`; unversioned APK URLs return 404.
No published component was installed onto a user's Mac or phone by this publication.

An authenticated public-edge synthetic transfer downloaded 134,217,735 bytes in bounded Range
requests with matching SHA-256; control REST responses during transfer peaked at 29 ms. The
legacy 100 MiB GET cap, unauthenticated rejection and stale ETag rejection were verified. This
does not measure a home/cellular link or original HONOR MP4 playback. Those device smoke gates
remain open, including every available OEM's background-download policy.

Public smoke caught nginx's automatic 301 for bare `/api/files/downloads`: a trailing-slash
proxy prefix redirects the preparation POST and changes it to GET. The live edge now has an
exact preparation route, and all repository edge generators/templates have the same exception
with a regression test. This follow-up changes operator templates, not the already published
binaries. Keep the account binary regex before the generic account API regex when integrating
routes into an existing site; preserve unrelated routes and validate nginx before reloading.

The product-specific publication receipt has SHA-256
`55e57ffef1a2750c06e871363f9a92ae8d25de7e4fd064e4c604344d8d447de9` (canonical sorted JSON). It binds
the before/after sources, Gateway production switch and identity, public Range smoke, both
Desktop indexes/signatures/files, and the official Android workflow/package/public-file proof.


## Video streaming follow-up — approved 2026-10-21

Android uses Media3 1.11.1 with an authenticated custom DataSource. The player uses opaque local
media URIs; bearer credentials, original paths and Gateway URLs are never passed to a media intent.
Legacy peers retain full-download-then-play for small videos; large videos report HR-FILE-012.
Both playback and download request closed 256 KiB ranges, verify strong If-Match/ETag, exact
Content-Range and body length, and reject 200 responses. Seeking may fetch any aligned range,
including the MP4 tail index. No whole-file staging, transcoding or HLS service is introduced.

A separate app-private 256 MiB LRU block cache stores only complete validated blocks using atomic
replacement, a stored length and SHA-256 corruption check. Its key binds Gateway/account/Mac,
original path, file size and ETag; changing the ten-minute opaque reference does not invalidate
matching bytes. The player retains at most one 256 KiB block per DataSource in memory. Explicit
downloads reuse cached blocks and persist only committed sequential prefixes; gaps never count as
a finished download. Cache eviction or cache storage pressure does not invalidate durable files.

A per-owner arbiter retains the existing one-transfer-per-Mac limit. Active playback cancels only
an in-progress local download range, rolls it back to the last committed prefix and holds download
work until playback stops. This is an automatic yield, distinct from user Pause/Cancel. User-paused
tasks never restart on lease release. Other phones remain subject to Gateway capacity and receive
HR-FILE-013 with retry. No wire/capability/protocol change is needed; Gateway, Connector, Desktop,
Web and release-server consumers keep their existing contracts and require no new release.

Expired references or a transport reconnect re-prepare once and compare ETag and size before any
new data is accepted. Changed sources and switched owners stop playback. Controls preserve position
for the current ViewModel and source generation, including retry and close/reopen (not across process
death). Pausing, leaving the screen or backgrounding releases the player and its network loader;
returning waits for Play. Explicitly started downloads continue under the existing job/service.

Test impact: add cache integrity/eviction/isolation and arbiter cancellation tests, including OkHttp
IOException during preemption; random-access API validation; generation-preserving renewal;
playback/download reuse; bilingual playback errors with diagnostic redaction; update the old large
video hard-disable UI expectation. Device tests use an original generated H.264/AAC MP4 and virtual
free atoms before its tail moov to prove both <100 MiB and >100 MiB first-frame-before-full transfer
and seeking through the production DataSource and real decoder. No new APK publication is implied.


### Follow-up verification — 2026-10-22

- Full Android JVM baseline: **2,613 tests, zero failures**. Final related regressions after audio
  focus/external-player integration: **200/200**; two additional UI policy/recovery cases and the
  existing screenshot cases: **12/12**. Final request-owner race guard: **8/8** network API cases;
  switching credentials while building a request is rejected before any HTTP request. assembleDebug, assembleDebugAndroidTest and lintDebug pass.
  LocalizationCoverage initially flagged the neutral duration string; it now carries the explicit
  language-neutral rationale. No test or lint rule was disabled.
- L1: six updated/new attachment and player-error screenshots cover light/dark, Chinese/English
  and enlarged text; inspected directly. Complete-file Other player recovery and large non-video
  Download-first policies are exercised separately.
- L3: **Pixel_9_API_36_1 / emulator-5554, API 36**, VideoStreamingDeviceTest **3/3**. For the
  134,986,891-byte tail-index MP4, first frame arrived after **1,031,307 bytes**, also less than the
  2,866,315-byte actual MP4 media payload. The 4,963,467-byte sample gives the same startup count.
  Real decoder/surface, 12-second seek, authenticated headers, cache-reusing explicit download,
  actual player Pause/Play/Download and background/foreground network stop pass. A real player
  screenshot was also inspected directly. The device fixture uses system accessibility windows
  because this API-36 image cannot initialize Espresso's old InputManager reflection adapter.
- L2 unavailable: no physical phone attached. This does not certify targetSdk 37, the original
  HONOR MP4/codec, cellular/home NAT, or OEM background policy; retain the smoke gates above.
- Local development APK signing certificate matches SIGNING.md:
  `06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5`.
  No version bump, distributed APK, production deployment or release is part of this follow-up.
