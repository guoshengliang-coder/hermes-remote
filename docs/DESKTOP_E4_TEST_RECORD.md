# Desktop E4 test record

Date: 2026-09-09
Status: E4-D local multi-device UX, signed bootstrap, packaged orchestration, account Connector,
binding, rollback, and restart recovery are complete. The internal 0.3.0 arm64 candidate reached
account mode on one physical Mac, but phone continuity failed and the Mac was rolled back to legacy;
physical acceptance, Developer ID/notarization, and Android account migration remain pending.

## E4-E offline release publisher

The repository now includes a default-inert offline publisher and independent public-key verifier for
the exact E4 signed manifest. Automated coverage proves successful Ed25519 packaging and byte-for-byte
verification of both archives, canonical public-key export, strict fields/origin/lifetime, safe tar
members and declared entrypoints, SHA-256/size validation, no-overwrite behavior, cleanup after partial
failure, private-key ownership/permission enforcement, symlink refusal, artifact-tamper rejection, and
`HR-RELEASE-004` diagnostic redaction. This closes the tooling gap only; real Hermes/Connector archives,
key custody approval, HTTPS publication, notarized Desktop packaging, and physical migration remain
separate gates.

The following component-archive slice adds clean full-commit/version checks, fixed Hermes source and
metadata allowlists, explicit exclusion of environment/private-key filenames, bundled
architecture-matched Python and Node runtimes, production-only Connector JavaScript, bounded tree
walks, relative launchers, public build identities, and partial-output cleanup. The extraction gate's
former 4,096-entry limit could not contain a real Python runtime; it is now still bounded at 65,536
entries and 16 MiB of listing output. Regression tests accept a 4,100-file dependency tree and reject
65,537 entries before extraction without weakening traversal, link, or special-file checks.

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

The following E4 acceptance-preflight fix removes the remaining UI dead end for an existing Mac.
A recognized running legacy Connector may now enter the already implemented two-stage migration only
when its configured Hermes status URL is healthy and the complete signed-release/Gateway runtime gate
is ready. The final preflight reuses that configured URL instead of incorrectly assuming the future
managed loopback endpoint is already active. Stopped, unhealthy, unsigned, disabled, or mismatched
cases remain read-only; no production flag or target service is changed by this source fix.
The focused 12-test preflight suite and the complete 150-test Desktop suite passed on 2026-09-09,
followed by canonical asset comparison, release-mode app assembly, and strict ad-hoc codesign
verification. This is still source/local evidence rather than target-Mac migration acceptance.

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

## 2026-09-09 internal arm64 artifact gate

The approved internal Ed25519 signing key was generated outside the repository with owner-only permissions. From
clean Hermes GO commit `82c711182f37ba139a359113374bb815746fd575` and clean upstream Hermes commit
`f159e581c7afd22a5c94652c569e3859f1b994d2`, the release tools produced one internal 0.3.0 arm64 set under
`~/.hermes-go-releases/internal/0.3.0`: Hermes Server 0.21.0 (289,150,358 bytes, SHA-256
`bad166c542c4182ba431ced5edc67280eed8be112c00d1bb16ade69e6a091933`) and Connector 0.1.2 (37,061,648
bytes, SHA-256 `01476c7ebf5b3b3bde2d1dd590f824576e5b52c9232ec5e85bb2331765e3cca6`). The independent public-key verifier
accepted the manifest, both archives, origin, channel, architecture, sizes, hashes, entrypoints, and tar safety.

An extraction smoke proved bundled arm64 Python 3.11.15 and Node 22.23.2. The signed Hermes entrypoint emitted the
exact random-port readiness marker and `/api/status` reported 0.21.0 from an isolated empty `HERMES_HOME`; the signed
Connector reached its expected missing-credential boundary without a missing-runtime/module failure. Raw staging,
smoke directories, and the release worktree were deleted after verification, leaving only the final signed set and
protected signing key. HTTPS publication, pinned configured Desktop build, Developer ID/notarization, and physical
target-Mac migration are still pending; no production flag or running target service changed during this gate.

## 2026-09-09 physical migration and restart recovery

The internal 0.3.0 manifest and both signed arm64 components were downloaded and verified by the
production-configured ad-hoc Desktop app on `LGS-MACMINI`. Three earlier confirmed attempts exercised the
pre-commit rollback path: generations 4 through 6 stopped the managed candidate, removed the active-release
pointer, and restored the exact legacy Connector after the local Hermes readiness gate timed out. The machine
remained reachable and the existing connection was preserved after every failure.

A reversible isolated launch measured the real cold-start boundary before changing the timeout: the managed
Hermes process was running immediately, appended the exact new readiness marker after about 21 seconds, and first
returned HTTP 200 from `http://127.0.0.1:9119/api/status` after about 35 seconds. The former 30 one-second polls
therefore rejected a healthy cold start. The candidate now retains the exact marker-plus-loopback-HTTP proof but
allows 75 one-second polls. The focused nine-test coordinator suite, complete 162-test Desktop suite, canonical
asset comparison, release app assembly, and `git diff --check` all passed before installation.

Generation 7 completed at `2026-09-09T15:10:55.520Z` with journal state `account_active`, release `0.3.0`, and the
current symlink set to `releases/0.3.0`. Exact LaunchAgents `com.hermesgo.hermes-server` and
`com.hermesgo.connector` were both loaded and running; `com.hermesremote.connector` was absent; loopback Hermes
returned HTTP 200; and the four managed stdout/stderr files contained no error, fatal, panic, unauthorized, or
forbidden entries. The account view reported `连接正常`, Connector online, Hermes reachable, managed release
0.3.0 connected, and the account bound to this Desktop.

Quitting and reopening only the Desktop app preserved both managed service PIDs, journal state, generation, active
release pointer, and loopback HTTP health. The reopened account view again recognized the managed connection as
active, closing the E4-D restart-recognition implementation gate. The tested Desktop executable SHA-256 was
`048b77b7ea25dfc6832af60349786975158145ee2a78fe50fe349d89feaf88a0`; it is ad-hoc signed test material, not a
Developer ID signed or notarized release.

The physical acceptance nevertheless failed immediately afterward: Android 0.1.112 was not authorized on the
account and still used the legacy App Token route, so stopping `com.hermesremote.connector` made the phone report
`HR-CONN-005`. The operator stopped both managed services, restored the exact legacy LaunchAgent, verified public
`/relay-health` returned one online `mac-mini`, returned the journal to `legacy_active`, and removed the exact
`releases/0.3.0` activation symlink. The signed release directory remains inert for a future retry. Account-mode
migration must remain disabled for this Mac until an Android account-login build is installed, authorized, and
proven against the account Connector; service-only/Desktop-only success is insufficient.

The public `/relay-health` response intentionally reflects only the legacy Connector registry, so it reports zero
during an account-only migration; account Connector state is currently observable through the authenticated
account surface and structured Gateway logs instead. This remains an operator-observability limitation.
