# The managed Hermes: pinned copy, patched fork, or no copy at all

> Status: **re-decided 2026-09-21 — one Hermes per Mac.** Hermes GO uses the Hermes already on the
> Mac and installs one only when there is none; it no longer ships a second copy of its own. This
> supersedes the 2026-09-20 decision (Option B), which is kept below together with the reasoning
> that led to it, because the incidents and measurements behind it are what made the new decision
> possible. Until each Mac has been switched, the bundled copy still exists as a fallback and the
> *Operating rules* at the end still govern it.
>
> **2026-09-22, phase 1 of install-when-missing:** local runtime mode is on by default, and a Mac
> without Hermes is offered upstream's own installer, run directly against upstream through the
> system proxy. The bundled copy stays as the fallback until phase 2 (see *用户决策 · 2026-09-22*).

## 用户决策 · 2026-09-21 — one Hermes per Mac

> 「如果本机有 hermes 就不应该再装一个；没有的话也只是帮忙启动安装了一个而已。」

**Hermes GO owns the process, not the code.** On a Mac that already has Hermes, Desktop's launchd
job starts *that* Hermes (`~/.hermes/hermes-agent/venv/bin/hermes serve --host 127.0.0.1 --port
9119`) instead of the bundled `hermes-server`. On a Mac without Hermes, Hermes GO runs upstream's
own installer into upstream's standard location, after which the Mac is simply "a Mac with Hermes"
and takes the same path. There is never a second copy for anyone to discover later, and a person
who installs Hermes themselves afterwards finds the one that is already there.

### Why the 2026-09-20 decision did not hold

Option B made drift *scheduled work* instead of a surprise; it did not remove it. Two facts found
on 2026-09-21 made removing it both possible and cheap:

- **The second copy only ever served the phone.** The owner does not use a Hermes client on the Mac
  mini; their MacBook's Hermes.app reaches it remotely, and that remote backend, the messaging
  gateway, the cron owner and the dashboard all run the owner's own `~/.hermes/hermes-agent`. The
  managed `serve` was the one process on the machine running different code.
- **The two copies were the same code until the day it broke.** The managed copy appeared on
  2026-09-09 (release 0.3.0), pinned to `f159e581`; the owner's checkout sat on that same commit
  from 2026-09-05 until `hermes update` moved it to `17b5df02` at 20:37 on 2026-09-19. The
  `display_identity` 500 followed that evening. Before that the "two copies" had never actually
  differed, which is why the design looked fine for ten days.

### How Option C's objections are answered

Option C below was rejected for three reasons. Each belonged to *attaching to a process someone
else started*, and none survives once Desktop starts the process from the local code:

| Objection | Answer |
|---|---|
| `/api/ws` needs the process's exact session token | Desktop still starts the process, so it still generates and holds the token |
| No stable endpoint (`--port 0`, Tailscale-bound dashboard) | Desktop still chooses `127.0.0.1:9119` |
| No lifecycle control | launchd still supervises it; quitting the owner's Hermes.app does not affect it |

What remains of C's cost is real and is accepted deliberately: **Hermes GO no longer decides which
Hermes version runs.** The owner's `hermes update` does. There is no pinned artifact to reproduce,
health-gate or roll back. Compatibility therefore moves out of packaging and into the clients:

1. **Clients speak old and new protocols side by side**, deciding from what the server sends, not
   from a version guess.
