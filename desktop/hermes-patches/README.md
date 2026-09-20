# Managed Hermes patch set

Patches applied to the **staged** copy of Hermes when a component archive is built. There is no
long-lived checkout carrying them — that is the thing that diverges quietly, and avoiding it is the
point (`docs/MANAGED_HERMES_STRATEGY.md`, 用户决策 · 2026-09-20).

An empty directory is a valid state and means the managed Hermes is verbatim upstream.

Current read-side patches:

- `010`: drops unknown database columns from message response dictionaries.
- `020`: lets remote reads opt out of transmitting stored inline image bytes while preserving the
  old default for every existing caller.
- `030`: exposes cross-process ownership and run state as a small, non-mutating `session.access`
  projection. It does not acquire or release a lease.

## Adding one

File name `NNN-short-name.patch`; `NNN` fixes apply order. Header, all four fields required:

```
Upstream-Issue: https://github.com/NousResearch/hermes-agent/issues/116510
Upstream-PR: https://github.com/NousResearch/hermes-agent/pull/116677  # optional until one exists
Why-upstream-will-not: <one sentence>
Read-side-only: yes
Added: 2026-09-20
```

Produce the diff against the pinned upstream commit, paths relative to the Hermes repository root
(`git diff` from a clean checkout gives this; the packer applies with `-p1` inside the staged
`app/`).

The patch artifact contains only hunks for files copied into that staged `app/`. In particular,
upstream `tests/` hunks stay in the upstream pull request and are not copied into this repository's
patch file. The loader and packager share one source allowlist and reject any path that will be
absent from the managed archive.

**`Why-upstream-will-not` is the field that decides whether the patch should exist.** "Inlining is
free between local processes and not free over a relay" is a reason to carry one indefinitely.
"They have not got round to it" is not — that is a patch waiting on an issue, and it is deleted the
moment upstream merges. A patch nobody can justify is a patch nobody will dare remove.

## The rule the loader enforces

**A patch may change what is read or rendered. It may never change what is written, the schema, or
migration behaviour.**

The managed copy and the owner's own Hermes share one `state.db`. Today they are two versions of one
program; a patch makes them two different programs. A patch that wrote a row only our copy
understands would leave the owner's Hermes unable to read its own database — the 2026-09-19 failure
again, and harder to explain because no version number would account for it.

`scripts/lib/hermes-patches.mjs` refuses a patch that touches a schema-owning file, or whose own
added or removed lines contain a write statement. The check is strict on purpose: a false positive
costs an argument, a false negative costs the database.

## Adopting a newer upstream commit

A conflict **stops the adoption**. It is not resolved inside the packer, because resolving it there
resolves it invisibly. Decide each conflicted patch deliberately — upstream fixed it (delete it),
upstream moved the code (rewrite it and update the header), upstream changed the behaviour (re-read
the rule above first) — then run the rest of the gate in `docs/MANAGED_HERMES_STRATEGY.md`.
