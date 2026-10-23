# Attachment inspection and downloads (HG-222)

The approved independent-channel design and wire/HTTP details are in [ARTIFACT_CHANNEL.md](ARTIFACT_CHANNEL.md).
Android completes a file before handing a content URI to an installed viewer/player. The HG-222 video
follow-up adds in-app Range playback; HG-232's negotiated concurrency, adaptive download batches and
sequential playback prefetch are documented in [ATTACHMENT_PERFORMANCE.md](ATTACHMENT_PERFORMANCE.md).
100 MiB is the quick-open/share threshold, not the new transport cap.

## Inspection

Authenticated HEAD `/api/files?path=<encoded original reference>` reuses the existing filesystem
policy. It returns original Content-Length/Content-Type and legacy X-Hermes-Max-File-Bytes, without
reading file bytes or building thumbnails, including when the file is oversized. A new Mac adds
X-Hermes-Max-Download-Bytes. A compatible Gateway adds X-Hermes-File-Channel: 1 only when it sees that
new Mac capability. Android then uses the independent ceiling; otherwise the legacy 100 MiB cap
(and any smaller Mac cap) applies. Old HEAD 405/501 leaves size unknown, never falls back to GET.

Visible cards coalesce metadata reads with three concurrent slots and reset at conversation,
account/Relay/Mac/profile boundaries. Metadata is a hint; actual download reopens the file safely.

## Interaction and persistence

Small files can transparently download before opening/sharing, or explicitly download. A known
undownloaded file above 100 MiB offers Download and guidance to download first. Downloads show
progress and pause/resume/cancel. Completed files of any supported size open/share locally, even
while the Mac is offline. Actual safety-limit refusal still uses HR-FILE-004 and recovery copy.

New downloads use app-private persistent storage and SQLite tasks, isolated by account,
installation, Gateway, original device and remote reference. Persist only fully validated Range
checkpoints, after fsync; extra bytes left by a crash are truncated back to that checkpoint.
Resume prepares a fresh reference and rejects a changed source generation. A source-change recovery
explicitly starts again; transient transport errors keep the validated prefix. Finalization renames
the partial atomically; a crash between rename and DB completion can recover that completed file.
No credential or one-use return ticket is persisted. Old GET and thumbnail clients remain unchanged.

## Verification and release

Protocol/parser, real Connector/Gateway loopback, Android network/persistence/state/UI and error
localization tests cover the testable boundaries. See SMOKE_TEST.md and ANDROID_SMOKE.md for actual
cellular/NAT, OEM background survival and original-player checks. L1/L3 do not certify HONOR behavior.
Gateway, Connector and Android must be published and verified separately before MissionGo enters
verification. Deployment and artifact publication require their own authorization.
