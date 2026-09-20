# The managed Hermes: pinned copy, patched fork, or no copy at all

> Status: **decided 2026-09-20 — Option B, with patches flowing upstream in parallel.** Written the
> same day, after two incidents made the trade-off concrete. Read `docs/HERMES_CONTRACT.md` and the
> 2026-09-19/20 entries in `docs/DESKTOP_E4_TEST_RECORD.md` first. The options are still weighed
> below because the reasoning is what makes the rules that follow enforceable.

## 用户决策 · 2026-09-20

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

These exist because a patch set decays without them. Every rule below has a failure it prevents.

## 1. Patches live here, not in a fork

A patch is a file in `desktop/hermes-patches/NNN-short-name.patch`, applied by the component packager
to the **staged** Hermes tree — the copy it has just made from the pinned upstream commit, never the
checkout itself. There is **no long-lived checkout of upstream carrying our changes**; that is the
thing that silently diverges. `scripts/lib/hermes-patches.mjs` loads, validates and applies the set,
and `desktop/hermes-patches/README.md` is the contract for adding one.

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
the gate below, a managed release, and a manual controlled activation (HG-68 is unfixed, and the
0.3.5 activation rolled back twice before it took) — on nothing. And **the durable fix is not
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
