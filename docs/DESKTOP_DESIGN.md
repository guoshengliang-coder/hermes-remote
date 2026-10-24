# Hermes Go Desktop design contract

## HG-199: separate Hermes version card

The explicit-root external dependency material store is backend-only. Saving/exporting selected bytes
must never appear as “dependencies recoverable” or completed upgrade; full root/ABI/OS proof and
transaction custody are separate. Export creates a new directory, never overwrites the live home/tool
root or later files, rewrites a venv reference or launches a program. Failures retain existing
`HR-HERMESUPDATE-002/003` mapping without raw paths/errors. No new controls or positive status are added.

The “本机 Hermes” page owns the “版本与升级” card below its authenticated usability card, using
existing setting rows, card spacing and native bordered small controls. Settings owns Desktop
app/background updates only; its unified update action does not also check upstream Hermes.
The two Hermes read-only checks remain separate and are disabled during Desktop installation/update
operations. No upgrade/recovery execution button is introduced by this relocation. “安装版本” and “代码提交” describe disk evidence;
they must not imply a checked boot version. “上游稳定版本” and “GO 验证推荐” are separate rows. Unknown
recommendation reads “尚无验证结果”; unverified latest explains that execution is unavailable.
“检查 Hermes 版本” remains available without configured GO update indexes. During a request it reads
“正在检查…” and is disabled. Failed checks clear earlier success and show `HR-HERMESUPDATE-001`
through GateIssueBanner, keeping raw technical causes behind details. No upgrade confirmation or
execution button is exposed until the official adapter/recovery certification in stage two.

After a successful version check, “检查升级条件” uses the same small bordered control. It adds
“本地修改”, “升级恢复”, and “其他更新” rows; dirty files are explained without printing filenames or
private content. This check does not stop services or modify data and does not certify execution.
An unfinished/unsafe upgrade journal keeps `HR-HERMESUPDATE-003` visible after another version check
and pauses automatic recovery, including when the local-runtime setting is off. Execution/confirmation
and actual recovery controls still require a certified concrete adapter; they are not exposed yet.
The packaged execution worker adds no visible action until that certification. A target already moved
on disk requires reconciliation of its retained official receipt/tail; the product must not label a
fresh retry or an exit-zero receipt with owed work as a completed upgrade.
The backend-only finish resume accepts a successful PM handoff with a durable ready checkpoint and
an unfinished original receipt; any existing official archive must agree and have no finish effects.
Missing/unsafe checkpoints, advanced/terminal receipts and service
debts keep the registered recovery-required error (`HR-HERMESUPDATE-003`); no new recovery action
or automatic retry is exposed until the full machine adapter is certified.
Standard blob:none metadata alone is not an error when the fixed current source is locally whole.
Missing source objects keep recovery-required guidance; checking must not trigger background Git
downloads. This backend change adds no recovery control and does not relabel service debt as success.
Displayed completion requires the receipt's version and fixed commit to agree; a tagless checkout
whose official version stamp says unknown must remain pending even if its dependency sync succeeded.
Maintenance admission/service restoration remains backend-only until full certification. It adds no
execution control. A maintenance refusal uses bilingual `HR-HERMESUPDATE-005` in the existing error
surface and directs the owner to Mac progress/recovery, with no immediate Retry. Private mailbox,
process and plist details stay behind diagnostics. Existing admitted work is allowed to finish.

The backend Machine Operating composition adds no UI controls or certified paths. Both target
verification and pre-source-change cancellation reopen admission only after independent local
source/dependency/boot/original-load-set proof, then require fresh business checks before a terminal
label. A failed business check re-pauses the same run with `HR-HERMESUPDATE-003`; later history is
retained. “Cancelled” is not displayed before original-installation business verification succeeds.
Live evidence collection and real-machine acceptance remain required before any execution control.

