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
