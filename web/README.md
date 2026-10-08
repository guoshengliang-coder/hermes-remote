
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
