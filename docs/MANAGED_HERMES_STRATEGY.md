# The managed Hermes: pinned copy, patched fork, or no copy at all

> Status: **open question, nothing decided.** Written 2026-09-20 after two incidents in one day made
> the trade-off concrete. Read `docs/HERMES_CONTRACT.md` and the 2026-09-19/20 entries in
> `docs/DESKTOP_E4_TEST_RECORD.md` first; this document only weighs the options.

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
| HG-65: a conversation's history reached 26.30 MiB and killed the tunnel on every reconnect | `session_history.py`, two `image_urls=True` call sites | three client-side mitigations across Connector and Android, one day of work |
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

## Deciding

A reasonable test: over the next six months, how many problems are expected whose cause is upstream
behaviour that is right for a desktop and wrong for a relay? One or two argues for A plus upstream
requests. Several argues for B. Two appeared on 2026-09-19 alone, both affecting daily use.
