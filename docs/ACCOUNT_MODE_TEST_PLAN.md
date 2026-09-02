# Hermes GO account-mode test plan

Status: I0 test-impact and release-gate contract with the local I1 backend gate passed. Tests are
added with each implementation iteration; this document records the required coverage before
account mode becomes the default.

Current automated I1 evidence lives in `gateway/src/account-mode.test.ts` and
`gateway/src/account-database.integration.test.ts`. On 2026-09-02 the latter passed against an
isolated temporary PostgreSQL 18 instance, including database transaction/race/restart cases.
PostgreSQL is intentionally not bundled in the repository, and account mode remains disabled by
default.

`gateway/src/account-database.integration.test.ts` activates when
`ACCOUNT_TEST_DATABASE_URL` points to a disposable PostgreSQL database. It creates and removes an
isolated random schema and exercises migration, restart persistence, concurrent refresh rotation,
reuse detection, family revocation, changed display email without account reassignment, scoped
reauthentication, current-session sign-out, account-wide session revocation, idempotent lost-response
replay, and a persisted-data secret scan. It must never target a production database.

The latest command-level evidence and explicit skipped gate are recorded in
`ACCOUNT_MODE_I1_TEST_RECORD.md`.

The implemented I2 control-plane slices are covered by `gateway/src/account-control.test.ts` and
`gateway/src/account-control-database.integration.test.ts`. It exercises a real Ed25519
challenge, the one-pending/one-active database constraints, concurrent Desktop contenders,
proof/health activation gates, scoped reauthentication, atomic replacement, replacement expiry,
old-generation rejection, explicit unbind, Desktop-only installation management, current-phone
self-revocation, two-phone isolation, and cross-account denial. Current evidence and the explicitly remaining I2 work are recorded in
`ACCOUNT_MODE_I2_TEST_RECORD.md`.

`gateway/src/account-routing.integration.test.ts` adds a real Gateway/PostgreSQL/WebSocket path. It
proves Ed25519 V2 handshake and preflight, REST and WebSocket forwarding, health/offline persistence,
dual-credential rejection, account A/B denial, unauthenticated socket capacity, and separation from
a simultaneously connected legacy Connector using the same public `deviceId`. The same test sends a
real account lifecycle event twice, verifies durable deduplication, gives two phones independent
delivery/read receipts, and proves another account cannot read or mutate either receipt.

## 1. Test layers

| Layer | Primary coverage | Required command/evidence |
| --- | --- | --- |
| Protocol | V2 auth/control parsers, bounds, canonical challenge bytes, compatibility | `npm test -w @hermes-remote/protocol` |
| Gateway/account | Google verification boundary, tokens, DB transactions, isolation, routes | Gateway unit/integration suite with disposable database |
| Connector | Challenge proof, generation/revocation, reconnect, migration recovery | Connector tests plus local smoke stack |
| Desktop core/UI | OAuth coordinator, Keychain, states, destructive confirmations, migration | Desktop test/app build and visual/accessibility inspection |
| Android data/UI | Credential exchange, encrypted sessions, startup states, Remote device route | JVM/unit/navigation/screenshot tests and debug build |
| Compatibility | Old Android/Connector against dual-mode Gateway | Versioned smoke clients and current baseline suites |
| Physical E2E | Mac mini, two phones, second Mac, restarts/outages | Timestamped test record without secrets |
| Release | APK identity/signature/hash; DMG codesign/notary when applicable | Existing package gates and clean-install evidence |

The normal repository baselines in `AGENTS.md` remain mandatory. Account tests are additive.

## 2. Contract fixtures

I1/I2 add sanitized deterministic fixtures for:

- valid Android and macOS provider claim shapes after signature verification;
- wrong issuer/audience/nonce, expired/missing subject, and email-change cases;
- access/refresh success, rotation, reuse, expiry, and revocation envelopes;
- no binding, healthy binding, offline Connector, Hermes unavailable, revoked generation, and
  replacement-pending responses;
- phone list with two installations and escaped/untrusted display names;
- every new structured `HR-AUTH-*`, `HR-BIND-*`, and `HR-MIGRATE-*` error;
- Connector challenge/authenticate/ready messages and canonical signed bytes;
- capability combinations for legacy-only, dual-mode, allowlisted account mode, and account default.

Fixtures contain only reserved example domains, fake UUIDs, fake keys, and impossible test tokens.

## 3. Authentication and session cases

- accept valid proof only for the registered platform audience;
- reject invalid signature, issuer, audience, expiry, nonce, and missing subject;
- create one account for the same provider issuer+subject across Desktop/Android;
- never join accounts by equal email or display name;
- rotate access/refresh pairs and reject old refresh reuse;
- replay lost Google-exchange, refresh, and reauthentication responses only for the exact same
  account/session/request/`Idempotency-Key`; reject key reuse with changed input;
- replay lost sign-out and revoke-all `204` results for the exact same account/session/request/key
  even though the first request revoked the calling credential;
- revoke only current session on normal sign-out;
- revoke all sessions for one phone when that installation is removed;
- require recent reauth and correct scope for replace/unbind/revoke-all;
- rate-limit exchange/refresh without leaking account existence;
- survive service/database restart with token state intact;
- redact all token classes and provider proof from responses/logs/metrics/traces.

## 4. Binding, routing, and multiple phones

- first Desktop binding remains pending until key-possession/health proof and succeeds exactly once;
- two concurrent bind transactions yield one active binding;
- second Desktop receives conflict and cannot change the active binding without confirmation;
- expired/used/wrong-account replacement requests fail without modifying the old binding;
- successful replacement invalidates the old key generation atomically;
- replacement same-key retries replay the committed result while a second confirmation is rejected;
- unhealthy and expired replacement candidates leave the prior generation active;
- unbind cancels a live replacement candidate, revokes only Connector credentials, and leaves
  account sessions intact;
