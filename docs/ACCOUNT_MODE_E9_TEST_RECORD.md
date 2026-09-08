# Account platform E9 account-lifecycle test record

Date: 2026-09-08

Scope: local default-off permanent Cloud-account deletion for Gateway, secure Web, Desktop, and Android

Production changes: none

Backup/restore limitation: the existing generic PostgreSQL restore manifest does not replay deletion
obligations newer than a restored backup and therefore cannot authorize enabling this feature. The
required older-backup recovery sequence is recorded in `ACCOUNT_DELETION_REVIEW.md`; implementation
and isolated evidence remain an explicit gate.

## Implemented boundary

- `ACCOUNT_DELETION_ENABLED=0` remains the default. The capability and native/Web deletion routes
  appear only when account authentication, identity management, and at least one provider are
  configured.
- The native and secure Web APIs require a current access token, a fresh single-use
  `account.delete` grant, a stable idempotency key, and the exact permanent-deletion acknowledgement.
- The first committed transaction fixes a 30-day deadline, changes the account to
  `pending_deletion`, revokes all account sessions, refresh credentials, installations, owned
  bindings, active grants, and pending invitations, and publishes live access invalidations.
- Sign-in, refresh, and old access tokens fail with the registered `HR-ACCOUNT-012` pending-deletion
  contract. A secure Web bootstrap clears stale cookies and shows a terminal deletion explanation.
- Invitations created by the account or addressed to one of its verified emails are cancelled.
  Only a keyed, non-displayable email fingerprint remains during the deletion period. Invitation
  creation and deletion serialize on that fingerprint, so an invitation cannot race through the
  deletion boundary or become usable by a later account created with the same address.
- Every unused email OTP for the deleting identity is invalidated in the deletion transaction. New
  requests keep the ordinary `202`, expiry, cooldown, and rolling-rate contract but create only a
  suppressed correlation row and never call the email provider; verification remains the generic
  `HR-AUTH-009` failure. This does not claim that provider mail already accepted or in flight can be
  recalled, but such a code is unusable after the deletion commit.
- At the exact deadline, the unified skip-locked retention worker removes account-linked rows,
  addressed invitations, OTP correlation rows, purpose-separated temporary email fingerprints, and
  cross-account audit rows containing the affected invitation/grant email hints. It shares the
  existing per-table batch ceiling and finalizes the account exactly once under concurrent sweepers.
- Final state retains only the non-PII account UUID/timestamps and one metadata-free
  `account.deleted` audit event. An account-free completion receipt contains only keyed hashes of
  the deletion idempotency key and exact request fingerprint; this permits deterministic retry from
  a Desktop that stayed offline beyond final cleanup without retaining account, email, bearer,
  grant, or response data.
- Web and Desktop require typed `DELETE` plus a separate acknowledgement. Desktop performs fresh
  email verification, persists the scoped grant and mutation key before network I/O, retries an
  ambiguous request after restart, clears only its account-management session after completion,
  and preserves the local Connector machine identity and all local Hermes data.
- Cross-client candidate copy uses “permanent deletion” consistently and reports a committed request
  as “deletion submitted” or “in progress” throughout the 30-day cleanup lane. Web and Android no
  longer claim that the account or Cloud data has already been erased immediately after commit. Web
  now replaces the destructive dialog with the submitted state immediately after the `204`, and
  Desktop returns the same terminal presentation after direct success or restart recovery instead of
  falling through to an ordinary sign-in screen. Web, Desktop, and Android then offer an explicit
  “use another email account” exit; taking it starts an empty session and cannot restore the old one.
- Android exposes the action only from Settings when the capability is on, requires the same typed
  acknowledgement plus a current-email code, and keeps the typed value/code in memory only. It
  encrypts the challenge, grant, and exact deletion replay key; an ambiguous response survives
  process death. Committed deletion clears account credentials, persists a terminal transport gate,
  blocks retained App Token fallback, preserves local Mac data, and offers only explicit new-account
  sign-in or Legacy compatibility exits.

## Automated evidence

- Full `npm test` passes with PostgreSQL 18.6 and loopback network tests enabled: Protocol,
  Connector, Gateway, release server, migrations, release/deployment tooling, and email-domain/
  staging suites all completed with zero failures or skips.
- The disposable-PostgreSQL deletion test proves immediate revocation, exact 30-day state, exact
  boundary behavior at deadline minus one millisecond and at the deadline, exact replay versus
  conflict, durable replay after final cleanup, blocked sign-in, addressed-invitation cancellation,
  unrelated-audit preservation, cross-account email-hint removal, bounded staged cleanup,
  one-winner concurrent finalization, and new-account creation after identity removal.
- A second disposable-PostgreSQL deletion case proves a previously delivered unused OTP becomes
  invalid, later requests preserve neutral cooldown behavior without another provider submission,
  suppressed verification stays generic, and the fixed 30-day sweep removes both OTP rows and their
  purpose-separated fingerprints. The direct service and HTTP regressions also prove the suppression
  state never appears in the public response.
- Two blocked-writer PostgreSQL races prove terminal cleanup takes the same ordered fingerprint locks
  as OTP and invitation creation: a correlation row committed by the already-running writer is then
  removed before the account reaches `deleted`, while the unrelated invitation owner's binding stays
  active.
- The disposable-PostgreSQL sharing test proves an email fingerprint under deletion cannot receive
  a new whole-device invitation.
- Focused Gateway account/Web/retention tests pass, including default-off routing, explicit Boolean
  acknowledgement, cookie clearing, `HR-ACCOUNT-012`, and aggregate-only retention metrics. The Web
  shell regression also requires an independent checkbox in addition to exact typed `DELETE`, reads
  that checkbox for the API acknowledgement instead of hard-coding consent, and clears both inputs
  whenever the destructive dialog opens or closes.
