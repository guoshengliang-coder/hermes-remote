# Desktop E4 test record

Date: 2026-09-07
Status: E4-D local multi-device UX, signed bootstrap, packaged orchestration, account Connector,
binding, and rollback/restart recovery complete; real release and target-Mac enablement remain pending.

## Scope completed

- Desktop decodes the additive E3 capability and owned-device response only when
  `supportsDeviceSelection=true`; older Gateways remain on the existing singular binding view.
- The selected Mac is stored locally per internal account ID. A missing/stale selection falls back to
  the server's account default, then the first available device.
- Local selection and account default are separate actions. Default changes persist their idempotency
  key before the request and reuse it after a lost response.
- The account UI identifies this Desktop's binding, the locally selected Mac, the account default,
  Connector state, and Hermes reachability.
- Bootstrap planning is read-only. Existing running or stopped Connectors are preserved, clean Macs
  stop at the signed-release gate, and every future machine-changing operation is marked as requiring
  confirmation.
- `HR-BIND-009`, `HR-BIND-010`, and `HR-BIND-011` map to bilingual Desktop issues and device-selection
  recovery without exposing server response text.
- Signed envelopes are verified over exact payload bytes with pinned Ed25519 public keys. Unknown
  fields/keys, tampering, expiry, incompatible platform/architecture/macOS, wrong origins, oversized
  responses, redirects, and mismatched artifact sizes/hashes fail closed.
- Both required tar archives are preflighted before extraction. Traversal, duplicate normalized
  entries, symlinks, hard links, devices, FIFOs, and special entries are rejected.
- Releases stage into private immutable version directories and activate with one atomic relative
  symlink update. Connector credentials and the account LaunchAgent are private files; the plist
  includes a credential-file path but no Connector Token or Hermes password.
- The Connector defaults to unchanged legacy mode. Explicit account mode loads a strict owned 0600
  Ed25519 credential, proves the exact Gateway challenge, runs bounded local Hermes preflight, and
  begins routing only after `connector.ready` enables it.
- Binding creation and confirmation persist their idempotency keys before network calls and reuse
  them after a lost response.
- The migration coordinator requires exact user confirmation, enforces one legacy/account Connector,
  waits for key proof plus health before remote commit, restores legacy after pre-commit failure, and
  recovers interrupted runs from its durable journal. An ambiguous remote commit stops the candidate
  and enters manual attention without reviving legacy.
- `HR-MIGRATE-001` through `HR-MIGRATE-005` now have bilingual Desktop issue mappings based on the
  safe terminal state.

## Automated evidence

The initial E4-B `npm run desktop:test` baseline passed 117 Desktop core tests, including device-path
validation, discovery decoding, per-account persistence,
stale-selection fallback, local/cloud selection separation, signed acquisition/extraction,
filesystem ownership and permission gates, activation/rollback, exact launchd operations, migration
fault paths, restart recovery, and lost-response idempotency reuse.

The latest local slice adds `DesktopReleaseAcquirer`, closing the previously disconnected path from
signed manifest acquisition through deterministic Hermes Server/Connector download, hash validation,
and safe extraction. Four focused tests prove canonical component order plus explicit discard,
partial-workspace cleanup after the second component fails digest validation, and fail-closed handling
of incomplete/duplicate component sets, symlinked workspace roots, or colliding run IDs. The result
remains inert input to the existing migration coordinator; no credential, LaunchAgent, process,
binding, or active-release pointer is changed by acquisition.

`DesktopManagedBootstrapExecutor` now provides the only core transition from acquired, inert inputs
to the existing migration coordinator. Its current eleven tests cover separated preparation/commit,
exact version confirmation, rejection of foreign preparations, manifest-derived executable paths,
TLS/loopback WebSocket derivation, cancellation, retained cleanup retry, migration failure with safe
cleanup, and dual migration/cleanup failure escalation. Five runtime tests cover inert composition,
deterministic per-user paths, strict active-install recognition, and interrupted-state classification.

The following local E4-C contract slice removes the arbitrary `signedReleaseAvailable` Boolean.
Desktop now derives readiness from complete packaged HTTPS origin/channel/architecture/pinned-key
configuration plus the exact `hermes-serve-v1` runtime contract, and requires Gateway to advertise
the same contract through a separate default-off capability. The frozen process boundary is
`hermes serve --host 127.0.0.1 --port 9119`, one absolute non-root `HERMES_HOME`, and exact ready/
port-conflict sentinel lines. Invalid configuration, absent capability, and mismatched versions all
remain read-only and perform no download or mutation.

The migration core now writes a second private plist for the exact
`com.hermesgo.hermes-server` label, using only the signed Hermes entrypoint, `hermes-serve-v1`
arguments, and `HERMES_HOME`. It starts Hermes before Connector. Candidate readiness requires both a
new exact ready line appended after a file-identity/size checkpoint and healthy loopback HTTP; stale
markers, an unrelated service on port 9119, unsafe/symlinked logs, or more than 64 KiB of new log data
cannot pass. Timeout prevents Connector startup and reverse-order rollback stops managed Connector
then Hermes before restoring legacy state.

On 2026-09-08 the E4-D baseline reached 145 passing Desktop tests after adding two-stage preparation/
commit, manifest-derived LaunchAgent paths, cleanup retry retention, unknown-port blocking,
deterministic managed paths, a non-mutating journal read, managed failed-release replacement,
active-install/account-binding recognition, and interrupted-state classification. The
SwiftUI target compiled with the default-off action and exact-version confirmation sheet. Canonical
asset comparison and the final local release-mode app build both passed at the end of this slice.
The packaged plist kept managed bootstrap disabled and all release-manifest/signing fields empty;
strict codesign verification passed for the ad-hoc app. The output remains development material,
not a Developer ID signed, notarized, or distributable release.

`npm run test -w @hermes-remote/connector` passes the Connector suite, including strict account
credential loading, canonical challenge signatures, time/binding/generation checks, and exact v2 URL
derivation. The final local `npm run build` and `npm test` baseline also passed: Protocol 13/13,
Connector 17/17, Gateway 114 passed with 24 environment-gated PostgreSQL/network cases skipped,
release server 30/30, and scripts 100 passed with one disposable-PostgreSQL case skipped. Those skips
remain real-environment gates and are not counted as E4-D physical acceptance.

## Safety boundary and remaining gates

All machine-changing tests use temporary roots and injected launchctl state. No real Connector or
Hermes process was started, stopped, installed, upgraded, or reconfigured. No real LaunchAgent or
credential was written, and no Android or production configuration was changed.

E4 is not release-complete. Still required: provision the real release-signing key and pinned public
key, publish signed/versioned Hermes and Connector archives, Developer ID sign/notarize/staple the
release, prove the packaged Hermes Server satisfies the frozen runtime/provider contract, authorize
staging flags, and collect install/upgrade/interruption/rollback evidence on clean supported Macs.
Physical phone continuity and production rollout remain separate gates.
