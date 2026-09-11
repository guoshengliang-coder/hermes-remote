# Desktop E4 test record

Date: 2026-09-11
Status: E4-D local multi-device UX, signed bootstrap, packaged orchestration, account Connector,
binding, rollback, and restart recovery are complete. Android account continuity and managed account
mode were subsequently proven on the physical Mac. The corrective internal 0.3.1 arm64 release is
published and Desktop 0.2.4 is installed on the migrated Mac. Its packaged reboot preserved the
single-Connector invariant, and post-reboot Android account REST/WebSocket traffic passed. Developer
ID signing/notarization and clean-Mac acceptance remain pending.

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

## 2026-09-10 corrective internal release and installation

PR #148 merged as `95acaa3e2b8fecbe9f55ba91fe7378d77b7f2340`, and every PR check plus the
post-merge CI, SAST, and Gateway OCI workflows completed successfully. From an isolated clean worktree
whose `HEAD` exactly matched `origin/main`, the internal publisher produced release 0.3.1 for arm64 with
the existing approved key ID `desktop-internal-2026-a`. The manifest SHA-256 is
`d952c8bfe71357b3ca16a541e583c46adc4d09d0ee0f79cc463851e49665677f`.

The two signed components are Hermes Server 0.21.0 (289,157,113 bytes, SHA-256
`b3816f71d008f077e2c83ba640288e2678216d8ccbf2742a3c6cd3e11dfe2e99`) and Connector 0.1.3
(37,062,094 bytes, SHA-256 `92df148998d1dbe235b9992ebc4ed12c4849abddd8cdd858902616a06382a33b`).
All three immutable URLs under `https://mrlgs.net/desktop/releases/0.3.1/` returned HTTP 200 with the
declared sizes and content types. Full public HTTPS downloads reproduced all three hashes and passed
the independent public-key verifier. The previous 0.3.0 files and routes remain available for rollback.

The configured internal Desktop 0.2.1 DMG (bundle build 4, SHA-256
`c191c10200dd1ebf98c80fcb652e83a47e02d0df7cbef3c768163d9b6c59a4d9`) passed `hdiutil verify` and
strict ad-hoc codesign verification. It was installed over 0.2.0 on `LGS-MACMINI`, then launched from
`/Applications/Hermes Go Desktop.app`. Its embedded internal manifest URL points to 0.3.1. The GUI-only
replacement preserved the running managed Hermes and Connector PIDs; authenticated loopback
`/api/status` returned Hermes 0.21.0 and the Connector retained an established TLS connection to the
Gateway. The currently active managed release remains 0.3.0 with the previously applied compatibility
repair; 0.3.1 is the signed source for future clean installation or an explicit managed upgrade, not
an assertion that the already running services were silently upgraded.

This remains an internal ad-hoc test release. No Developer ID Application identity was available, so
the app was not notarized or stapled and must not be presented as a public macOS release.

## 2026-09-10 Desktop 0.2.2 effective-Agent correction

PR #150 merged the effective-Agent status reducer as `be97a5a24172f314ac821ddf77545119f3cfc969`,
and PR #151 merged the 0.2.2/build 5 version gate as
`f4c3ec612b4afb348ff6663a85cb056409fe2fd3`. Every applicable PR check and both post-merge CI/SAST
workflows completed successfully. The final configured internal DMG was built from a clean detached
worktree whose `HEAD` exactly matched `origin/main`; the complete 171-test Desktop suite, canonical
asset comparison, release build, strict ad-hoc codesign verification, and `hdiutil verify` passed.
The 2,041,257-byte DMG SHA-256 is
`f3568a3aa1a481640386a5737b7c66129af0b74e314c9f68f98d51658410e1d2`.

The DMG was copied to the target Mac mini, and its remote size and SHA-256 matched before installation.
Desktop 0.2.2 replaced 0.2.1 at `/Applications/Hermes Go Desktop.app`; only the GUI process was
restarted. The managed Hermes and Connector processes retained their pre-install PIDs, the legacy
Connector label remained unloaded, and the migration journal remained `account_active`. Physical UI
inspection confirmed `工作正常`, `托管后台连接正在运行`, `托管 Connector 正在运行`, and managed mode
`0.3.0`, closing the false legacy-Agent failure that motivated the correction. The temporary 0.2.1
rollback app, transferred DMG, and clean release worktree were removed after verification.

