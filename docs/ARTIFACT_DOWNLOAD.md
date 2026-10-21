# Attachment inspection and opening (HG-222)

Android downloads a complete attachment to its private cache before handing a content URI to
an installed viewer/player. It does not stream video playback. The app ceiling remains
104857600 bytes (100 MiB); a smaller Connector `MAX_FILE_BYTES` lowers the effective ceiling.

## Download-free metadata

Authenticated `HEAD /api/files?path=<URL-encoded original path>` follows the existing
legacy or account/device tunnel. Connector uses the same `openArtifactFile` descriptor and
FILES_ROOT/temporary-directory policy as GET. It closes that descriptor without reading the
body, generating a thumbnail or starting a chunk stream. Successful HEAD returns 200 even
when the original is oversized:

- `Content-Length`: original byte count, including zero.
- `Content-Type`: existing Connector MIME mapping.
- `X-Hermes-Max-File-Bytes`: positive configured Connector download ceiling.
- `Cache-Control: no-store`.
- No response body. Original paths/credentials are not added to diagnostic logs.

403/404/400 retain the existing forbidden/missing/invalid-file policy with no HEAD body.
GET retains its existing size rejection, acknowledged streaming and error behavior.
Gateway forwards the bounded existing headers and the added limit header; authentication,
account/device authorization, browser route policy and shared wire versions do not change.

Android clamps the reported ceiling to 100 MiB. It uses HEAD only and never falls back to GET
for size discovery. An older Connector's 405/501, missing/invalid length or unavailable network
leaves size unknown, with ordinary opening still available. An older Gateway that filters the
limit header still forwards Content-Length, allowing Android's own ceiling to be enforced;
a lower Mac ceiling is still enforced by GET. HEAD reads use the normal bounded REST deadline.

## Card behavior and final enforcement

Cards show type and size before download; unknown sizes are labeled explicitly. Visible cards
coalesce inspection per path within one conversation screen with at most three concurrent
requests. Inspection state is recreated at account/Relay/Mac/profile/conversation boundaries,
and leaving cancels its UI coroutine. Reopening rechecks metadata. The metadata is a display hint,
not an authorization grant: files can change after HEAD and GET always reopens/revalidates them.

A known size above the effective ceiling keeps the name/size readable, displays HR-FILE-004
with the ceiling and compression/segmentation recovery, and disables both opening and sharing.
Exact-limit files remain eligible. Downloads still check Content-Length and every received chunk;
failure removes partial files. A late 413 also locks the card and opens a persistent explanation.
Metadata failures do not become noisy download errors or fabricate a zero-byte size.

Common extensions select nine themed thin-stroke icons: video, audio, document, HTML/code,
PDF, spreadsheet, presentation, archive and unknown. MIME fallback supports extensionless media.

## Compatibility and release artifacts

Publish Gateway + Connector + Android App for the complete new experience; source merge alone does
not update any installed component. Existing GET clients (Desktop and Web included) remain
compatible, need no code change and keep their current behavior. This is additive HTTP behavior,
with no new upstream Hermes dependency, wire field, error code or raised download limit. Deployment,
version allocation and official APK publication remain separate owner-authorized gates.

## Verification

- Connector real-process integration: ordinary/oversized HEAD return exact size and zero chunks;
  GET keeps exact bytes, policy refusals and stream acknowledgements.
- Gateway loopback integration: authenticated HEAD and size/limit forwarding without a body;
  unrelated response headers remain filtered.
- Android network/state/UI tests: HEAD-only old-Connector fallback, Mac cap clamping, exact/over-limit
  and unknown-length downloads, partial cleanup, screen isolation/cancellation, known/unknown size,
  disabled open/share, bilingual registered errors and type classification.
- Roborazzi: light, app-dark/system-light and enlarged Chinese/English text.

Actual OEM player handling and the original MP4 need a physical-device check after the relevant
components are separately released. See `ANDROID_SMOKE.md` and `SMOKE_TEST.md`.

Local verification on 2026-10-21: Android full unit/build baseline passed (2,577 tests), followed by
33 focused tests including the added account/device routing and preflight rejection cases; lint
passed. L1 light/dark/large-text screenshots and the SVG icon sheet were inspected directly. L3
passed one real-touch component test on Pixel 9 API 36 (no physical device attached). Connector
HEAD/filesystem streaming regression and all 10 Gateway loopback/header tests passed.
`npm run build` passed; `npm test` passed all Protocol/Connector/Gateway/release-server cases it ran,
but two unrelated legacy-recovery script tests fail on this host's LibreSSL 3.3.6. An isolated
CMS AES-GCM encrypt/decrypt fixture also fails at decryption with exit 4, and the same two tests
fail when rerun alone. Those recovery sources were not changed. No production deploy or official
APK publication was performed.
