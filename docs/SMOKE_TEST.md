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
   notification remains while the run is active. Complete the task and confirm the completion
   notification replaces the running state and the service stops.
3. With no Android-started task active, leave the app in the background. Confirm there is no
   persistent service and no idle gateway ping loop. A task completed elsewhere should be found by
   the next OS-managed periodic check; Android may defer that check beyond 15 minutes.
4. Repeat a delivered Relay batch or restart Android between notification delivery and cursor
   persistence. Confirm the stable notification is updated rather than duplicated.
5. Select **Real-time** and confirm the background service remains present. Select **Power saving**
   and confirm the service stops even when a locally started run is still active.
6. Deny notification permission and verify chat remains functional. On Android 13+, re-enable the
   permission in system settings and repeat the lock-screen and heads-up checks for each channel.

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
