# HG-257 — related session operations, 2026-11-16

The user explicitly expanded implementation from archive/restore to checking and repairing related
session actions. This supplements the original scope; it does not retroactively claim that the
previous archive-only commit fixed delete or rename. No upstream source is patched.

| Operation | Checked implementation / installed-upstream evidence | Result |
|---|---|---|
| Archive / restore | Completed-handshake RPC/authority REST adapter and existing isolated checks | Keep the preceding fix; no automatic replay |
| Delete | Same owner-checked REST mutation as archive; GO-source cold rows fail. Official standalone `session.delete` accepts the durable ID and profile and refuses active sessions | Connector adapts the existing DELETE contract to official RPC in standalone only; checks exact deleted ID |
| Rename | Same failing REST owner path. `session.title` requires a live runtime and is not a stored-ID replacement | Connector uses installed Hermes' official curated `/api/console` command `sessions rename` in the explicit profile, without resume, inference, child processes, or GO database writes |
| Move project | Already uses official stored-ID `session.workspace.move`; isolated upstream handler changes cwd successfully | No replacement write path needed for the current standalone runtime |
| Pin / unpin | Android PinStore and Web app.togglePin are device-local, with profile/device identity | Not affected by upstream REST ownership; remains local |
| Copy/share/export/history | Local actions or existing read contracts, not the failing PATCH/DELETE route | Code path inspection only; no production interaction claimed |

## Routing, permission and lifecycle boundaries

Connector intercepts only an existing exact session-id DELETE or a title-only PATCH. Combined
metadata writes, extra fields and unrelated paths are not reinterpreted. Its existing authentication
and device tunnel policy remain the boundary; no new browser RPC/console forwarding is opened.
Credentials and WS tickets remain on the Mac. Commands are constructed exclusively from a validated
stored ID and one quoted title; a client cannot supply command names, options or arbitrary lines.
The curated console is the running installed Hermes, not a second checkout or external CLI launcher.

A dedicated authenticated local `/api/ws` handshake selects the mode before any write. Authority
mode retains original REST ownership/revision checks; unknown handshakes perform no
write. Delete never resumes a conversation; rename never calls session.resume or session.title.
Authority DELETE additionally needs expected_generation in this upstream. If the caller omitted it,
Connector reads the exact target mutation-snapshot; it fills the generation only if exists is true,
target ID matches and runtime_revision still equals the caller's original revision. A changed fence
refuses without deletion. Supplied generation is never replaced. A one-shot console socket cannot rebind a chat transport. Its ready/complete/confirmation protocol
is bounded by deadline/frame limits; confirmation is sent only for the exact command resulting from
the user's rename action and never for a different server-supplied command. No provider/agent is built.

HTTP cancellation reaches ticket acquisition, WS work and cleanup. One target/profile is admitted
at a time in the Connector; client writers are route/account bound. Both clients retain request_id
and expected_revision, but no longer replay rename/delete on revision conflict. Missing or wrong-ID
RPC acknowledgements and internal/storage failures are unconfirmed, not permission to try REST.
A Console error may follow partial execution and is conservatively unconfirmed. No automatic writes
follow any failed mutation. HR-SESS-027/028 have bilingual text, redacted details and read-only refresh.

Legacy writes are not CAS. An external client can still act concurrently; matching command completion
is not a promise against later external edits. Mode changes caused by a concurrent service restart
remain a lifecycle race; this does not certify all future upstream modes. Authority workspace movement
is not implemented here: its legacy RPC is forbidden upstream and must not be bypassed.

## Upstream title restrictions

The official curated Console rejects shell syntax even inside quoted text: `|`, `<`, `>`, `;`,
backticks and `$(` are unsupported. Such titles remain rejected; GO does not encode around the policy
or open an unrestricted command channel. Ordinary Unicode, spaces and apostrophes are supported and
covered by the real engine test. Clients retain their 200-character limit. A refused title must not be
reported saved. Console availability/auth and these semantics are part of the next-upgrade gate.

## Verification and delivery

The opt-in `scripts/dev/check-hermes-archive.py` now also exercises actual REST rename/delete
owner semantics, the real curated command engine, its injection refusal, real delete RPC across
GO/Desktop/TUI sources and stored workspace movement. All writes use disposable rows. The fixture
replaces only DB/profile roots and external Git probes; it is not a production network smoke test.
Connector local WebSocket tests cover routing, authority no-write, exact command confirmation,
Unicode/quotes, active/missing/storage failures, unknown mode and cancellation. Existing strict
browser allowlists are unchanged. Client tests cover no replay and new error semantics.

This source change now requires a Connector delivery alongside Android/Web for complete behavior;
shipping only the app changes cannot install the Mac-side adapter. Gateway routes/wire format,
Desktop UI, versions and signing are unchanged. Publication remains separately authorized.
Physical-device, real-browser-to-Mac, live network interruption, Basic Auth Console-ticket integration
and multi-client concurrency acceptance remain outstanding. No production session was changed.