This is still an internal ad-hoc build. It is not Developer ID signed, notarized, stapled, or approved
for public distribution. Migration Assistant transfer to a different physical Mac remains a separate
acceptance gate; this run verified same-Mac overwrite behavior and the deterministic transferred-state
reducer only.

## 2026-09-10 Desktop 0.2.3 account-mode legacy-probe retirement

PR #154 merged the account-aware health presentation and 0.2.3/build 6 version gate as
`8972317b375d3dfde4d09fd2e49069576fa4da7d`. Every PR check and the resulting main CI and SAST
workflows passed. From an isolated clean worktree at that exact `origin/main` commit, the complete
172-test Desktop suite, canonical asset comparison, release build, strict ad-hoc codesign
verification, and `hdiutil verify` passed. The 2,041,359-byte DMG SHA-256 is
`fc17da801db26141a2e3a9b8c7178a9ea82454389bb84e79a7e4146290978ce5`.

Desktop 0.2.3 replaced 0.2.2 at `/Applications/Hermes Go Desktop.app`. A one-time macOS Keychain
access approval was accepted for the newly signed app. Live Accessibility inspection then confirmed
that account loading had completed, the account did not require login, and neither the legacy
App-Token row nor its end-to-end check was visible. The authenticated loopback Hermes probe returned
HTTP 200 and Connector retained an established upstream connection. A loopback session token exposed
during local diagnostics was rotated immediately; the old value was invalidated and no credential
value was retained in this record.

This remains an internal ad-hoc build. It is not Developer ID signed, notarized, stapled, or approved
for public distribution.

## 2026-09-10 Migration Assistant physical transfer finding

Migration Assistant moved the active managed installation to a new Mac mini with the journal still
`account_active`, release 0.3.0, and binding generation 7. It also caused launchd to load both
`com.hermesremote.connector` and `com.hermesgo.connector`, demonstrating that a successful `bootout`
during the original migration did not persist the single-Connector invariant across machine transfer.
The transferred installation predated the private session-token contract and required a local token
rotation before managed Hermes and Connector could share the current credential format.

The target was repaired without changing the Cloud binding or Hermes data: the legacy label was
stopped and persistently disabled, managed Hermes authenticated its protected loopback endpoint, the
managed Connector established TLS to the Gateway, and Desktop 0.2.3 launched. Temporary repair
snapshots and the retired credential were removed after the live checks passed; no secret value was
recorded.

The corresponding regression changes make legacy disable/enable part of the normal takeover/rollback
pair and let Desktop suppress a transferred duplicate at startup only when the durable journal is
exactly `account_active` and both managed labels are already loaded. Intermediate and incomplete
states remain inert or fail closed. A packaged run of the corrected Desktop followed by a full Mac
reboot passed on 2026-09-11 as recorded below. Android REST/WebSocket verification after that reboot
also passed later that morning.

## 2026-09-11 Desktop 0.2.4 migrated-Mac reboot acceptance

PR #162 merged the deterministic Migration Assistant correction as
`d7e43e6dd168cec5819c38b9bdec053c9eabb0be`. PR #163 then merged the 0.2.4/build 7 version gate as
`48a6c2ed610efd83fdb8d55610245f4ac0b26345`; all applicable PR checks and the resulting main CI and
SAST workflows completed successfully. From an isolated clean worktree whose HEAD exactly matched
that `origin/main`, the canonical asset check, complete 178-test Desktop suite, configured release
build, strict ad-hoc codesign verification, and `hdiutil verify` passed. The 2,045,620-byte DMG
SHA-256 is `8ba346e4409fa9a72b0999878cc230e23af08268a02b623418311443895a057a`.

The DMG was independently size/hash/image-verified on the migrated `LGS-MACMINI` before replacing
Desktop 0.2.3. The newly installed `/Applications/Hermes Go Desktop.app` reported 0.2.4/build 7 and
passed strict codesign verification. Launching it preserved the running managed Hermes and Connector
PIDs 27473 and 27608, kept `com.hermesremote.connector` disabled and unloaded, retained the
`account_active` generation-7 journal, returned authenticated local Hermes HTTP 200/version 0.21.0,
and retained an established Connector TLS connection. The 0.2.3 app remains as a local rollback copy
until the final phone gate closes.

