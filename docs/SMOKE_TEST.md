# Local relay smoke test

Start the gateway and mock connector as described in the root README. Connect a WebSocket client to `ws://127.0.0.1:8787/v1/connect` and send:

Before opening the Connector, verify the release probes. With account mode disabled, both requests
return `200`; `/readyz` reports the database and migration checks as `disabled`/`not_required`:

```bash
curl --fail http://127.0.0.1:8787/healthz
curl --fail http://127.0.0.1:8787/readyz
```

```json
{"type":"hello","version":1,"role":"app","deviceId":"android-dev","token":"dev-app"}
```

Then send:

```json
{"type":"command","version":1,"id":"request-1","targetDeviceId":"mac-mini","payload":{"kind":"chat","input":"hello"}}
```

Expected terminal event:

```json
{"type":"event","version":1,"requestId":"request-1","event":"complete","data":{"sessionId":"request-1"}}
```

## Hermes compatibility smoke test

The repository also includes a protocol-shaped Mock Hermes server that requires the same Basic Auth → Cookie → WS Ticket sequence as the real dashboard.

Run the Mock Hermes server, Gateway, and Connector on separate terminals, then run:

```bash
npm run smoke:compat
```

The test passes only after `/api/status` traverses the REST tunnel and a JSON-RPC request traverses `/api/ws` in both directions.

### Stalled control-channel recovery (HG-90)

Automated unit and loopback tests cover the cancellation wire message and the Gateway heartbeat.
For an end-to-end fault injection, run the local stack and verify all three boundaries:

1. Pause the Connector process longer than `CONTROL_HEARTBEAT_TIMEOUT_MS`. The Gateway must log
   `connector.heartbeat_timeout`, close the stale control socket, and report the device offline;
   after the process resumes, the Connector must reconnect without a Gateway restart.
2. Start a slow `/api/*` request and abort its HTTP client. The Connector must receive exactly one
   `tunnel.http.cancel` with `client_aborted`, log `http.cancelled`, and stop the local Hermes fetch
   or response-chunk ACK wait. Repeat by letting `REQUEST_TIMEOUT_MS` expire; the reason must be
   `gateway_timeout`, with no late response forwarded to the abandoned client.
3. While Android shows the unhealthy strip, allow a different Connector-routed `/api/*` request to
   return 2xx. Android must immediately re-probe `/api/status`; only a successful probe clears the
   strip, and the WebSocket state must remain unchanged. Relay-owned `/api/mobile/events`,
   `/relay-health`, and `/health` must not trigger this recovery probe.

During a rolling upgrade, deploy the new Connector before relying on request cancellation. The
additive message is safe with an old Connector, but only the new Connector consumes it.