The existing “检查升级条件” action now collects native read-only process/profile/interpreter
observations on its background task, with no extra rows or controls. Raw command lines, environment
values and private interpreter paths are not retained in its report. Incomplete observations use
existing bilingual `HR-HERMESUPDATE-002`; pending transaction failures retain `003`. An empty process
sample must never become “tasks stopped”, “safe to upgrade” or “backup recoverable”. Execution and
recovery controls stay disabled until independently fenced live evidence is certified.

The same action now includes a bounded authenticated serve activity diagnostic without new rows or
controls. Busy/unknown, stale or mismatched PID/kernel birth/boot source uses `HR-HERMESUPDATE-002`;
pending recovery keeps `003`. “Idle observed” describes a moment in one serve process; it must never
be rendered as all profiles stopped or permission to upgrade. Missing old-version identity fields
remain unknown. Session/job IDs and raw diagnostic responses are discarded; the product check never
prepares or commits upstream retirement.

## Overview visual hierarchy — 2026-10-24

Keep the three vertical Cloud / local Desktop / local Hermes sections. The headline is one impact
statement with a semantic status icon; remove the duplicate status-link boxes. Every section follows
icon + name + state, key evidence, divider + actions, and a collapsed evidence detail. Cloud emphasizes
only the fresh direct-probe latency; Desktop uses three independently colored marks for background,
cloud connection and mobile verification. Unknown, stale, future, signed-out and busy evidence stays
neutral. A healthy card cannot paint an unverified individual check green.

Normal-state explanation and actual check times move into expandable details; failures and specific
repair guidance remain visible. Healthy reconnect moves inside connection details and retains the
existing preparation/confirmation flow. Keep refresh, updates, diagnostics, Hermes open and mobile
onboarding reachable. Adaptive columns and native fitting layouts wrap long Mac names and actions at
compact widths. Use existing semantic colors and Hermes artwork, with no fabricated charts or scores.


## Status hierarchy and actionable diagnostics — 2026-10-24

Hide only native window title text, preserving traffic lights, drag behavior and semantic window identity.
Overview puts the Mac name in its Desktop section title with a quiet local badge; remove the redundant
page eyebrow. Cloud shows this Mac's direct HTTP probe latency, never a Connector-reported RTT; failed,
stale, missing and future samples have no numeric latency. Use the same small monochrome Hermes portrait
outline in sidebar and Overview, keeping upstream bob/headband/profile features distinct from GO branding.
Local Hermes has no placeholder version/data card or roadmap/update prose; an existing-install note
belongs beside its version, only when an existing installation was actually found.

Diagnostics reads top-to-bottom: one summary, seven check rows, a usable log viewer, collapsed technical
details. Each check has result, detail, evidence source and actual time. Include local authenticated
Hermes and cloud-reported Hermes separately in the same list. Missing/stale evidence is pending; all
seven must pass for an all-passed conclusion. A cloud refresh failure invalidates remote evidence.
Show repair beneath the failed background check; reconnect only alongside a failed/degraded remote
check and through the existing confirmation. No healthy-state repair card. Technical details separate
installed versions from the last installation preflight plan, which is not current runtime evidence.

Logs show bounded redacted records with source, text and warning-keyword filters, scrolling, refresh,
copy filtered results and open-directory actions. Explicitly state 20 lines/source and historic dates;
keyword matches are not current incidents. Preserve log source/security restrictions and do not read
Hermes conversation stdout. A carried-in issue is labeled historical and dismissible; it cannot turn
current healthy checks into a false alarm. No new error codes, runtime, account or service mutations.

## Recovery and page responsibilities — 2026-10-24 follow-up

Overview's headline is information only; the duplicate status links were removed by the visual-hierarchy follow-up. Actions belong to their
Cloud/Desktop/Hermes section. Devices contains only device selection, sharing, removal and authorized
phones; no local status, install-preflight or repair panels. Connection setup expands contextually
from Overview/Local Hermes so the existing root confirmation sheets still work (no nested setup modal).
Diagnostics retains repair, component/preflight details and logs. Blocked repair shows its concrete
reason next to “重新检查修复条件”, rather than inviting the same ineffective repair again.