### Final local results

- Android unit tests and assembleDebug passed: 3,032 tests, zero failures/errors. L1 includes the
  new generic mutation-error dialog; its rendered Chinese screenshot was inspected. L2 has no
  attached device; available L3 was not run. No APK distribution was produced.
- Web: 105 test files / 1,093 tests, typecheck and production build passed.
- Node build and suites passed: protocol 27, Connector 189, Gateway 237, release server 41,
  scripts 548; 33 environment-dependent tests skipped. The invocation uses the already installed
  OpenSSL 3 for the host's legacy recovery fixtures; no tool paths were committed.
- Installed-upstream disposable validation: 35 checks passed at
  bf0203a794a07aac3ff764972ab9087baa4e4f4e. No upstream Git changes or production mutations.
- First expanded fixtures caught the missing delete generation and duplicate synthetic titles;
  the fixtures now use full generation fences and distinct titles. Android initially caught old
  error-code assertions and an overly strict selected-Mac check; these were fixed and the full
  baseline rerun. Explicit historical-Mac targeting still works, and route/account changes reject
  stale completion. No test was weakened to accept invalid_params as a successful deletion.

Only local source changes are delivered. No push, PR, integration registration, merge, version bump,
service restart, client installation or publication has been performed. HG-257 remains in progress.

## Related-operation audit follow-up — three rounds, 2026-11-16

The user requested at most three rounds with repairs. These are separate evidence-led passes by
this implementation session, not an external human review or independently staffed Agent review.
After round 3, remaining runs only verify its repairs; no fourth audit round was started.

1. **Exact target and acknowledgement.** The official curated rename resolves prefixes/aliases,
   whereas the adapter had trusted `complete: ok` alone. A read-only exact-ID mutation-snapshot now
   proves the stored target exists before opening Console and compares any supplied original
   revision. A successful rename additionally requires the official stdout acknowledgement naming
   the exact ID and title; its command/run ID must match the subsequent completion and differ from
   the confirmation request. Missing/wrong-target/wrong-title output is unconfirmed, never success.
   Four new negative cases failed against the preceding code and passed after repair.
2. **Connection lifetime and response ownership.** Closing the mode-probe socket had not cancelled
   the separate Console socket. Its lifecycle signal now reaches child ticket acquisition, snapshot,
   Console and authority HTTP work. Tests cover probe loss before command, cancellation after the
   snapshot, interleaved delete/rename and release of the per-target guard. Tightening this scope
   also exposed a repair regression: disposing it could abort a returned authority Response body.
   The bounded authority body is now drained before returning a detached Response; an incremental
   body regression test failed before that fix and passed afterwards. The probe-loss test likewise
   failed on the previous implementation. A write already sent before loss remains uncertain.
3. **Input contract and upgrade evidence.** Duplicate query parameters could make Connector read
   the first profile while FastAPI uses the last. All duplicates are refused before I/O. Present
   counters must be nonnegative safe integers (canonical decimal query syntax); request IDs must
   be nonempty, at most 128 characters and control-free. PATCH fence types follow the same rule.
   Absolute, authority-relative and normalized traversal paths are never adapted. Twelve added
   negative cases failed before repair and passed afterwards. Real-upstream validation adds exact
   Console output, demonstrated prefix resolution and the snapshot's exact-ID refusal, bringing the
   installed-source probe to 38 passed checks.

The exact-ID snapshot and the English Console output grammar are explicit upstream dependencies,
not a promise of stable version negotiation. Unknown or missing fields/output fail closed. This
preflight is not atomic with legacy rename: external deletion/replacement or edits can still race
it. No CAS claim, authority bypass, unsupported-title workaround or automatic write retry is added.
Existing active-delete, shell-syntax title, authority workspace and physical acceptance limits above
still apply.

Validation: focused Connector mutation suite 32 passed; full Node build/test passed (protocol 27,
Connector 209, Gateway 237, release server 41, scripts 548; 33 environment-dependent tests skipped).
The real installed upstream probe passed 38 checks at bf0203a794a07aac3ff764972ab9087baa4e4f4e;
its Git worktree remains clean. git diff --check passed. Android/Web source did not change in this
follow-up, so their prior 3,032 / 1,093 successful baselines were not repeated. No device, browser
production end-to-end, live Basic Auth Console integration or external-client race verification was
performed. No push, PR, integration registration, merge, version bump, restart or publication.

## HG-271 active delete feedback

Connector reports HR-SESS-031 for standalone delete RPC 4023 or a confirmed authority
`session_busy` refusal on the fenced DELETE. Android presents read-only refresh and preserves the row.
The user can confirm again after the runtime releases the conversation. Authority revision and
generation fences, generic refusal and uncertain outcomes retain their existing behavior.