With `HERMES_MODE=live`, the Connector also opens a private observer socket. An account-mode
Connector (`CONNECTOR_MODE=account`, which is what Desktop's managed LaunchAgent runs) defaults to
`live` when `HERMES_MODE` is unset; a legacy Connector still defaults to `mock`, which echoes commands
and never starts the observer (HG-101). Confirm its log contains
`Hermes lifecycle observer connected`, then query the Relay inbox without exposing the token in the
URL:

```bash
curl -H "X-Hermes-Session-Token: $APP_TOKEN" \
  "http://127.0.0.1:8787/api/mobile/events?after=0&limit=20"
```

Start and finish a Hermes task from another local client. Expect exactly one ordered set of lifecycle
transitions, no prompt/tool/file content in the JSON, and no duplicates after restarting the
Connector. Stop the Mac Connector and repeat the GET: persisted events must still be available.
After noting an event ID, verify both idempotent state routes:

```bash
curl -X POST -H "X-Hermes-Session-Token: $APP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"event_ids":["EVENT_ID"]}' \
  http://127.0.0.1:8787/api/mobile/events/ack
curl -X POST -H "X-Hermes-Session-Token: $APP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"event_ids":["EVENT_ID"]}' \
  http://127.0.0.1:8787/api/mobile/events/read
```

### Connector contract check against a real Hermes (added 2026-09-21)

Automated coverage stops at fixtures (`connector/src/hermes-contract*.test.ts`) and Android JVM
tests. Against a live stack:

1. Start the Connector against the real local Hermes. Its log carries exactly one
   `"kind":"hermes.contract"` line with `"status":"compatible"` and the Hermes version.
2. Read the report through the Relay, as the phone does:

   ```bash
   curl -s -H "X-Hermes-Session-Token: $APP_TOKEN" http://127.0.0.1:8787/api/hermes-remote/contract
   ```

   Expect `"schema":1,"status":"compatible","missing":[]`. Repeat it: the Connector must not log a
   second `hermes.contract` line (the result is cached).
3. Restart Hermes (not in production without the owner): the observer reconnects and the check runs
   again; an unchanged result still logs nothing new.
4. Breaking path, dev stack only: point the Connector at a mock whose `/openapi.json` omits
   `/api/sessions/{session_id}/messages` (for example serve
   `connector/fixtures/hermes-openapi/hermes-missing-required.json`). The report turns `breaking`
   with `HR-COMPAT-001`; the phone's health strip turns red with 「Mac 上的 Hermes 不兼容」, its sheet
   lists 历史记录 and shows `HR-COMPAT-001` on its own line; chat and every other route still relay.
5. Unknown path: make `/openapi.json` answer 404. The report is `unknown`, the phone shows nothing.

Device status: **not yet run on a device** (L2) as of 2026-09-21 — only L1 (JVM) covers the strip.

### Approval and clarify against a real Hermes

Automated coverage stops at a mock: the question protocol changed between Hermes f159e581 (events +
`*.respond`) and 17b5df02 (server→client requests; `docs/HERMES_CONTRACT.md` section 3), and each
way it can break is silent — no card appears. Run this against every Hermes the phone is about to
talk to, on a device or emulator connected through the real Gateway and Connector:

1. Open the in-app diagnostic log after connecting. On a new Hermes it must show
   `server requests advertised (gen=…); Hermes may ask: approval,clarify,…`; on f159e581
   `client.capabilities unsupported (…, code=-32601); questions arrive as events`. Neither may be
   missing.
2. Ask the agent to run a command that needs approval (e.g. `rm -rf /tmp/hr-approval-probe`). The
   approval sheet must appear in the chat and, with the app in the background, as a notification.
   Approve once from the **notification shade**; the command must run. Repeat and deny from the
   sheet; the agent must report the denial.
3. Ask for something that makes the agent use clarify with one question, answer it; then with
   several questions (a batch), answer the first, force-stop the app, reopen the conversation: the
   batch card must come back with the first answer already ticked. Finish it; the agent must quote
   every answer.
4. Leave an approval unanswered until Hermes times it out (or press stop): the card and its
   notification must disappear by themselves (`request.cancel` on the new protocol).
5. New Hermes only: with the phone disconnected (airplane mode) while the agent asks, reconnect and
   open the conversation — the card must be there (`open_requests`), and answering it must work.

The same flow runs locally against the dev mock with `HR_MOCK_SERVER_REQUESTS=1` (every run also asks
one `sudo`, which the app must refuse with -32601 — the mock logs `answered with error`), and without
the flag for the old protocol. With that flag (or `HR_MOCK_STRICT_PARAMS=1` alone) the mock also
refuses any params key 17b5df02 does not declare with 4000, from `docs/hermes-rpc-params.json`, and
logs `refused 4000` — a line that must never appear while using the app.

### Android notification and battery checks

1. Enable notifications and keep **Smart** selected. While the app is open, create and complete a
   task from another client; the event should appear within a few seconds without a permanent
   foreground-service notification. Its session row should move through running, waiting (when
   applicable), and completed/unread states.
2. Start a task from Android, put the app in the background, and confirm one ongoing service
   notification ("后台保持连接 · 正在监控 1 个任务") plus one silent per-session progress card
   (session title, "运行中", elapsed timer, tool name) remain while the run is active. Complete the
   task and confirm the SAME card turns into "已完成" with the reply snippet and duration, and the
   service stops without removing that card.
3. With no Android-started task active, leave the app in the background. Confirm there is no
   persistent service and no idle gateway ping loop. A task completed elsewhere should be found by
   the next OS-managed periodic check; Android may defer that check beyond 15 minutes.
4. Repeat a delivered Relay batch or restart Android between notification delivery and cursor
   persistence. Confirm the session's single card is updated rather than duplicated, and that a
   completion already delivered by the live socket (and already read) does not come back as a
   second "已完成" card.
5. Select **Real-time** and confirm the background service remains present. Select **Power saving**
   and confirm the service stops even when a locally started run is still active.
6. Deny notification permission and verify chat remains functional. On Android 13+, re-enable the
   permission in system settings and repeat the lock-screen and heads-up checks for each channel.
7. Card rules (2026-09 one-card-per-session rework): trigger an approval while the app is in the
   background — expect a heads-up card titled with the session name, header "需要审批", the command
   in the body, and Allow once / This session / Deny buttons. Approve it inside the app instead and
   confirm the card disappears immediately. Trigger a clarify with two choices — expect the two
   choices as buttons plus "回复…"; tap one and confirm the card shows "处理中…" then moves on.
8. While viewing the chat that is running, confirm no card for that session is showing; navigate
   to the session list (app still foreground) and confirm its card appears silently (no sound, no
   heads-up). Lock the phone with the chat still open and confirm the card is posted normally.
9. Run two sessions from two profiles at once: expect two progress cards with different accent
   colours matching the in-app avatars, the profile name in each header, and a group summary line
   ("2 个运行中"). On a ROM that replaces the small icon with the launcher icon, the header text
   must still identify the profile.
10. Swipe a running card away: it must not return until the run finishes; the "已完成" card must
    then appear. Open that chat and leave it: the card must not come back.
11. Put the phone in airplane mode, press Deny on an approval card: expect the card to show
    "发送失败，请重试" with HR-NOTIF-001 and the buttons restored; restore the network and retry.
12. On Android 16, confirm the running card is promoted to a status-bar Live Update with the
    "n/m" chip when the run reports todos; with two runs the system picks one — no crash, no
    cross-overwrite.
13. Foreground-service stop race (2026-09 branch claude/fgs-stop-race): with a locally started
    run active, swipe the app to the background and back to the foreground as fast as possible,
    ten times in a row; also background the app in the last second before the run completes.
    The app must never crash with `ForegroundServiceDidNotStartInTimeException`. In the debug
    log, a `stop deferred: startForeground still pending` line followed by `stop deferred past
    startForeground; stopping now` shows the gate handled the race. The service notification
    may flash for an instant in that case; afterwards it must be gone while the app is in the
    foreground.

For a deployed relay, set `PUBLIC_GATEWAY_URL` and `APP_TOKEN` in the invoking shell before running the same script. Keep the token out of command history and source control. A successful real-Hermes run accepts `gateway.ready` and any non-error result from `session.create`.

## Attachment and large-response smoke test

With Gateway and Connector running locally and `FILES_ROOT` pointing at a dedicated test directory:

1. Upload a small text, PDF, and processed photo through the Android attachment sheet. Confirm each
   appears on the outgoing user turn and Hermes receives the attachment before the prompt.
2. Capture a photo with the system camera, cancel once, then capture successfully. Confirm cancel is
   harmless and the successful capture produces a thumbnail.
3. Have Hermes return `@image:/absolute/path/to/image.png` and `@file:/absolute/path/to/report.pdf`.
   Confirm the image opens full screen and the file card opens/shares through the Android system UI.
4. Download a file larger than the former whole-message ceiling (for example 24 MiB but below
   `MAX_FILE_BYTES`). Confirm the download completes and the Connector remains online afterward.
5. Attempt a path outside `FILES_ROOT`, an upload above `MAX_UPLOAD_BYTES`, and a download above
   `MAX_FILE_BYTES`. Expect request-scoped 403/413 errors with no control-WebSocket disconnect.

## Single-screen navigation and profile-scope smoke test (2026-08 redesign)

These flows need a device or emulator against a running Gateway/Connector; JVM unit tests cover the
logic but not the interaction feel.

1. **Card page and profile switch.** From the session list tap the top-left avatar. Confirm the
   card page opens with the current identity hero, other profiles (with running/waiting sub-lines
   when applicable), usage/remote-device tiles, and the cron/settings/update entry rows. Switch to
   another profile: the list, projects, archived, cron, usage, models, skills, and messaging
   screens must all show ONLY that profile's data afterwards.
2. **Switch failure.** Stop the Connector (or drop the network) and attempt a switch. Confirm a
   "couldn't switch" toast, the avatar and list stay on the previous profile, and nothing renders
   the target profile's data.
3. **Needs-you and collapsing.** Drive a session into an approval wait. Confirm it jumps to the
   需要你处理 group at the top; collapse each group header and confirm counts stay visible and the
   state survives rotation.
4. **Cron alert strip.** Make a cron job fail (or overdue). Confirm the strip appears above the
   list, opens the cron screen, and disappears once resolved.
5. **Archived segment.** Archive a session, switch the segment control to 已归档, unarchive it, and
   confirm it returns to the list without a restart.
6. **Search.** Open search from the top-right icon: title matches must appear instantly (archived
   rows tagged 已归档), and the keyboard search action must return message-content matches. All
   results stay within the active profile.
7. **Legacy deep links.** Send `hermes://tab/activity` and `hermes://tab/you` (e.g. via `adb shell
   am start -a android.intent.action.VIEW -d ...`). Both must land on the session list, never
   crash. The launcher widget must show only New chat and Chats.
8. **Remote device tile.** With the Connector attached, confirm the card page shows its DEVICE_ID
   and Connected · latency; kill the Connector and confirm the tile flips to offline.

## Startup recovery and navigation smoke test (0.1.66)

Run these flows on a device or emulator against a real Gateway/Connector. They guard the startup
and navigation regression that could pass JVM tests while making every pushed screen unusable.

1. **All entry points stay open.** From Chats, open an existing conversation and create a new one
   with the add button. Then open Search, Models, Cron and Settings. Every destination must remain
   visible until Back is pressed; none may flash and immediately return to Chats.
2. **Unread transition.** Complete a conversation while it is off-screen and confirm its blue unread
   dot appears. Tap the row: the conversation must open, its history must finish loading, and the dot
   must clear rather than reappearing because navigation was rejected.
3. **First pairing readiness.** Clear app data and open the app. The setup page must appear directly,
   without a decorative startup delay. After a valid Relay URL and App Token are saved, the startup
   page must complete connection, profile loading, and the initial session snapshot before Chats is
   revealed; Chats must not show another full-screen loading state.
4. **Healthy warm return.** Background the connected app from a conversation and return without
   interrupting the connection. No startup page should appear and the same conversation/scroll
   position should remain visible.
5. **Interrupted warm return.** Background the app, interrupt its connection, then return. The
   startup page must remain until the exact visible destination is refreshed. Repeat from an open
   chat, flat Chats, an open project, Archived, Search and Models; no destination should show a second
   full-screen loading state after the startup page disappears.
6. **Failure routing.** With phone networking disabled, the startup page must show the network error
   and Retry. With the Connector stopped but Relay reachable, it must report that the Mac/Desktop is
   offline (HR-CONN-005). With an invalid URL or rejected token, it must open the prefilled connection
   repair page; saving valid values must rerun readiness and return to the page that was interrupted.
7. **Connection-test race.** Start a connection test, edit the URL or token before it completes, and
   confirm the obsolete result is discarded. While a test is active, Test and Save stay disabled.

## Model selector smoke test (2026-09-12 Stitch 基线-模型选择 重做)

JVM unit tests cover the grouping/collapse logic, the recents ordering, override tracking and error
codes, and seven Roborazzi goldens cover the light/dark/switching/bare/failed/large-type looks
(`app/screenshots/model-select*.png`). What is below still needs a device or an emulator against a
running Gateway/Connector, and all of it is pending device verification for the current iteration —
the 2026-09-11 attempt could not reach a Relay from either phone, so no model catalogue loaded.

1. **Default open state.** In a chat, tap the model chip. The sheet must open on: the status card
   (6dp blue stripe, model name, 「当前使用」 pill, `提供商 · 跟随默认`/`此对话覆盖` subline,
   推理强度 dropdown row), the 快捷切换 chip row, then the 收藏模型 card and one card per provider.
   The current model's row is filled and ringed in blue with NO check mark — the highlight is the
   check mark. The current model's provider card is expanded; the others are collapsed to a
   「name + N 项 + chevron」 bar. There is **no search box** — it was removed on 2026-09-12.
2. **Collapse.** Toggle a few provider cards: a collapsed card keeps its bar and its 「N 项」 count
   and drops its rows. Favourites never collapse. Leave and re-enter the sheet: collapse state is
   per-visit (`rememberSaveable`), not persisted across launches.
2b. **快捷切换 (new 2026-09-12).** Switch models three or four times, then reopen the sheet: the chip
   row lists the most recent five, newest first, with no duplicates. Tap one — while the switch is
   in flight that chip rings blue and spins, every other chip dims and goes inert, the title says
   「正在切换至 X」, the target row in the list shows 「切换中…」 and the row that was in force shows
   「前次生效」. The chip for the model already in force is present but not tappable.
3. **Session override loop.** Switch the model with scope 此对话. The chip stays in its plain
   style (name + caret — no tag, no tonal background); reopen the sheet and confirm the summary
   strip reads 此对话覆盖 with a 恢复默认 action. Tap 恢复默认: the session returns to the default
   model and the summary flips back to 跟随默认.
4. **Spaced model names.** Pick a model whose name contains spaces or parentheses (OpenRouter often
   has them) with scope 此对话. Confirm the switch succeeds — the app quotes `/model` arguments,
   and the upstream slash parser's handling of quoted arguments has NOT yet been verified against a
   live Hermes.
5. **Scope separation (2026-09).** The chat sheet has NO scope control — selecting a model always
   switches only the current chat. The profile default is edited only on 设置 › 模型: change it
   there and confirm the top summary card and row highlight update; already-open chats keep their
   model until reopened.
6. **Failure surfaces.** Drop the Connector and attempt a switch: the sheet must stay open showing
   HR-RPC-004 (session) or HR-RPC-005 (default); the model list failure state must show HR-RPC-003
   with a working Retry.
7. **Composer chip layout.** With a very long model name active (e.g. an OpenRouter
   `vendor/model:variant`), the mic, attach (+), and send controls must keep their exact size and
   position — the chip ellipsizes inside the leftover width instead of displacing them. Also check
   the short-name state (chip hugs left) and the pre-load state (默认模型 placeholder).
8. **Warm catalog (2026-09 startup prefetch).** Cold-start the app, wait a few seconds, then tap
   the model chip: the sheet must show the list immediately — no loading spinner. Background the
   app, return (warm start), open the sheet again: still instant. Airplane-mode cold start: the
   sheet falls back to the old loading→error+Retry path, and reconnecting refreshes the catalog
   automatically. After changing the default in Settings › Models, the chat sheet's 当前 markers
   must reflect it without a manual reload.
9. **Manual refresh (2026-09).** Add/remove a model upstream, then tap the refresh icon in the
   sheet's title row (or the 设置 › 模型 top bar): the list must update and the icon must be
   replaced in place by the **working ring** (`RunSpinner` — a blue arc over a faint full circle),
   NOT the brand H mark. Same ring on the row and chip being switched to. The brand mark is only
   correct on the first-load 「正在加载模型列表…」 state, where the sheet is otherwise empty.
   Offline, the tap is a silent no-op (no crash, no error toast).
10. **Reasoning effort (dropdown since 2026-09-12).** In the status card, open the 推理强度
   dropdown: it lists 关 plus the seven levels, with the session's effective level
   (`config.get key=reasoning`) marked and the scope note 「仅当前对话；该模型的选择会被记住」
   at the foot of the menu. 关 is the upstream `none` level — there is no separate 思考 toggle
   any more. Pick a level:
   the chip suffix in the composer updates (e.g. `fable-5 · 高`), and the choice is remembered for
   that model — switch to another model and back, and the remembered level is re-applied to the
   session (each row shows its remembered level as a small badge beside the name). Turning 思考 off maps to `none`;
   a failed change must roll back and show HR-RPC-006 in the sheet. The upstream `config.get/set
   {key:"reasoning"}` RPC has NOT yet been verified against a live Hermes from this app.

## Sheet polish smoke test (2026-09 branch claude/ui-polish-sheet-scroll)

Device/emulator flows for the surface-token, sheet-layout, gesture, and list-reveal changes.
All pending device verification.

1. **No purple cast anywhere.** Open every bottom sheet (model, approval, persona, attach,
   prompt, theme, session menu, health, clarify, notification onboarding, avatar color) plus
   menus/dialogs, in BOTH light and dark theme: containers must read as the cool blue-tinted
   neutrals, never the old reddish/purple cast.
2. **Model sheet layout.** Title 选择模型 centered with the refresh icon at the right; ONE
   status card holds the current model (scope line, 恢复默认 when overridden) above a hairline
   and the 推理强度 row below it, expanding inside the card; the search field is a filled
   rounded box.
3. **Model sheet gestures.** Scroll the model list up and down aggressively — the sheet must
   never collapse or close. Closing works only via: tapping or short-dragging the top grab bar,
   tapping the scrim, or Back.
4. **Sheets open full.** Approval, persona and saved-prompt sheets with tall content must open
   fully expanded — no half-open state that needs a manual pull-up. The approval sheet still
   refuses swipe-dismiss.
5. **Needs-you reveal.** With the list at top, drive an off-screen session into an approval
   wait: the list must auto-scroll so the 需要你处理 header and row are visible. Repeat while
   scrolled deep into the list: no yank — a ↑ pill appears; tapping it jumps to top; scrolling
   to top yourself dissolves it.

## Turn navigation smoke test (2026-09 branch claude/turn-jump)

Device/emulator flows for the turn-jump pill and the 我的提问 list (docs/DESIGN.md §5.4). The
grouping, visibility and list-row logic is covered by `TurnJumpTest`; the pill and rows have
Roborazzi goldens. Items 1–4 were exercised on the Pixel 9 API 36 emulator against the mock
Hermes stack on 2026-09-02 (three long exchanges); items 5–7 and a real device still need
verification.

1. **Pill appears only in history.** Open a chat with at least three exchanges whose answers are
   taller than the screen. At the bottom, while an answer streams, no pill. Scroll up into the
   latest answer until its question leaves the screen: a pill with that question's first line
   fades in at the top of the list. Keep scrolling into the previous exchange: the pill text
   changes to that question. Scroll back down until a question bubble is on screen: the pill fades
   out. Direction never matters — stop mid-answer and scroll a little either way.
2. **Tap aligns the question to the top.** Tap the pill: the list animates (when the bubble was
   already near) or snaps so that the question bubble sits just below the top of the list, then
   the pill disappears because the bubble is visible. Repeat from far away (five screens of
   answer) — the landing position must be the same, and the newest-message edge must not be
   overshot when the target is the latest exchange.
3. **Split pill deep in history.** Scroll up past the second exchange from the end: the pill
   grows a right segment with a list icon (150ms). Tap it: the 我的提问 sheet opens with the
   current exchange highlighted and scrolled into view. Scroll back into the last two exchanges:
   the segment collapses.
4. **Menu entry.** From the top-right ⋮ menu, 我的提问 is the first item and opens the same
   sheet anywhere, including while pinned to the bottom (the current row is then the exchange
   under the top of the screen). Pick a row: the sheet closes and the list lands with that
   question at the top, exactly like the pill.
5. **Leading content.** In a chat that starts with a Hermes message (greeting, scheduled task
   output) before any question, scrolling into that block shows a 会话开始 pill; tapping it goes to
   the very top. The sheet lists 会话开始 as a grey first row without a time.
6. **Search and rotation.** Open in-chat search: the pill sits below the search row and still
   works; search-hit navigation does not leave the pill flickering. Rotate while the pill is
   showing: the pill reflects the new viewport within a frame or two.
7. **Attachment-only prompts.** A question that is only an image or a file shows 图片 / 文件：<name>
   in both the pill and the sheet.

## Profile identity smoke test (2026-09 branch claude/identity)

Device-only checks for per-profile display name, avatar photo, colour, and style
(`docs/DESIGN.md` §2.4 and §5.10). The JVM suite covers the store, migration, resolver, contrast
invariants, and the edit view model; the items below still need a phone or emulator with a working
Relay connection.

1. Card page → identity card → 身份: every row shows a pencil button; tapping the row still
   switches profile, tapping the pencil opens 身份设置 for that profile without switching.
2. In 身份设置, type a display name and save. The card page shows the name as the big line and
   the profile name underneath; the picker row shows the name with `profile · 当前身份`. Clear the
   name with × (the field shows the profile name as placeholder), save, and confirm both surfaces
   read exactly as before the change. Chat and session-list surfaces never show the display name.
3. Choose a photo through the system picker (no storage permission prompt may appear). The 96dp
   preview updates immediately; 保存 becomes enabled; after saving, the 36/44/48dp avatars on the
   session list, card page, and picker all show the cropped square photo. Pick a portrait and a
   landscape photo and confirm both are centre-cropped, and an EXIF-rotated camera photo appears
   upright on Android 9+.
4. Pick a photo, then press back: the 放弃更改？ dialog appears; 放弃 returns without changes and
   the temporary file is gone (`run-as com.hermes.remote ls files/avatars`).
5. With a lettered avatar, switch 实心 / 空心 and drag the hue slider: the preview follows live;
   the swatch check moves to the first (default) circle only when no custom colour is set. In the
   dark theme the outline ring and initial stay clearly visible (lifted colour), and the solid
   fill looks identical to light.
6. Start a run on a profile with a custom colour and confirm the notification accent uses it.
7. Upgrade from a build that stored avatar colours in the old `avatar_colors` DataStore: the
   previously chosen colours appear as the selected swatch on first open of 身份设置.
8. Break the photo pipeline (pick a non-image or corrupt file if the picker allows it) and confirm
   the toast reads 无法读取所选照片，请换一张再试。 (HR-MEDIA-002) and the previous avatar stays.

## Share transcript smoke test (2026-09, three formats)

Device flows for the share-format picker. Pending device verification.

1. **Picker appears.** 更多 › 分享对话 opens the format sheet (not the system share sheet) with
   three rows: 文字 / Markdown 文件 / 长图. An empty conversation still shows the
   "暂无可导出的内容" toast instead of the picker.
2. **Plain text.** Unchanged behaviour: shares as text into WeChat/mail.
3. **Markdown file.** Share to a file-capable target (mail, cloud drive, Obsidian). The received
   file is `HermesGO-<title>-<stamp>.md`; opening it shows the title, the metadata line (time,
   model, Hermes GO) and `## 你` / `## 助手` sections, with code fences and tables intact. Try a
   session whose title contains `/`, `:` or emoji — the file must still save.
4. **Image, short conversation.** Shares one PNG containing the whole conversation, rendered in
   the LIGHT theme even when the app is in dark mode, with the footer showing title + date.
   Cached inline images appear; images never downloaded show a placeholder box.
5. **Image, long conversation.** A long transcript must NOT render: the picker closes and a toast
   suggests the Markdown file instead. Nothing crashes and no partial image is shared.
6. **Failure surfaces.** If file creation or rendering fails, the toast carries HR-FILE-002 or
   HR-MEDIA-003 respectively.

## Search smoke test (2026-09 branch claude/search-v1)

Automated: `SearchQueryTest`, `HermesRestApiSearchTest`, `SearchTextTest`, `RecentSearchesTest`,
`SearchViewModelTest`, `ChatSearchHighlightTest`, `ChatRouteTest`, `AppErrorTest`. The items below
need a phone with a working Relay connection.

### Production probe record (Mac mini, 2026-09-03, read-only)

- Store: `~/.hermes/state.db`, 43k messages. FTS tables present: `messages_fts` (unicode61) and
  `messages_fts_trigram`; **no `messages_fts_cjk`** (the loadable CJK tokenizer is not built on the
  mini), so CJK queries take the trigram path (≥ 3 CJK chars) or the LIKE full scan (1–2 chars).
- `SessionDB.search_messages` direct calls: `的历史记录` → 1 row in 2 ms (trigram);
  `的历史记录*` → **0 rows** (the gateway route appends `*` to every unquoted token);
  `"的历史记录"` → 1 row; `的历` → 50 rows in ~1.5 s (LIKE scan); `的历*` → 0 rows;
  `gradle` → 50 rows in 4 ms (fts5). This is why the client quotes CJK tokens.
- Snippet shape varies by path: ~22–51 chars around the match (trigram), 120 chars starting 40
  before the match (LIKE), ~330 chars with an ellipsis (fts5). No markup markers. The client
  re-centres to ±40 chars.
- The HTTP route (`/api/sessions/search`) enriches each hit with `title`, `last_active`,
  `archived`, `source`, `message_count`, `preview`. Gateway round-trip from the phone was not
  measured (needs the app on a configured device); server-side search is single-digit ms except
  the 1–2 char CJK LIKE scan.

### Device cases

1. Open search from the list. With an empty field the recent searches (if any) are listed with
   ×; type one character and confirm the hint 输入至少 2 个字符可搜索消息正文 appears when no
   title matches. Type a two-character **mid-sentence Chinese fragment** from a known chat and
   wait: within ~1 s the 消息匹配 section shows the hit with the title as the first line, a
   relative time on the right, and the fragment highlighted in the two-line snippet. Repeat with
   an English word (prefix, e.g. `grad` for `gradle`).
2. Edit the query (append characters): the previous query's message rows disappear at once and
   the header reads 搜索中… until the new results land. Press the keyboard Search key mid-debounce:
   results arrive without waiting.
3. Turn off Wi-Fi and mobile data, search: the message section shows the error strip 消息搜索失败，
   请重试。 (HR-SEARCH-001) with 重试 while title matches remain. Long-press the strip and paste
   somewhere: the diagnostic contains the code and no token. Restore the network and tap 重试:
   results appear.
4. Tap a message hit: the chat opens with the search bar in the top bar's place, the query filled,
   the counter at 1/N, and the matching words marked inside the text (Markdown body, user bubble).
   The turn itself carries NO outline, fill or shadow (HG-46) — the marks are the only marking.
   Tap ↓ repeatedly: the counter advances, the transcript follows, and within a turn that has
   several marks the solid brand-coloured one moves from mark to mark (HG-45). A hit is never
   inside 查看思考过程 or a tool card: those are out of the search scope and draw no marks.
4a. With the search bar open, the message composer at the bottom is gone (HG-46) and the transcript
   still sits above the keyboard rather than under it; the 「内容由 AI 生成」 footer goes with it.
   Type a draft first, then open search, then close it: the composer comes back with the draft
   intact. Neither the turn-jump pill nor the 回到最新消息 button appears while search is open.
5. In a chat, search for a word that does not occur: the bar reads 此会话中没有匹配 with
   在全部会话中搜索; tap it and confirm the search screen opens with the query filled and the
   message search running by itself.
6. Rotate the phone with the search bar open, then press back: the bar closes first (chat stays);
   press back again to leave. Re-enter the chat from the list: the search bar is not re-opened.

## Usage figures smoke test (2026-09 branch claude/usage-data-correctness)

Automated: `UsageMathTest` (calendar filling, the week window against sparse rows, main/auxiliary
reconciliation, empty-vs-failed, error mapping, wire parsing with null aggregates), `ErrorColorsTest`.
Verified on the Pixel 9 emulator against `scripts/dev/dev-stack.sh`: empty state, a populated page
whose figures reconciled to the fixture, and the drawer stat cell. The cases below need a phone with
a working Relay connection and a profile that has real history — the emulator path cannot produce
sparse real-world data, a genuinely offline Mac, or a stalled tunnel.

### Device cases

1. Open 用量 from the drawer's 本周用量 cell. Confirm 主对话 / 辅助 is a real split (not `x / 0`
   unless the profile truly has no auxiliary traffic) and that the model rows' token figures add up
   to at least the 主对话 figure — `by_model` includes auxiliary spend, `totals` does not, so the
   rows summing higher is correct and the reverse is a regression.