Existing named profiles and the sticky active profile do not make Hermes unusable. GO continues the
original default data home, explicitly pinned in its private launcher; the confirmation explains this.
It never rewrites profiles, config, databases or sticky selection. Custom/ambiguous installation paths
remain an explained block; this iteration does not add arbitrary-home selection or version management.
Account-derived health expires after 180 seconds (future skew over 5 seconds is unknown). Diagnostics
uses the active local binding, never the selected remote Mac. Devices keeps each Mac's own freshness.

## Overview and global account — 2026-10-24

Overview always describes this Mac. Show an impact headline, then three vertical sections: Hermes GO Cloud, this Mac’s Desktop, and local Hermes. Each
section owns its evidence, actual check time and relevant actions. Unknown/stale evidence is neutral,
not healthy or a proven outage; public Cloud reachability is separate from account API availability.
Remote usability additionally needs a fresh local-binding end-to-end report. Never borrow the
selected remote Mac’s health. Installation/version presence is not authenticated Hermes usability.

The fixed sidebar footer is the global account entry on every route. Signed-out/expired users can
read the overview and open the existing email-code login from this entry or the Cloud section action.
Other management sections remain gated until setup is complete; existing onboarding and mutation
locks remain in force. Signed-in users see their name/email and account menu. Account management
opens separately; the Devices section owns bindings/devices/sharing, and Settings owns preferences
and updates. Remove duplicate overview login badges and Settings account summaries. Preserve the
explicit deletion receipt, installation confirmations, rollback and sign-out background-service rule.

## Account-only connection flow — 2026-10-24

Remove the Advanced legacy URL/Token/QR card, compatibility-observer settings, old-mode banner and
observer health row. Account unavailability points to settings/retry, never a legacy connection.
Keep download/onboarding QR links for account clients. Logs read only the bounded, redacted managed
log set; an empty set does not fall back to another installation's logs. Retired startup-item/job
conflicts show `HR-MIGRATE-022` and instructions to remove the old startup item while keeping Hermes
data. No automatic takeover or old-service restoration is offered.

This section supersedes historical legacy-observation/migration descriptions below.


## Desktop as one product (HG-235)

KEEP: native SwiftUI surfaces, existing type scale, cards, 34 pt page inset, sidebar width and dark-mode colors.
CHANGE: navigation is Overview / Local Hermes / Account & Devices / Diagnostics / Settings. Logs are
an expandable bounded, redacted section inside Diagnostics. Component names, versions and preflight
breakdowns belong there, with a copyable redacted diagnostic report. Overview describes user impact.
DO NOT CHANGE: account binding, Hermes configuration/data, remote task history, signed installers,
rollback, runtime ownership, and release gates. Task activity remains in Hermes, not diagnostic logs.

Local Hermes reads this Mac's installation and authenticated probe, never the selected remote device.
Installed files alone show “已安装，待验证”; only authenticated healthy status enables Open Hermes.
Unsupported layouts remain distinct from missing installs. Existing install/setup/repair entry points
are reused. Upstream Hermes execution, switch and rollback management remain gated. HG-199 read-only version
and upgrade-condition checks live on Local Hermes, separate from Desktop product updates.

All update discovery leads to one Desktop update sheet and one action. Background changes run through
the existing verified prepare/confirm/commit transaction first; only success without pending cleanup
allows app replacement. Cancellation/failure stops the sequence. Successful background work is removed
from the remaining plan, so retrying an app failure does not repeat it. Explain phone interruption,
possible task interruption and app restart before confirmation. Automatic discovery cannot replace a
running plan. Component versions remain diagnostic facts, not competing product update buttons.

