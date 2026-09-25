# Desktop E4 test record

Date: 2026-09-11
Status: E4-D local multi-device UX, signed bootstrap, packaged orchestration, account Connector,
binding, rollback, and restart recovery are complete. Android account continuity and managed account
mode were subsequently proven on the physical Mac. The corrective internal 0.3.1 arm64 release is
published and Desktop 0.2.4 is installed on the migrated Mac. Its packaged reboot preserved the
single-Connector invariant, and post-reboot Android account REST/WebSocket traffic passed. Developer
ID signing/notarization and clean-Mac acceptance remain pending.

## Managed in-app upgrade transaction — 2026-09-19

Desktop now offers the signed two-stage action to an `account_active` installation when the target
is a strict version increase. Automated coverage proves schema-v1 and schema-v2 executor routing,
same-version/downgrade rejection, no binding create/confirm call, Connector-before-Hermes shutdown,
old-listener convergence before replacement startup, Hermes-before-Connector startup, exact binding
and fresh Cloud health, byte-identical LaunchAgent/pointer rollback, bundled-to-component transition,
and durable restart recovery. `npm run desktop:assets:test`, all 334 `npm run desktop:test` cases, and
`npm run desktop:app` passed locally. No production deployment, version bump, notarization, or physical
Mac upgrade was performed by this change.

## Desktop 0.2.17 internal publication — 2026-09-19

PR #323 allocated Desktop 0.2.17/build 20 and merged as
`f32c81b444420698199c075046279087b1735bfb`. Its PR and post-merge CI/SAST checks completed
successfully. A fresh detached worktree at that exact `origin/main` commit passed the canonical asset
comparison, all 334 Desktop tests, the release-mode app build, strict ad-hoc codesign verification,
and `hdiutil verify`.

The configured DMG was built in one invocation so the packaged app retained managed bootstrap,
schema-v1 release 0.3.5, disabled component preflight, the approved
`desktop-internal-2026-a` trust identity, `internal`/`arm64`, `hermes-serve-v1`, the production
Gateway origin, and `LSUIElement=false`. Reading `Info.plist` back from the final mounted DMG
confirmed 0.2.17/build 20 and every value above. The exact 2,595,633-byte artifact has SHA-256
`dda8e691bf4ae879776f04a05e829b1fd65d1793130a7f82ef1c44522c957d49` and is published at
`https://mrlgs.net/desktop/apps/0.2.17/Hermes-Go-Desktop-0.2.17-dev.dmg`.

Publication used an owner-only staging directory on the HK host and required the uploaded artifact
to reproduce the local size and hash. The final file was installed root-owned and mode 0644 under a
new mode-0755 version directory. The exact Nginx route was appended only after the route file matched
its audited prior hash; `nginx -t`, reload, active-service and Relay health checks then passed. A full
public re-download reproduced the exact size and hash, passed `hdiutil verify`, exposed the expected
packaged configuration, and passed strict codesign verification again. The response is HTTP 200 with
one-year immutable caching and `nosniff`; the version directory returns 404, POST to the exact route
returns 403, and the 0.2.16 URL still returns 200. Private staging and the one-time route backup were
removed after verification.

No Mac was changed by this publication. The current MacBook already runs managed release 0.3.5, so
installing 0.2.17 there can verify ordinary Desktop replacement and service continuity but cannot
exercise the strictly-newer managed upgrade action. Full product-path acceptance still needs a Mac on
an older managed release, or the next signed managed release above 0.3.5. This internal build remains
ad-hoc signed; it is not Developer ID signed, notarized, or stapled.

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

## 2026-09-11 Desktop 0.2.6 corrective release and pre-reboot acceptance

PR #180 merged the runtime-release gate as `21d69386feea515c84bfd9b4bd2df771baa396ce` after all
applicable PR checks passed. PR #181 then released Desktop 0.2.6/build 9 as
`d76b5af1f13687615c8b353b8ae33fdaa587959e`; its PR checks and resulting `main` CI/SAST workflows
also completed successfully. From a fresh detached worktree at that exact `origin/main`, the
canonical asset check, complete 186-test Desktop suite, configured release build, strict ad-hoc
codesign verification, and `hdiutil verify` passed. The 2,072,466-byte DMG SHA-256 is
`938a3d15f79db2074dee9c614c32f50b5ea2d4043455b6bff9d46db75a6cc879`.

The verified DMG replaced Desktop 0.2.4 on `LGS-MACMINI`; the prior app remains in an owner-only
Recovery directory. The installed app reported 0.2.6/build 9 and passed strict codesign verification.
Before and after launch, the active managed release remained 0.3.0, both LaunchAgent files were
byte-for-byte unchanged, both retained their historical inline token field, and neither acquired a
token-file field. The private token file and completion marker remained absent. Managed Hermes PID
84188 and Connector PID 84446 were unchanged, proving that the new release skipped the incompatible
startup migration without restarting either service.

An authenticated loopback `/api/status` request returned HTTP 200, a direct authenticated `/api/ws`
upgrade returned 101, and Connector retained one established upstream TCP connection. After a fresh
Connector log checkpoint, the operator refreshed Android status and exercised a real session. The
new log segment contained 16 opened and closed `/api/ws` tunnels, 202 frames to the phone and 98 frames
from it, with no `tunnel.local_error` or `tunnel.open_failed`. A final comparison again found the same
LaunchAgent bytes, inline-token layout, absent token files, and unchanged managed PIDs.

This closes the 0.2.6 installation and pre-reboot Android REST/WebSocket gate. The owner deliberately
deferred the Mac reboot while the host is doing other work. Full acceptance still requires a fresh
baseline, launchd recovery after that reboot, confirmation that the 0.3.0 inline-token layout remains
unchanged, and another Android REST/WebSocket exchange with clean tunnel telemetry. This remains an
internal ad-hoc build and is not approved for public distribution.

## 2026-09-12 managed release 0.3.2 and Desktop 0.2.7 offline candidates

PR #196 merged the HG28 packaging correction in `9c6f3b271f8b999b3192c93f69ac7b113039711d`.
From a clean detached checkout of `origin/main` at
`4224188449d63dc42fdaaaaf31284e52cdbbdfde`, the component packager rebuilt Hermes Server 0.21.0
from upstream `f159e581c7afd22a5c94652c569e3859f1b994d2` and Connector 0.1.3. The signed internal 0.3.2
candidate passed the independent public-key verifier. Its immutable offline artifacts are:

- manifest: 1,306 bytes, SHA-256
  `aec0180acac9f7d38744d5efcb901f6e850a201150ecabfe59aa5ce93cbba295`;
- Hermes Server: 285,058,184 bytes, SHA-256
  `86c411c17ac9e3fa3f4bdc26c51578a2fb92de959b0881fef258df67bbd88b58`;
- Connector: 37,056,988 bytes, SHA-256
  `f2f0faabb50ad21f3cfefb46d1b47fbd3c9dcce8786bb2c68906204fea874758`.

The final Hermes archive was extracted to a new directory and started with an empty environment.
Its bundled Python imported `tui_gateway.slash_worker` with `PYTHONPATH` absent, proving the exact
child-process boundary that failed in managed releases 0.3.0 and 0.3.1. The focused packaging and
relocation suite passed 7 tests, including the pre-fix failure proof and partial-extraction behavior.

Desktop 0.2.7/build 10 is the coordinated app candidate because configured Desktop builds pin an
immutable manifest URL. The canonical asset check and all 186 Desktop tests passed. Its configured
app embeds the 0.3.2 manifest URL, `internal` channel, `arm64` architecture, approved key ID, and
`hermes-serve-v1` contract; strict ad-hoc codesign verification passed. The 2,072,617-byte DMG passed
`hdiutil verify` and has SHA-256
`4c4f39462d3183e2137e363f0652a677138b01e40c7bc847be46f5988b4925a4`.

Nothing in this candidate step was uploaded, installed, launched on the target Mac, or deployed.
Coordinated physical acceptance still needs the managed upgrade plus `/model`, `/compact`, a normal
prompt, Android REST/WebSocket traffic, rollback, and restart checks. Developer ID signing,
notarization, stapling, and clean-Mac launch acceptance also remain required for public distribution.

## 2026-09-12 managed release 0.3.2 publication

After PR #197 merged Desktop 0.2.7/build 10 as
`21f810f03c06f0fd80db2f08fcf5b3decdc15877` and both post-merge CI and SAST passed, the owner
authorized publication of managed release 0.3.2. The three previously verified candidate files were
uploaded to an owner-only staging directory on the HK host, re-hashed there, installed as root-owned
mode-0644 files under a new mode-0755 `/srv/hermes-desktop-releases/0.3.2` directory, and exposed only
through three exact GET/HEAD Nginx locations. The route file was replaced only after its previous hash
matched the audited value; `nginx -t` passed, reload completed, and Nginx remained active.

