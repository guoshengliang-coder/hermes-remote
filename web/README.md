
HG-192 table charts use the fixed, dependency-free renderer packaged under Android's
`app/src/main/assets/table-chart/`; Vite emits the same files at `/app/charts/`. LocalStorage holds
only presentation/filter configuration, scoped to the conversation and table fingerprint. Original
tables remain intact. The Gateway chart CSP changes are required alongside this bundle; ordinary
message HTML remains disabled. See `../docs/DESIGN.md` §5.22 and `../docs/ANDROID_SMOKE.md` for behavior
and device checks. No AI chart-generation requests are made.

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
