HG-231 follows the initiating pointer outside the hold button and uses the final release position.
Matching touch-end/cancel events provide a fallback when WebKit omits a pointer end; other fingers
cannot finish the recording. Pointer cancellation, unexpected capture loss, window blur and missed
mouse/pen releases stop capture immediately and preserve partial text in the existing draft without
sending. Explicit Cancel/Edit buttons work even if gesture bookkeeping is missing. One terminal
action owns each recording; duplicate/late events cannot send again. Existing send, deliberate
cancel, final-result wait and five-minute rules remain. DOM regressions cover these paths; physical
iPhone Home Screen Web App acceptance is still required in `../docs/SMOKE_TEST.md` (HG-231).
No Gateway, Connector, Android, protocol, version or error-code changes are required.

HG-216 P1c adds separately capability-gated account model favorites and remembered reasoning.
Legacy stays local; existing local data requires explicit import confirmation. A durable IndexedDB
outbox uses field CAS, signed leases and explicit conflict review. Cloud updates never switch a
live model or reasoning setting. Unavailable favorites remain visible and removable; recents and
group expansion remain local. See `../docs/ACCOUNT_DATA_SYNC_MODELS.md` for schema20 and release gates.

HG-216 adds default-off account-profile sync. When the Gateway advertises version-1 accountProfile,
name/photo edits persist in an account-scoped IndexedDB queue before sending and use field CAS and
stable operation IDs. Foreground pulls preserve typing; conflicts require explicit confirmation.
Sign-out invalidates writers and clears the queue/cache. Existing Gateways keep the legacy editor.
Other open tabs consume the same logout signal and immediately hide private pages even offline;
they do not repeat server sign-out or rebroadcast. Live local-browser/native verification and the
opt-in smoke drivers are documented in `../docs/ACCOUNT_DATA_SYNC_VALIDATION.md`.
This does not enable Hermes identity decorations, pins, read state or prompts; see
`../docs/ACCOUNT_DATA_SYNC_P1A.md` for the exact scope and release gates.

HG-205 keeps chat tables as source tables and creates the shared offline chart only inside
fullscreen. Reliable time/category data gets a local recommendation; unsuitable data stays a table.
Adjust is collapsed, initial data is unfiltered, and applied filters show a range and Restore all.
Long labels use horizontal bars; explicit 100-point pages retain access to every plot point and
exact value. Closing restores the same card and ancestor scroll. The global Data presentation
setting is removed; versioned per-table preferences remain local and scoped. Vite emits the fixed
Android assets at `/app/charts/`; the existing opaque sandbox and CSP stay in force. No AI generation
request is made. See `../docs/DESIGN.md` §5.22 and
`../docs/TABLE_CHART_FULLSCREEN_VERIFICATION.md`.

HG-196 makes voice auto-send use the complete composer: existing text first, then final speech,
plus every pending image/file. The composer clears after handing the message to send; retry keeps
the complete payload. Cancel, edit, recognition failure and an unavailable send retain the draft
and attachments. Cancelling the first bot-send notice restores both text and attachments. DOM and
transport regressions are automated; real microphone/provider/browser checks remain in
`../docs/SMOKE_TEST.md`.

HG-206 makes voice recording last up to five minutes of actual audio on Web and Android. The
last 15 seconds show a countdown; automatic completion waits for the final result and keeps it
in the complete draft, never auto-sending. Release-to-send retains its independent final timeout.
Distinct bilingual failures explain disconnection, final timeout and empty recognition, with actual
text-retention copy. Requires the accompanying 330-second Gateway relay lifetime; deploy Gateway
before clients. Physical iPhone Safari/Home Screen and Android/provider checks remain pending in
`../docs/SMOKE_TEST.md`. No Connector, Desktop, database, credential or wire-format changes.