Settings explains login startup, close-window and quit behavior; quitting does not unload committed
launchd services, but installation/repair must finish with Desktop open. Sleep/logout/shutdown can
interrupt access and tasks. There is no new toggle that bypasses the existing service transactions.

Acceptance: ordinary pages use Desktop language; five navigation destinations; local/remote evidence
cannot be confused; failed/cancelled/pending-cleanup updates cannot trigger app restart; logs/report are
redacted; repair remains reachable from Diagnostics; compact and dark layouts remain readable.

## Managed setup progress (HG-226)

Both schema-v1 and schema-v2 confirmation sheets show the actual execution stage and elapsed time
after the owner confirms. Stages describe checking, staging, stopping the old services, waiting for
shutdown, starting/verifying Hermes, starting Connector, waiting for Cloud health, committing,
cleanup, and rollback. Show only stages the transaction actually reaches; there is no percentage
or promised completion time. Elapsed time uses a monotonic clock and updates once per second even
when a network callback has not arrived. The current stage is the primary line; elapsed time and
the instruction to keep Desktop open are secondary, in the existing native 540 pt sheet style.

Execution, including rollback, remains non-cancellable and non-dismissible. The lock begins with
the confirmation's final preflight, so double confirmation cannot start overlapping preflights.
A terminal success or failure closes the execution sheet before account/overview refreshes. Results
and existing structured errors remain on the main surface. A committed install with pending cleanup
keeps its retry handle and “重试清理”, but must not keep or reopen an installation confirmation.
Closing a sheet never implies cancelling a committed transaction.


## PM runtime and connection recovery (HG-212 follow-up)

Diagnostics' “重新诊断” and “再次尝试” refresh local evidence and the authenticated account snapshot
in one action. The spinner covers both reads. Each connection row shows its actual checked time;
Hermes/end-to-end rows retain the Connector report time, rather than borrowing a local probe's time.
An unknown report time says “检查时间未知”. A separate “这台 Mac 的 Hermes” card shows the local
authenticated probe and time, explicitly separate from the selected account device. Its success
cannot clear an unhealthy account chain. Account-refresh errors stay visible with copy diagnostics.

For the exact active owned local binding with a healthy local authenticated probe only, “重新连接” opens the existing 540 pt native recovery
sheet. It says that only the Hermes GO Connector restarts, phones briefly disconnect, and Hermes,
account/binding/credentials/data remain intact. Only the sheet's explicit “重新连接” commits. Service
identity, files and account are revalidated; all competing installation/account actions are locked.
Local authentication is checked again before commit. Unknown jobs, other/shared Macs, missing jobs/files and interrupted repair snapshots remain on the
existing repair path and codes. A new Cloud health timestamp is required before success.

Installation's “verifying” phase loads `serve --help` through the selected entrypoint before showing
success. A failed check remains `HR-MIGRATE-018`, with fixed details, retry guidance and no raw output.
Subsequent managed activation still requires fresh service stdout, public and authenticated local
HTTP health, then Cloud binding health; installing files alone does not mean phones can connect.

## Repairing a committed installation (HG-175)

A committed `account_active` install with either managed LaunchAgent file missing or job unloaded
shows “修复 Desktop” in the Overview Desktop section and Diagnostics. It replaces
that path's generic ownership alarm with `HR-MIGRATE-019`. Checking is read-only and cancellation
changes nothing. The 540 pt native confirmation names the original release and file scope, whether
Hermes uses the Mac's standard installation or the original bundled runtime, and any brief Connector
restart. It states that the account, binding, credentials and Hermes data are preserved; only
“修复并连接” changes the machine. Installer/account actions and dismissal are disabled during repair.
An interrupted repair offers “恢复上次操作”, restores the saved prior state first, and requires a new
check/confirmation for another attempt. Unsafe records, identity mismatch, unknown jobs or occupied
9119 show `HR-MIGRATE-020` with a specific next step; failed repair/restoration uses `021`, retains
any unresolved private snapshot and directs the owner back to the same recovery entry.


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
  Settings. Account & Devices contains only account-managed connections.