2. Check the drawer cell against the page: 本周用量 covers seven calendar days, the page covers the
   window named in the footnote, so the cell should be the smaller number. On a profile used once a
   month the cell must read 0 for a quiet week rather than reaching back to the last active day.
3. Confirm the daily chart's bars sit on real dates: a profile with gaps should show blank slots,
   not a run of adjacent bars. Compare the busiest bar against the same day in Hermes's own
   dashboard.
4. Quit Hermes Go Desktop on the Mac and reopen 用量: the page must show Mac 端当前离线，请启动
   Hermes Go Desktop with `HR-CONN-005` and a working 重试, not the generic Relay failure. Restart
   the Desktop app and retry: the page loads.
5. Switch to a profile that has never run a session: the empty state 这个身份还没有用量记录 appears
   — not zeros. Switch back and confirm the figures return.
6. Confirm the footnote reads 统计窗口 N 天 · 按会话开始日归集 · 日界为 UTC, and that a session
   started between 00:00 and 08:00 local time (UTC+8) is attributed to the previous day in the
   chart. This is upstream behaviour, not a client bug; the footnote exists to state it.
7. Both themes: the error state's code line and the empty state's icon must be legible in dark mode
   (the error colour family is now explicit — see `ErrorColorsTest`).

## Background connection smoke test (2026-09 branch claude/background-connection, R1)