All three immutable HTTPS URLs returned HTTP 200 with the declared content lengths, JSON/gzip content
types, one-year immutable cache policy, and `nosniff`. A full public re-download reproduced the exact
candidate sizes and SHA-256 hashes recorded above, and the independent Ed25519 verifier accepted the
downloaded manifest plus both downloaded archives. The 0.3.0 and 0.3.1 manifests remained available,
and the 0.3.2 directory itself returned 404 instead of exposing a listing. The Relay health endpoint
continued to respond successfully after the Nginx reload. Temporary upload and route-backup files were
removed after verification.

This publication did not install Desktop 0.2.7, switch the target Mac from managed release 0.3.0,
restart either managed service, or perform the deferred physical acceptance. Those steps remain gated
on the Mac becoming available.

## 2026-09-12 managed release 0.3.2 historical-token failure

Desktop 0.2.7/build 10 was installed on `LGS-MACMINI` after its DMG size, SHA-256, image, embedded
0.3.2 configuration, and strict ad-hoc signature were rechecked. The previous 0.2.6 app was retained
in an owner-only recovery directory. Starting 0.2.7 preserved the running 0.3.0 Hermes and Connector
PIDs, and the account view again reported the existing managed connection as active after the two
distinct Keychain items were authorized.

The independently verified 0.3.2 components were staged and activated with an owner-only snapshot of
the 0.3.0 journal, LaunchAgents, and current target. The new Hermes and Connector started successfully
with the preserved inline token; authenticated loopback `/api/status` returned HTTP 200/version
0.21.0/status `ok`, and Connector re-established its TLS connection. On the next Desktop launch, the
intended token-file migration wrote the existing valid 64-character lowercase-hex token to a private
regular file and updated both LaunchAgents. The packaged Hermes reader accepted only the 43-character
base64url format, exited 78 with its fixed invalid-token diagnostic, and never reached readiness.
Desktop then restored both exact inline LaunchAgents and restarted healthy 0.3.2 services. No mixed
or dead service state was accepted.

The component packager now generates a reader matching Desktop and Connector: 43-character base64url
and historical 64-character lowercase hex are both accepted, while wrong formats and non-private
files still exit 78. A corrected signed managed release, repeated token-file migration, slash-command
checks, Android traffic, and restart recovery remain required before physical acceptance.

## 2026-09-12 managed release 0.3.3 corrective offline candidate

PR #208 merged the historical-token correction in
`9f66ac43f8caeacd99b48020d47c43ce6f9a3ba9`; both post-merge CI and SAST passed. From a clean detached
worktree at that exact `origin/main`, the component packager rebuilt Hermes Server 0.21.0 from upstream
`f159e581c7afd22a5c94652c569e3859f1b994d2` and Connector 0.1.3. The signed internal 0.3.3 candidate
passed the independent public-key verifier. Its immutable offline artifacts are:

- manifest: SHA-256 `bdab78aa751808a100ccc7096abd8c1639e65c959cf04cc2988948fe629d3042`;
- Hermes Server: 285,055,469 bytes, SHA-256
  `caa4650dacae1c8d6c4d194cc9c114ca08cdb0aac903d7fd54ebde4157c2a63c`;
- Connector: 37,056,989 bytes, SHA-256
  `e4434a00c0ed19c4a07cffd7bc0e88fe01943183a627dc40f40ac803f89ce3c2`.

The final Hermes archive was extracted to a new directory. Its actual bundled Python reader returned
the exact 64-character lowercase-hex fixture from a mode-0600 token file, while an uppercase
64-character value produced no stdout, the fixed safe diagnostic, and exit status 78. Desktop
0.2.8/build 11 is the coordinated app candidate because the configured app must pin the immutable
0.3.3 manifest URL. The canonical asset check and all 186 Desktop tests passed. The configured app
inside the final DMG embeds version/build 0.2.8/11, enabled bootstrap, the 0.3.3 manifest URL,
`internal` channel, `arm64` architecture, approved key ID and public key, and `hermes-serve-v1`.
Strict ad-hoc codesign verification passed after mounting the final image. The final clean-main
2,072,628-byte DMG passed `hdiutil verify` and has SHA-256
`85446f008ab47ca5c74eb8061d94a8b35fcd3384f57383a844cc2a4d34ba4d65`.

The three 0.3.3 files were published beneath the immutable public `/desktop/releases/0.3.3/` route.
All public objects returned HTTP 200, exact lengths, expected content types, immutable cache headers,
and `nosniff`; a full public re-download reproduced the hashes above and passed the independent
Ed25519 verifier. The route directory returned 404, 0.3.2 remained available, and `/relay-health`
remained healthy after the Nginx reload.

Desktop 0.2.8/build 11 and the publicly downloaded 0.3.3 components were then installed on
`LGS-MACMINI` with owner-only recovery snapshots. Both services first started successfully with the
existing inline token. Desktop wrote that valid 64-character lowercase-hex token to the private file
and restarted in file mode. The corrected packaged Hermes reader stayed healthy, but the packaged
Connector's TypeScript reader still accepted only the 43-character base64url form, exited with
`Hermes session token file is malformed`, and never established its control connection. Desktop did
not commit the migration: it restored both exact inline LaunchAgents, removed the token file, and
restarted healthy 0.3.3 Hermes and Connector services. The overview returned to `工作正常`; neither a
mixed state nor a false success was retained.

The Connector reader now has its own regression coverage for both canonical token formats and for
rejecting an uppercase 64-character value. A new immutable managed component release and coordinated
Desktop build are required before repeating physical token migration. Slash-command, Android traffic,
and restart gates remain open; this ad-hoc app has not passed Developer ID signing, notarization,
stapling, or clean-Mac launch.

## 2026-09-12 managed release 0.3.4 and Desktop 0.2.9 corrective candidates

PR #215 merged the Connector historical-token correction as
`c4142804a95898a6de3f44f04455175ab8d66bc2`; every applicable PR check and the post-merge CI, SAST,
and Gateway OCI workflows passed. From a clean detached worktree whose `HEAD` exactly matched that
`origin/main`, component packaging rebuilt Hermes Server 0.21.0 and Connector 0.1.3 and executed the
staged Connector reader against both accepted token formats plus an uppercase-hex rejection before
creating the archives. The signed internal 0.3.4 candidate then passed the independent public-key
verifier. Its immutable offline artifacts are:

- manifest: SHA-256 `19c6365932032b8efde20c94c4bcef04b224199d88ca3cca48e8a6b96ebf9348`;
- Hermes Server: 285,054,389 bytes, SHA-256
  `8ae357d78a7836a0680125f7e241243af1555418d9d2e12e68433b5ff1f0ca44`;
- Connector: 37,057,047 bytes, SHA-256
  `29151d0359f2548baa58e15227320c49cf6ab8ae8dbcc0a823469987c64c4ca7`.

The final Connector archive was separately extracted. Its actual JavaScript reader returned the exact
43-character base64url and 64-character lowercase-hex fixtures from private files, rejected the
uppercase 64-character fixture, and carried the exact 0.1.3/arm64/source-commit identity. Desktop
0.2.9/build 12 is the coordinated app candidate because the configured app must pin the immutable
0.3.4 manifest URL. The canonical asset check and all 186 Desktop tests passed. The configured app
inside the candidate DMG embeds version/build 0.2.9/12, enabled bootstrap, the 0.3.4 manifest URL,
`internal` channel, `arm64` architecture, approved key ID and public key, and `hermes-serve-v1`.
Strict ad-hoc codesign and `hdiutil verify` passed. The 2,072,632-byte candidate DMG has SHA-256
`c09fe717450bfe63b6d8407e8982cdd0130bf8f85e5b3c84318d9e927a123e2f`.

Neither 0.3.4 nor 0.2.9 has been published, installed, or activated. A final DMG must be rebuilt from
the clean merge commit before handoff. Production publication and the repeated physical token
migration remain separate gates; slash-command, Android traffic, service restart, and deferred full
Mac reboot checks remain open. This ad-hoc app has not passed Developer ID signing, notarization,
stapling, or clean-Mac launch.

## 2026-09-12 managed release 0.3.4 publication and Desktop 0.2.9 activation

PR #216 merged the coordinated Desktop 0.2.9/build 12 version gate as
`996df9cf184bff92888a963132bad64e32a8761b`; every PR check and the resulting main CI and SAST
workflows passed. A fresh detached worktree at that exact `origin/main` rebuilt the configured DMG.
The mounted app carried version/build 0.2.9/12 and the expected enabled 0.3.4 internal manifest,
architecture, key, origin, channel, and Hermes launch-contract settings. Strict ad-hoc codesign and
`hdiutil verify` passed. The final 2,072,633-byte DMG SHA-256 is
`0f788e517633bf43d69bca57b5dccb5d526257ced9759218aa89d173c363aa8d`.

