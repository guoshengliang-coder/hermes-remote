# Hermes Go Desktop design contract

This document adapts the Android contract in `DESIGN.md` to the native macOS companion. Shared brand
decisions remain authoritative; macOS-specific navigation and controls follow native platform
conventions.

## Product form

- A standard macOS app with its canonical icon visible in the Dock while it runs, plus a menu-bar
  utility with a reopenable main window.
- Closing the main window does not stop the Connector.
- Users can choose Dock's native “Keep in Dock” option if they want the app to remain there after it
  quits; the app does not change personal Dock preferences automatically.
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

For an active managed installation with a strictly newer signed target, the card changes to “可升级到
Hermes Go …” and the preparation action reads “下载并验证更新”. The confirmation action reads
“升级并重连” and states that the current account, device binding and local data are preserved, that
both services briefly restart, and that failed validation restores the old version. Same-version and
downgrade targets remain in the connected read-only state.

The componentized v2 preflight uses a separate native card before the machine-changing confirmation.
Its header names the target release. Three compact pills show direct reuse count, bootstrap download,
and deferred first-use download; the deferred pill is omitted when its byte total is zero. Every
component row has one unambiguous state: managed reuse, compatible system reuse, install-time download,
or on-demand download. Sizes use stable `KiB`/`MiB`/`GiB` units, and the footer always states that this
read-only scan does not modify Homebrew, the user's environment, or running services. The card has no
install action until a production v2 capability and signing configuration make the result actionable.
The packaged feature remains visually absent and performs no network or filesystem scan while its
default-off configuration gate is closed. With a complete development configuration, Desktop loads the
signed result after account bootstrap and again from the existing Refresh action only when Gateway also
advertises component manifest schema 2 and the exact `hermes-serve-v1` runtime contract. An absent or
future schema, runtime mismatch, failed download, signature check, or local scan stays fail-closed,
performs no component-manifest request, and does not expose a machine-changing fallback.
A successful refresh keeps the verifier-issued installation token only in model memory beside that
exact displayed result; the card itself still receives presentation data only and cannot manufacture
an installation request.
When the schema-v2 rollout gates are enabled and the existing-service preflight is also safe, the card
adds “下载缺失组件”. Its progress copy states that services are unchanged during preparation. A second
native sheet names the signed version, describes the managed component directory, two user LaunchAgents,
account binding, and brief service switch, and requires “安装并连接”. While either schema-v1 or v2
operation is preparing, awaiting confirmation, committing, recovering, or awaiting required cleanup,
the other installer, account mutations, and manual refresh remain disabled. A failed cleanup offers
only “重试清理”; successful migration with cleanup pending must not offer installation again.
Once Gateway selects schema 2 for a locally configured Desktop, a v2 trust/download/scan failure shows
the structured migration error and Refresh recovery; the schema-v1 download button must not silently
reappear as a fallback.

For the email-first release, the signed-out Desktop surface must use email plus a six-digit one-time
code as its only login action. It must not show or attempt Google when the Gateway advertises only
`email_otp`. The existing browser OAuth UI remains future-provider code and is not part of the first
release acceptance surface.

The future Google action uses the system default browser. Existing browser Google sessions appear in
Google's own account chooser, allowing direct authorization without re-entering credentials while
keeping browser cookies and profile data outside Hermes Go Desktop.

Implemented account states are capability checking/unavailable, signed out, email-code verification,
signed in with no binding, bound healthy/offline, pending/replacement/revoked, and session-needs-login.
The revoked card explains that a locally proven, safely rolled-back migration may be retried without a
new login; all other revoked bindings still require identity verification and replacement.
The same recovery rule applies when Cloud already committed the exact binding before the local machine
returned to a terminal rollback state: Desktop may resume only when the terminal journal's binding ID
and generation match the active Cloud binding and the active fingerprint matches this Mac's retained
machine key. This is recovery of the same binding, not replacement, so it does not request a second
replacement confirmation. Any ID, generation, fingerprint, or journal-state mismatch remains blocked
with the existing binding-conflict path.
The normal signed-in view shows the account, current Desktop, one binding, and independently removable
phones. Account errors appear with stable `HR-*` codes and copyable redacted diagnostics.
The signed-in dashboard follows the capability snapshot field by field: the account summary remains available after
email-only authentication, while phone installations and Connector binding are loaded only when their respective
identity-management and binding capabilities are enabled. A disabled optional surface is shown as empty/unavailable;
its absent route must not turn a successful email exchange into an account-service error.

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
drifts from the canonical source. The packaged app declares `CFBundleIconFile=AppIcon` and is not an
`LSUIElement` agent, so the running app is represented by this icon in the Dock.

