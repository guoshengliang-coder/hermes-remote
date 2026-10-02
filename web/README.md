
HG-192 table charts use the fixed, dependency-free renderer packaged under Android's
`app/src/main/assets/table-chart/`; Vite emits the same files at `/app/charts/`. LocalStorage holds
only presentation/filter configuration, scoped to the conversation and table fingerprint. Original
tables remain intact. The Gateway chart CSP changes are required alongside this bundle; ordinary
message HTML remains disabled. See `../docs/DESIGN.md` §5.22 and `../docs/ANDROID_SMOKE.md` for behavior
and device checks. No AI chart-generation requests are made.