R1 changes when the app is allowed to keep its socket while backgrounded. Everything below is
reproducible on the emulator against the local dev stack — no production access and no real device
are needed for cases 1–5.

Environment (build and boot separately; the emulator on the dev host starves under concurrent
Gradle work — see the header of `scripts/dev/emulator.sh`):

```bash
./scripts/dev/emulator.sh start Pixel_9_API_36_1
./scripts/dev/dev-stack.sh start
# logs: $TMPDIR/hermes-dev-stack/{mock,gateway,connector}.log
```

The gateway itself logs only startup and shutdown, so its log proves nothing about a socket. The
decisive evidence is the app's own diagnostic log, which mirrors to logcat once 设置 → 诊断 →
诊断日志 is on:

```bash
adb logcat -s HermesDebug | grep -E "\[(ws|service|lifecycle)\]"
```

`socket closed (gen=N): client closing` is the client deciding to disconnect; `opening socket` marks
a reconnect. A window containing neither means the connection was never dropped — which is the
distinction that matters here, because the R3 banner grace deliberately hides a fast reconnect and
would otherwise make a dropped socket look like a socket that survived.

### Emulator cases

1. **The reported bug.** Turn every notification switch off. Send a prompt, and while the answer is
   still streaming switch to the launcher for ~90 seconds (longer than the 45s grace), then return.
   Expected: no reconnect banner, the stream continues, and the gateway log shows no close. Before
   R1 this closed the socket at 45s and the session came back as 正在恢复连接….
2. **Ownership survives an intermediate completion.** With notifications still off, run a prompt
   that leaves a background process running, wait for the assistant message to complete, then
   background the app for ~90s. Expected: still connected — `message.complete` no longer releases
   phone ownership while work continues.
3. **Idle still disconnects.** Notifications off, nothing running: background the app for ~90s.
   Expected: the socket closes after the grace period (`1000 / client closing` in the gateway log)
   and no foreground-service card appears. R1 must not turn into "always connected".
4. **Power saving still wins.** Set 监控策略 to 省电, start a run, background the app. Expected: the
   socket closes after the grace period — an explicit instruction outranks the run.
5. **The service card.** With notifications off and a run in flight, the MIN `service` card appears
   while backgrounded and disappears when the run ends (docs/DESIGN.md §5.10). Confirm it is silent
   and cannot be dismissed while the run is live.

### Emulator cases — banner and reconnect cost (R3)

6. **A blip says nothing.** With a run streaming, switch to the launcher and back within ~2 seconds.
   Expected: no banner at any point, and no 连接已恢复 strip either — if the user was never told it
   broke, there is nothing to repair. Repeat a few times; a flash of red on the first frame back is
   the regression.
7. **A real outage still reports promptly.** Stop the dev stack (`./scripts/dev/dev-stack.sh stop`)
   while the chat is open. Expected: within ~2.5s the banner appears in the calm progress style
   (spinner, 正在重新连接…), not the red failure style, and it keeps updating through the backoff
   rather than staying hidden. Restart the stack: the banner disappears and 连接已恢复，正在同步会话…
   appears for three seconds.
8. **Failure still looks like failure.** Point the app at an unreachable gateway. Expected: the red
   `errorContainer` banner with HR-CONN-002, 详情 and 重试.
9. **No transcript storm.** With diagnostic logging on, open an idle chat (nothing running), drop
   and restore the connection. Expected: the `history` channel shows no reconcile pass for that
   chat — an idle chat has no gap to recover. Repeat with a chat that is mid-answer: that one must
   reconcile.
10. **Notification settings copy.** Turn 启用通知 off: the explanation about the silent 后台保持连接
    card appears under the switch and disappears when the switch is back on.

### Emulator pass, 2026-09-04 (branch claude/background-connection)

Cases 1–5, 9 and 10 were run on Pixel_9_API_36_1 against the local stack and passed. Two findings
came out of the pass rather than out of review, and both are fixed on the branch:

- The 「连接已恢复」 strip still appeared after a *deliberate* idle disconnect. The outage had burned
  its grace while the app was backgrounded, so the first frame back was already "interrupted". The
  grace now only runs while the chat is on screen (see docs/DESIGN.md, connection visual grading).
- With notifications off the 后台监控方式 group was greyed out, which after R1 left those users no
  way to opt out of the keep-alive at all — 省电 is now the only control that does that. The group
  is reachable regardless of the notification switch.

Ports: the stack was run on 8788 rather than the script's 8787, which was held by an unrelated
project. Note that `dev-stack.sh stop` kills whatever holds its ports.

### Device cases (still unverified — no real device on the dev host)

11. **Screen-off survival.** Start a run, lock the phone for 5–10 minutes, unlock. Record whether the
   socket survived and, if not, the close code and the elapsed time. This is the M1 item: the
   emulator reaches the gateway over `adb reverse` on loopback, which never drops and never passes
   through the edge nginx `proxy_read_timeout 75s`, so it cannot answer this question. The result
   decides whether the 45s ping cadence R2 settled on is enough tolerance. If sockets still die with
   the screen off, the next step is an application-level heartbeat that can forgive a single missed
   beat — OkHttp's own ping treats one late pong as a dead connection and offers no leniency knob,
   which is why R2 could only widen the window rather than add tolerance.
12. **Reaching the real edge.** Because loopback bypasses nginx, at least one run should be observed
   against the production gateway to confirm the 45s ping actually keeps the proxy's 75s idle timer
   from firing. Ordinary use of the app is enough; no deployment is involved.
13. **Vendor battery management.** On a Chinese OEM ROM, confirm the foreground service is not killed
   during a run, and whether the app needs to be added to the battery whitelist.

## Session state consistency smoke test (2026-09 branch claude/session-state-desync-tests)

HG-6, HG-7 and HG-8 were one incident on one conversation (2026-09-05, session
`20260905_102612_6d5fd4`), reconstructed from the Mac mini `messages` table, the Gateway's
`lifecycle-events.json` and the edge Nginx access log. The mechanism is fully covered by unit
tests (`SessionStateDesyncRegressionTest`, `HistoryReasoningAndToolsMappingTest`,
`SessionRunIndicatorTest`); the cases below exist because the trigger — the phone asleep when the
run ends — is not something a JVM test can produce. Across 180 observed completions, 26% reached the
phone more than 30s late, so ordinary use reproduces this several times a day.

Diagnostic log as in the background-connection section (设置 → 诊断 → 诊断日志, then
`adb logcat -s HermesDebug`). The gateway log proves nothing here either.

### Device cases (need a real device — the emulator never sleeps)

1. **Finished while asleep (HG-6).** Send a prompt that runs for 2–3 minutes, immediately switch
   apps and lock the phone, return after the run has finished. Expected: the list row shows 已完成
   (or nothing, once the chat has been opened); the answer bubble shows the action row, **not**
   「生成中」 with a running timer; the composer offers 发送, not 停止. Before the fix the bubble kept
   counting for as long as the process lived.
