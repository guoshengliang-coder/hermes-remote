# Account expansion E7 Desktop email-first test record

Date: 2026-09-08

Scope: local Desktop email-code login, scoped recent authentication, and selective phone revocation

Production changes: none

## Outcome

The local E7 Desktop account surface is email-first. Signed-out users request and exchange a six-digit
email code without a Google client configuration. A signed-in Desktop that removes another phone must
first verify the current account email and obtain a ten-minute, single-use
`account.installation.revoke` grant.

The native `DELETE /v2/installations/{installationId}` route is available only when identity
management is enabled. It requires a Desktop principal, a bounded JSON `grant`, and an
`Idempotency-Key`. The control service pins the operation to target kind `phone`; Desktop, browser,
current-installation, and foreign-account targets fail without changing them. The previous internal
phone-removal repository operation that did not require recent authentication has been removed.

Desktop opens a dedicated verification sheet, explains the selective effect, sends the code to the
signed-in account email, and enables “验证并移除” only for a six-digit input. Codes remain in view
memory. A scoped grant and the exact mutation/re-authentication idempotency keys may be kept only in
the account-session Keychain record while an ambiguous response is retried. A successful retry
replays the committed result even though the target and grant were consumed by the first request.
Definitive completion or a stale-grant response clears that recovery state.

`HR-ACCOUNT-009` is mapped to registered Chinese and English Desktop copy when identity management is
not advertised. Server messages and account credentials remain outside primary UI text and redacted
diagnostics.

## Automated evidence

- `npm run build` passes for Protocol, Gateway, and Connector.
- Full `npm test` with PostgreSQL 18.6 and network tests enabled passes: Protocol 13, Connector 17,
  Gateway 112, release server 30, and scripts 88 tests; zero failures and zero skips.
- The PostgreSQL control test proves non-phone rejection, cross-account isolation, one-time grant
  consumption, selective session/refresh revocation, and exact idempotent replay.
- Gateway HTTP tests prove the identity-management flag is default-off, a missing grant fails before
  repository mutation, and the bounded grant body is hashed and passed with required target kind
  `phone`.
- `npm run desktop:test` passes 104 tests. Coverage includes email challenge/exchange, capability
  fail-closed behavior, request path/header/body bounds, code-to-grant exchange, Keychain recovery,
  lost-response replay, bilingual error mapping, and preservation of the Connector machine identity.
- `npm run desktop:assets:test` passes.
- `npm run desktop:app` completes the release Swift build, app assembly, plist checks, and strict
  ad-hoc signature verification. The resulting local app is not a distributable release.
- `git diff --check` passes.

The disposable database listened only on local loopback, was stopped after the suite, and its exact
temporary directory was deleted. No production host, account, mailbox, DNS record, Connector,
Hermes process, Android device, or account rollout flag was touched.

## Remaining E7 release gates

- verify the sending domain and run live transactional-mail delivery, resend/cooldown, expiry,
  neutral account-existence behavior, bounce, and provider-failure acceptance;
- inspect the packaged email-only UI with keyboard/VoiceOver, light/dark mode, long content, and
  network recovery;
- run two-phone selective removal against the packaged Desktop and a non-production Gateway;
- complete Developer ID signing, notarization, stapling, and clean-Mac launch verification;
- run staging soak/rollback and obtain explicit production deployment authorization.

The feature remains default-off. This record is local implementation evidence, not production-release
or physical-device evidence.