After a full Mac reboot at 2026-09-11 09:51:02 +08:00, launchd automatically restored only managed
Hermes PID 1674 and managed Connector PID 1675. The legacy label remained persistently disabled and
unloaded; the journal stayed `account_active`, generation 7, managed release 0.3.0. Starting Desktop
0.2.4 exercised its startup reconciliation without changing either managed PID or reviving the
legacy Connector. The protected loopback check again returned HTTP 200/version 0.21.0 and the
Connector again held an established TLS connection.

The attached HONOR CLK-AN00 contained Android 0.1.89/build 90, which predates account mode and was not
used for acceptance. The operator instead used an account-capable Android client signed into the same
account, refreshed status successfully, and opened a real session. Relative to the pre-action
Connector log baseline, this created one new `/api/ws` tunnel at 2026-09-11T02:12:55.296Z. Before the
phone-side connection ended 45 seconds later, the tunnel carried 1,572 Hermes-to-phone frames and 28
phone-to-Hermes frames with no `tunnel.local_error` or `tunnel.open_failed` event. The phone-side
abnormal close code 1006 was mapped to the Connector's safe local close code 1011 after the verified
exchange; it did not restart either managed service or revive the legacy Connector.

This closes the Migration Assistant packaged-reboot and Android account REST/WebSocket gate. No
screenshot or user content was exported. This remains an internal ad-hoc Desktop build and is not
Developer ID signed, notarized, stapled, or approved for public distribution.

## 2026-09-11 pre-contract session-token migration regression

The next Desktop maintenance source adds an always-available startup repair for the migrated Mac's
remaining pre-contract credential layout. The repair requires the exact committed journal binding and
both running managed labels, preserves the shared local token rather than rotating it, atomically
removes the inline value from both owner-only LaunchAgent plists, and writes only its canonical private
file path. Hermes restarts and proves a fresh readiness marker plus authenticated loopback health before
Connector starts; the same binding ID/generation must then regain Connector, Hermes, and end-to-end
health before an owner-only completion marker is written.

Temporary-root regression tests cover the complete inline pair, a matching half migration after a
simulated power loss, an already completed idempotent state, mismatched inline values, mismatched account
generation, exact stop/start order, and a failed candidate readiness proof followed by byte-for-byte
plist/token rollback and restored service health. The focused 28-test installer/coordinator set passed
locally. The canonical asset comparison, complete 185-test Desktop suite, release-mode app assembly,
strict ad-hoc codesign verification, and `git diff --check` also passed. Packaged 0.2.5 evidence,
target-Mac migration, reboot persistence, and post-migration Android traffic remain pending at this
point; no target service or production setting was changed by this source iteration.

## 2026-09-11 Desktop 0.2.5 physical compatibility failure and rollback

PR #176 merged the pre-contract token migration and PR #178 released Desktop 0.2.5/build 8 after the
185-test Desktop suite, asset gate, release build, codesign verification, DMG verification, PR checks,
and post-merge CI/SAST passed. The configured arm64 DMG was independently hash- and image-verified on
`LGS-MACMINI`, then installed with the prior 0.2.4/build 7 app retained for rollback.

After Keychain access allowed startup recovery to run, Desktop moved the active managed 0.3.0
installation to `HERMES_SESSION_TOKEN_FILE`, restarted Hermes and Connector, observed new PIDs,
received authenticated loopback HTTP 200 from Hermes 0.21.0, retained a Connector control connection
to the Gateway, and wrote the completion marker. Those signals were insufficient: managed release
0.3.0 contains Connector 0.1.2, which does not consume the file environment variable. Each Android
WebSocket tunnel therefore reached the Connector but failed its local Hermes upgrade with HTTP 403,
surfacing `HR-CONN-002`. The Cloud binding health accepted by startup was not required to be newer than
the Connector restart, so its previous healthy value did not catch the broken per-tunnel path.

The operator stopped Desktop 0.2.5, preserved its app and migrated files in an owner-only recovery
directory, restored the two inline-token LaunchAgents, restarted Hermes before Connector, and restored
Desktop 0.2.4/build 7. Authenticated loopback HTTP returned 200, a direct `/api/ws?token=` upgrade
returned 101, Connector re-established its upstream connection, and the Android client worked again.
Desktop 0.2.5 is withdrawn and must not be reinstalled. The corrective source refuses token-file
migration for managed releases older than 0.3.1 before account refresh, file mutation, or service
restart, and adds 0.3.0 as an explicit regression fixture. A replacement Desktop release remains
pending its own version gate and physical Android REST/WebSocket acceptance.