2. **Follow-up after that (HG-7).** From case 1, send a follow-up. Expected: exactly one bubble is
   live; the previous answer keeps its action row. Before the fix two 「生成中」 rows stacked.
3. **Reasoning and tool cards survive (HG-8, second half).** Open a conversation whose last turn
   used tools and reasoning, wait for the run to finish, background and return so a history
   reconcile runs (watch for `history reconcile ... accepted=true` in the log). Expected: 查看思考过程
   and the tool timeline are still there. Before the fix both vanished on the first reconcile.
4. **Waiting is reported as waiting (HG-8, first half).** Trigger a clarify/approval while the
   phone is on a flaky network (toggle airplane mode for ~10s and back). Expected: the list row
   still says 等待你的确认 / 等待你的回答 / 等待你处理 after the reconnect, never 思考中.
5. **Run active, no bubble yet.** Start a run from the Mac (or let a scheduled run start) and open
   the chat before its first token. Expected: the mark renders alone in the bottom slot of the
   transcript; the list row and the chat agree that something is running.

Report each case with the diagnostic log window around the reconnect or the observed
`run.completed`. Nothing in this branch changes the transport: a completion still arrives late
when the phone is asleep — the fix only guarantees that what is shown is true once it arrives.

6. **Tool timeline after completion (0.1.94, docs/DESIGN.md §5.4).** Watch a tool-heavy run to
   the end without leaving the chat: the timeline stays open and gains a summary row. Leave the
   chat and reopen it: the timeline is folded to「N 次工具调用 · 耗时」; tap to unfold; every row
   now carries the real target name (`mcp__…`, not `tool_call`), its output and exit code —
   identical to what streamed live.
7. **Refresh as a truth check (0.1.96).** With a run that finished while the phone slept (case 1),
   press the chat refresh button *before* the list row updates on its own. Expected: within about a
   second the bubble closes and the toast says「已同步 · 运行已结束」; the composer offers 发送. On a
   run that is genuinely still going, the toast says「已同步 · 仍在运行 · 已运行 N 分钟」 and nothing
   is queued. On the list, pull to refresh while a row says 思考中: the row corrects itself without
   opening the chat. Stop the Mac's Hermes entirely, wait 30 minutes with a run showing 思考中, bring
   the app to the foreground twice a minute apart: the row turns 已中断 instead of spinning forever.


### Emulator pass, 2026-09-06 (0.1.98, Pixel 9 API 36 against the local dev stack)

Build `Hermes-Remote-0.1.98-debug.apk` from `main` b9751e1, mock Hermes via
`scripts/dev/dev-stack.sh` (gateway on 8788, connector 0.1.2), diagnostics switch on. The mock
answers every prompt with the same tool-using turn, so it exercises the state machine but not
the sleep trigger. What the pass proved:

- **Events before the session is bound are kept.** The first prompt's `session.info` arrived
  before the chat was bound to the new id; the log shows `[event] buffered` and then `replaying`
  once the id was known, and the transcript never lost the turn.
- **Phase trail.** `[phase]` lines run `IDLE→THINKING→STREAMING→COMPLETED→IDLE`, each with its
  `cause`, and the session-level mark plus the running footer stay in the bottom slot for the whole
  run (screens 03, 14, 16).
- **Completion closes the bubble** (cases 1 and 2 without the sleep). After `message.complete`
  the answer keeps its action row, the composer offers 发送, and a second prompt gives exactly one
  live bubble.
- **Refresh as a truth check** (case 7, finished-run half). 更多 → 刷新对话 on a finished run: the
  toast reads 「已同步 · 运行已结束」 within about a second (screens 12/13).
- **Reasoning survives reopen** (case 3, reasoning half). 查看思考过程 is still there after
  leaving and reopening the chat; the `[history] reconcile s=… 4 rows cover the local turns` line
  confirms the reconcile ran and was accepted.
- **List row.** The row reads 已完成 after the run; no 思考中 residue.
- **Diagnostics page.** The session chips (全部 / `stored-mock-1`) appear; selecting the session
  chip leaves only lines carrying `s=stored-mock-1` / `session=stored-mock-1` (screen 25).

What the mock cannot show and still needs a real device or production Hermes:

- Folding the tool timeline on reopen (case 6): the mock turn has fewer than three tool calls and
  its history rows carry no persisted `tool_calls` / `reasoning`, so the fold threshold and the
  `tool_call → arguments.name` label resolution were only covered by unit tests.
- Cases 1, 2, 4 with the phone actually asleep (Doze latency), the 30-minute hard cap, and the
  list pull-to-refresh probe (no row was active when the pull was tried).

Found during the pass, not fixed in 0.1.98 (**stale approval sheet**): after answering the
mock's `approval.request` with 拒绝 and letting the run finish, reopening the chat shows the
modal 需要审批 sheet again (screens 07/08/17); BACK does not dismiss it, and answering again
flips the finished session to a phantom 思考中 (`IDLE→THINKING cause=input-answered`). The
reducer clears `pendingClarify` on the next `message.delta` but never clears `pendingApproval`,
and neither was cleared when the phase left the active set. Fixed on branch
`claude/stale-approval-clear`: `normalized()` drops both cards once the phase leaves the active
set, and answering a card on a run the store has already seen end no longer restarts it
(`StalePendingCardTest`). Still to confirm on a device: open a conversation whose approval you
let time out — no sheet, no 思考中.

## Diagnostic observability and HG-1 / HG-10 (2026-09 branch claude/diagnostic-observability)

### Verified on the emulator, 2026-09-05

Captured from `adb logcat -s HermesDebug` while the gateway was deliberately unreachable:

```text
[startup] CONNECTION_RECOVERY · CONFIGURATION → NETWORK → AUTHENTICATION
[startup] hot start HR-CONN-002 — leaving the app visible
[health]  unknown → unreachable(unreachable)
[rest]    GET /api/mobile/events?after=0&limit=100 ← 502 (3096ms)
```

That covers: the startup trail carries reason, phases and outcome; a hot-start failure no longer
blocks (the sessions list and health strip stayed visible, with no full-screen error); health
resolves to *unreachable* rather than *device-offline* on a device whose network works; and a REST
call is one line with a duration, with failures logged where they previously were not.

### Still unverified — the emulator on the dev host degraded mid-pass

System UI began ANR-ing repeatedly (the failure mode `scripts/dev/emulator.sh` documents), which
blocked UI taps and prevented the WebSocket from establishing. These need another pass:

1. **Share produces a file.** Settings → 诊断 → 分享 must hand the share sheet a
   `hermes-diagnostic-*.txt` attachment, not a wall of pasted text, and the file must contain more
   than 500 entries after a long session.
2. **「标记现场」** inserts a visible divider, and the button is disabled while logging is off.
3. **A quiet poll writes nothing.** With the gateway reachable, an idle foreground minute must
   produce no `rest` line for `/api/mobile/events`. (Only the failing case was observable in the
   pass above — failures are logged by design.)
4. **HG-1 end to end.** Let a run finish while the app is backgrounded, then reopen that chat: the
   transcript restores with no full-screen error. Unit-covered, not yet seen on a device.
5. **HG-10 end to end.** Requires a network whose `NET_CAPABILITY_VALIDATED` is absent while
   traffic flows — the emulator cannot produce it. On a real device, a `[net] connectivity check
   says offline · …` line naming the missing capability, with the app still working, is the
   confirmation that the diagnosis was right.

## HG-9 / HG-11 / HG-12 / HG-13 / HG-14 (2026-09-06 branch claude/hg9-14-ui-fixes)

All five are Compose behaviours — coroutine-scope lifetime, LazyColumn scroll anchoring, hit
targets and cross-cell alignment — that JVM unit tests cannot observe. What *is* unit-covered is
stated per item. This branch also carries the previously unreleased HG-1 / HG-10 fixes, so the pass
in the section above still applies and item 5 there (a network missing `NET_CAPABILITY_VALIDATED`)
remains the only way to confirm HG-10.

### Verified on the emulator, 2026-09-06 (Pixel_9_API_36_1, local dev stack)

Driven against `scripts/dev/dev-stack.sh` (mock Hermes → connector → gateway, development tokens
only — no production credentials were used). The mock gained an opt-in
`MOCK_HERMES_EXTRA_SESSIONS=40`, because the five fixture sessions never fill a phone viewport and
scroll-anchoring bugs need a list long enough to scroll.

- **HG-11 cold start** — with 43 sessions, a force-stop and relaunch put 已置顶 at the top of the
  first painted frame, no scrolling. Confirmed twice (5-session and 43-session lists).
- **HG-11 pin from depth** — scrolled to 填充会话 38, long-press → 置顶: the list carried itself to
  the top with that session visible in 已置顶. This is the case the user reported as "it disappears
  and you have to drag it back".
- **HG-13a** — checked against the real public index. 版本记录 now begins with **0.1.98 「当前」**
  and its notes open; before the fix the record began at 0.1.96 and the running build's notes were
  unreachable.
- **HG-14** — 本周用量 and 远程设备 line up on all three rows, chevrons on the value line. With a
  three-digit latency (`已连接 · 915 ms` — the exact shape from the report) the sub now fits on ONE
  line, because the sub reclaimed the 20dp the chevron reserves.

### Still needs a device

- **HG-9** — the mock has no transcript worth exporting and the share sheet is a system surface;
  the coroutine-scope race is also timing-dependent, so ten taps on a real device is the test.
- **HG-12** — the mock emits no `MEDIA:` file attachments, so no file card could be rendered. The
  READY / UPLOADING / FAILED hit-target matrix is entirely unverified.
- **HG-14 wrapped sub** — `settings put system font_scale 1.3` did not change rendering on this
  emulator, so the two-line-sub fallback (the state that originally misaligned the card) was never
  actually reproduced. Only the now-single-line case is confirmed.
- **HG-10** — unchanged: needs a network whose `NET_CAPABILITY_VALIDATED` is absent.