- The retention scheduler keeps the ordinary six-hour cadence when idle, but a saturated table
  budget, account-deletion progress, or account finalization schedules the next bounded transaction
  after one second. Its timer regression proves catch-up runs and then returns to the idle cadence,
  preventing a one-due-account-per-six-hour backlog without increasing the 1,000-row/table limit.
- `npm run desktop:test` passes 108 tests. Coverage includes the exact DELETE body/headers,
  capability gating, fresh email grant, lost-response retry, process-restart recovery, bilingual
  error mapping, Keychain state cleanup, and machine-identity preservation.
- `npm run desktop:assets:test`, `npm run desktop:app`, and `git diff --check` pass. The Desktop app
  was built locally in release mode and ad-hoc signed; it is not a distributable release.
- The complete Android JVM suite and `:app:assembleDebug` pass. Focused API/session/ViewModel/
  navigation/Compose coverage proves capability parsing and absence, exact reauthentication/deletion
  requests, durable replay state, typed-confirmation gates, success, lost-response retry, restart
  recovery, `HR-ACCOUNT-012`, invalid-code isolation, terminal startup routing, and explicit-only
  Legacy fallback. `:app:compileDebugAndroidTestKotlin` also passes for the encrypted-store cases;
  those tests were not executed because this Mac has no Android Emulator component configured.
- `:app:recordRoborazziDebug --tests com.hermes.client.ui.account.AccountDevicesScreenTest` passes.
  The capability-on danger zone and extracted production confirmation content were inspected in dark
  mode at 1.3× font, and the committed terminal state in light mode; none clipped or obscured its
  action. The acknowledgement is one full-row checkbox semantic target. The platform dialog
  container, focus traversal, keyboard, and TalkBack remain part of the emulator/physical gate below.

The first focused Web run showed that the stale-cookie fixture omitted the access cookie needed to
reach the pending-deletion account branch; the fixture now models the real request. The first Swift
run exposed an async XCTest expression inside a non-concurrent autoclosure; the result is now awaited
before assertion. Privacy review then found that an invitation addressed to a deleting email and the
sender's audit hint could outlive the account relationship. The temporary fingerprint, serialized
invitation rejection, cross-account audit cleanup, and real-database regressions close that gap.
The follow-up OTP audit found that a deleting email could otherwise request fresh codes and leave
correlation rows past its deletion deadline. Purpose-separated fingerprints, transactional challenge
invalidation, neutral delivery suppression, shared ordered cleanup locks, and deletion-lane cleanup
close that path.

The final cross-client audit found one additional Web-only confirmation gap: the shell required typed
`DELETE` but did not expose the independently required permanence checkbox even though the API body
always sent `true`. The Web form, browser-state reset, API acknowledgement, and regression now use the
real checkbox state. The same audit added the missing pre-deadline database assertion, proving that
Cloud PII remains in the protected deletion lane until the exact fixed deadline rather than being
eligible one millisecond early. The retention review then found that the ordinary six-hour scheduler
could otherwise process only one due account per interval; bounded one-second catch-up now drains
both deletion and saturated-table backlog.

## Cross-client completion audit

| Contract boundary | Gateway / Web evidence | Desktop evidence | Android evidence |
| --- | --- | --- | --- |
| Default-off exposure | Runtime prerequisite and 404-route tests; Web danger zone hidden | Capability-gated dashboard | Capability parser plus absent-entry Compose test |
| Irreversible confirmation | Exact Boolean API validation; typed `DELETE` and independent Web checkbox | Typed `DELETE`, checkbox, fresh current-email code | Typed `DELETE`, full-row checkbox, fresh current-email code |
| Ambiguous-response recovery | Transaction replay plus account-free completion receipt | Keychain grant and stable mutation key survive restart | Encrypted grant and stable mutation key survive process death |
| Immediate access revocation | PostgreSQL session/installation/binding/share assertions and committed revocation bus tests | Account session removed; Connector machine identity preserved | Account transport/inbox/WebSocket stopped; Legacy remains explicit-only |
| Fixed cleanup and privacy | Deadline −1 ms / deadline assertions, bounded dependency cleanup, writer races, metadata-free tombstone | Copy states immediate logout, 30-day Cloud cleanup, local Mac preservation | Copy and terminal gate state the same boundary and preserve local Mac data |
| Already-pending recovery | `HR-ACCOUNT-012`, blocked sign-in/refresh, stale Web-cookie clearing | Stable bilingual mapping | `HR-ACCOUNT-012` completes local cleanup and persists terminal routing |

## Remaining E9 release gates

- Complete and sign `ACCOUNT_DELETION_REVIEW.md`: product/privacy/support approval for the permanent,
  non-recoverable Cloud-deletion copy, 30-day schedule, tombstone classification, backup behavior,
  and retained keyed completion receipt.
- Inspect the packaged Web/Desktop confirmation flow with keyboard and VoiceOver, light/dark mode,
  enlarged text, network loss, process restart, and a disposable real account.
- Run staging with live email delivery, multiple accounts, two Macs, two phones, owned/shared-device
  revocation, Gateway restart, and a forced due-deletion sweep; verify backups and aggregate metrics.
- Inspect the Android confirmation dialog on an emulator and physical phone in Chinese/English,
  light/dark mode, 1.3× font, TalkBack, offline retry, process restart, and a disposable real account.
- Complete Developer ID signing/notarization and authorize staging/production deployment separately.

No production host, account, mailbox, DNS record, Connector, Hermes process, Android device, rollout
flag, version number, or published artifact was changed by this record. The ordinary debug build was
not handed off as an APK release.
