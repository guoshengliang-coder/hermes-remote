# HG-257 — archive compatibility without changing Hermes

2026-11-16: user-authorized related-operation repairs now supplement the original archive-only
scope; see [HG_257_SESSION_OPERATIONS.md](HG_257_SESSION_OPERATIONS.md). The historical checks
and scope statements below describe the preceding delivery, not the new delete/rename implementation.

## Failure and supported paths

Verified upstream: v0.22.5, `bf0203a794a07aac3ff764972ab9087baa4e4f4e`.
The old `17b5df02` PATCH handler resolved a stored ID and changed a flag directly. The current
handler routes to the session owner. Its historical-source admission excludes `hermes_remote`;
unbound GO records fall through to `restore_api_session`, lack that API receipt, and answer
`404 not_found` even though the row exists. Supplying HG-250's revision fence alone cannot fix this.

The same build officially exposes the standalone `session.archive` RPC with a durable
`session_id`, explicit `archived` boolean and optional owning `profile`. It changes the real
Hermes archive flag and compression lineage while retaining messages. GO does not alter the
source label, adopt/resume the session, start inference, use SQL, or install a second Hermes.

| Current completed handshake | GO operation |
|---|---|
| Object payload, `session_authority` absent or false | Official standalone `session.archive` |
| `session_authority: true` | Existing revision-fenced REST |
| Malformed/missing payload or unknown flag format | `HR-COMPAT-004`, no write |
| Standalone RPC explicitly answers `-32601` | Existing REST path for older upstreams |
| Explicit permission, conflict or invalid-parameter refusal | Preserve refusal; never change protocol |
| Internal/storage or unknown future RPC error | Fresh read only; a write may already have committed |
| Timeout, disconnect, malformed/wrong-target acknowledgement | Fresh read of original Mac/profile; never replay the write |

The standalone flag is idempotent, **not compare-and-swap**: external clients can still write an
opposite flag concurrently. GO blocks a second in-flight intent for the same target locally and
does not queue it. A fresh matching state confirms the current observation, not which concurrent
writer produced it. Absence/opposite state never proves a timed-out write failed. Unknown outcomes
use `HR-SESS-025`; explicit refusals/in-progress use `HR-SESS-026`. Recovery reads state only.
Archive revision conflicts are surfaced without the shared rename/delete automatic retry.
No new fallback is applied to rename/delete or to an authority-owned session.

Android uses its authenticated socket and binds the archive call to the handshake generation;
Mac/account route changes cancel the operation. Web uses an existing one-shot device socket,
pins its device/profile, cancels on sheet/route/account changes and closes on sign-out. Neither
creates a live conversation handle. Web feature `session-archive-rpc` gates the new exact-shape
browser allowlist; older Gateways retain the original REST behavior. Publish Gateway before Web.
Desktop, Connector, wire protocol/version, database and minimum-client policy are unchanged.

## Why the startup compatibility check missed this

`connector/src/hermes-contract.ts` compares OpenAPI paths and methods. Both remain present here.
Its `compatible` verdict is route compatibility, not authorization/side-effect acceptance. It must
never perform mutations of user sessions as a startup probe. Shared fixtures in
`docs/hermes-archive-cases.json` pin runtime-mode interpretation in Kotlin and TypeScript;
real-handler validation below supplements those fixtures when upstream changes.

## Safe real-upstream upgrade validation

Run with the **already installed** upstream interpreter and dependencies, from this repository:

```sh
<installed-hermes-python> -I -B scripts/dev/check-hermes-archive.py \
  --hermes-root <installed-hermes-checkout> \
  --site-packages <existing-application-site-packages> > <outside-repo-result.json>
```

`--site-packages` is optional/repeatable when that interpreter already has the dependencies.
The command never calls an installer or launcher. It sets a disposable `HERMES_HOME`, suppresses
dependency synchronization during imports, disables dotenv loading, and audits writes so they
cannot escape that temporary home. Network and child processes are forbidden after read-only
source identification. Only synthetic rows are created, and the temporary store is cleaned up.
The source checkout and a user's real `state.db` are never written. It tests real upstream REST
owner handlers and the WS dispatcher/handler, not just mock responses. Output records source SHA,
every check and limitations; any changed/unusable contract fails rather than claiming compatibility.

The installed build passes 20 checks: REST source comparison, RPC archive/restore across GO and
native sources, messages retained, compression chain, same-ID profile isolation, missing ID/profile,
authority refusal of legacy fallback, a real write followed by RPC storage error, and the
external-SQLite guard. A future upstream fixing GO-source REST admission is
allowed; a different refusal or removal of the supported RPC fails the validator for investigation.

## Gate before adopting another upstream

1. Record exact upstream commit and dependency/interpreter identity. Do not infer compatibility
   from a version string, available route or the previously verified commit.
2. Run the shared Kotlin/TS fixture and parser/consumer suites, then the isolated real-upstream
   validator. Review actual changes to source ownership, request params, replies and error reasons.