1. **HG-9 — Markdown share actually opens.** In a chat, 分享对话 → 「Markdown 文件」. The system
   share sheet must appear every time, with a `.md` attachment. Repeat ten times, including
   immediately after opening the chat and on a long transcript; a single silent no-op is a
   regression. Then check the failure path is audible again: with the transcript cache dir made
   unwritable, the same tap must show the 无法导出 toast rather than nothing.
2. **HG-11 — pins are visible without scrolling.** Pin a session, force-stop the app, cold start it.
   The 已置顶 section must be on screen at the top of the list without any scrolling. Then, while
   scrolled part-way down, long-press a session → 置顶: the list must carry you to the pinned
   session rather than leaving it above the viewport. Unpinning must NOT jump the list. Unit-covered:
   the unread-vs-empty pin seed and the pin-only reveal request.
3. **HG-12 — the whole file card opens.** Tap a file card's name, its icon, and its empty space:
   all three open the file. The card shows one trailing button (分享) and no 打开 button. A card
   still uploading, and one that failed, must not ripple and must not react to a tap.
4. **HG-13 — the running version's notes are readable.** Settings → 检查更新 while on the newest
   published build: the 版本记录 list must start with the installed version, carrying the 「当前」
   badge, and expand to its full notes. With an update available, that newer version must appear in
   the card at the top and NOT be repeated in the record. Unit-covered as `historyRows`.
5. **HG-14 — the stat card halves line up.** Card page, with a device whose latency reads three
   digits (`已连接 · 231 ms`): 本周用量 and 远程设备 must have their titles on one line, their values
   on one line, and their sub-lines starting on one line, whether or not the right sub wraps. Check
   again at font scale 1.3 and with a long device name, where both cells shrink together.

## HG-15 / HG-16 (2026-09-06 branch claude/hg15-16-chat-noise)

### Verified on the emulator, 2026-09-06 (Pixel_9_API_36_1, local dev stack)

- **HG-15 reasoning row.** In a real chat, dark theme: 「查看思考过程」renders as a quiet grey line
  with a 12dp chevron — no chip, no border — and tapping it still expands the reasoning and flips
  the label to 「收起思考过程」. A lone tool call still renders as its own card, which is the
  documented exception (`docs/DESIGN.md` §5.4: a single call was never folded).

Two Roborazzi goldens pin the rest: `turn-fold-quiet` (the quiet reasoning + folded tool-timeline
summary sitting above an answer) and `task-list-settled` (a finished turn whose third item Hermes
left `in_progress`). Both were recorded and eyeballed as §5.4 requires.

### Still needs a device

1. **HG-15 with a real tool timeline.** The dev mock streams reasoning but no tool calls, so the
   「N 次工具调用」summary was only seen in the golden, never in a live transcript above a long
   answer. Check that folded it reads as one quiet line and that tapping still unfolds the rows.
2. **HG-16a end to end.** Needs a run that leaves a task list at 2/3: the third item must be an
   ordinary unfinished row (no bold, no filled marker) and the card header must not wear the
   running marker either. While the run is still going, the in-progress item must look exactly as
   it did before — this fix must not quiet a live list.
3. **HG-16b end to end.** Needs a session long enough for Hermes to compress its context. Expected:
   a one-line 「上下文已压缩」note that expands to the original text, never a user bubble. The case
   worth hunting for is the other one — a compression that lands on a turn where you also typed
   something: your text must survive intact with the scaffolding cut off it.

## HG-3 / HG-4 / HG-5 (2026-09-06 branch claude/hg3-4-5-chat-entries)

### Verified on the emulator, 2026-09-06 (Pixel_9_API_36_1, local dev stack)

- **HG-5 top bar and menu.** The chat top bar reads `[←] 标题 [＋] [⋮]` — the search icon is gone,
  replaced by 新建对话. The 「更多」menu lists 搜索对话 → 我的提问 → 刷新对话 → 复制对话 → 分享对话
  → 归档对话 → 切换人格, exactly the order `docs/DESIGN.md` §5.4 now specifies.
- **HG-5 archive.** 归档对话 opens the confirm dialog (「归档这个对话？归档后它会从会话列表移到
  「已归档」，随时可以恢复。」/ 取消 · 归档), neutral coloured, not error-red. Confirming archived
  the conversation and returned to the sessions list.

The dev mock now emits Hermes' own `timestamp` field on history messages
(`scripts/dev/mock-hermes-stream.mjs`), so HG-4 is reproducible locally — a mock without it looked
identical to the bug.

### Still needs a device

1. **HG-3 with two tool calls.** The dev mock emits at most one tool call per run, so the new
   two-call grouping was only exercised by unit tests. On a device: a turn that used exactly two
   tools must show ONE timeline (a single quiet 「2 次工具调用」line once the turn completes), not
   two separately bordered cards. A turn with exactly one tool must still show its own card.
2. **HG-4 in a real transcript.** Open a conversation with history from before this build:
   **every** prompt in 我的提问 must now carry a time, not just the ones sent in this session, and
   the times must match when they were actually asked. This is the whole point of the fix — the
   unit test proves the mapping, only a real transcript proves the data arrives.
3. **HG-5 archive failure path.** With the Mac unreachable, confirming 归档 must leave you in the
   chat with the `HR-SESS-008` message — not bounce you to the list as if it had worked.
4. **HG-5 ＋ button.** Tapping ＋ in a chat must create a new conversation in the default project
   and open it; a second tap while it is still creating must do nothing (the button shows the
   brand mark and disables). Emulator taps kept landing off-target here, so this went unverified.

## Transcript disk cache (2026-09-07 branch claude/transcript-disk-cache)

Unit tests cover the store (round trip, LRU-by-use eviction, oversize skip, corrupt file, unusable
directory, path-safe keys), the fact that a payload off the disk maps to a byte-identical
transcript through the same mapper as a fresh fetch, the runtime store's refusal to let a stored
copy overwrite the network or a live run, and the presentation gate that used to mask a cached
transcript. What none of them can prove is what a person actually sees, so on a device:

1. **The point of the whole change.** Open a conversation, force-stop the app, reopen it and open
   the same conversation. The transcript must appear **immediately** — no skeleton — with a 2dp
   line at the top while the refresh runs. Before this change that path showed the chat skeleton
   for as long as the round trip took, which after an app update was every session.
2. **A session this device has never opened** must still show the skeleton, not a blank screen.
   The cache is not a substitute for the first fetch.
3. **Nothing stale is shown.** Send a message from the desktop while the phone is closed, then
   open that conversation on the phone: the cached copy paints first, and the new turn must appear
   a moment later without a jump in scroll position or a visible remount.
4. **Changing the Relay drops it.** In 设置 → 连接, save a different Relay URL or token, then open
   a conversation that was cached: it must fetch fresh rather than show the previous account's
   history. This is the privacy case and it is the one worth doing carefully.
5. **Storage stays bounded.** After browsing many conversations, the app's storage figure in system
   settings must not grow without limit — the cache prunes to 32 MB / 300 entries.

Emulator-only checks (1) and (2) were exercised during development; (3), (4) and (5) still need a
real device with a real Mac at the other end.

## HG-58 / HG-57 / HG-56 (2026-09-18 branch claude/hg-58-57-56-send-and-run-state)

Three reports from one HONOR MBH-AN10 (Android 16 / API 36) on 0.1.127–0.1.128. All three were
diagnosed from the attached diagnostic logs; the parts below are the ones no JVM test can settle.

### Automated here (L1)

- The `SESS-016` bubble: copy, compact code, and **no tap** — `DeliveryStateTest`, plus the
  `user-bubble-delivery*` goldens (including 360dp / fontScale 1.3, where the sentence has to yield
  and the code must not wrap).
- `TERMINAL_SEND_ERROR_CODES` holds exactly the three codes that withhold the tap, and not the two
  that clear by themselves — `DeliveryStateTest`.
- `sessions.changed` reaches a probe instead of being dropped, is throttled to one round per burst,
  is never credited to whichever run happens to be active, and a curiosity probe that fails never
  writes a verdict — `SessionsChangedProbeTest`.
- The run-wait label: silent under five seconds, `已运行 4分28秒` at the duration HG-56 actually sat
  through, and nothing at all without a start time — `SessionRunIndicatorTest`.
- Desktop: the written bundled-layout LaunchAgent carries the search path, an agent written without
  one is repaired by startup reconciliation, and a malformed `PATH` is refused —
  `DesktopManagedBootstrapConfigurationTests`, `DesktopMigrationCoordinatorTests`.

### Still needs a device or a real Mac

1. **HG-58 end to end — unverified.** Attach a PDF from the phone and send it. Requires Hermes Go
   Desktop rebuilt from this branch **and the managed Hermes service restarted** so the rewritten
   agent takes effect; no machine has done that. Read-only precondition check on the Mac: the
   managed `hermes-server` process's `PATH` contains `/opt/homebrew/bin`. Expected afterwards: the
   PDF sends. Expected if the dependency is genuinely absent: the bubble reads
   「Mac 缺少 PDF 渲染依赖 SESS-016」and **does not respond to a tap**.
2. **HG-57 on a device.** Start a conversation on the Mac (Desktop or a cron job), leave it running,
   then open it on the phone. Expected: the running state appears without the user doing anything,
   and pressing refresh on an apparently-finished conversation reports 「仍在运行」rather than
   「当前对话已刷新」. The diagnostic log should no longer contain runs of
   `unmatched sessions.changed without session id`.
3. **HG-56 on a device.** Send a prompt to a conversation whose first token is slow (a long tool
   chain). Expected: after five seconds the indicator starts reading 「已运行 N 秒」and keeps
   counting, so the wait is legible instead of being read as a failed send.
4. **Vendor spread.** The reporting phone (HONOR MBH-AN10, API 36) is not attached to the build
   host. The devices here are vivo V2166BA (SDK 33) and HONOR CLK-AN00 (SDK 34); neither reaches
   `targetSdk` 37. Name the device in any result rather than writing "verified on device".

## Web app on iPhone (2026-09-21 branch claude/web-app)