The three signed 0.3.4 files were published beneath the immutable public
`/desktop/releases/0.3.4/` route. The active Nginx route passed syntax checking and reload. Every
public object returned HTTP 200 with its declared length, expected content type, immutable cache
header, and `nosniff`; the route directory returned 404 and the 0.3.3 manifest remained available.
Full public HTTPS downloads reproduced all three hashes recorded above and passed the independent
Ed25519 verifier. `/relay-health` remained healthy after the reload.
The temporary upload directory and pre-0.3.4 route backup were removed after these checks.

Those public artifacts and the final DMG were installed on `LGS-MACMINI` with owner-only recovery
snapshots. The managed release pointer moved atomically from 0.3.3 to 0.3.4. Desktop then completed
the pre-contract migration: the existing 64-character lowercase-hex session token is held only in
the owner-only regular token file, both owner-only LaunchAgents refer to that file and contain no
inline session-token variable, and the owner-only completion marker contains version `1`. The
migration journal remains `account_active`; both managed labels run, the legacy Connector label is
unloaded, authenticated loopback `/api/status` returns HTTP 200 from Hermes 0.21.0, and Connector
reports an active account-mode Gateway connection.

A live Desktop inspection after Keychain authorization showed 0.2.9-dev, `账号已登录 工作正常`, and
`托管版本 0.3.4 已接管后台连接`. An ordered service recovery check then restarted Hermes before
Connector. Both PIDs changed, authenticated loopback health returned HTTP 200 before and after the
restart, Connector re-established an upstream TLS socket, and the legacy label stayed unloaded. A
full Mac reboot remains deliberately deferred; it is not implied by this service-restart result.

The attached vivo V2166BA and HONOR CLK-AN00 both ran Android 0.1.121/build 122. Their first cold
start correctly failed with `HR-CONN-002` because both had been left on the documented development
loopback address after an earlier dev-stack run and neither retained an account session. This does
not exercise the production account route. Account login, real `/model`, `/compact`, normal-prompt
traffic, and the final Android REST/WebSocket evidence remain pending until the operator completes
email verification on the phones.

This is an internal ad-hoc Desktop build. It is not Developer ID signed, notarized, stapled, or
approved for public distribution.

## 2026-09-12 post-restart Cloud-health freshness correction

The 0.2.5 incident showed that an established Connector control socket and a previously healthy
Cloud binding could survive long enough to mask failure in a newly restarted Connector's local
tunnel path. The coordinator now snapshots the exact binding's server-provided
`endToEnd.checkedAt` immediately before every already-bound Connector start. Candidate acceptance
requires the same binding ID and generation to remain healthy with a strictly newer timestamp.
Rollback of a token-file migration follows the same rule before the restored configuration is
declared healthy.

The regression fixture holds all health booleans true while returning the old timestamp through the
candidate polling window. The candidate times out, both original inline LaunchAgent files are
restored byte-for-byte, Hermes and Connector restart in order, and rollback completes only when a
new Cloud timestamp appears. The focused 19-test migration-coordinator suite passed. This source
correction also passed the canonical asset check, all 187 Desktop tests, and the release app build.
It does not allocate a Desktop version, publish an artifact, install an app, or deploy a service;
those remain separate release gates.

## 2026-09-13 Desktop 0.2.10 release candidate

Desktop 0.2.10/build 13 allocates the internal app version for the post-restart Cloud-health
freshness correction merged in PR #230. Managed release 0.3.4 remains unchanged and already
published; this Desktop update changes no component archive, Gateway setting, account binding, or
managed service configuration.

The release branch passed the canonical asset comparison, all 187 Desktop tests, the focused
19-test migration-coordinator coverage included in that suite, and a release-mode app build with
strict ad-hoc codesign verification. The version-only change needs no additional regression test;
the source correction already covers the stale-timestamp failure and the rollback path.

Final configured-DMG packaging must run from the clean merged `origin/main` commit. Physical
acceptance must first prove that replacing and launching the app preserves the existing managed
Hermes and Connector processes. A separate Connector restart must then preserve the exact Cloud
binding ID and generation while producing a strictly newer server-provided `endToEnd.checkedAt`.
The target Mac mini was unreachable over its recorded Tailscale SSH address while this candidate was
prepared, so this entry does not claim installation or live acceptance. This remains an internal
ad-hoc release and is not Developer ID signed, notarized, stapled, or approved for public
distribution.

## 2026-09-13 Desktop 0.2.10 activation and Cloud-health freshness acceptance

PR #240 merged the 0.2.10/build 13 release gate as
`d868b7996e85b2fb8cf1630d3a0e9023d2d1fcd8`; every applicable PR check and the resulting `main` CI
and SAST workflows completed successfully. A fresh detached worktree whose `HEAD` exactly matched
that `origin/main` commit repeated the canonical asset comparison and all 187 Desktop tests, then
built the configured internal DMG. The mounted app carried version/build 0.2.10/13, enabled managed
bootstrap, the immutable 0.3.4 manifest, `internal`/`arm64`, the approved key ID and public key, and
the `hermes-serve-v1` runtime contract. Strict ad-hoc codesign and `hdiutil verify` passed. The final
2,078,505-byte DMG SHA-256 is
`eba645ef54f95b0b69981209ba543d6689604cea1c9ea0742a42d0039926aded`.

The execution host was the target `LGS-MACMINI` itself; only its obsolete self-referential Tailscale
SSH route was unreachable. Desktop 0.2.10 replaced 0.2.9 in `/Applications`, with the prior app kept
in an owner-only Recovery directory. Launching the new app preserved managed Hermes PID 22269 and
managed Connector PID 22750. The current component pointer remained 0.3.4, the journal remained
`account_active` at binding generation 7, and the legacy Connector remained unloaded.

A separate Connector-only restart then changed its PID from 22750 to 60731 while Hermes retained PID
22269. The Connector log recorded the old process shutdown and a new account-mode Gateway connection;
one established TLS socket was present afterward. Authenticated loopback `/api/status` returned HTTP
200 and Hermes version 0.21.0. A read-only server-side query against the exact journal binding showed
generation 7, active state, online Connector, reachable Hermes, and healthy end-to-end status both
before and after the restart. Its server-provided `endToEnd.checkedAt` advanced strictly from
2026-09-12 15:22:05.18103 +08:00 to 2026-09-13 13:34:30.67518 +08:00. No account access token was
exported to a diagnostic script.

This closes the ordinary-upgrade process-preservation and Connector-restart Cloud-freshness gates.
Physical Android account traffic and a full Mac reboot remain separate deferred checks. This is an
internal ad-hoc Desktop build; it is not Developer ID signed, notarized, stapled, or approved for
public distribution.

## 2026-09-14 Desktop 0.2.11 component-install candidate

Desktop 0.2.11/build 14 allocates the internal app version for the schema-v2 component preflight and
two-stage installation path merged through PRs #292–#294. The path remains inert unless the packaged
app contains a complete pinned v2 trust configuration and Gateway independently advertises component
manifest schema 2 beside `hermes-serve-v1`. Preparation rechecks both gates and the machine state,
writes only an owner-private UUID cache, and downloads only missing exact-content components. Commit
requires the executor-issued release confirmation and repeats both checks before the existing account
binding, Hermes-first startup, Cloud health, rollback, and cleanup transaction may run.

The version branch must pass the canonical asset comparison, all Desktop tests, and a release-mode
app build before review. Final configured-DMG packaging must run from the clean merged `origin/main`
commit and pin the separately signed internal schema-v2 component release 0.4.0. Publication,
production capability enablement, and clean/existing-Mac installation remain separate recorded gates.
No Developer ID identity is installed on the build Mac, so this candidate remains ad-hoc signed and
cannot claim notarization, stapling, clean-machine Gatekeeper acceptance, or public distribution.

## 2026-09-14 Desktop 0.2.11 and component 0.4.0 production availability

PR #295 merged Desktop 0.2.11/build 14 as `813b78300ef373d87b61e2890051f6524aeeb94e`. A fresh detached
worktree at that exact `origin/main` built the production-configured internal DMG with managed bootstrap and
preflight enabled, schema-v2 component manifest URL, the approved `desktop-internal-2026-a` trust identity,
`internal`/`arm64`, and `hermes-serve-v1`. Strict ad-hoc codesign and `hdiutil verify` passed. The final
2,662,459-byte DMG has SHA-256 `028049016ee3115fd50b97c97a8a60d4507ae6dbab5cdf1be7aaeac88e869a60`
and is published at
`https://mrlgs.net/desktop/apps/0.2.11/Hermes-Go-Desktop-0.2.11-dev.dmg` with immutable caching and `nosniff`.