3. Exercise standalone and authority selection; -32601 is the only definite non-execution fallback.
   Never add 403/404/409 or network failures to that fallback list to turn a test green.
4. Check archive and restore from Android and Web with default/nondefault profiles, historical GO
   rows, compression, repeated clicks, opposite intents and disconnects. Confirm no messages/source
   IDs changed. Use disposable sessions/environment; real-user acceptance needs separate authority.
5. Reconnect after an upstream change; previous handshake capability decisions must not survive it.
6. Keep route checks and semantic evidence separate. Record failed/not-run checks and the exact
   verified upstream commit. This iteration does not certify every upstream feature or future version.

## Verification status and release boundaries

The investigation and opt-in handler test use isolated data, not production archive calls. Native
device, actual browser-to-production-Connector and multi-client physical-network acceptance remain
separate smoke checks in `SMOKE_TEST.md`. The new Android error dialog has localized codes, bounded
redacted details and a read-only Refresh state action; record-only Roborazzi tests cover Chinese
light, English dark and Chinese large text. No release version is allocated by this source change.

## Local implementation verification — 2026-11-15

- Android `:app:testDebugUnitTest :app:assembleDebug`: passed, 371 suites / 3,030 tests,
  zero failures/errors. L1 JVM/Compose/Roborazzi ran; all three dialog renders were inspected.
  L2 unavailable: no attached physical device. L3 available (Pixel_API_37), not run; no
  targetSdk-gated behavior changed. This build is not an APK distribution or a release.
- Web: typecheck, production build and all 105 test files / 1,084 tests passed.
- Root: build passed. Test suites passed: protocol 27, Connector 176, Gateway 237,
  release server 41, scripts 548; 33 existing environment-dependent tests skipped.
  The initial scripts baseline failed two archive-decryption cases under macOS LibreSSL 3.3.6;
  rerunning with the already installed Homebrew OpenSSL 3 on invocation-only PATH passed.
  No repository-wide tool path or unrelated recovery behavior was changed.
- Installed upstream validator: all 18 checks passed at
  `bf0203a794a07aac3ff764972ab9087baa4e4f4e`; upstream Git status stayed clean.
- First Android baseline caught two stale expectations (the RPC contract documentation row and
  the old generic archive error code). Both were updated; the full rerun above passed.
- Real-device behavior, actual browser-to-Gateway/Connector end-to-end behavior, live network
  interruptions and external-client concurrency still need the disposable-environment smoke
  checks. Database-backed Gateway integration tests require their configured test database.
- Source-only delivery in `codex/hg-257-archive-compat`; no push, PR, merge, version bump,
  production mutation, restart, installation or publication has been performed.

## Follow-up audit — three rounds, 2026-11-15

The user requested at most three rounds with fixes. The following are separate audit passes by
this implementation session, not claims of external human review or independently staffed agents.

| Round / perspective | Finding | Fix and evidence |
|---|---|---|
| 1 — lifecycle and write ordering | Web cancellation closed WS but did not abort REST; shared conflict handling could issue another archive write. README insertion split two existing paragraphs. | Internal AbortController reaches REST and fresh reads; profile-scoped revision reads check cancellation before writing. Both clients surface archive conflicts without automatic retry; rename/delete retain their existing behavior. Tests cover aborted revision read, sign-out during REST, and one-write conflict refusal; README paragraphs restored. |
| 2 — actual upstream failure semantics | Official RPC catches storage exceptions as 5007 even if the archive flag was already committed. Treating every nonzero RPC code as refusal skipped reconciliation. | Explicit validation/permission/conflict codes remain refusals; internal/storage/unknown RPC codes and HTTP 408 are ambiguous and only read state. Client tests cover 5007, -32603, -32000 and a future code; the real upstream fixture writes then raises and proves 5007 with a changed flag. |
| 3 — upgrade probe isolation and final consistency | Python file-open auditing does not cover SQLite connections opened by its native extension. | A sqlite3.connect audit guard permits only disposable-home databases or :memory:, parsing legitimate read-only file URIs before checking the canonical path. The negative guard check never actually opens an external store; all real upstream checks pass with the guard installed. |

Final verification: Android full unit-test/build baseline passed, 3,031 tests, no failures/errors;
Web all 105 files / 1,091 tests and typecheck/build passed. Focused regression suites also passed.
The isolated installed-upstream validator now passes all 20 checks at the same pinned source SHA;
upstream Git status is still clean. git diff --check passed. No Gateway/Connector/Protocol runtime
code changed during this audit, so their previously successful baseline remains the applicable
result. Web build retains its existing chunk-size advisory.

No additional audit round was started after round 3; subsequent runs only verified those repairs.
L1 ran; no attached physical device (L2), and available L3 was not run. Real-browser/production
end-to-end, physical network loss and external-client concurrency remain unverified. No push,
integration registration, merge, version allocation, APK handoff or publication was performed.