- account A cannot route to or observe account B even with guessed IDs/device names;
- phone A and phone B share the same Hermes data path;
- revoking/signing out phone A leaves phone B and Connector online, including a lost-response retry
  after phone A's access has already been revoked;
- request/tunnel replies return only to their exact owner under concurrent traffic;
- phone lifecycle delivery cursors and notification acknowledgements are independent;
- local visual read/unread state is not cleared by another phone;
- an account request containing both legacy and account credentials is rejected;
- old App/Connector Token behavior remains unchanged while dual mode is enabled.

## 5. Desktop cases

- OAuth system-browser account chooser with zero/one/multiple signed-in browser accounts, success,
  explicit account switch, user cancel, timeout, state mismatch, nonce mismatch, loopback bind
  failure, duplicate callback, and app restart; verify the app never reads browser cookies/profile;
- account session and Connector key remain separate in Keychain and diagnostics;
- account management sign-out does not masquerade as Connector unbind;
- unbind and replacement require explicit confirmation/recent reauth;
- phone list displays active/last-seen/revoked state and removes only the selected phone;
- account/Connector/Gateway/Hermes/end-to-end states remain distinct;
- I3 never changes legacy Connector PID, launch count, configuration, or Hermes;
- dark/light mode, keyboard navigation, VoiceOver labels, long names, and network recovery;
- packaging continues to use the canonical app icon.

## 6. Android cases

- fresh-install Google sign-in and existing-install opt-in migration;
- Credential Manager authorized-account-first behavior, one-credential auto-select, multiple-account
  chooser, no-authorized-account fallback to all on-device Google accounts, and add/switch account;
- no-Desktop state automatically becomes connected after Desktop binds;
- account session expiry asks for login without deleting preferences/history navigation;
- healthy account is visible only in Settings, not Sessions/chat/card page;
- existing Hermes identity selection remains unchanged;
- Remote device stat is clickable and opens the one Hermes detail;
- detail renders healthy, Connector offline, Hermes unavailable, Gateway unavailable, and revoked
  binding separately;
- no duplicate Connection & devices Settings entry exists;
- Legacy connection is reachable from Remote device details during compatibility;
- “Sign out on this phone” leaves another physical phone working;
- process death, rotation, background/foreground, offline start, refresh race, and clock skew;
- TalkBack, 48dp targets, Chinese/English, large font, dark mode, and screenshots.

## 7. Migration fault-injection matrix

Inject failure or termination before and after each durable transition:

| Boundary | Expected recovery |
| --- | --- |
| Before snapshot | No mutation; legacy remains active |
| After snapshot, before staging | Remove/retain bounded snapshot per policy; legacy active |
| After key staging | Cancel pending key; legacy active |
| After legacy stop | Automatic rollback restarts exact legacy service |
| Candidate process start failure | Stop candidate remnants; restore legacy |
| Connector proof failure | Revoke/cancel pending generation; restore legacy |
| Local Hermes probe failure | Restore legacy; do not touch Hermes |
| End-to-end account probe failure | Restore legacy |
| Immediately before remote commit | Restore legacy; old binding authoritative |
| During/after remote commit response loss | Query generation; complete new mode or stop for attention, never guess |
| New generation reconnect failure after commit | Show account Connector offline; explicit post-commit rollback only |
| Rollback launch failure | Enter attention-required state; no retry loop/second Connector |

For every row verify:

- exactly one or zero intentionally stopped Connector processes, never two serving instances;
- no Hermes source/config/data/credential change;
- no secret in migration state/log/diagnostic output;
- deterministic restart recovery from the durable state;
- the phone receives a layer-accurate status.

## 8. Physical acceptance run

Hardware:

- target Apple Silicon Mac mini with current Hermes and legacy Connector;
- phone A and phone B with independent Android installations;
- a second Mac or isolated Desktop installation for replacement testing.

Sequence:

1. Record baseline Connector PID/launch count, Gateway device count, Hermes health/version, and
   legacy Android access.
2. Upgrade Desktop without migration; confirm every baseline remains unchanged.
3. Sign into Desktop and phone A with the same account; bind/migrate only after the explicit test gate.
4. Verify sessions, streaming, files, profiles, lifecycle notifications, and restart recovery.
5. Add phone B; run concurrent independent requests and notification/read-state checks.
6. Revoke/sign out phone A; verify phone B and Desktop continue.
7. Exercise second-Mac cancelled/failed/successful replacement.
8. Exercise Gateway/account/provider/network outages and all device restarts.
9. Run explicit post-commit rollback, verify legacy clients, then migrate forward again.
10. Inspect logs/diagnostics/storage for canary secrets and personal content.

The record includes timestamps, versions, hashes, status codes, safe correlation IDs, and pass/fail;
it never contains real tokens, provider proofs, Cookies, passwords, private keys, or message content.

## 9. Release gates

- **R0 Contract:** API, data, threat, error, migration, and test contracts reviewed.
- **R1 Backend:** auth/binding isolation, token lifecycle, DB restart, and compatibility tests pass.
- **R2 Client alpha:** Desktop and Android state/navigation/security tests pass against dev backend.
- **R3 Migration:** every fault-injection boundary returns to a known state; exactly-one-Connector
  invariant passes.
- **R4 Physical:** Mac mini + two-phone + second-Mac acceptance passes.
- **R5 Artifact:** versioned APK and Desktop package gates pass; docs and rollback are current.
- **R6 Production:** separately authorized staged enablement succeeds with monitored rollback signals.

Any cross-account access, credential leak, ambiguous binding, unrecoverable migration state, Hermes
mutation, or legacy regression is a release blocker regardless of other passing tests.