Local reproduction: `./scripts/dev/web-stack.sh up` (Postgres, mock Hermes, TLS Gateway on
`https://localhost:18443`, account-mode Connector), then `./scripts/dev/web-stack.sh code` prints
the email code for `dev@example.test`. `HR_WEB_STACK_LEGACY_PROTOCOL=1` switches the mock to the
older approval/clarify events. Mock prompt prefixes pin one run: `!clarify-single`,
`!clarify-multi`, `!clarify-batch`, `!quick`, `!media <absolute path>…` (several paths give one
message several images), `!table` (ends with a Markdown table); `!owned` and `!gone` make
`prompt.submit` answer 4090 and 4007. The Connector runs its lifecycle observer against the mock's
`session.active_list`, so a run left waiting on an approval raises `run.waiting` in the inbox.

### Verified locally, 2026-09-22 (Playwright WebKit, iPhone 15 Pro viewport)

Both protocols, end to end: sign-in, Mac auto-selection, list and search, a new chat streaming,
tool rows, approval, single / multi-select / three-question batch clarify, interrupt, a `MEDIA:`
image, an uploaded attachment, reconnect after going offline, history after reload, dark mode,
sign-out clearing every cache. Also: a run left waiting puts its session under "需要你处理" with a
foreground toast and a title count, and answering clears it; leaving and reopening a conversation
brings its pending approval back from `open_requests`; `!owned` shows `HR-SESS-013` once with
Retry; `!gone` shows `HR-SESS-001` once and disables the composer; the service worker registers
with scope `/app/`; sending immediately after a reload succeeds (it failed about half the time
before the boot-refresh fix). The login page also renders in iOS 26.5 Simulator Safari, light and
dark. None of this replaces the device items below.

Batch 1 (branch `claude/web-batch1`, 23 checks, same harness): the project sheet lists derived
projects; filtering keeps only that folder's sessions (needs-you excepted) with branch · model
sublines; a new chat from a filtered list is created in that folder; code blocks carry their
language and copy verbatim; a table copies as tab-separated cells; "copy reply" confirms; tapping
one of two images opens the viewer at `1 / 2`, a swipe pages to `2 / 2`, double tap zooms to 2.5×
and a drag then pans instead of paging; pinning from the chat's "more" menu puts the chat under
「已置顶 · 仅此设备」 with a pin mark, stores `default/<id>` for that Mac, and unpinning removes the
group; a collapsed group stays collapsed across opening a chat; sign-out removes every pin.

Batch 2 (branch `claude/web-batch2`, 23 checks): a non-default-profile session names its profile on
resume and history (unit-tested; the mock has one profile); the top bar shows `project · branch`,
＋ and ⋮ (both hidden on an empty new chat, which shows the greeting); a streaming turn shows the
running line; three tools fold into 「3 次工具调用 · 1 次失败」 with a task-list card; embedded JSON
leaves the prose; a diff card shows `+1 −1`; the action row has copy / thumbs / read aloud /
regenerate / ⋯, and regenerate appears on the latest answer only and appends a new one; ⋯ opens
readable text with a source toggle; tapping the user bubble → edit & resend fills the composer;
in-chat search counts `5/5`, hides the composer and offers "search all chats" on no match; your
prompts lists and jumps; jump-to-latest appears when scrolled up; refresh confirms; Markdown export
goes to the share sheet when the browser can share files, else downloads `HermesGO-….md`, and text
falls back to the clipboard; a draft survives a reload; after reload the history shows one turn per
answer.

Batch 3 (branch `claude/web-batch3`, 20 checks; the mock gained a DingTalk fixture and a real
`/api/sessions/search`): the title reads 会话 and the 会话 / 机器人 segments appear; the bot
conversation leaves 会话 and sits under 钉钉 with 模型未知 and `<time> · 4 条`, no FAB; its chat
says 「来自钉钉 · 运维值班群」 and the first send asks once, cancel restoring the text; a waiting run
shows under 需要你处理 with 等待你处理 and an amber dot (seen from a second tab — the mock ends a run
when its socket closes, real Hermes does not); the default project's folder is dropped from
sublines and named 默认项目 in the project sheet; a draft shows 草稿; the archived page lists the
archived fixture; title matches highlight instantly, message hits arrive with centred snippets and
open the chat with in-chat search pre-filled; recent searches remember the query; the top bar stays
pinned while scrolling; sign-out leaves no `hermes-go.*` key behind.

Batch 4 (branch `claude/web-batch4`, Gateway + Web, 22 checks; the mock gained PATCH/DELETE,
a model list, `/model` switching, reasoning and a `!proc` background task): the composer chip reads
`claude-opus-5 · 中`; the model sheet reads the session's reasoning, a change to 高 updates the chip,
switching to `gpt-5.6-sol` confirms; "retry with another model" switches then regenerates; `!proc`
shows 「后台任务运行中 · 1」 with the command and output tail; tapping the project subtitle moves the
chat to `hermes-remote`; ⋮ → 归档对话 asks (not red), returns to the list with 「已归档」 and the row
leaves; on the archived page press-and-hold → 取消归档 restores it; press-and-hold on a list row
opens pin / rename / move / archive / delete without opening the chat; rename shows in the list;
delete asks in red naming the conversation and removes the row; no request the UI sent was refused
by the Gateway. Gateway unit and integration tests cover every refused shape.

Batch 5 (branch `claude/web-batch5`, Web only, 23 checks, `/tmp` drive script against
`scripts/dev/web-stack.sh`; the share sheet is stubbed away so the download fallback is what runs):
the table card head offers 保存为图片 and 全屏查看; fullscreen shows the whole table; saving
downloads `HermesGO-Table-<stamp>.png` (light palette, 170px columns) and flashes 已保存表格图片.
Scrolling past a prompt shows the turn pill with its summary and the ≡ list; tapping it lands on
the bubble with the outline. ＋ opens 拍照 / 照片 / 文件 + 常用提示 / 添加会话; a prompt created in
the library is inserted and kept in `hermes-go.prompts`; picking a conversation counts down
「最多再选 8 个」 and attaches `<title>.md`. A picked PNG opens in the preview with 移除 / 编辑; a
stroke enables undo; 取消 with edits asks 放弃这些修改？; ink, mosaic and a corner-dragged crop
(1:1 squares it, undo restores) bake to `batch5-shot-edited.jpg` in the same strip position;
完成 with no edits leaves it alone; the edited image is sent with no error notice. The 4007
recreate of an empty conversation is covered by `session.test.ts` (the mock cannot reclaim).

Batch 6 (branch `claude/web-batch6`, Web only: rotation, foldables, keyboard, system back). Local
drive against `scripts/dev/web-stack.sh`, 29 checks in Chromium (Android Chrome profile) and WebKit
(iPhone profile), plus the batch 5 drive re-run as a regression (23/23):
- Back (browser back = the system back gesture): closes the ＋ sheet and stays in the chat; unwinds
  prompt form → library → saved-prompts sheet → chat one level at a time; closes the ⋮ menu, the
  image viewer, the fullscreen table and in-chat search. With edits in the image editor it asks
  放弃这些修改？ and stays open; back on that question closes only the question; 放弃 returns to
  the preview, back from the preview to the chat with the chip kept. After all of that, one back
  goes to the list — closing overlays with ✕ left no dead history steps.
- Short screens (915×412 landscape, 412×360 standing in for the keyboard, 852×393 iPhone
  landscape): the input stops at three lines (76px), the model chip and project line hide, the top
  bar is 40px and the message list keeps 55–67% of the height. A normal portrait input still grows
  to 200px.
- 344px foldable cover: the crop preset row starts 8px from the left edge and scrolls to its last
  control. Unfolded (829px): the 会话 / 机器人 switch is 688px, the list column's width. Rotating
  with the turn pill showing re-measures it to the group now at the top.