2. **The Connector checks the upstream contract at startup** (`docs/HERMES_CONTRACT.md` §2 paths in
   the server's `openapi.json`) and reports a registered `HR-` code instead of failing later.
   *Built 2026-09-21 (step 3b)*: at Connector startup, on every reconnect to Hermes and when
   `/api/status` reports a new version; a missing required route is `HR-COMPAT-001`, a missing
   optional one `HR-COMPAT-002`, a Hermes older than the verified minimum `HR-COMPAT-003`, and a
   schema it could not read is `unknown` and shows nothing. It reports and never refuses to relay.
   The phone shows the code on its health strip. Reaches a Mac only with the next Connector
   (managed component) release and a phones' APK that reads it.
3. **The `serve` Hermes GO owns is restarted when the local code changes.** Two mechanisms, and
   the second is the backstop for the first. Upstream `hermes update` (0.21.3) restarts every
   launchd job whose `ProgramArguments` contain `hermes serve` with `launchctl kickstart` — which in
   local mode includes ours, deliberately: the launcher keeps the real entrypoint in `argv[1]` so
   that match succeeds. Desktop independently restarts it when the checkout's resolved commit
   changes (`docs/DESKTOP_PHASE0.md`). Without either, the phone keeps talking to the old code still
   in memory.

### Compatibility of the owner's Hermes, verified 2026-09-21

Checked against `17b5df02` (the owner's checkout) versus `f159e581` (the managed copy the contract
was verified on), and again after `main` reached `83031d0`:

| Area | Finding | Consequence |
|---|---|---|
| Approval and clarify | Upstream replaced the `approval.request` / `clarify.request` events and the `clarify.respond` method with **server→client JSON-RPC requests** (`tui_gateway/server_requests.py`). A client that never sends `client.capabilities {server_requests: true}` is treated as outdated: approvals are withdrawn and clarify returns nothing | **Blocker — fixed in #350** (both protocols; see `docs/HERMES_CONTRACT.md` §3). Still has to reach the phones in a released APK |
| RPC params | Every params model is `extra="forbid"` (`tui_gateway/contracts/base.py`): an unknown key answers **4000**. The app sent three: `session.resume` `inline_images` (every conversation would fail to open), `image.attach_bytes` `mime_type`, `approval.respond` `approved` | **Blocker — fixed in #350** with one params form both servers accept (`session.resume` now sends `omit_messages`). `RpcParamContractTest` pins the allowlist at build time. Missed by the first check on this page, which compared method names only |
| Inline images on read (patch 020) | `session_history.py` still reads with `image_urls=True`. `session.resume` no longer carries a transcript (`omit_messages`), so only REST `GET /messages` pages remain affected | Degraded: existing sessions with large inlined images are costly again on the phone. Page size on 17b5df02 not yet measured (contract checklist 8g) — measure before the switch. Resolved upstream by PR #116677 |
| `session.access` (patch 030) | Not present | Degraded, fail-open: the phone cannot show "occupied elsewhere" in advance (HG-82/88); `prompt.submit` 4090 still guards. Resolved upstream by PR #116677 |
| Unknown columns (patch 010) | The owner's code drops `display_identity` / `display_order` itself | Not needed: one code writes and reads |
| REST paths, events, mirrored constants, error numbers, `/api/ws` auth, cron `fire_claim` and synchronous trigger | Unchanged | Compatible. `session.lifecycle`, listed in the contract, exists in neither commit |

### Order of work

1. **Done (#350, merged 2026-09-21):** Android speaks both question protocols and sends only params
   both servers accept. Precondition for any switch, and it has to reach the phones in a released
   APK first; verified at L1 only, so the real-Hermes smoke test in `docs/SMOKE_TEST.md` runs before
   the switch.
2. **Done (#350):** `docs/HERMES_CONTRACT.md` records the new protocol and the params allowlist.
3. **Done (#351, merged 2026-09-21):** Desktop local-Hermes mode — detection, launching the local
   `hermes serve`, restart on commit change, rollback to the bundled agent. Default off at first,
   switched on per Mac; **on by default since 2026-09-22** (step 4). `HERMES_DESKTOP=1` stays: from 0.21.3 the ticker it starts checks, per tick, whether the
   owner's gateway is running and stands down if so (`profile_gate` now applies with one profile),
   so it no longer competes for `cron/.tick.lock`; removing the variable would break `/api/ws` auth
   once `dashboard.public_url` names a non-loopback host. The minimum local version is 0.21.3.
3b. **Built (2026-09-21, not yet shipped):** the Connector's startup contract check (principle 2
   above; mechanism in `docs/HERMES_CONTRACT.md` §2, "Connector contract check"). Shipping it needs
   a managed Connector component release for the Mac and an APK carrying the Android half; either
   half alone is harmless — an older Connector answers the report route with Hermes' 401/404, which
   the app reads as "no report", and an older app never asks.
4. **Phase 1 built (2026-09-22, not yet released):** install-when-missing through upstream's
   installer, and local runtime mode on by default. The open question — GitHub is often unreachable
   from the owner's network, so should a first install be served through the Hong Kong release
   server? — was decided by the owner: **no mirror; upstream directly, through the system proxy**.
   Mechanism in `docs/DESKTOP_PHASE0.md` ("Installing Hermes when the Mac has none"); decisions and
   the installer trust decision in *用户决策 · 2026-09-22* below. Phase 2 (retiring the bundled
   copy, the manifest schema change, the patches and the drift check) is step 6.
5. **Done (2026-09-21, 20:55 +08:00):** the Mac mini runs its own Hermes. Desktop 0.2.22 switched
   the managed `hermes-server` job to `~/.hermes/hermes-agent` (0.21.3, `17b5df02`) with about 17 s
   of Hermes unavailability; the bundled agent is kept as the rollback. The first attempt with
   0.2.21 left Hermes unloaded for ~3 minutes and led to the #356 fix — see
   `docs/DESKTOP_E4_TEST_RECORD.md`.
6. Retire the bundled copy — component archive, `desktop/hermes-patches/`, schema baseline and
   `HR-MIGRATE-006` — once install-when-missing replaces it as the fallback. Not before: it is the
   only path for a Mac without Hermes until step 4 exists.

Waiting for upstream PR #116677 before step 5 avoids both degradations; switching earlier is
possible and costs exactly the two rows marked *Degraded* above.

## 用户决策 · 2026-09-22 — install from upstream, through the system proxy

Three decisions, and what each one means in the code.

**1. Upstream directly, no Hong Kong mirror.** When detection finds no Hermes at all
(`.absent(hermesDataPresent: false)`), Desktop offers upstream's official installer,
`https://hermes-agent.nousresearch.com/install.sh`, and installs to the standard location
(`--dir ~/.hermes/hermes-agent --hermes-home ~/.hermes`), so the Mac becomes an ordinary "Mac with
Hermes" that the owner can `hermes update` themselves. The network cost is accepted rather than
engineered around:

- Desktop downloads the script with `URLSession`, which follows the macOS system proxy (manual or
  PAC) by itself.
- The installer's children (`git`, `curl`, `uv`, `npm`) read only environment variables. Desktop
  exports the **manual** HTTP/HTTPS/SOCKS proxies from System Settings to the installer's
  environment only — `http_proxy`/`https_proxy`/`all_proxy` in both cases, loopback always in
  `no_proxy` — and only when one is configured. Explicit proxy variables already in Desktop's
  environment win unchanged. A PAC-only configuration cannot be expressed as one variable: it is
  logged and the installer runs without one. Proxy credentials live in the keychain and are never
  read; a proxy that needs them fails with a 407, which is a network failure.
- Any network failure — the download, or a stage whose output matches a network signature — is
  `HR-MIGRATE-015`, which tells the owner to check the network and proxy and retry, and offers the
  bundled copy instead. A retry resumes where the installer stopped (its `repository` stage updates
  an existing checkout).

**2. Local runtime mode is on by default.** `DesktopLocalHermesRuntimeSetting.defaultValue = true`;
the environment variable, the user default and `Info.plist` still override it. What this does on
each kind of Mac after it upgrades Desktop:

| Mac | Next refresh |
|---|---|
| Managed install, bundled agent, usable standard Hermes | `switchToLocal` through the proven path (#351, and the #356 shutdown/reload fixes): keep the bundled agent, write the launcher, restart only Hermes, readiness proof, byte-for-byte restore on failure. This is what the Mac mini did by hand on 2026-09-21 |
| Managed install, no Hermes of its own | Nothing: `.absent` with a bundled agent is `.keep`. No install is offered to an installed Mac |
| Managed install, Hermes in an unusual shape | Nothing is changed, but `HR-MIGRATE-008` now appears on its card, where before the setting kept it silent |
| Fresh Mac with a usable Hermes | The fresh managed install writes the local agent directly (`freshInstallProvider`); the bundled agent is only the kept fallback and never runs |
| Fresh Mac without Hermes | The install offer (decision 1) appears above the setup card; setup waits for the owner's choice |
| Fresh Mac with Hermes in an unusual shape | The fresh install is refused with `HR-MIGRATE-008`, as it was with the setting on |

Opting out stays one command: `defaults write com.hermesgo.desktop HermesGoLocalHermesRuntimeEnabled
-bool false`, which on a local-mode Mac is also the rollback.

**3. Phase 1 keeps the bundled copy as the fallback.** The manifest schema, `hermes_server`, the
patches and the drift check are unchanged. "改用内置 Hermes" in the offer, and after a failed or
cancelled install, persists the setting off for this Mac, after which setup behaves exactly as it did
before local mode existed. Retiring the bundled copy is phase 2 (step 6).

### How the installer is driven

- **Protocol, not text.** Desktop runs `install.sh --manifest`, requires `protocol_version` 1, then
  runs each listed stage as `install.sh --stage <name> --non-interactive --json` and reads the last
  `{"ok":…,"stage":…,"skipped":…}` line of its output — the protocol upstream's own Electron and
  Tauri bootstraps drive (`apps/desktop/electron/bootstrap-runner.ts`,
  `apps/bootstrap-installer`). Like them, every stage is invoked and the installer itself skips the
  interactive ones. A different protocol version, a malformed manifest or a stage with no result
  frame is `HR-MIGRATE-017` and nothing further runs.
- **Interactive stages are skipped, not answered.** `setup` (API keys and provider) and `gateway`
  (messaging gateway service) are `needs_user_input` and are skipped by `--non-interactive`, the
  same effect as `--skip-setup` in the one-liner. The owner configures a model provider afterwards
  with `hermes setup`; Desktop runs the serve itself, so the messaging gateway is not needed for the
  phone.
- **Branch `main`**, upstream's own default, and no `--commit` pin: the installed checkout is then
  what `hermes update` moves, which is the point of one Hermes per Mac. If upstream's `main` is ever
  below `DesktopLocalHermesDetector.minimumVersion`, detection refuses it with `HR-MIGRATE-018`
  instead of running it.
- **No root, no sudo, no terminal.** Desktop refuses to run as root. The installer runs as the
  owner with standard input on `/dev/null`, `NONINTERACTIVE=1` and `GIT_TERMINAL_PROMPT=0`, in its
  own process group, with a private `bin/` first on `PATH` whose `sudo` refuses. On macOS upstream
  uses no `sudo` at all; it may use an existing Homebrew for optional tools and may open Apple's
  Command Line Tools installer.

### Installer trust decision

Upstream publishes **no checksum or signature** for `install.sh` (verified 2026-09-22 against
`17b5df02`: nothing in the repository, its site-deploy workflow included, produces one; there is no
`.sha256`, `.sig` or `.asc` beside it; and upstream's own Tauri bootstrap downloads it from
`raw.githubusercontent.com` without verification). There is nothing to verify it against, so the
trust decision is explicit rather than implied:

- **Trusted: TLS to the official origin.** The script is fetched only over HTTPS from
  `hermes-agent.nousresearch.com`; after redirects the final URL must still be HTTPS on that host,
  or a file under `raw.githubusercontent.com/NousResearch/hermes-agent/` (where upstream's own
  bootstrap fetches it), otherwise `HR-MIGRATE-017`. This is the same trust the owner extends when running upstream's documented
  one-liner, and no more: the script then clones the repository and installs its dependencies over
  the same channels, so a verified script alone would not verify what it installs.
- **Recorded, not verified:** the script's SHA-256 is written to `Managed/logs/hermes-install.log`
  before it runs, so an incident can be traced to the exact bytes.
- **Bounded:** at most 2 MiB, must start with `#!`, runs from a private 0600 file in a 0700
  directory that is removed afterwards, and must answer the stage protocol before any stage runs.
- **Revisit** if upstream starts publishing a checksum or signature: verify it before running, and
  record that here.

## 用户决策 · 2026-09-20 (superseded 2026-09-21)

The managed Hermes becomes **ours to patch**, and every patch is **also submitted upstream** — the
two directions run in parallel rather than one waiting on the other. Upstream acceptance removes a
patch; upstream silence does not block a fix. The owner's stated reason is freedom of movement: two
problems in one day were each under ten lines and each unfixable by us.

Handling the rebase conflicts and holding the rules below is our job, not something to be discovered
per release.

**This decision does not authorise a fork of the Hermes repository.** See *Operating rules*: patches
live in this repository and are applied at build time, so there is no long-lived divergent checkout
to drift.

## The conflict

Hermes GO installs and runs its own copy of Hermes, pinned to one upstream commit, and points it at
`HERMES_HOME=/Users/bs/.hermes` — the owner's own Hermes home. Two goals are being served at once,
and on a Mac where the owner also runs their own Hermes they pull against each other:

- **Pinning** exists so a signed release is reproducible and auditable: Desktop knows exactly what it
  is running, can health-gate an upgrade, and can roll back.
- **Sharing the database** exists so the phone sees the owner's real conversations. Without it there
  is no product.

Pinned code and rolling code writing one database means drift is certain. It is not a question of
whether an upstream change breaks the pinned copy, only which one and when.

Both of the day's incidents are instances of this, and both were fixable in under ten lines of
upstream code that we do not own:

| Incident | Upstream line | What we did instead |
|---|---|---|
| HG-65: a conversation's history reached 26.30 MiB and killed the tunnel on every reconnect | `session_history.py`, two `image_urls=True` call sites — but only the **transfer** half; the bytes are inlined when the row is written, which is write-side and out of bounds (see below) | three client-side mitigations across Connector and Android, one day of work |
| A conversation returned 500 after the owner updated their own Hermes | `hermes_state_messages.py`, five `SELECT *` sites | nothing yet; sessions created after the schema change cannot be opened from the phone |

## Option A — keep the verbatim pinned copy (today)

Change nothing. Track upstream releases, re-cut a managed release when adopting one.

- **For**: provenance is trivial — the artifact is upstream at a commit, auditable by pointing at
  upstream. No merge burden.
- **Against**: every problem whose cause is upstream behaviour stays unfixable by us, and is paid for
  in client-side workarounds. Drift keeps detonating; the managed copy can only ever lag.

## Option B — a thin read-side patch set on the pinned copy

Treat the managed copy as ours: carry a small set of patches, rebase them onto each upstream commit
we adopt.

- **For**: the two incidents above become direct fixes. More generally it lets the relay's Hermes
  make different choices from a desktop's, which is the actual difference — inlining attachments is
  free between local processes and expensive over a relay.
- **Against**: every upstream adoption becomes a rebase, not a pull. Provenance moves from "upstream
  at a commit" to "upstream plus our patches", and we have to carry that story. Drift does not
  disappear; it becomes scheduled work instead of a surprise, which is better but not free.
- **Hard rule if adopted**: **a patch may change what is read or rendered, never what is written or
  the schema.** Today the two copies are different versions of one program; a patch makes them
  different programs sharing one database. A write-side or schema-side patch would leave the owner's
  own Hermes unable to read what the managed copy wrote — the same class of failure as 2026-09-19,
  harder to diagnose. Both candidate patches happen to satisfy this; that is luck, not a guarantee.
- **Keep the set shrinking**: submit each patch upstream, drop it when accepted. A patch set that
  only grows is a fork by another name, and the rebase cost grows with it.

## Option C — attach to the owner's Hermes instead of installing one

Detect a usable Hermes on the machine and connect the Connector to it; install the managed copy only
when there is none.

- **For**: one code, one database. Drift becomes structurally impossible rather than managed.
- **Against**, and these are not small:
  - **Authentication.** `/api/ws` requires that process's exact session token. Today Hermes GO owns
    the token because it starts the process. Attaching means reading a token it did not generate and
    re-reading it whenever that Hermes restarts.
  - **No stable endpoint.** The owner's Hermes.app backend runs on `--port 0`; their dashboard binds
    the Tailscale address. Neither is a fixed loopback target.
  - **No lifecycle control.** Desktop currently starts, supervises, health-gates and rolls back
    Hermes. Attached, the phone's availability depends on a process Desktop cannot manage — quit the
    app and the phone is down.
  - The managed path cannot be deleted anyway: owners without their own Hermes still need it.
- Net: it trades a known failure mode (drift) for a different one (Desktop no longer knows what it is
  running, or whether it is running).

## B and C are mutually exclusive

Sharing one copy of the code leaves nothing to patch. Choosing B is choosing that the managed Hermes
is ours to shape; choosing C is choosing that it is the owner's and we only borrow it.

## What is worth doing under any option

These do not depend on the decision and should not wait for it:

1. **Make component archives byte-reproducible.** Measured 2026-09-20: the rebuilt `hermes_server`
   differs from the published one in packaging only — all 23,111 files are byte-identical, and the
   2.5 MiB delta is pax headers forced by sub-second mtimes. Normalising mtimes makes rebuild→hash
   comparison a real check, and makes B's rebase gate cheap.
2. **Gate on schema version.** The managed Hermes cannot work against a database newer than itself,
   but it can say so at startup instead of returning 500 from the response encoder hours later.
3. **Distinguish history failures on the phone.** An upstream 5xx, a dropped connection and a parse
   failure are all `HR-RPC-001` today, so the person holding the phone cannot tell retrying from
   reporting.

## A worked example of the read-side rule, found while applying it

The first attempt at the second founding patch was to flip `image_urls` to `False`, on the belief
that Hermes expanded `@image:` references into base64 *at read time*. It does not: the payloads are
already in `messages.content` when the row is written — 27,483,342 bytes in one row, 110,160,145
across the session that reported HG-65. The 151-byte figure that started that belief came from
`sqlite3`'s `length()`, which stops at the first NUL, and these rows begin with `\x00json:`.

Two things follow, and both are worth keeping in front of whoever adds the next patch.

**The patch is smaller than it looked.** Flipping the switch changes what is *sent*, not what is
*stored*. That is still worth having — transfer is the half that broke the tunnel — but it does not
make the conversation smaller, and a conversation that keeps growing will keep costing.

**The other half is out of bounds by our own rule.** Deciding not to inline at write time is a
write-side change, and a write-side patch is exactly what rule 2 forbids: the owner's own Hermes
reads the same rows. So that half is upstream's or nobody's, and the honest thing is to say so
rather than reach for it.

## Deciding (kept for the record)

The test applied was: over the next six months, how many problems are expected whose cause is
upstream behaviour that is right for a desktop and wrong for a relay? One or two would argue for A
plus upstream requests; several argues for B. Two appeared on 2026-09-19 alone, both affecting daily
use. B was chosen.

---

# Operating rules

> **Scope since 2026-09-21:** these rules govern the **bundled** copy only, for as long as it
> exists as the fallback (see *Order of work* above). A Mac running in local-Hermes mode carries no
> patches and no schema baseline, and nothing here applies to it. Do not add new patches: a problem
> that would have needed one is now an upstream issue plus, where it matters, client-side tolerance.

These exist because a patch set decays without them. Every rule below has a failure it prevents.

## 1. Patches live here, not in a fork

A patch is a file in `desktop/hermes-patches/NNN-short-name.patch`, applied by the component packager
to the **staged** Hermes tree — the copy it has just made from the pinned upstream commit, never the
checkout itself. There is **no long-lived checkout of upstream carrying our changes**; that is the
thing that silently diverges. `scripts/lib/hermes-patches.mjs` loads, validates and applies the set,
and `desktop/hermes-patches/README.md` is the contract for adding one.

The staged tree is deliberately smaller than the upstream checkout. A carried patch therefore
contains only runtime-source hunks for paths the component packager copies; upstream `tests/` hunks
remain in the upstream PR. Patch validation and staging consume the same allowlist, and the archive
tests apply a real patch after that copy step so a full-checkout-only patch cannot reach release.

Each patch file carries a header:

```
Upstream-Issue: https://github.com/NousResearch/hermes-agent/issues/NNNNNN
Why-upstream-will-not: <one sentence>
Read-side-only: yes
Added: YYYY-MM-DD
```

`Why-upstream-will-not` is the field that decides whether a patch should exist at all. "Inlining is
free between local processes and not free over a relay" is a reason. "They haven't got round to it"
is not — that is a patch waiting on an issue, and it should be dropped as soon as the issue lands.

## 2. Read-side only — the rule that protects the shared database

**A patch may change what is read or rendered. It may never change what is written, the schema, or
migration behaviour.**

The reason is specific, not stylistic. The managed copy and the owner's own Hermes share one
`state.db`. Today they are two versions of one program; a patch makes them two different programs.
A write-side patch would leave the owner's Hermes reading rows only ours understands — the same
class of failure as 2026-09-19, and harder to diagnose because no version number would explain it.

Both founding patches satisfy this by luck, not by design, so the loader checks it rather than
trusting a reviewer: a patch is refused if it touches a schema-owning file, or if its own added or
removed lines contain a write statement. Strict on purpose — a false positive costs an argument, a
false negative costs the database. A patch that cannot satisfy the rule is not a patch; it is a
reason to reconsider Option C.

## 3. Bidirectional by default

Every patch is submitted upstream when it is written, not "later". The patch is dropped from the set
the moment upstream accepts it. A patch set that only grows is a fork by another name and its rebase
cost grows with it.

Filed so far: [#116510](https://github.com/NousResearch/hermes-agent/issues/116510) (`SELECT *`
reads) and [#116511](https://github.com/NousResearch/hermes-agent/issues/116511) (the `image_urls`
switch).

## 4. Adopting a new upstream commit is a gate, not a pull

**Being behind is the normal state, and is not by itself a reason to adopt.** "Version parity" is
not a state that can be held: the owner's own Hermes rolls forward whenever they update it, while
the managed copy moves only when someone packages it. Parity is a moment, not a condition, so the
question is never "are we behind" but "has being behind started to cost something".

Measured on 2026-09-20, two weeks behind (pinned `f159e581` of 09-04 against the owner's `17b5df02`
of 09-19) cost nothing the phone can see: every REST path in `docs/HERMES_CONTRACT.md` §2 was
present in the running managed copy's own `openapi.json`, and all 35 top-level keys of the shared
`config.yaml` were understood by it. What the pinned copy lacked — `/api/audio/voice-live/*`,
`/api/dashboard/plugins/catalog`, `/api/gateway/migrate*`, and the newer `hermes_state_*` modules —
the app does not call.

Adopt when one of these is true, and not otherwise:

1. **An incompatibility the patch set cannot absorb.** Not "a new column" — those are dropped by the
   allowlist — but a column whose meaning changed, a renamed RPC, an altered text grammar. This is
   the forced case, and `HR-MIGRATE-006` exists to make it arrive as a sentence rather than a 500.
2. **Upstream merged one of our patches**, so adopting *deletes* a patch. This is the case worth
   seeking out: the patch set is borrowed time, and the smallest one is the healthiest one.
3. **A specific upstream capability is wanted**, named by the owner.

Two things follow. Adopting because the version numbers look untidy spends a real risk budget —
the gate below, a managed release, and a manual controlled activation (HG-68 is not yet closed on
a physical Mac — until 2026-09-21 every in-app upgrade's stop proof timed out, see
`DESKTOP_E4_TEST_RECORD.md` — and the 0.3.5 activation rolled back twice before it took) — on nothing. And **the durable fix is not
adoption at all**: an upstream that stops putting unknown columns into responses
([#116510](https://github.com/NousResearch/hermes-agent/issues/116510)) makes every future column
harmless at once, where each adoption only settles the one in front of it.

In order, and it stops at the first failure:

1. record the new upstream commit; keep the old one until the gate passes;
2. apply every patch in order — **a conflict stops the adoption**, it is not resolved inside the
   packager;
3. for each conflicted patch decide explicitly: upstream fixed it → delete the patch; upstream moved
   the code → rewrite it and say so in the header; upstream changed the behaviour → re-read rule 2
   before rewriting anything;
4. rebuild, and compare the hash against a second build of the same inputs (see rule 5);
5. run the component and connector tests, then the release gate;
6. record in `docs/DESKTOP_E4_TEST_RECORD.md`: the upstream commit, the patch set applied, and the
   resulting hashes.

## 5. Provenance is now "upstream at X plus patches Y"

Option A's provenance was free — point at upstream. It is not free any more, so it has to be
produced deliberately:

- `BUILD-IDENTITY.json` records the upstream commit **and** the applied patch list with their hashes;
- component archives must be byte-reproducible, so that "this artifact is that commit plus those
  patches" is a check someone can run rather than a claim they have to trust. Until the packager
  normalises mtimes this is not possible — which is why that work is a precondition for this rule,
  not an optimisation.

## 7. The drift check has to be reachable, not merely present

Drift is detected by comparing the live `state.db` columns against the `schemaBaseline` the packager
records in `BUILD-IDENTITY.json`, and shown as `HR-MIGRATE-006`. Not against `schema_version`:
upstream added `display_identity` and `display_order` while leaving `SCHEMA_VERSION = 30` on both
sides, so the obvious gate would have missed the incident it exists for.

The rule is about the *last* link. HG-71 landed the comparison, the inspector, the error code and
eight passing tests, and this Mac still said nothing — because nothing ever called the inspector.
Every test exercised the mechanism in isolation, and a check that never runs can never fail, so
merging and releasing both looked clean. Two things follow for anything added in this area:

- the check runs on Desktop's ordinary refresh and is **not** gated on the managed installation
  reading `active`. Any Mac that also runs its own hermes-agent reports `inconsistent` forever, and
  that is exactly the Mac that has drift;
- a column that has been examined and found harmless is subtracted from the report, but only on
  the terms recorded with it in `DesktopManagedSchemaAcknowledgement.known` — the patch that
  neutralises it must actually be in that release's `BUILD-IDENTITY.json`, and the column's type
  must still be what was examined. A permanently lit notice is one nobody reads, and this check has
  exactly one job: to be read the one time it is new. An unexamined column always reports;
- `ManagedSchemaWiringTests` asserts the call site exists. It reads the app target's source, because
  `HermesGoDesktop` is an `executableTarget` that SwiftPM cannot import into a test target — a
  weaker check than calling the code, and still the only one that catches "nobody wrote the call".

## 6. A patch is not a licence to skip the upstream contract

`docs/HERMES_CONTRACT.md` still governs. Patching what we read does not make the wire format,
the RPC names or the error numbers ours; those remain upstream's and remain unnegotiable. Run the
upgrade checklist as before.