The signed component release 0.4.0 is published beneath `/desktop/components/0.4.0/`. Its exact schema-v2
manifest is 3,816 bytes with SHA-256
`31e85f64d3347cb0302450f400b1357acd440c7dff041e8a896d67ee13dfa47f`; independent public-key verification
passed against all four public archives. The bootstrap download is 102,881,663 bytes (98.12 MiB): Python
48,062,625 bytes, Hermes core 17,762,179 bytes, Node 36,999,070 bytes, and Connector 57,789 bytes. Their
archive SHA-256 values are respectively
`7af7938616e059de81e165b13ff13e02b61dcb4468211037709dadedabc774e4`,
`09dc9bc70547c47053b8f1d7f8a2feaab5a33932cb0500a72d50fb0f1d8ba49c`,
`3d5fd7a8da312dfc5ce8718fc278e56403df4aa2c17411e95b923edb0d647021`, and
`65ccf7fc79b08c81d8f4d2867877cc24e0c62db07db2ddd3f605726ce92d21dc`.

Gateway 0.4.16 merge `0adccd7b834f1ab3366caf6091c2a3426b5b28f1` is active in production and the
authorized component rollout run `a310a75c-ada9-4e0a-b64a-010a3fdf0434` committed schema-v2 capability.
Public `/v2/capabilities` now advertises `hermes-serve-v1` and `componentManifestSchemaVersion: 2`; the active
green container is healthy with zero restarts and the manifest downloaded through the public route reproduces
the exact signed hash. This closes publication and production capability enablement. Normal-user installation,
the explicit two-stage confirmation flow, existing-Hermes reuse, managed-service activation and rollback still
need physical acceptance on the user's MacBook. The DMG remains ad-hoc signed and has not passed Developer ID
signing, notarization, stapling or clean-machine Gatekeeper acceptance.

## 2026-09-15 Desktop 0.2.12 account-selected Overview release

PR #303 corrected account-mode Overview so its heading, connection summary, and
Connector/Gateway/Hermes/end-to-end topology follow the Mac selected in Account & Devices. PR #304
then allocated Desktop 0.2.12/build 15 and merged as
`a790e5dc3c4a2b99bae9167f1430125f7ee3f41f`, including the separately merged Dock-visibility
correction. The release commit's CI and SAST workflows completed successfully.

A fresh detached worktree whose `HEAD` exactly matched `origin/main` passed the canonical asset check,
all 312 Desktop tests, debug and configured release builds, strict ad-hoc codesign verification, and
`hdiutil verify`. The production-configured app pins `https://mrlgs.net`, managed release 0.3.4,
component release 0.4.0, the approved `desktop-internal-2026-a` trust identity, `internal`/`arm64`, and
`hermes-serve-v1`; managed bootstrap and component preflight are enabled. Its Dock application flag is
enabled (`LSUIElement=false`).

The 2,672,675-byte DMG has SHA-256
`c78ee7d7f9438981da5ba7095a506fee3f4e0f202e8f16ace37b162492fa8723` and is published at
`https://mrlgs.net/desktop/apps/0.2.12/Hermes-Go-Desktop-0.2.12-dev.dmg`. The root-owned mode-0644
server copy reproduced the local size and hash. A full public HTTPS download returned HTTP 200 with
immutable caching and `nosniff`, reproduced the exact size and hash, and passed `hdiutil verify` again.
The 0.2.11 rollback URL still returns HTTP 200, while POST to the new exact route is rejected with HTTP
403.

This remains an internal ad-hoc build. It is not Developer ID signed, notarized, or stapled. Physical
MacBook acceptance must still confirm the selected-device Overview and Dock behavior after installing
0.2.12.

## 2026-09-19 component release 0.4.1 publication

PR #313 changed what the Connector binary does: an oversized frame from the local Hermes now closes
the tunnel with 1009 and a reason naming the limit, instead of the anonymous 1006 the relay refuses
to forward. PR #314 then allocated Connector 0.1.4 and merged as
`aae4b2f72f62b551dbc7279fd64eff821ba195ac`; the release commit's CI, SAST and Gateway OCI workflows
all completed successfully. The same content could not ship as 0.1.3, which is what component release
0.4.0 already carries.

Packaged from two fresh detached worktrees — this repository at the release commit, and
`hermes-agent` at the pinned `f159e581c7afd22a5c94652c569e3859f1b994d2` — with clean trees.

**Only the Connector was rebuilt.** The other three components reuse 0.4.0's exact published
artifacts, downloaded and hash-checked before packaging. That is not only economy: rebuilding them
from the inputs available on this machine does **not** reproduce them. `hermes_core` did come back
byte-identical (`f173c0f6…`), but `python_runtime` rebuilt to `7561866a…` against 0.4.0's
`0b4f4479…` — 269,440,138 bytes against 48,062,625, because the 0.3.4 managed install's
`runtime/site-packages` is a full installed set rather than the bootstrap venv 0.4.0 was built from —
and `node_runtime` rebuilt to `632317…` against `25d90ef6…`. Anyone repeating this must reuse the
published artifacts rather than trust a rebuild to match. (`/Users/bs/.local/bin/node` is also a
symlink, which the component gate rejects; the real path has to be given.)

| Component | Version | Size | SHA-256 | Content SHA-256 |
|---|---|---|---|---|
| python_runtime | 3.11.15 | 48,062,625 | `7af79386…` | `0b4f4479…` (reused from 0.4.0) |
| hermes_core | 0.21.0 | 17,762,179 | `09dc9bc7…` | `f173c0f6…` (reused from 0.4.0) |
| node_runtime | 22.23.2 | 36,999,070 | `3d5fd7a8…` | `25d90ef6…` (reused from 0.4.0) |
| connector | 0.1.4 | 62,944 | `409febac…` | `e6dae772…` (new) |

The 3,816-byte manifest has SHA-256
`997ba7da356d0c4dd2ae4208a808367850c8c8ab07d4a40e6353ed31ffd10cd8`. The packaging gate derived the
public key `vhY90f6lZlNjbin2kY0zRh4OPxb-ROou9uO-dZ-bhxA`, matching the approved
`desktop-internal-2026-a` identity; the independent verifier accepted the local output.

Uploaded to an owner-only staging directory on the HK host, re-hashed there against the packaging
output, then installed as root-owned mode-0644 files under a new mode-0755
`/srv/hermes-desktop-components/0.4.1`. The route file was appended only after its previous hash
matched the audited `da5c3efe1f932275ff6a9060f670ccd0ee08b8af30da1966988b9a9f4e601387`; `nginx -t`
passed, reload completed, Nginx stayed active and the Relay health endpoint still answered 200.

A full public re-download of all five files reproduced the exact sizes and hashes, with
`application/json`/`application/gzip` content types, one-year immutable caching and `nosniff`, and the
independent Ed25519 verifier accepted the downloaded manifest plus all four downloaded archives. The
0.4.0 manifest still returns 200, the 0.4.1 directory itself returns 404 rather than a listing, and
POST to an exact route is rejected with 403. Staging and route-backup files were removed after
verification.

**This publication did not switch any Mac to 0.4.1.** Activation is a separate confirmation in
Desktop, so the Mac that reported HG-65/HG-64 still runs the 0.4.0 Connector (0.1.3) until someone
confirms the change there. schema-v1 `0.3.5` was not published; the rollback channel still ends at
0.3.4.

## 2026-09-19 managed release 0.3.5 and Desktop 0.2.15 publication

Publishing component release 0.4.1 earlier the same day did not put the Connector fix within reach of
the Mac that reported HG-65/HG-64, and the reason is worth recording because it is not visible from
the repository. That Mac's installed Desktop 0.2.14 carries

```
HermesGoDesktopReleaseManifestURL   = .../desktop/releases/0.3.4/Hermes-Desktop-0.3.4-arm64.manifest.json
HermesGoDesktopComponentManifestURL = (empty)
HermesGoComponentPreflightEnabled   = false
```

and its managed store agrees: `current -> releases/0.3.4`, `migration-state.json` reading
`schemaVersion: 1`, `releaseVersion: 0.3.4`, `state: account_active`. The schema-v2 component channel
has never been enabled there. The release URL is written into `Info.plist` at build time
(`desktop/scripts/build-app.sh`), so a Mac cannot be pointed at a newer release by publishing one —
publishing 0.3.5 alone would have been just as inert as 0.4.1.

**Managed release 0.3.5** was therefore published on the schema-v1 channel. Hermes Server reuses
0.3.4's exact artifact (285,054,389 bytes, `8ae357d7…`, downloaded and hash-checked before
packaging); Connector 0.1.4 is new (37,063,386 bytes, `cc55baf0…`). The 1,306-byte manifest has
SHA-256 `d7deea4672ed7f4d52ae554e9963f6bfbc4682a633b5e6611aa6eae736c99d16`, and the packaging gate
derived the approved `desktop-internal-2026-a` public key.