HG-197 adds the adaptive conversation workspace at `https://mrlgs.net/app/`: automatic list/chat
panes when the container can fit 15rem + 22.5rem + the 1.5rem divider (624 CSS pixels at the default
root size). The drawer's “Large screen layout” choice also offers a single column. Drag the divider,
use Left/Right/Home/End, or reset its width from the drawer; width is remembered without narrowing
the saved preference when the window folds. Chat stays mounted through resizing and pane collapse.
Draft text is scoped to origin/account/Mac/profile/session; legacy text is assigned once on opening.
Unsent attachment Blobs and reading/search snapshots survive conversation switches in page memory
only and clear on sign-out/revocation. Reading restores after Markdown paints; pane navigation waits
for any composer blur history rewind. No Gateway/Connector contract or upstream Hermes change is needed.
Real folding, Android Chrome and iPhone/iPad Safari keyboard/accessibility checks remain in the smoke plan.

HG-201 isolates the gesture that opens a session action sheet: its release click cannot activate
the newly covered menu or scrim. The next independent primary press or keyboard action restores
pointer clicks immediately; non-pointer keyboard/assistive activation remains available. The guard
survives a source row refresh and does not expire while a finger is still held. DOM regressions
cover ordinary, bot and archived rows; iPhone Safari/PWA verification remains in the smoke plan.

HG-202 sets only the iOS chat Files input to `accept="application/octet-stream"` so WebKit opens
the document picker directly; iPadOS desktop user agents are included. Files still supports multiple
selection and the actual selected MIME types, existing attachment validation and input reset.
Camera and Photos keep their existing inputs, and other platforms keep unrestricted Files selection.
Composer regressions cover platform routing, mixed files, cancellation and same-file reselection.
Physical iPhone Safari/Home Screen and iCloud checks in `../docs/SMOKE_TEST.md` are required before
publication; the iOS 26.5 Safari simulator picker experiment is not physical-device verification.

HG-203 aligns the session-list new-chat FAB with Android's themed M3 shape: a 56px rounded square
with 22px corners at the default root size, using rem for both dimensions and corners. Light/dark
and single/split layouts share the shape. The Android and Web rules in `../docs/DESIGN.md` and the
repository design-system prose now reflect it; physical browser checks are in `../docs/SMOKE_TEST.md`.

HG-204 puts a branded cold-start gate in the initial HTML. A small hashed classic script restores
appearance before paint and keeps a 15-second timeout/reload action working even without the main
bundle. The target mounts once underneath the gate: lists wait for their first result, stored chats
for history and Markdown paint, new chats for a live connection. Login, Mac choice and account pages
enter when usable. Background data never blocks entry. The 700ms status delay and 180ms transition
add no minimum hold; reduced motion uses a direct switch. Warm resume and reconnect stay on the page.
`HR-WEB-013` is Web-only; no Gateway, Connector, Android or Desktop contract changes are required.
Physical Safari/Home Screen and Android Chrome acceptance remains in `../docs/SMOKE_TEST.md`.


HG-224 reads compaction-archived display history for chat, older pages, prompt lists, search,
sharing/attachments and full tool/reasoning rows. Pages retain upstream logical order even when
representative row IDs go backwards. Older requests overlap one boundary row and fail safely
with retryable HR-SYNC-001 if the window moved; retry refreshes the tail. Android invalidates its
old active-only disk windows once. Old Hermes without display projections retains the existing
content parser. No upstream code/schema, Gateway/Connector runtime, credentials or versions change.
Real incident history and physical-device acceptance remain in `docs/SMOKE_TEST.md` (HG-224).

HG-228 restores chat run state from `session.resume.running` (or `info.running`) without depending
on a later `session.info` push. Snapshot application stays in WebSocket reader order, before newer
stream frames. Lifecycle inbox changes invalidate the snapshot and re-check Hermes; delayed inbox
entries never directly start or stop a run. Submissions wait for an in-flight snapshot, show the
existing dots/Stop UI while awaiting Hermes, and cannot be restarted by a late submit acknowledgement.
The pre-stream status is not a transcript item; real streaming replaces it without a duplicate.
A reconnecting Stop resolves the live handle and sends the interrupt. No server, upstream, credential,
version or error-code contract changes are required. Physical Safari/PWA and Android Chrome checks
remain in `../docs/SMOKE_TEST.md`.