- QR links in onboarding open download/sign-in destinations without embedded credentials.

The default-off E4-D managed setup surface sits directly beneath the account/binding summary. Existing
Connector installs and unknown Hermes responders remain read-only. A Hermes that answers on 9119 and
is provably this Mac's own usable checkout is neither: it is offered for **adoption**, which installs
no second copy and touches neither `~/.hermes` nor the checkout. Only a clean preflight with matching
packaged and Gateway runtime contracts shows “下载并验证安装包”. That first action may use network and
private cache space but cannot change an installation or process. After all signature, artifact,
checksum, and archive checks pass, a native sheet names the exact release and explains the managed
Hermes Server, Connector, two user LaunchAgents, account binding, and brief restart effects. Only its
explicit “安装并连接” action may enter the machine-changing phase — and on the adoption path the sheet
and that action both say what they will actually do instead (“接管并连接”, no Hermes installation);
the plan's readiness, not the intent, decides which wording applies. Commit/recovery disables dismissal
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

## Installing Hermes on a Mac that has none (2026-09-22)

On a fresh Mac about to be set up with no Hermes at all, a native card titled “这台 Mac 还没有 Hermes”
sits directly above the setup-preflight card. It recommends installing upstream's official Hermes,
says it lands in `~/.hermes` exactly as a manual install would and that Hermes GO then starts it, and
lists — as small secondary-label rows with regular SF Symbols — the source (official HTTPS installer,
branch `main`), destination with approximate size and time (2–4 GB, 10–20 minutes on a good
network), the network and the proxy that will be used (the system proxy summary in the owner's
words), that it runs as the owner without administrator rights or `sudo` and can be cancelled, and
that model providers are configured later with `hermes setup`. Two actions: bordered “改用内置
Hermes” and prominent “安装 Hermes…”. The prominent action never runs anything: it opens a 540 pt
native sheet that repeats the facts, names what the installer may do on its own (download Python,
Node.js and browser components; use an existing Homebrew; Apple's Command Line Tools dialog),
states the trust decision in one line (HTTPS from the official origin; upstream publishes no
checksum or signature), and only its default “开始安装” starts the install.

While the owner has not decided — offer shown, install running, failed or cancelled — the setup card's
download action is replaced by one secondary line asking to decide first, and the component card's
action is disabled. A running install shows one row per stage from the installer's own manifest,
with the manifest's order and Chinese titles for known stages (unknown stages keep upstream's
title): a small spinner for the running stage, a blue check for done, a grey minus with
“需要交互，稍后配置” for stages the installer skips non-interactively, a grey circle for pending, a
linear progress bar by completed stages, the private log path as selectable secondary text, and a
bordered “取消安装” (“正在取消…” while it stops). Success reads “Hermes 已安装” with the version and
says the setup below will use this Hermes and will not install a second one. Cancellation is not an
error: “已取消安装 Hermes”, no code, with “重试” (it resumes) and “改用内置 Hermes”. A failure keeps the
stage list with the failed stage in red and shows the registered code inline (`HR-MIGRATE-015`
network/proxy, `016` a stage failed, `017` installer not supported, `018` not usable afterwards)
with “复制诊断”, the log path, “重试” only when the code is retryable, and always “改用内置 Hermes”.
“改用内置 Hermes” asks once in a native confirmation dialog and explains that setup then installs the
built-in Hermes as before and that `~/.hermes` is left as it is. Macs with an existing or unusual
Hermes never see this card; the `HR-MIGRATE-008` rule below still holds — the product never implies
that installing a second Hermes is the fix. **One narrow exception:** when a fresh setup is refused
with `HR-MIGRATE-008` and the blocking checkout is the one Hermes GO's own install left behind
(never a Hermes the owner installed), a small card beside the code says so (“这份 Hermes 是 Hermes GO
之前的安装留下的”) and offers bordered “改用内置 Hermes” behind a confirmation dialog. For the owner's
own Hermes the card instead carries secondary guidance to fix or remove that install, and offers no
alternative.