**Desktop 0.2.15/build 18** (`c057a5ccf0e98f64dde1f8ecf5c2d554c6c45be6`, CI and SAST successful) is
0.2.14 with the pinned release manifest moved to 0.3.5 and nothing else: the component manifest URL
stays empty and preflight stays off, because which channel a Mac uses is not something a bug fix
should change. Built from a fresh detached worktree whose `HEAD` matched `origin/main`, with a clean
tree, after `desktop:assets:test` and all 321 Desktop tests passed. The packaged app's `Info.plist`
was read back and confirmed to carry 0.2.15/18, the 0.3.5 manifest URL, the empty component URL,
preflight false, managed bootstrap true, the approved key ID and public key, `hermes-serve-v1`, and
`LSUIElement=false`. Strict `codesign --verify --deep --strict` passed (ad-hoc, `com.hermesgo.desktop`,
no Team ID).

The 2,690,709-byte DMG has SHA-256
`42707f0f545bdc60efd4b00336e299c5e6cdc6982c4373900038028431a98f0b` and is published at
`https://mrlgs.net/desktop/apps/0.2.15/Hermes-Go-Desktop-0.2.15-dev.dmg`.

Both publications followed the same route: owner-only staging on the HK host, re-hashed there,
installed as root-owned mode-0644 files under new mode-0755 directories, and routed only after the
route file's previous hash matched the audited value. `nginx -t` passed and reload completed on each,
Nginx stayed active, and the Relay health endpoint answered 200 throughout. Full public re-downloads
reproduced every size and hash; the independent Ed25519 verifier accepted the downloaded 0.3.5
manifest and both archives, and `hdiutil verify` accepted the downloaded DMG. Directory URLs return
404 rather than listings, POST to an exact route returns 403, and the 0.3.4 release plus the 0.2.12
DMG still return 200. Staging and route-backup files were removed.

The DMG serves as `application/octet-stream` rather than the configured `default_type`, which is how
0.2.12 already serves — nginx's `mime.types` maps `.dmg` before `default_type` applies — so it is the
established behaviour, not a regression.

**Nothing on any Mac has changed.** 0.2.15 is not installed, no Mac has migrated to 0.3.5, and the
machine that filed HG-65/HG-64 still runs Desktop 0.2.14 with the 0.3.4 Connector 0.1.3. Installing
0.2.15 replaces a running app that manages the live Hermes and Connector, and the migration to 0.3.5
restarts Connector, so it should be done when no phone-side run is in flight. The migration itself
requires the explicit confirmation in Desktop that exists to obtain a person's consent; it was
deliberately not automated. This remains an internal ad-hoc build: not Developer ID signed, not
notarized, not stapled.

## 2026-09-19 managed release 0.3.5 controlled activation exception

The MacBook used for the HG-65/HG-64 reproduction was subsequently running Desktop 0.2.16/build 19,
whose schema-v1 release pin is 0.3.5, but its already-active managed installation still had no
product upgrade action. The owner therefore explicitly authorized one operator-controlled exception
to move the existing account-mode installation from 0.3.4 to 0.3.5. This is evidence for that one
activation; it does not claim that Desktop now implements an active-install upgrade path.

Before mutation, the public copies of the 0.3.5 manifest and both archives passed the independent
Ed25519 verifier against the approved key, origin, channel and architecture. Their sizes and hashes
matched the publication record above. The managed journal was `account_active` at release 0.3.4,
both managed services were running, loopback status returned HTTP 200, Connector had an active
account-mode Gateway connection, and Hermes reported zero active sessions. Each activation attempt
first wrote an owner-only recovery snapshot of the journal, both LaunchAgents and previous current
target. The 0.3.5 component trees were installed under a new owner-only immutable release directory;
their identities are Hermes Server 0.21.0 at the pinned upstream commit and Connector 0.1.4 at the
release commit recorded above.

The transaction's gates caused two safe rollbacks before the successful activation. The first
operator wrapper used ordinary `mv -f` to replace a symlink to a directory; macOS instead placed the
temporary link inside the old release. The exact-current-target gate caught that no switch occurred,
restored the 0.3.4 journal and services, and the stray temporary link was later removed. The wrapper
was corrected to use symlink-preserving replacement semantics and verified in an isolated directory.
The second attempt switched the pointer, but its 75-second Hermes health gate expired. It restored
the 0.3.4 pointer and journal, restarted Hermes before Connector, and recovered loopback health and
the active account-mode Gateway connection. The new Hermes tree differed from 0.3.4 only by absent
runtime-generated Python caches and passed an isolated cold-start probe in 16 seconds. Operational
evidence was consistent with treating launchd label removal as complete before the old managed PID
and loopback listener had fully drained.

The successful retry temporarily quit Desktop to remove coordinator races, then required the old
Hermes and Connector PIDs to exit and `127.0.0.1:9119` to become free before switching. It atomically
moved `current` from `releases/0.3.4` to `releases/0.3.5`, updated only the journal's release version
and timestamp, started Hermes before Connector, and reopened Desktop after both gates passed. Final
inspection found both launchd PIDs executing from 0.3.5, Hermes 0.21.0 returning HTTP 200 with config
version 40 and zero active sessions, Connector 0.1.4 holding an established upstream TLS connection
and reporting active account mode, the journal reading 0.3.5, and the release marker and journal
remaining owner-only. The old 0.3.4 release and all recovery snapshots remain available for
rollback.

This closes the controlled activation only. A phone-side reproduction of the original oversized
local WebSocket response must still confirm the Connector 0.1.4 behavior: close code 1009 with the
declared limit and no rapid anonymous-1006 reconnect loop. A full reboot and a normal Desktop-driven
active-release upgrade remain separate acceptance gaps.

## 2026-09-19 managed release 0.3.6, Desktop 0.2.18, and a second controlled activation