### Menu-bar status glyph

The menu-bar extra is an exception to the full-color identity treatment: it uses the native
monochrome `h.circle` template glyph at 15 pt, rather than the square app artwork. This keeps one
small system-tinted (gray in an inactive/light menu bar) indicator aligned with neighboring macOS
status icons, avoids a white app-icon tile, and remains legible in both appearance modes. The glyph
opens the same Hermes Go Desktop menu; it is a status affordance, not a replacement app icon.

## Status and error language

- Every layer keeps its own state: Desktop Agent, Gateway, local Hermes, optional observer, end to end.
- The Desktop Agent layer represents the effective connector mode. An exact active managed install
  supersedes the stopped legacy label instead of producing a false failure. If the managed services
  survive a Migration Assistant transfer but this-device-only account credentials do not, the layer
  stays visible as running-but-unverified and asks for sign-in; it never starts a duplicate Connector.
- An optional observer failure may degrade the product but cannot mark a working main path offline.
- “Cannot reach from this Mac” must not be rewritten as “Hermes is down.”
- New user-visible errors must be registered in `ERROR_HANDLING.md` before implementation.
- Startup repair cards preserve the severity of the failed boundary. Only a failure to prove a
  single Connector topology may replace the managed upgrade action with a blocking
  `HR-MIGRATE-002`. Token-storage and search-path repair failures use retryable `HR-MIGRATE-007`
  beside the still-actionable upgrade; an advisory repair must never masquerade as a Connector
  conflict.
- Which Hermes the managed service runs is its own card, beside the schema-drift card and never
  folded into the bootstrap state. A Mac whose own Hermes cannot be used reads non-retryable
  `HR-MIGRATE-008` and says that nothing was changed *to avoid a second copy* — the product must
  never imply that installing its own Hermes is the fix. A failed switch or restart reads
  `HR-MIGRATE-009`, says what was restored, and retries by itself; it offers no button that would
  restart Hermes on demand. A Mac left with no Hermes to run at all reads non-retryable
  `HR-MIGRATE-010`, which says to reinstall rather than promising a retry. With the setting off the
  card never appears unless the Mac is actually in local mode. A successful switch or restart is silent: the Hermes row keeps reporting
  reachability, not which codebase answered.
- Technical details and logs are secondary, selectable, and redacted before presentation.
- Current reachability and historical log warnings are separate. Old warning lines may be counted and
  shown as history, but cannot by themselves mark a currently healthy connection offline.
- End-to-end failures show a registered `HR-*` code and recovery action; raw HTTP bodies and tokens
  never become primary UI text.
- While a Hermes GO account session is signed in, Overview, Diagnostics, and aggregate health omit
  the legacy App-Token end-to-end probe. The probe remains scoped to the collapsed legacy editor and
  must not make a healthy account-mode connection appear degraded or unconfigured.
- In account mode, Overview names the Desktop-selected Mac and projects its Connector, Gateway,
  Hermes, and end-to-end state from the authenticated device snapshot. A saved legacy profile must
  not rename that device or turn a healthy account Gateway into an unconfigured failure. Switching
  devices changes only this presentation and future content; it never changes a local service.
- A managed local Hermes WebSocket is authenticated with one private installation token shared by
  file path between Hermes and Connector. The value is never rendered, copied into diagnostics, or
  sent to Gateway; a missing or unsafe file leaves the path offline rather than weakening auth.

## Concept references

- [Overview and menu bar](design/desktop/overview.png)
- [Phone pairing](design/desktop/pairing.png)
- [Diagnostics and logs](design/desktop/diagnostics.png)
- [Phase-0 SwiftUI implementation](design/desktop/implementation-phase0.png)

These are direction references, not pixel specifications. Generated placeholder QR codes are not
functional, and generated approximations of the H mark must be replaced by the canonical app icon.