## Sign-in gate and first-run onboarding (2026-09-25)

A full-window gate replaces the sidebar whenever the owner cannot use the app: launch-checking,
launch-failed, sign-in, session-expired, Gateway-without-account-support, and account-deletion
submitted. It keeps the shared tokens — cool canvas, one content column about 420 pt wide centred in
the window, the canonical app icon at the top, one prominent action — and shows no sidebar and no
alternative entry, because nothing it hides is usable before sign-in. Sign-in failures and the
expired-session banner use the registered `HR-*` code with the same "code · explanation" shape as
every other issue, and 「复制诊断」 sits in the window footer.

First-run onboarding keeps that shell and adds a four-step indicator (登录 · 准备 Hermes · 连接这台 Mac
· 连上手机) above the content, one screen per step, one prominent action below, and 「复制诊断」 in the
footer with 「查看详情」 where detail exists. Step 3 shows one progress card with a linear bar and at
most five rows the owner can read; internal plan step names stay behind 「查看详情」. Step 4 is the
only step with a secondary 「稍后再说」 exit, because a Mac that is already set up can wait for a
phone. Its 「Android ｜ iPhone / iPad」 segmented control switches wording and QR only; both QR codes
are rendered locally from the account Gateway's origin (`/` and `/app/`) and are never generated
placeholders. A Mac with a valid local binding and completed local install goes straight to main
after relaunch or upgrade, even with phones offline; the optional phone step is reserved for first setup. Existing active phone/Web
sign-in records satisfy it without another login. Another Mac's binding does not skip local setup
(2026-10-23).

Disabled primary actions are a defined state — a muted surface with a secondary-coloured label — not
the prominent blue at reduced opacity, which was unreadable in dark mode. The overview can carry a
problem card (a half-installed or inconsistent Mac) and, in manage-only mode, a "本机（…）只用于管理"
banner that names this Mac and the Mac it manages; a compatible, already-installed Mac gets neither.
The menu bar mirrors the gate: signed out it offers 「登录 Hermes GO」, a signed-in Mac with nothing
installed offers 「完成设置」, and manage-only mode replaces the Gateway/Hermes rows with the managed
Mac's name, Connector and Hermes rows. Before setup the menu bar never reads as a failure: its
headline counts the remaining steps (「还差 2 步完成设置」) and its single row says 「这台 Mac · 未连接」
instead of two Gateway/Hermes rows that would look like two faults caused by not having signed in.
The 17 screens of this flow are direction references in `docs/design/desktop-onboarding/`.

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
HG-116 keeps the same faceted H on both platforms but removes the solid white square from the
canonical 512 px artwork. Transparent space lets the mark sit cleanly in the macOS Dock; Android's
adaptive launcher continues to supply its platform background behind the same mark.

### Menu-bar status glyph

The menu-bar extra is an exception to the full-color identity treatment: it uses the native
monochrome `h.circle` template glyph at 15 pt, rather than the square app artwork. This keeps one
small system-tinted (gray in an inactive/light menu bar) indicator aligned with neighboring macOS
status icons, avoids a white app-icon tile, and remains legible in both appearance modes. The glyph
opens the same Hermes Go Desktop menu; it is a status affordance, not a replacement app icon.

## Update surface

- One "检查更新" action appears in two places: the menu-bar menu and Settings → 更新. Both call the
  same check; neither can install anything the other cannot.
- When a check finds an app update, a managed-release update, or both, one sheet opens titled
  “发现新版本”. It lists each available update in its own card — 应用更新 and 组件更新 — with the
  version, the release notes, and a single “立即更新”.