Connector 0.1.5 carries the half of HG-65 that stops the failure rather than naming it: an oversized
answer from the local Hermes is received and dropped, and the tunnel stays open. 0.1.4, already
published in 0.3.5 and 0.4.1, carries only the earlier close-code change, so the content could not
ship under that number. Released from `255aad9a75f51cc096c867dbb4dd021bdbe7a796` (PR #327), whose CI,
SAST and Gateway OCI workflows all completed successfully.

Packaged from two fresh detached worktrees with clean trees — this repository at the release commit,
and `hermes-agent` at the pinned `f159e581c7afd22a5c94652c569e3859f1b994d2`.

**Only the Connector was rebuilt.** The rebuilt Hermes Server came back 287,628,978 bytes /
`d22fe875…` against the published 285,054,389 / `8ae357d7…`, so the published artifact was
downloaded, hash-checked before packaging, and used; the rebuild was discarded. Reusing the
published artifact remains the rule. **But the reason is not the one this document gave at first,
and the difference matters** — see the correction below.

| Component | Version | Size | SHA-256 |
|---|---|---|---|
| hermes_server | 0.21.0 | 285,054,389 | `8ae357d78a7836a0680125f7e241243af1555418d9d2e12e68433b5ff1f0ca44` (reused from 0.3.4) |
| connector | 0.1.5 | 37,065,281 | `418ec0108ed7abb07e9612d4a00b518cc20847d4028f5be88418ba484c8c92ea` (new) |

The 1,306-byte manifest has SHA-256
`937dbae1aebcc11cc1b8f434670e8997f444b329527c45180c324ba671997bd2`. The packaging gate derived the
public key `vhY90f6lZlNjbin2kY0zRh4OPxb-ROou9uO-dZ-bhxA`, matching the approved
`desktop-internal-2026-a` identity.

Uploaded to an owner-only staging directory, re-hashed there against the packaging output, then
installed as root-owned mode-0644 files under a new mode-0755 `/srv/hermes-desktop-releases/0.3.6`.
The route file was appended only after its previous hash matched
`5905882512b6fb9242756182a281b052a5854674fe487117e92ec228aca7ce13`; `nginx -t` passed before each
reload, Nginx stayed active and the Relay health endpoint answered 200. A full public re-download of
all three files reproduced the exact sizes and hashes, and the independent Ed25519 verifier accepted
the **downloaded** manifest and archives. Staging was removed; two route backups were kept.

**Desktop 0.2.18/build 21** is 0.2.17 with the pinned schema-v1 manifest moved to 0.3.6 and nothing
else changed. The DMG (2,595,705 bytes, `aabb34dfbfc6bbdfa0a8bef71797b7df92fc5708feb096efa3932d5b0fd2784a`)
was **mounted and its embedded `Info.plist` read before upload** — 0.2.18, build 21, manifest URL
0.3.6, empty component URL, preflight off. That check exists because the 0.2.15 DMG shipped repository
defaults; it is not optional. The published copy re-downloaded to the same bytes.

### The in-app upgrade did not work, and that is now HG-68

0.2.17 added the managed in-app upgrade specifically so an already-active installation would no
longer need an operator. On this Mac it refused: `HR-MIGRATE-002`, `cause=invalidState`,
`retryable=false`, no upgrade action offered. Every condition `DesktopManagedBootstrapRuntime.reduce()`
requires was verified present — both managed agents loaded, the legacy agent not loaded, the account
binding equal to the journal's, and a journal that passes every `DesktopMigrationJournal` check. The
error comes from the catch in `DesktopViewModel.recoverManagedBootstrapAfterRestart()`, which maps any
throw from the three reconcile calls onto `migrationConnectorMismatch`. Worth noting while fixing it:
`.accountActive` has an empty transition list, and an active installation upgrading must leave exactly
that state. Filed as **HG-68**.

### Second controlled activation exception

The owner authorized one more operator-controlled activation, 0.3.5 to 0.3.6. Before mutation an
owner-only snapshot recorded the journal, both LaunchAgents and the previous `current` target. The
0.3.6 trees were extracted from the **publicly downloaded, independently verified** archives into a
new owner-only release directory; their `BUILD-IDENTITY.json` files name Connector 0.1.5 at the
release commit and Hermes Server 0.21.0 at the pinned upstream commit.

**The first attempt rolled back, and the rollback path is now proven rather than assumed.** Its
drain gate required TCP 9119 to be free of all listeners; this Mac also runs a separate, non-managed
`hermes-agent` bound to the Tailscale address on that port, so the gate could never pass. It
restored nothing because nothing had been switched yet, and both services came back. The gate was
narrowed to loopback (`-iTCP@127.0.0.1:9119`, with `-t` so lsof's header is not counted) — a
condition that is about the managed Hermes rather than about the port.

The successful run quit Desktop first, booted out both agents, observed the release PIDs gone and
loopback 9119 free within 2 seconds, replaced the symlink with `mv -h` (`mv -f` put the temporary
link inside the old release during the 0.3.5 activation) and read the target back, updated only the
journal's `releaseVersion` and `updatedAt`, started Hermes and waited for loopback health (200 after
20 seconds), then started the Connector. Final inspection found all three managed PIDs executing from
`releases/0.3.6`, zero processes left on 0.3.5, Hermes returning 200, the Connector reconnected as
`account mode (active)` with the binding ID and generation unchanged, and the journal reading 0.3.6.
The 0.3.5 release tree and the snapshot remain available for rollback.

This closes delivery of the HG-65 Connector fix to this Mac. It does not close HG-68: the next
managed release will need an operator again until that is fixed.

## 2026-09-20 correction: the Hermes Server rebuild IS reproducible; the packaging is not

The 0.4.1 entry recorded that rebuilding `python_runtime` produced 269,440,138 bytes against the
published 48,062,625, and traced it to the wrong input — a full installed `site-packages` instead of
the bootstrap venv it was built from. That finding stands for `python_runtime`.

**It was then applied to `hermes_server` without being checked, and that was wrong.** The two
archives were compared entry by entry:

| | 已发布 | 重建 |
|---|---|---|
| tar entries | 23,111 | 46,222 |
| files present | identical set | identical set |
| **file contents** | — | **0 differ** |
| tar format | all `ustar/gnu` | `pax` + `ustar/gnu` mixed |
| distinct mtimes (first 400) | 8, integers | 208, some fractional |

Every one of the 23,111 files is byte-identical. The rebuilt archive simply carries one pax
extended-header pseudo-entry per file, because some files reached the packer with sub-second mtimes
and tar cannot express those in `ustar`. That is ~2 KiB per file: 47 MiB uncompressed, 2.5 MiB
compressed, and a different SHA-256.

So this is **packaging non-determinism, not input contamination**, and it is fixable: normalise
mtimes (or force `ustar`) in `scripts/lib/desktop-component-archives.mjs` and the archive becomes
byte-reproducible from a source commit. Two things follow once it is:

- a published artifact can be verified against the commit it claims to come from, instead of being
  trusted because it is the one that was published;
- keeping the managed Hermes in step with upstream stops meaning "produce a fresh artifact whose
  provenance rests on the build host's state".

Until that lands, reuse the published artifact — the instruction is unchanged, only its reason is.

## The managed Hermes and the user's own Hermes share one database

This is not a deployment accident; it is what makes the product work, and it is also the sharpest
constraint on it. Both are documented here because the 2026-09-19 incident below was the first time
they collided.

The managed launch agent sets `HERMES_HOME=/Users/bs/.hermes` — the **user's own** Hermes home. The
phone is therefore a remote view of the conversations on the Mac, which is the entire point of
Hermes GO. On a Mac where the owner also runs their own Hermes, the result is **two copies of the
code writing one `state.db`**:

| | version policy | why |
|---|---|---|
| managed copy (`Managed/releases/<ver>/hermes_server`) | **pinned** to one upstream commit | a signed release has to be reproducible and auditable |
| the owner's own checkout (`~/.hermes/hermes-agent`) | **rolling** — updated whenever the owner pulls | it is their working Hermes |

Those two policies are opposites, so drift is guaranteed; the only question is which upstream change
detonates it.

**2026-09-19 incident.** The owner updated their checkout to a build carrying
`display_identity BLOB` (`hermes_state_common.py`) and restarted it around 21:00 CST. It migrated
the shared database and began writing 32-byte digests — 1,954 rows across 12 sessions. The pinned
managed copy does not contain the string `display_identity` anywhere, and reads rows with
`SELECT * FROM messages` (`hermes_state_messages.py`, 5 sites), so the unknown column travelled
straight into the API response, where FastAPI's encoder called `bytes.decode()` on it:

```
UnicodeDecodeError: 'utf-8' codec can't decode byte 0xff in position 0
  ... fastapi/routing.py serialize_response → encoders.py:69 <lambda>
```

Every `GET /api/sessions/{id}/messages` for an affected session returned 500. The relay forwarded it
faithfully, and the phone showed the generic `HR-RPC-001`. Sessions created before 21:02 still open.

**What this means for anyone changing this system.** An upstream schema addition can disable the
pinned copy at any time, silently, and the symptom appears on the phone rather than on the Mac.
Nothing in this repository can prevent that: the fix is upstream selecting known columns, or the
managed copy never lagging the owner's. What this repository *can* do is refuse to fail silently —
compare the database's schema expectation against the pinned Hermes at startup and say so plainly.

**Do not "fix" this by giving the managed copy its own database.** It would stop the phone seeing
the owner's conversations, which is the product.

## 2026-09-21 restart-probe incident: the managed Hermes was left unloaded for ~3 minutes

Desktop 0.2.21 (local runtime mode, #351) on the owner's Mac mini tried to switch the managed
`com.hermesgo.hermes-server` job from the bundled copy to the owner's own Hermes. The phone lost its
Hermes until an operator loaded the job by hand. Evidence below is from launchd's own log
(`/var/log/com.apple.xpc.launchd/launchd.log`) and file modification times, read only; nothing was
changed on the Mac by the investigation.

| Time (CST) | What happened |
|---|---|
| 19:56:52.17 | Desktop (PID 48509) starts. |
| 19:56:53.53 | `bootout initiated by: launchctl[48567]<-HermesGoDesktop[48509]`. The old Hermes (PID 28260, running since the manual 0.3.8 activation the evening before) exits on SIGTERM at 19:56:53.74 and the label is removed. |
| — | **No bootstrap by Desktop follows.** |
| 19:58:51 | The bundled agent is restored: `~/Library/LaunchAgents/com.hermesgo.hermes-server.plist` and `Managed/bin` both carry this time. This is the switch's catch path — its stop wait had just ended in `hermesStopTimedOut`, ~118 s after the bootout, which is 75 attempts × the 500 ms probe timeout plus 74 × 1 s. |
| 19:59:24 | launchd logs one "job not found, returning ENOSERVICE" lookup (it does not name the caller). |
| 20:00:05.46 | `Bootstrap by launchctl[50354] … succeeded` — the operator's manual recovery. Hermes spawns. |
| ~20:00:43 (inferred) | Desktop's restore attempt ends the same way: its stop wait times out too, `ensureHermesLoaded` finds the job loaded (by the operator) and does nothing, and Desktop shows `HR-MIGRATE-009` with `cause=stage=runtime rollbackFailed` and nothing else. |

The phone had no Hermes from 19:56:53 to 20:00:05, **about 3 minutes 12 seconds**.

**Root cause.** `DesktopHermesShutdownChecker` proves the old listener is gone by connecting to
`127.0.0.1:9119`. It accepted only `.failed`/`.cancelled` as "gone" and read a 500 ms timeout as "still
listening". On macOS a TCP connection to a free loopback port never reaches `.failed`:
Network.framework reports `.waiting(POSIXErrorCode 61: Connection refused)` within milliseconds and
stays there (reproduced with a standalone script: `preparing` → `waiting(ECONNREFUSED)` at 1–2 ms →
nothing until cancelled). So once the old Hermes had exited, every probe timed out, every wait failed
after ~112 s, and **every** caller of the wait failed: each runtime switch, restart, restore, reload,
the managed upgrade, and the upgrade's rollback. The coordinator tests never noticed because they
inject a fake checker that answers "stopped".

**Why `ensureHermesLoaded` produced no bootstrap.** It was not a refusal by launchd, nor the
`validPlist`/`isLoaded` guard: Desktop simply never got there first. The job was unloaded at 19:56:53;
the switch's wait took until ~19:58:51; the restore then restarted with a second full wait, which the
operator's bootstrap at 20:00:05 overtook; when `ensureHermesLoaded` finally ran it saw the job loaded
and returned. Had the operator not intervened it would have bootstrapped at ~20:00:43 — after nearly
four minutes — and with `try?` any refusal would have been silent. Worse, every later refresh would
then have left the job down: the runtime planner treated a **bundled** job that is not loaded as
`.keep` ("in some other operation's hands"), so nothing would ever have loaded it again.

**The journal half of the incident.** The manual 0.3.8 activation on 2026-09-20 had written the
migration journal's `updatedAt` as `2026-09-20T11:48:09Z`. The reader required fractional seconds,
so the whole journal read as `DesktopMigrationJournalError.invalidState`, and Desktop showed
"受管服务状态不一致" with `HR-MIGRATE-002`. It was repaired by hand to `2026-09-20T11:48:09.000Z`.

**HG-68.** Two separate things, and this incident explains both:

- *The refusal HG-68 was filed for* — `HR-MIGRATE-002`, `cause=invalidState`, no upgrade offered on
  2026-09-19 — happens before any service is touched, so the probe cannot be its cause. But
  `invalidState` is a case of exactly one type in the code base, `DesktopMigrationJournalError`, so
  the journal failed validation; the 0.3.5 controlled activation that morning had "updated only the
  journal's release version and timestamp" by hand; and today the same hand-written-timestamp
  mistake produced the identical code and cause. The 2026-09-19 post-activation journal was not
  preserved (the `recovery/` snapshots hold only the pre-activation copies, whose timestamps are
  canonical), so this is the most probable explanation rather than a proven one.
- *The need for a manual operator activation* would have survived that fix: with a readable journal,
  an in-app upgrade would stop Hermes, time out on the stop proof, roll back, time out again, and
  end in `rollbackAttentionRequired` with both services down. The controlled activations succeeded
  because they used their own `lsof` drain gate instead of this probe.

**Fix** (branch `claude/desktop-restart-probe`):

- The probe decision is a pure function, `DesktopLoopbackProbeDecision.decide`: `.ready` is
  listening; `.waiting` or `.failed` with `ECONNREFUSED` is stopped; everything else (other errors,
  `.setup`, `.preparing`, `.cancelled`, a timeout) stays undecided and is treated as listening.
  A real loopback test proves a free ephemeral port stopped in one probe and a listening socket not.
- `ensureHermesLoaded` bootstraps the agent at the managed path — the file the rollback just
  restored — up to three times (2 s, 4 s back-off). If the job is still not loaded the failure is a new
  registered, retryable `HR-MIGRATE-013` instead of silence.
- A bundled job that is not loaded now plans `.loadAgent` and is loaded again, with the setting on
  or off, bounded by those retries and the five-minute runtime back-off. Desktop stopped nothing in
  that case, so it probes 9119 once first: if another process already listens there it loads
  nothing (a bootstrap would crash-loop on `EADDRINUSE` against the shared `state.db`) and surfaces
  retryable `HR-MIGRATE-014`.
- **Runbook change for controlled activations: quit Desktop first** (or hold
  `Managed/state/migration-operation.lock` throughout). Desktop now loads an unloaded bundled job on
  its next refresh, so a Desktop left running would reload the old agent mid-activation. The
  2026-09-19 activations already quit Desktop; it is now a hard precondition.
- `DesktopServiceRecoveryFailure` carries the operation, the original error and the recovery error
  into `HR-MIGRATE-009`/`-013`/`-004` diagnostics, redacted.
- `Managed/logs/desktop-runtime.log` (0600, rotated at 256 KiB) records every launchctl mutation with
  its status and launchd's stderr, a summary of each convergence wait, refused starts, and every
  shutdown/readiness/reload outcome.
- The journal reader accepts RFC 3339 `updatedAt` without fractional seconds (writing stays
  canonical). **The journal must never be edited by hand**; `DESKTOP_PHASE0.md` ("Proving the old
  listener is gone…") says how to change it safely if an operator activation ever has to, and how to
  repair one that is already broken.

Verified: `swift test --package-path desktop` and `npm run desktop:assets:test`. The new loopback test
was run against the old decision mapping and failed (the wait took the full 15 s and answered "not
stopped"). **Physically unverified:** no packaged build ran, no service was touched on any Mac, and
the in-app upgrade and a runtime switch still need a physical run to close HG-68.

## 2026-09-21 Mac mini switched to its own Hermes (Desktop 0.2.22)

Owner-authorised switch of the managed `com.hermesgo.hermes-server` job from the bundled copy
(managed release 0.3.8, upstream `f159e581` + patches) to the owner's `~/.hermes/hermes-agent`
(0.21.3, `17b5df02`), per `docs/MANAGED_HERMES_STRATEGY.md` (one Hermes per Mac).

Sequence:

1. Desktop 0.2.21 (#351, #355) was built from a clean worktree at its merge commit with the same
   packaged configuration as the installed 0.2.20 (compared key by key), installed over it with the
   GUI restarted only; Hermes and Connector PIDs were unchanged and, with the setting off, the
   LaunchAgent stayed byte-identical.
2. The first attempt surfaced `HR-MIGRATE-009 cause=stage=runtime invalidState`: the migration
   journal's `updatedAt` had been hand-written without fractional seconds by the 09-20 controlled
   activation, so the journal read as invalid (this also produced the long-standing "受管服务状态不一致"
   and `HR-MIGRATE-002`, which disappeared once repaired). Only that field was rewritten to the
   same instant with `.000Z`, with Desktop quit, backup kept.
3. The second attempt left Hermes unloaded for ~3 minutes — the restart-probe incident recorded in
   the entry above. It was restored by bootstrapping the unchanged bundled agent by hand, and the
   setting was turned off until #356 shipped in Desktop 0.2.22 (#357).
4. With 0.2.22 installed the same way (setting off: no change), the setting was turned on at
   20:53:52 +08:00. Desktop switched at 20:55:17; `desktop-runtime.log` shows `bootout status=0`,
   `wait-stopped result=stopped attempts=1 elapsed=0.0s`, `bootstrap status=0`, and
   `restart hermes result=ready` at 20:55:34. Hermes was unavailable for about 17 s and the job
   never left launchd.

Verified after the switch:

- `com.hermesgo.hermes-server` runs `Managed/bin/hermes-local-serve` →
  `~/.hermes/hermes-agent/venv/bin/hermes serve --host 127.0.0.1 --port 9119`; the token is handed
  over from the private file, not the plist; `/api/status` reports 0.21.3 and `HERMES_HOME=~/.hermes`.
- The Connector (unchanged PID) kept serving the phone.
- Cron: every execution after the switch was claimed by the owner's gateway; the new serve claimed
  none, i.e. its desktop ticker stands down while the gateway runs (0.21.3 `profile_gate`).
- Owner, on a physical phone through the public relay: three tasks worked end to end.

Rollback remains `defaults write com.hermesgo.desktop HermesGoLocalHermesRuntimeEnabled -bool false`,
which restores the kept bundled agent on the next refresh. The in-app managed upgrade path (the
other half of HG-68) has not been exercised on this Mac yet. The owner's checkout is ~34k upstream
commits behind `origin/main`; run the `docs/HERMES_CONTRACT.md` upgrade checklist before the next
`hermes update`.

## 2026-09-21 managed release 0.3.9 (Connector 0.1.6), Desktop 0.2.23 and Android 0.1.138

Ships the Connector upstream-contract check (#359) to the Mac mini and the phones. Version and
publish gates were authorised by the owner.

**Managed release 0.3.9** (schema-v1, channel `internal`, `desktop-internal-2026-a`):

| Artifact | Bytes | SHA-256 |
|---|---|---|
| `Hermes-Desktop-0.3.9-arm64.manifest.json` | 1,306 | `935a2bc3bee822b5b1fbb993cf9d354a1927f740981ddfbc0c1b9286fabbd44c` |
| `Hermes-Server-0.21.0-arm64.tar.gz` (0.3.8's exact published artifact, downloaded and checked against the signed 0.3.8 manifest before reuse) | 285,064,472 | `06513b2a2d137d70541d2a7b190ea305365a421f8f90d6e21ebb9e814852dc1e` |
| `Hermes-Connector-0.1.6-arm64.tar.gz` (built at `4234589`, `BUILD-IDENTITY.json` connector 0.1.6) | 37,066,062 | `569bcdc9ec82baa2c3f94d2eb193bc8e6389c17b480e652aebe65d6f49bbbc9d` |

Created 2026-09-21T14:55:00Z, expires 2026-10-05T00:00:00Z. The packaging gate derived the pinned
public key `vhY90f6l…`; the independent verifier accepted the local output and, after publication, a
full public re-download. The component packager requires realpaths, so every input was given under
`/private/tmp` rather than `/tmp`; its throwaway Hermes rebuild came out byte-size identical to the
published one but was not used.

Publication on the HK host followed the 0.3.5 route: owner-only staging, re-hashed on the host,
root-owned 0644 files under a new 0755 `/srv/hermes-desktop-releases/0.3.9`, and three exact
`location` blocks appended to `/etc/hermes-go/desktop-release-routes.conf` only after its hash
matched the audited `43404078…` (new hash `0715c3a3…`, backup
`/root/desktop-release-routes.conf.before-0.3.9`); `nginx -t` passed, reload completed, Nginx
active. The directory URL returns 404, POST 403, 0.3.8 and the Android index still 200, Relay
`/health` 200. Staging removed.

**Desktop 0.2.23/build 26** (#360, `4234589`) is 0.2.22 with the pinned manifest moved to 0.3.9. Built
from a clean detached worktree at the merge commit after 470 Desktop tests passed; every packaged
`Info.plist` value except the version and that URL matched the installed app; strict codesign
passed (ad-hoc). Installed over 0.2.22 with only the GUI restarted.

**Activation — the first in-app managed upgrade to complete unattended.** The owner confirmed the
upgrade in Desktop. `desktop-runtime.log` shows it took 31 s: both jobs booted out, `wait-stopped
result=stopped attempts=1 elapsed=0.0s`, Hermes bootstrapped and ready, then the Connector. The
journal reads `account_active 0.3.9`, `current -> releases/0.3.9`, and local-Hermes mode survived the
upgrade (the job still runs `hermes-local-serve` → `~/.hermes/hermes-agent`). The new Connector
logged `hermes.contract status=compatible version=0.21.3 checked=41 missingRequired=0
missingOptional=0` at startup. Together with the 2026-09-21 restart-probe entry this is the physical
evidence HG-68 was waiting for.

**Android 0.1.138/code 139** (#361, tag `android-v0.1.138` at `80f8704`) published by the release
workflow: 31,995,744 bytes, SHA-256 `211695aa8cb33cc1acae73dbe33d7eaf38f960babae68a42b1babba374f003d5`,
certificate `06c18dfc…`; the public file and index entry were re-downloaded and matched. Installed
on HONOR CLK-AN00 as an upgrade with data kept; the vivo was not attached.

## 2026-09-22 Desktop 0.2.24 publication (install-when-missing, local Hermes by default)

Desktop **0.2.24/build 27** (#366 at `bffe87c`) ships #365: a Mac with no Hermes is offered upstream's
official installer, and this Mac's own Hermes is used by default. The pinned managed manifest stays
0.3.9. Version and publish gates were authorised by the owner.

Built from a clean detached worktree at the merge commit after `desktop:assets:test` and all 522
Desktop tests passed. `desktop:dmg` rebuilds the app itself through `build-app.sh`, so the packaged
configuration has to be **exported** for that run rather than prefixed to `desktop:app` — otherwise
the DMG carries an unconfigured app. The DMG was mounted and its app's `Info.plist` compared key by
key with the installed 0.2.23: only the version and build differ; strict codesign passed (ad-hoc,
`com.hermesgo.desktop`).

The 3,032,940-byte DMG has SHA-256
`a6be473abba272543e0b9279e6e8ba936b4870e9c11e4e587404541ef6c776d5` and is published at
`https://mrlgs.net/desktop/apps/0.2.24/Hermes-Go-Desktop-0.2.24-dev.dmg` the same way as earlier
DMGs: owner-only staging, re-hashed on the host, root-owned 0644 under a new 0755 directory, the
route appended to `/etc/hermes-go/desktop-release-routes.conf` only after its hash matched
`0715c3a3…` (new `48393691…`, backup `/root/desktop-release-routes.conf.before-dmg-0.2.24`),
`nginx -t` then reload. The public re-download reproduced size and hash and passed `hdiutil verify`;
the directory URL returns 404, POST 403, the 0.2.20 DMG, the 0.3.9 manifest and Relay `/health` 200.

Installed over 0.2.23 on the Mac mini with only the GUI restarted: Hermes and Connector kept their
PIDs, the job stayed in local mode (this Mac already had the setting on), and Desktop performed no
service operation. The install-when-missing path and default-on switching of a Mac that never opted
in remain physically unverified (`docs/DESKTOP_TEST_PLAN.md`). This is an internal ad-hoc build: not
Developer ID signed, notarized or stapled.

## 2026-09-25 Desktop 0.2.28 publication (sign-in gate and first-run onboarding, HG-129)

Desktop **0.2.28/build 31** ships HG-129: a full-window sign-in gate, four-step first-run onboarding,
the Android/iPhone–iPad split of its phone step, the "connect this Mac too / only manage from here"
choice for an existing account on a new Mac, and the menu-bar and overview adjustments that go with
them. The requirements and 17 mockups (#425 at `9540c598`), the implementation (#426 at `cb0b2f47`)
and the version (#427 at `9e84f15b`) are all on `main`; version and publish gates were authorised by
the owner.

**0.2.27/build 30 never reached the host.** It is recorded on `main` and a dev DMG was built locally on
2026-09-24, but `https://mrlgs.net/desktop/apps/0.2.27/Hermes-Go-Desktop-0.2.27-dev.dmg` returns 404
while 0.2.26 and earlier return 200. 0.2.28 supersedes it — it carries HG-120–122 as well as HG-129 —
so no number is lost and no published artifact changed meaning.

The published configuration was taken from the **published 0.2.26**, not from this Mac's installed
0.2.27: mounting `Hermes-Go-Desktop-0.2.26-dev.dmg` and reading its `Info.plist` shows the two index
pointers (`/desktop/releases/index.json`, `/desktop/components/index.json`), component preflight off,
`internal`/`arm64`, key `desktop-internal-2026-a` and `hermes-serve-v1`. The locally installed 0.2.27
carried a pinned concrete 0.4.3 manifest and component preflight on, which no published build has
used; that configuration was deliberately not adopted.

Built from a clean detached worktree at `9e84f15b` after `desktop:assets:test` and all 503 Desktop
tests passed. Before building, the pinned key was checked against both live indexes
(`RELEASES_VERSION=0.4.4`, `COMPONENTS_VERSION=0.4.4`, `DESKTOP_SIGNING_CONTINUITY_OK`). The release
configuration was exported into the `desktop:dmg` run, because `desktop:dmg` rebuilds the app through
`build-app.sh` and would otherwise package an unconfigured app. Reading `Info.plist` back from the
mounted DMG and comparing all 28 keys with the published 0.2.26 shows **only the version and build
number differ**; strict `codesign --verify --deep --strict` passed (ad-hoc, `com.hermesgo.desktop`,
arm64).

The 3,215,182-byte DMG has SHA-256
`d4d985e52fe2d5871613135193a35bb72a4f28011c02bb7eb6f50ff066c6a954` and is published at
`https://mrlgs.net/desktop/apps/0.2.28/Hermes-Go-Desktop-0.2.28-dev.dmg`.

Publication used an owner-only staging directory on the HK host and required the uploaded artifact to
reproduce the local size and hash. The final file was installed root-owned mode 0644 under a new
mode-0755 `/srv/hermes-desktop-apps/0.2.28`; the exact route was appended to
`/etc/hermes-go/desktop-release-routes.conf` only after that file still matched its audited
`6dd0b4dc…` (new `1733e372…`, one-time backup `/root/desktop-release-routes.conf.before-dmg-0.2.28`);
`nginx -t` passed, Nginx reloaded and is active. A full public re-download reproduced the exact size
and hash, passed `hdiutil verify`, exposed the same packaged configuration from the mounted app, and
passed strict codesign again. The response is HTTP 200 with one-year immutable caching and `nosniff`;
its `Content-Type` is `application/octet-stream`, unchanged from how 0.2.26 is served. The version
directory returns 404, POST to the exact route returns 403, and the 0.2.26 DMG, the releases index,
the Android download redirect and Relay `/health` all still answer as before. Private staging and the
one-time route backup were removed after verification.

Installed on the Mac mini (`LGS-MACMINI`, M4) after re-downloading the published DMG and checking its
size and SHA-256 against this record. Only the GUI was restarted: `com.hermesgo.connector` (pid
18088) and `com.hermesgo.hermes-server` (pid 18057) kept their PIDs, the Hermes job still runs
`hermes-local-serve` against `~/.hermes`, and `Managed/logs/desktop-runtime.log` gained no entry, so
Desktop performed no service operation. The replaced 0.2.27 build is kept beside the DMG until the
new build proves stable on this Mac. The DMG carried no quarantine attribute, so this ad-hoc build
needed no Gatekeeper detour; `codesign --verify --deep --strict` passes on the installed app.

Still not verified by this publication: the clean-Mac first run, both phone QR paths, the
resume-after-interrupt path and the "a gated session leaves the phones working" check remain manual
steps in `docs/DESKTOP_TEST_PLAN.md`. This is an internal ad-hoc build: not Developer ID signed,
notarized or stapled.
