# Hermes Go Desktop design contract

This document adapts the Android contract in `DESIGN.md` to the native macOS companion. Shared brand
decisions remain authoritative; macOS-specific navigation and controls follow native platform
conventions.

## Product form

- A menu-bar utility with a reopenable main window.
- Closing the main window does not stop the Connector.
- The main window uses a fixed desktop sidebar: Overview, Diagnostics, Logs, Account & Devices,
  Settings. Legacy pairing is nested inside Account & Devices.
- Phase 0 is visibly labeled **compatibility observation mode** so it cannot be mistaken for Agent
  takeover.
- Pairing uses a two-column layout: an explicitly revealed QR card and a manual connection editor.
- A saved long-lived App Token is masked in fields and the QR stays hidden by default. Revealing it
  always keeps the nearby-person warning visible.

The historical I3-A alpha implements the account-first navigation: **Phone Pairing** is now **Account & Devices**,
Google login is available only when both build configuration and Gateway capabilities allow it,
and QR/manual Token configuration lives in a collapsed **Advanced: Legacy connection** card. The
legacy editor and QR remain fully functional. Account binding takeover, replacement, and unbind are
not active in I3-A, so the current Connector remains authoritative until the later migration gate.
See `ACCOUNT_MODE_DESIGN.md`.

The default-off E4-D managed setup surface sits directly beneath the account/binding summary. Existing
Connector installs and unknown Hermes responders remain read-only. Only a clean preflight with matching
packaged and Gateway runtime contracts shows “下载并验证安装包”. That first action may use network and
private cache space but cannot change an installation or process. After all signature, artifact,
checksum, and archive checks pass, a native sheet names the exact release and explains the managed
Hermes Server, Connector, two user LaunchAgents, account binding, and brief restart effects. Only its
explicit “安装并连接” action may enter the machine-changing phase. Commit/recovery disables dismissal
and duplicate installation. A successful install with pending temporary cleanup shows only “重试清理”
and `HR-MIGRATE-005`; it never suggests reinstalling.

For the email-first release, the signed-out Desktop surface must use email plus a six-digit one-time
code as its only login action. It must not show or attempt Google when the Gateway advertises only
`email_otp`. The existing browser OAuth UI remains future-provider code and is not part of the first
release acceptance surface.

The future Google action uses the system default browser. Existing browser Google sessions appear in
Google's own account chooser, allowing direct authorization without re-entering credentials while
keeping browser cookies and profile data outside Hermes Go Desktop.

Implemented account states are capability checking/unavailable, signed out, email-code verification,
signed in with no binding, bound healthy/offline, pending/replacement/revoked, and session-needs-login.
The normal signed-in view shows the account, current Desktop, one binding, and independently removable
phones. Account errors appear with stable `HR-*` codes and copyable redacted diagnostics.

Removing another phone opens a dedicated sheet rather than immediately mutating the account. The
sheet names the target, explains that only that phone's account login is revoked, sends a six-digit
code to the signed-in account email, and enables the destructive “验证并移除” action only for a
six-digit input. The code remains in view memory and clears on dismissal or success. A short-lived
`account.installation.revoke` grant and the mutation's idempotency key may remain only in the
account-session Keychain record while an ambiguous response is retried; they are cleared after a
definitive completion or stale-grant response.

Permanent Cloud-account deletion is shown only when the Gateway advertises `accountDeletion`. It is
visually separated as a danger-zone action and requires all three signals: typed `DELETE`, an
explicit permanence checkbox, and a fresh six-digit code sent to the current email identity. The
sheet must state that Cloud access and sharing stop immediately, Cloud PII is cleaned after 30 days,
the old account cannot be restored, and local Mac Hermes data is untouched. A pending or completed
request must be described as deletion “submitted” or “in progress” until the 30-day cleanup finishes;
client copy must not claim that Cloud data has already been erased. A pending
`account.delete` grant and exact mutation idempotency key may remain in the account-session Keychain
record solely to recover an ambiguous response; the six-digit code and typed confirmation remain in
view memory. Success removes the Desktop account session but preserves the Connector machine identity
and every local Hermes file/configuration. The current process lands on a dedicated “云端账号删除已提交”
status card with the immediate access-stop, 30-day cleanup, and local-data boundary; it must not fall
straight through to the ordinary sign-in card. An explicit “使用其他邮箱账号” action moves from that
terminal receipt to the empty sign-in state; it does not restore the deleted account. Because no
account credential remains, a later fresh launch starts signed out.

When the E5 sharing capability is present, the device list labels owned and “他人共享” Macs separately
and reports the independent 3-owned/10-shared limits. An owner gets a per-device share action; an
operator gets only “退出共享”. The sharing card displays masked pending/active recipients and the
five-grantee limit. Create and accept controls stay disabled until the user explicitly acknowledges
that this is whole-Hermes access, including sessions, files, and configuration metadata. Destructive
revoke/leave actions use native confirmation dialogs and explain which active access is terminated.
Invitation tokens are paste-only, never copied into diagnostics, and cleared after success.
Before an owner sends an invitation, the sheet locks the recipient and whole-device acknowledgement,
sends a fresh six-digit code to the owner's signed-in email, and accepts the invitation mutation only
after scoped verification. The code remains in view memory and clears after success or dismissal.

## Shared visual tokens

- Brand primary: `#0B5FD0` in light mode; blue is chrome/action color only.
- Light canvas: `#F7F9FD`; light card: `#FAFBFD`; hairline: `#EBEDF2`.
- Dark canvas: `#111820`; dark card: `#1A212A`; hairline: `#333A44`.
- Healthy, degraded, and failed use independent green, amber, and red semantic colors.
- Cards rely on a one-step surface difference, a 1 px hairline, and a restrained light-mode shadow.
- Controls use continuous 9–18 px radii and an 8 px spacing rhythm.
- Sidebar symbols use regular-weight SF Symbols at native macOS sizes. The product mark itself never
  uses a generated or substituted symbol.

## App icon decision

The only app-icon source of truth is
`android/app/src/main/ic_launcher-playstore.png`. The macOS app, DMG, sidebar identity, menu-bar
identity, About page, and QR center mark use that artwork without redrawing or recoloring it.
`desktop/Packaging/AppIcon.png` is a synchronized packaging copy, and the packaging gate fails if it
drifts from the canonical source.

## Status and error language

- Every layer keeps its own state: Desktop Agent, Gateway, local Hermes, optional observer, end to end.
- An optional observer failure may degrade the product but cannot mark a working main path offline.
- “Cannot reach from this Mac” must not be rewritten as “Hermes is down.”
- New user-visible errors must be registered in `ERROR_HANDLING.md` before implementation.
- Technical details and logs are secondary, selectable, and redacted before presentation.
- Current reachability and historical log warnings are separate. Old warning lines may be counted and
  shown as history, but cannot by themselves mark a currently healthy connection offline.
- End-to-end failures show a registered `HR-*` code and recovery action; raw HTTP bodies and tokens
  never become primary UI text.

## Concept references

- [Overview and menu bar](design/desktop/overview.png)
- [Phone pairing](design/desktop/pairing.png)
- [Diagnostics and logs](design/desktop/diagnostics.png)
- [Phase-0 SwiftUI implementation](design/desktop/implementation-phase0.png)

These are direction references, not pixel specifications. Generated placeholder QR codes are not
functional, and generated approximations of the H mark must be replaced by the canonical app icon.