- 应用更新 downloads, verifies, and stages a replacement app, then quits and relaunches. 组件更新
  hands off to the existing signed prepare → confirm → commit sheet; the update sheet never replaces
  the exact-version confirmation the managed installer requires.
- “已是最新版本” is shown inline in Settings, not as a modal. A failed check shows a registered
  `HR-DESKUPDATE-*` code with its bilingual explanation and a retry action.
- Automatic checks run once at launch and then at most once every 12 hours; the owner can switch them
  off in Settings. A build with no update index reports that checking is unavailable rather than
  showing a failure.
- On a Mac with an existing managed installation and an index URL, a validated update discovery
  supplies the target version to the Account & Devices upgrade card immediately. The card still
  enters the signed prepare → confirm → commit flow; the discovered version is only a preflight hint.

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
  `HR-MIGRATE-010`, which says to reinstall rather than promising a retry. Repeated failures pause with `HR-MIGRATE-011`, which says what will make it try again; setting off with no kept bundled agent while the Mac's own Hermes keeps running is `HR-MIGRATE-012`, which must not say Hermes was removed. A Hermes service that is not running and could not be loaded reads retryable `HR-MIGRATE-013` ("Hermes 服务未运行"), which says plainly that the phone cannot reach this Mac for now and that it will retry — never the softer "whatever could be restored was restored" of `HR-MIGRATE-009`, and never a claim that Desktop restarted it (it may have found it not loaded). A job that is not loaded while another program holds port 9119 reads retryable `HR-MIGRATE-014` ("Hermes 端口被占用"), which asks the owner to close that program. With the setting off the
  card never appears unless the Mac is actually in local mode. A successful switch or restart is silent: the Hermes row keeps reporting
  reachability, not which codebase answered.
- Technical details and logs are secondary, selectable, and redacted before presentation.
- HG-212 startup diagnostics carry fixed reasons for a fresh Python import failure, generic fatal
  startup exit or explicit port-conflict marker through the existing runtime issue. Primary bilingual
  summaries, retryability and recovery actions remain the registered ones. Raw Hermes stderr is never
  a preview or copy-diagnostics payload; old errors and nonfatal update warnings cannot override fresh
  health. No new UI controls or service-takeover action are introduced.
- Diagnostics and Logs prefer bounded, redacted managed Connector and Desktop service-operation
  tails only; the old Connector log directory is never read. Hermes stdout
  is not previewed because it may contain conversation content.
- Current reachability and historical log warnings are separate. Old warning lines may be counted and
  shown as history, but cannot by themselves mark a currently healthy connection offline.
- End-to-end failures show a registered `HR-*` code and recovery action; raw HTTP bodies and tokens
  never become primary UI text.
- Overview names the Desktop-selected Mac and projects its Connector, Gateway, Hermes and
  end-to-end health from the authenticated account snapshot. Local checks use the fixed loopback
  contract. Switching selected devices never changes a local service.
- A managed local Hermes WebSocket is authenticated with one private installation token shared by
  file path between Hermes and Connector. The value is never rendered, copied into diagnostics, or
  sent to Gateway; a missing or unsafe file leaves the path offline rather than weakening auth.

## Concept references

- [Overview and menu bar](design/desktop/overview.png)
- [Phone pairing](design/desktop/pairing.png)
- [Diagnostics and logs](design/desktop/diagnostics.png)
- [Phase-0 SwiftUI implementation](design/desktop/implementation-phase0.png)
- [Sign-in gate and first-run onboarding](design/desktop-onboarding/README.md) — 17 screens, rendered
  from `docs/design/desktop-onboarding/onboarding.html`

These are direction references, not pixel specifications. Generated placeholder QR codes are not
functional, and generated approximations of the H mark must be replaced by the canonical app icon.

HG-199 upgrade qualification remains distinct from normal PM start eligibility: private dependency
metadata that GO cannot verify must block upgrading, without replacing HG-212's bounded published
launcher startup with a dependency-stamp alarm. No new execution control or confirmed target results.