Real Chrome 145 on the Android emulator (Pixel 9 image, API 37; Playwright `_android`), 2026-09-23:
the system back key with the ＋ sheet or the image viewer open closes only that and stays in the
chat; with the keyboard up in portrait the layout viewport shrinks to the space above it (792 →
456px) and the top bar stays on screen; Chrome reports the manifest installable with no errors;
switching the display to an unfolded size (690×680) and back keeps the chat and its input; the page
follows system dark mode. Landscape with the keyboard up leaves the page about 30px on this
emulator (status bar, Chrome's toolbar and the keyboard take the rest): only the caret line of the
input shows. That is the browser's limit, not something the page can reclaim.

HG-109 (branch `claude/hg-109-open-latest`, Web only): the mock now pages
`GET /api/sessions/{id}/messages` the way upstream does (`order=latest` counts back from the newest
row, each page ascending) and lists a 260-row fixture, 「长会话 · 260 条历史」. Before the fix, opening
it from the list landed 1,032px short of the bottom in 3 of 8 Chromium runs and **8 of 8 WebKit runs**
(8,600px short); after it, 8/8 in both, and reload, "scrolled up stays up", 「回到最新」 and an older
page loading while reading all hold. Batch 5 (23/23) and batch 6 (29/29) drives re-ran green.
Still open and pre-existing on `main`: in WebKit, the page of older turns that loads while scrolling
up leaves the reader 9,000px further down than the prompt they were on (Chromium keeps it through
native scroll anchoring, which Safari lacks).

### Still needs a real iPhone — none of this has been verified on a device

Every item below needs a physical iPhone against a Gateway reachable over real HTTPS; the iOS
simulator and Playwright WebKit do not exercise them faithfully. Record the iPhone model and iOS
version with each result.

1. **Add to Home Screen from Safari.** Share → Add to Home Screen. Expected: the launcher icon and
   name are Hermes GO, and it opens standalone (no Safari chrome) at `/app/`.
2. **Separate sign-in state in Safari and on the Home Screen.** iOS gives a Home Screen web app its
   own cookie jar. Sign in inside Safari, then open the Home Screen app. Expected: the Home Screen
   app asks for its own sign-in, and signing out of one leaves the other signed in.
3. **Background, then foreground.** Open a running conversation in the Home Screen app, lock the
   phone for more than 20 minutes (longer than the access token plus the Gateway's 5-minute grace),
   then unlock. Expected: the app refreshes the session, reconnects the socket and resumes the
   conversation without asking to sign in again. It should show at most a brief "reconnecting"
   state and no `HR-AUTH-*` error.
4. **iOS Chrome.** Repeat 1–3 in Chrome for iOS (its Add to Home Screen needs iOS 16.4+). Expected:
   the same results as Safari.
5. **Safe areas.** On a notched or Dynamic Island iPhone, in portrait and landscape, light and dark.
   Expected: nothing sits under the island, the home indicator or the rounded corners, and the
   composer stays above the home indicator while the keyboard is open.
6. **Download from the Mac.** Tap a `MEDIA:` file that is not an image, such as `.html` or `.pdf`.
   Expected: iOS offers to download or preview it. It never renders inside the app's origin.
7. **Sign out on a shared device.** Sign out, then reopen the app offline. Expected: no conversation
   content is shown from cache, only the sign-in page.
8. **Copy.** In Safari and in the Home Screen app, tap "copy reply", a code block's copy button and
   a table's copy button, then paste into Notes. Expected: 「已复制」-style confirmation, the Markdown
   source / exact code / cells land in Notes. If iOS refuses, the app shows `HR-WEB-007` and the text
   can still be selected by pressing and holding.
9. **Image viewer gestures.** Open an image from a message with several. Expected: pinch zooms
   smoothly without Safari zooming the page, double tap toggles, a zoomed image cannot be dragged
   off screen, a swipe at 1× pages within the message only, and "save image" offers Save to Photos
   or Share.
10. **Pins and project filter.** Pin a chat, force-quit the Home Screen app and reopen it. Expected:
    the pin is still there (it is per browser, so Safari and the Home Screen app keep separate pins),
    and it is gone after signing out. Filter by a project and start a new chat. Expected: the chat
    appears under that project on the Android app too.
11. **Chat parity (batch 2).** Read aloud a reply (Safari speech voice, stop by tapping again);
    share a transcript as text and as a Markdown file through the iOS share sheet; write half a
    message, force-quit the Home Screen app and reopen the chat (the draft is back), then sign out
    and in again (it is gone); search inside a long chat and step through hits (highlights need
    iOS 17.2+, older iOS only scrolls to the turn).
12. **List parity (batch 3).** With a real DingTalk / Feishu channel: the 机器人 segment appears and a
    bot chat asks once before the first send. Leave a run waiting on an approval and go back to the
    list: 需要你处理 shows it with 等待你处理; a run that finishes while you are on the list shows
    已完成 with an unread dot until opened. Scroll down, have a run start waiting, and the
    「N 个会话需要处理」 pill appears.
13. **Session management and model (batch 4).** On the iPhone: press and hold a row (the sheet opens,
    iOS shows no callout and the chat does not open); rename, archive (confirmation not red), restore
    from Archived, delete (red confirmation). Open a chat, switch the model from the chip and change
    the reasoning effort; confirm on Android that the same conversation shows the new model. Move a
    chat via its project subtitle. While Android runs a turn in the same conversation, open it on the
    Web: the composer is replaced by `HR-SESS-013` with Retry.
14. **Composer and chat extras (batch 5).** Save a table as an image: the iOS share sheet opens and
    Save Image puts it in Photos (legible, light background even in dark mode). ＋ → 拍照 opens the
    camera. Edit a photo: two fingers zoom without drawing or zooming the page, one finger draws,
    the crop handles are easy to grab, 完成 replaces the chip and the sent image is the edited one.
    Add a conversation and confirm Hermes on the Mac receives a readable `.md`. Scroll a long chat:
    the turn pill appears, fades 1.5 s after scrolling stops, and a tap lands on the prompt.
15. **Staying signed in.** Use the app, close it, wait more than 15 minutes (the access cookie's
    life), and open it again — from the Home Screen app and from a browser tab. Expected: the
    conversation list, no email code. Repeat after a day and after a week (the refresh cookie lasts
    30 days). Sign out explicitly and reopen: the login page, and it stays.
16. **Rotation, keyboard and back (batch 6).** On the iPhone: rotate a chat to landscape (compact top
    bar, three-line input, messages still readable) and back; swipe back from the left edge in the
    Home Screen app with a sheet or the image viewer open (only that closes). On an Android phone in
    **Chrome** (the only Android browser in scope): the system back gesture closes sheets, the
    viewer, the editor (asking first when there are edits) and in-chat search one at a time; the
    keyboard keeps the top bar visible in portrait; Add to Home Screen gives a full-bleed icon (the
    maskable one, not a small icon in a white frame). On a foldable, open and close it on a chat and
    in the image editor. None of this has been checked on a physical phone: Chrome is not installed
    on the HONOR test phone, so Android results so far come from the emulator only.

## HG-94 FCM push wake hints (2026-09-22 branch claude/hg-94-fcm-push)

### Automated here (L1)

- Gateway: only `run.waiting`, `run.completed`, `run.interrupted` and `run.unknown` wake phones; the
  FCM body is data-only, `HIGH` priority and never contains the session title; FCM responses map to
  sent / token dropped / failed; a failed send never throws into the Connector ack path
  (`gateway/src/push.test.ts`).
- Gateway: registration routes accept only Android phone installations and advertised providers,
  and answer 404 when no provider is configured; `capabilities.push` appears only when configured.
- PostgreSQL 18: one registration per live phone, account isolation, a rotated token is not deleted
  by a stale "unregistered" report, and revoking the installation deletes the registration while a
  revoked installation cannot register again (`gateway/src/push-registration-database.integration.test.ts`).

### Still needs a Firebase project, a deployed Gateway and a phone with Google Play services

None of this has been run: no Firebase project exists yet, the Gateway was not deployed, and the
attached HONOR CLK-AN00 has no Google Play services.

1. Put the service-account key at the path in `ACCOUNT_FCM_SERVICE_ACCOUNT_FILE`, restart, and check
   `GET /v2/capabilities` shows `"push": {"providers": ["fcm"]}`.
2. Build the APK with the four Firebase client values (`android/README.md`), sign in with an
   account, enable notifications. Expected: one `account_push_registrations` row for that phone.
3. Swipe the app away (do not force-stop). Start a run from Desktop and let it finish. Expected: the
   completion card appears within seconds, not after the 15-minute job; the same for a run that
   waits for approval.
4. Put the phone into Doze (`adb shell dumpsys deviceidle force-idle`) and repeat. Expected: the
   high-priority message still wakes it.
5. Sign out on the phone. Expected: the row is gone and no further pushes arrive.

## HG-104 payload size: device API gzip, tunnel deflate and history paging (2026-09-23 branch claude/hg-104-payload-perf)

### Automated here

- Nginx: `renderMultiDeviceNginxRoutes()` puts the five JSON-only gzip directives inside the
  `/v2/devices/<id>/api` location and nowhere else — in particular not in `/v2/devices/<id>/ws` or
  `/v2/connect` (`scripts/test/production-multi-device-rollout.test.mjs`). The later rollouts compare
  the live route file against the same renderer, so their fixtures follow automatically.
- Gateway: the Connector WebSocketServers negotiate `permessage-deflate` with both
  no-context-takeover flags, a client that does not offer it still connects, the inflated size is
  still bounded by `maxPayload` (close `1009`), and the app WebSocketServer never negotiates it
  (`gateway/src/gateway-server.test.ts`; with `RUN_NETWORK_TESTS=1`,
  `legacy-routing.integration.test.ts` checks the same on a spawned Gateway).
- Gateway: `http.tunnel` lines carry `status`, `bytes`, `chunks` and `ttfbMs` for buffered,
  streamed, failed-mid-stream, out-of-order and client-aborted requests
  (`gateway/src/http-tunnel-broker.test.ts`).

### Still needs a deployed edge (production application is not yet authorized)

Run against the edge once the regenerated `binding-routes.conf` is live (docs/DEPLOYMENT.md,
"HG-104"). `<device-id>` is a bound device and `$COOKIE` a signed-in Web session cookie (a cookie read
must carry the exact `Origin`); keep it out of shell history.

1. **Device API is compressed.**

   ```bash
   curl -sS -o /dev/null -D - -H 'Accept-Encoding: gzip' -H 'Origin: https://<host>' -H "Cookie: $COOKIE" \
     "https://<host>/v2/devices/<device-id>/api/sessions?limit=100"
   ```

   Expect `200`, `Content-Encoding: gzip` and `Vary: Accept-Encoding`. Repeat without the
   `Accept-Encoding` header: no `Content-Encoding`, and the decoded body is identical. A body below
   1 KB (for example `/api/status`) stays uncompressed.
2. **Device WebSocket is untouched.** Open a conversation in the Web app or on the phone and send a
   prompt: the answer still streams token by token (gzip never applies to the `/ws` location).
   Unauthenticated, `curl -sS -o /dev/null -w '%{http_code}\n' -H 'Connection: Upgrade' -H 'Upgrade:
   websocket' -H 'Sec-WebSocket-Version: 13' -H "Sec-WebSocket-Key: $(openssl rand -base64 16)"
   "https://<host>/v2/devices/<device-id>/ws"` still answers `401`, as before.
3. **Connector hop is deflated.** After the Gateway release carrying this change, the Connector's
   reconnect succeeds and `/relay-health` (legacy) or `/internal/account-connectors` (account) shows
   it online; no Connector or Desktop update is needed because the `ws` client offers the extension
   by default.

### History paging (Android and Web; needs the real client against a long conversation)

4. **First page is 100 rows.** Open a conversation with more than 100 stored messages. The first
   history request (Gateway `http.tunnel` line, or the edge timing log from docs/DEPLOYMENT.md) is
   `GET …/api/sessions/<id>/messages?order=latest&limit=100&offset=0`, and the chat shows the newest
   turns in the normal top-to-bottom order.
5. **Scrolling up loads the next older page.** Scroll to the top of the loaded history: exactly one
   request with `offset=100` follows, older turns appear above without the scroll position jumping,
   and no turn is shown twice. Continue until a page returns fewer than 100 rows: no further
   request is made.
6. **Session list fallback.** When a client falls back from `/api/profiles/sessions` to
   `/api/sessions`, the request is `GET /api/sessions?limit=100` and answers `200` (a limit above 100
   is `422` upstream — docs/HERMES_CONTRACT.md §1c).
