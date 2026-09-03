# Local relay smoke test

Start the gateway and mock connector as described in the root README. Connect a WebSocket client to `ws://127.0.0.1:8787/v1/connect` and send:

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

With `HERMES_MODE=live`, the Connector also opens a private observer socket. Confirm its log contains
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

## Model selector smoke test (2026-08 collapsible groups + current-model visibility)

JVM unit tests cover the grouping/collapse logic, override tracking, and error codes; these flows
still need a device or emulator against a running Gateway/Connector. All of them are pending device
verification for the current iteration.

1. **Default open state.** In a chat, tap the model chip. The sheet must open with the current-model
   summary strip on top, favorites pinned open, the current model's group expanded with the row
   highlighted (check mark + scrolled into view), and every other group collapsed to a single
   "name + count" line.
2. **Collapse and search.** Toggle a few group headers, then type a query: every group with matches
   must auto-expand and show its hit count; clearing the query (× button) must restore the previous
   collapse state.
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
   sheet's title row (or the 设置 › 模型 top bar): the list must update and the icon must show a
   spinner while fetching. Offline, the tap is a silent no-op (no crash, no error toast).
10. **Reasoning effort (2026-09).** In the sheet, expand 推理强度: the 思考 toggle and seven level
   chips must reflect the session's effective level (`config.get key=reasoning`). Pick a level:
   the chip suffix in the composer updates (e.g. `fable-5 · 高`), and the choice is remembered for
   that model — switch to another model and back, and the remembered level is re-applied to the
   session (row suffixes in the list show each model's memory). Turning 思考 off maps to `none`;
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
   the counter at 1/N, the first hit's turn outlined and the matching words marked inside the
   text (Markdown body, user bubble). Tap ↓ repeatedly: the counter advances and the outlined turn
   follows. When a hit is inside 查看思考过程 or a tool card, that card opens by itself.
5. In a chat, search for a word that does not occur: the bar reads 此会话中没有匹配 with
   在全部会话中搜索; tap it and confirm the search screen opens with the query filled and the
   message search running by itself.
6. Rotate the phone with the search bar open, then press back: the bar closes first (chat stays);
   press back again to leave. Re-enter the chat from the list: the search bar is not re-opened.
