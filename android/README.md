# Hermes Remote Android

The selected base is [`adebnar/hermes-android`](https://github.com/adebnar/hermes-android), pinned at tag `v0.1.52` / commit `9f08f39ed2b9fc7cb29a551b1d9b695a409fdb7a`.

The upstream client is Kotlin + Jetpack Compose and already implements Hermes REST, `/api/ws` JSON-RPC, sessions, streaming chat, Markdown, attachments, reconnect, and encrypted credential storage. Hermes Remote retains those foundations and routes them through the HK Relay.

## First UI pass

- Application ID: `com.hermes.remote`
- Default Relay: `https://mrlgs.net` (standard HTTPS/WSS port 443)
- Setup requires only the Relay URL and a dedicated App Token; Mac credentials never enter the app.
- Calm mint/neutral visual system with a floating, full-width composer inspired by WorkBuddy's layout language.
- Real Markdown rendering, readable JSON output normalization, and collapsed tool-result cards.
- Camera, photo picker, voice input, saved prompts, sessions, and model selection remain available.
- Version 0.1.2 uses a document-style assistant layout with stronger Chinese/Markdown typography,
  compact user bubbles, reply actions, a floating composer, and a WorkBuddy-inspired attachment sheet.
- Version 0.1.3 extracts JSON/terminal payloads that Hermes flattened into assistant prose and
  restores them as collapsed tool cards; expected background polling misses stay hidden. The same
  cleanup is applied when reopening existing conversation history.
- Version 0.1.4 shows the live session title in the chat header, replaces header search with a
  one-tap new-chat action, and expands the focused composer to expose the current model picker,
  attachment action, multiline input, and send control.
- Version 0.1.5 removes internal `<untrusted_tool_result>` safety wrappers from chat prose, keeps
  source data behind collapsed tool cards, simplifies the header to an adaptive session title,
  and collapses the focused composer when the conversation area is tapped.
- Version 0.1.6 also sanitizes historical messages stored with Hermes' `tool` role, preventing
  reopened sessions from rendering raw web-search wrappers and payloads as system prose.
- Version 0.1.7 removes every internal tool/function history turn at the repository boundary,
  covering command JSON, escaped markdown, skill documents, and future tool payload formats.
- Version 0.1.8 merges adjacent assistant records into one conversation turn, so copy, feedback,
  read-aloud, and more actions appear only once after the complete answer.
- Version 0.1.9 moves live runs into an application-level multi-session store, preserving reasoning
  and streaming output when leaving a chat. Session rows show live status, warm data remains visible
  during refresh, history uses an in-memory cache and skeleton, and settled chats reveal only after
  their initial bottom position is ready.
- Version 0.1.10 carries session title/profile through navigation, resets the composer for a newly
  created chat, follows reasoning and streamed output with a user-pausable bottom anchor, and uses
  the black-and-gold Hermes H launcher icon.
- Version 0.1.11 makes opening a conversation reliably land at the true latest-content bottom after
  cached/server history and Markdown layout settle, with retry-safe initial positioning.
- Version 0.1.12 streamlines Chats with a compact new button and long-press actions, adds running and
  persistent unread indicators, and introduces Chinese/English app language selection (Chinese default).
- Version 0.1.13 migrates the default Relay to `mrlgs.net` and restores automatic, versioned APK
  distribution artifacts so test packages can always be matched to their source version.
- Version 0.1.14 merges the hardened Connector `/api/files` download endpoint and disables Android
  cleartext traffic outside localhost development targets.
- Version 0.1.15 rebuilds the tester APK with the canonical shared debug signing identity so packages
  produced by Hermes, Codex, and Claude Code can update the same installed application.
- Version 0.1.16 fixes first-message delivery in a new conversation by navigating with Hermes'
  durable `stored_session_id` and resuming it into a live handle before submitting the prompt.
- Version 0.1.17 adds the in-app update center under “我的”, with a signed version catalog,
  resumable downloads, APK integrity/signature checks, and system-confirmed overwrite installation.
- Version 0.1.18 is the first release published specifically to verify the complete in-app update
  flow from version discovery through signed download and system-confirmed overwrite installation.
- Version 0.1.19 makes chat and session state self-healing: missing or reordered stream events recover
  their assistant turn, terminal/reconnect paths reconcile against server history with delayed retries,
  resumed runs restore their generating state, and warm session lists refresh without visual flashing.
- Version 0.1.20 moves all public Relay, WebSocket, and APK traffic to standard HTTPS/WSS port 443,
  automatically migrates stored `:8444` production URLs, and adds resumable APK byte-range downloads.
- Version 0.1.21 replaces competing chat auto-scroll effects with one coordinated state machine,
  preserves manual history browsing during streams, reliably jumps to the true tail, and reduces
  Markdown re-layout churn with stable conversation keys and frame-coalesced rendering.
- Version 0.1.22 adds bounded camera/photo/file input, raw attachment upload, image compression,
  output image previews, downloadable file cards, and acknowledged streaming downloads so large
  results cannot overflow the Connector control WebSocket.
- Version 0.1.23 unwedges chat scrolling: a failed jump-to-bottom falls back to browsing instead
  of locking the screen, tail detection tolerates dense-screen layout rounding, history refreshes
  no longer yank an upward-scrolled reader to the bottom, and tail-follow yields to search scrolls.
- Version 0.1.24 keeps the chat scroll executor alive when a user drag steals the scroll mutex
  from a programmatic snap; that collision previously cancelled the executor coroutine silently,
  after which the jump-to-bottom button and stream following never worked again for the screen.
- Version 0.1.25 rebuilds the chat list on reverseLayout: the viewport pins to the newest content
  by layout instead of chasing it with 64ms programmatic snaps, eliminating stream jitter, making
  jump-to-bottom a single exact scrollToItem(0), and turning at-bottom detection into an integer
  comparison with no pixel tolerance or scroll-mode state machine.
- Version 0.1.26 recognizes image-generation results that report a Mac absolute path in natural
  Chinese/English prose, local Markdown, or `file://` form; it hides the unusable Mac path and
  resolves the original image through the authenticated Relay into an on-device preview.
- Version 0.1.27 explicitly pins debug builds to the verified shared keystore, preventing CI or
  agent-specific Android home directories from silently producing an incompatible APK signature;
  it carries forward the Mac image-output preview support introduced in 0.1.26.
- Version 0.1.28 handles Hermes' actual image-generation reply shape where `图片路径：` is a
  Markdown hard-break label and the Mac path appears as inline code or a fenced block on following
  lines, removing that path and replacing it with the authenticated image preview.
- Version 0.1.29 recognizes generated-file prose such as `已生成:` followed by a Mac path, including
  inline-code, multiline, fenced, Chinese-filename, and size-suffix forms; it replaces the unusable
  path with a downloadable file card while keeping image extensions routed to image previews.
- Version 0.1.30 implements Hermes' canonical `MEDIA:<path>` attachment protocol in one parser for
  images, documents, archives, audio/video, quoted or spaced paths, multiple attachments, and local
  Markdown file links; protected examples remain prose and unsupported tags are never silently lost.
- Version 0.1.31 runs the full display-organization pass on every streaming render snapshot, so a
  tool payload that balances mid-stream becomes a collapsed card immediately instead of exploding
  into raw markdown and collapsing again at completion; payload masking now starts with the blob's
  first characters, and snapshots are organized off the main thread. Verified on an emulator against
  a scripted mock stream: prose above the stream stays pixel-stable through the payload lifecycle.
- Version 0.1.32 adds original-quality image export from the full-screen viewer: Android 10+ saves
  directly into `Pictures/Hermes Remote` through MediaStore, Android 8/9 uses the system Save As
  picker, and every supported version can share the hydrated original through a protected content URI.
- Version 0.1.33 makes streaming payload masking string-aware: braces inside JSON string values no
  longer flip the mask verdict between 64ms snapshots, ending the whole-answer height oscillation a
  user screen recording captured at ~7Hz. The verdict is now monotone (one transition per payload),
  enforced by a prefix-monotonicity unit test and re-verified on an emulator with zero reversals.
- Version 0.1.34 fixes camera capture on devices that enforce the CAMERA permission contributed by
  the QR-scanner dependency: the app requests permission before capture, resumes automatically after
  approval, offers a settings recovery path after denial, and hardens FileProvider URI grants for OEM
  camera apps. An API 36 emulator verified permission, capture, return, and pending-image staging.
- Version 0.1.35 ships the first phase of semantic chat rendering: tool cards gain status dots
  (pulsing while running, mint check on success, coral cross with the exit code on failure),
  command-shaped payloads render `$ command` with terminal output behind a hierarchy rail and are
  expandable in Product mode, unified diffs render as red/green rows with add/del counts, inline
  code becomes a soft chip, and reasoning-card expansion survives lazy-list recycling.
- Version 0.1.36 completes the normal-content typography pass: GFM tables render in a bordered
  rounded container with a tinted semibold header row, and H2/H3 headings gain top breathing room
  so long answers read in visual chapters.
- Version 0.1.37 adds the second semantic-rendering phase: task-list payloads render as checklist
  cards with a progress bar and per-item state, and three or more consecutive tool calls collapse
  into one timeline card with per-row status, command summary, duration/exit metadata, and
  tap-to-expand output — long agent runs no longer render as a wall of separate cards.
- Version 0.1.38 upgrades in-chat search to occurrence-level hits with context snippets across
  text, reasoning, and tool outputs; adds long-press "Select text" full-screen selection; stamps
  messages with timestamps and renders time separators after 20-minute gaps; collapses setup into
  a single verify-then-save connect action; and localizes twelve secondary screens (cron, usage,
  archived, management, admin, tools, memory, MCP, env, prompts, diagnostics, messaging) to Chinese.
- Version 0.1.39 adds typewriter streaming: a paced reveal boundary decouples the display from
  bursty WebSocket delta arrival, so the pinned viewport grows in small uniform steps instead of
  multi-line lurches; latency stays bounded (~4 ticks) and reconnect-sized backlogs fast-forward
  in one hop. Probe-verified smooth reveal straight through arrival stalls.
- Version 0.1.40 is a chat-polish batch aligned with the Claude-app design audit: the action row
  persists only on the latest assistant turn (history turns use the long-press menu); markdown
  paragraph/list rhythm gains ~+10dp of measured breathing room; a downward drag on the
  conversation dismisses the keyboard; and a persistent running-status line shows the active tool
  command, a one-line reasoning tail, or a breathing "generating" state for the whole run.
- Version 0.1.41 is the second experience batch: de-chromed top bar (bare icons, + folded into a
  restyled overflow menu), a composer that grows with content and unifies send/stop into one
  accent circle (collapsing after send), a live elapsed timer on the running-status line,
  bottom-sheet message actions with retry-on-another-model, wrapping table cells, code-block
  language header bars, proportional user-bubble width, a one-time post-pairing notification
  onboarding sheet, and Home surfacing sessions blocked on approval/clarification.
- Version 0.1.42 adds durable cross-device task monitoring without changing Hermes itself: the Mac
  Connector observes session lifecycle read-only, the Relay persists acknowledged transitions, and
  Android restores running/waiting/completed state after backgrounding or reconnecting. Smart
  monitoring stays real-time while the app or a phone-started task is active, falls back to a
  low-frequency system job when idle, and exposes Realtime/Smart/Power-saving choices in Settings.
- Version 0.1.43 rounds off real-device feedback: the send/stop circle gets a fixed 48dp size and
  theme-primary color (no more per-profile neon egg), the model selector loads lazily with
  loading/failed-retry states instead of a silent empty sheet, and tables tighten their fixed
  column width, gain a header bar with copy-as-cells, and open a fullscreen viewing dialog.
- Version 0.1.45 adds table image export (fullscreen viewer saves/shares a PNG containing every
  column via a GraphicsLayer recording of the full-width node), rotation-proof fullscreen viewing
  with a manual landscape toggle, a synchronous-parse fix for the blank-on-open dialog, a debug
  component gallery (settings -> diagnostics), Roborazzi screenshot tests with six goldens, and a
  one-command dev stack under scripts/dev/.
- Version 0.1.46 moves table export onto the in-chat card (copy / save / share / fullscreen in
  the header bar) via a record-only offscreen exporter, and makes fullscreen viewing genuinely
  rotation-proof by hoisting its state to the screen level — the previous in-tree fix was lost
  during the markdown re-parse window on Activity recreation.
- Version 0.1.56 ships the card-page polish that missed the 0.1.55 build: the dark-mode fix
  (theme-truth via surface luminance, one-step lifted cards and gear button), the hand-drawn
  1.7dp stroke icon set at reference sizes with the avatar initial at 35% of its circle, and the
  remaining Hermes GO renames (wordmark, QS tile, About, crash report, diagnostics subject,
  table-export filename) plus the theme quick-switch and current-model rows.
- Version 0.1.59 fixes the composer model chip displacing the send/attach buttons on real
  devices: the mic and trailing controls are measured first at fixed size, the chip only takes
  the leftover width (long names ellipsize at one smaller type step), the 此对话 override tag and
  tonal background are removed from the chip (the override state lives in the model sheet's
  summary strip with 恢复默认), and the pre-load placeholder reads 默认模型 instead of 自动.
- Version 0.1.60 stabilizes message identity: history reconciliation reuses live message ids
  (per-role ordinal alignment) and list keys become ids verbatim, so viewport anchors survive
  every background history swap — fixing the random jump-to-bottom / position-drift class of
  bugs at the data layer. Anchor/key diagnostics probes ship behind the diagnostics toggle.
- Version 0.1.62 keeps the model catalog warm: a process-wide ModelCatalogStore refreshes the
  provider list in the background on every app start/foreground (plus profile switch and the
  reconnect edge), so the model picker opens instantly from cache. Loading/error states appear
  only when nothing is cached; a failed background refresh silently keeps the previous list, and
  setting a new default refreshes the shared cache so the chat sheet's current markers follow.
- Version 0.1.63 fixes the fling-arrest mis-tap on the scroll-to-bottom FAB (hidden while any
  scroll is in progress, fading in on rest) plus per-frame costs on the fling path, and closes
  the 0.1.60 open-path regression: acceptHistory now id-aligns like reconciliation, the pre-open
  frame shows a bottom-anchored skeleton, and the chat list state is keyed per session.
- Version 0.1.65 brings the reasoning-effort experience: a 推理强度 panel in the chat model
  sheet (思考 toggle + seven levels, session-scoped via the same config.set RPC the desktop
  client uses), the composer chip shows the current effort (fable-5 · 高), and each model's
  chosen effort is remembered device-locally and re-applied whenever that model is selected
  (row suffixes mirror the desktop list). The sheet drops the 此对话/默认 scope control —
  it now only changes the current chat, with the profile default edited solely on 设置 › 模型 —
  and both surfaces gain a manual force-refresh button with an in-flight spinner.
- Version 0.1.70 keeps layout-only lifecycle changes entirely local: rotating, folding/unfolding,
  and returning from a fullscreen table reuse the live conversation without history loading or a
  skeleton. Same-width returns restore the stable lazy item and exact offset; real width changes
  restore the same semantic reading line, including while a reply streams, while a user drag always
  takes control. The chat header drops the profile avatar, promotes search beside More, removes the
  unused New chat menu entry, and adds a manual conversation refresh that preserves visible content,
  waits for active streaming to finish, then force-syncs and remeasures the transcript in place.
- Version 0.1.90 lets an existing installation enter its REST-backed session list when the Relay,
  Mac Connector and Hermes HTTP path are healthy but `/api/ws` does not deliver `gateway.ready`
  within the startup timeout. The realtime socket keeps reconnecting in the background instead of
  holding the whole app behind `HR-CONN-002`; warm recovery likewise keeps the current screen and
  avoids repeatedly restarting the recovery probe. First-time setup remains strict and still
  requires the WebSocket handshake. The connection-test result now says that the Relay and Mac are
  reachable rather than implying that the separate realtime channel has also been verified.
- Version 0.1.89 stops the app from re-downloading a conversation it already has, and stops a
  routine reconnect from hiding the conversation behind the launch screen. Chat open, history
  reconciliation, foreground recovery and the startup coordinator used to wake together after a
  reconnect and each fetch the same transcript: one 0.5 MB conversation was pulled 144 times in a
  day, up to seven times in five seconds for a single screen recovery, and every fetch the phone
  abandoned stalled the Connector for a further 30 seconds. Identical in-flight fetches now share
  one round trip, and the history reconciliation ladder stops as soon as a snapshot is accepted
  instead of running all four rungs. The startup gate no longer renders for a warm reconnect: the
  conversation keeps the screen with the content it already committed while the socket comes back
  on its own backoff, and only a cold start or a real failure takes the screen. A blip shorter than
  three seconds now costs nothing at all.
- Version 0.1.88 adds session search and transcript sharing. Search V1 runs automatically as you
  type, across session titles and message bodies, and returns enriched hits that anchor to the exact
  message rather than the session; CJK queries are quoted so a Chinese phrase is matched as a phrase
  instead of being split into characters, and a failed search surfaces `HR-SEARCH-001` with a retry
  instead of an empty list. Opening a result hands the query to the chat, which marks every substring
  occurrence, auto-expands collapsed content around a hit, and keeps a search bar in the top bar for
  searching inside the open conversation. A conversation can now be shared as plain text, as a
  Markdown file, or as an image. A new chat opens on a greeting empty state instead of a blank
  transcript. Landing feedback is outline-only, and sheet gestures are off as a global rule: list
  scrolling never collapses or dismisses a sheet, which closes only from the grab area, the scrim, or
  the back key.
- Version 0.1.87 makes both halves of the card page's stats block real entries. 本周用量 had
  been silently tappable with no chevron while 远程设备 was inert; now each half carries its own
  16dp chevron and destination (usage, and the relay/token screen until a device page exists), and
  the ripple covers a whole half because the padding moved inside the clickable. Cell side padding
  drops to 14dp so the 23sp value keeps full size next to the new chevron.
- Version 0.1.86 redesigns the chat's 我的提问 sheet around the user task of returning to any
  turn in a long chat. Rows carry a 26dp ordinal circle, the prompt, a time only when the message
  has one (gateway history carries no timestamps, which had collapsed the old rows into plain
  text), and a thin chevron; the current row is a primaryContainer block with a filled ordinal and
  no extra label (TalkBack gets a state description), and the sheet opens with two rows above it.
  The header is centred with the prompt count as subtitle and 回到最新 on the right. Jumping
  highlights the landed prompt for 1.5 s; the pill carries its list segment from three groups on
  and long-pressing it opens the list. Design contract in `docs/DESIGN.md` §5.4, mockup under
  `docs/design/prompt-list-redesign.html`; device-verified on the HONOR phone.
- Version 0.1.85 finishes the turn-jump fix: 0.1.84 could still end a far jump at the bottom of
  the chat, because the turns below the target compose a line or two tall until their Markdown
  parses and the first placement clamped the list before they grew. The settle loop now keeps
  re-placing the target from its last known size while the neighbours grow (bounded to 24 frames
  at a clamped end), verified on the HONOR phone and by a `TurnJumpAlignTest` case that fails on
  0.1.84. It also stops `GatewayConnectionService` crashing with
  ForegroundServiceDidNotStartInTimeException on a fast background/foreground flip: a stop requested
  while a foreground start is pending is deferred until `startForeground()` has run
  (`ForegroundStartGate`, covered by `ForegroundStartGateTest`).
- Version 0.1.84 fixes the turn-jump pill freezing the chat. Jumping to a prompt in the last one
  or two turns — the usual target when reading near the bottom — left the list clamped at its
  end, and the settle loop bounced every frame between placing the target at the bottom and a
  relative scroll that could not move, flickering for ~3 s while swallowing touches. Corrections
  are now absolute `scrollToItem` calls that stop as soon as the list is at the needed end
  (device-reproduced with per-frame logging and covered by `TurnJumpAlignTest`). The startup
  entrance also starts at 2× so the icon is continuous with the system splash glyph.
- Version 0.1.83 fixes the avatar photo that never refreshed once replaced (the decoded bitmap
  was kept across cache-key changes, so the identity preview stayed on the first photo and the
  list header disagreed with the card page) and tidies four interactions. Terminal run verdicts
  now clear when the chat is opened: a stop pressed in the app leaves no status at all, remote
  interruptions and failures persist only until seen, and 已中断 / 运行失败 rows drop their dot.
  The pinned marker moves from ListItem's leading slot into the subline as a 14dp stroke pin so
  every title shares one left edge (search title matches carry it too). The chat's turn-jump pill
  fades 1.5 s after the list settles and returns on the next scroll. The identity settings page
  pins 保存 to a bottom bar (new DESIGN.md §5.12) and shows the profile name as the display-name
  placeholder without a floating label. JVM tests and screenshot goldens cover the first four;
  the identity page layout and the 0.1.82 splash handoff still need a device pass.
- Version 0.1.82 redesigns the startup gate around the wordmark: a 144dp icon over a bold
  `HERMES GO` lockup anchored at 22.5% of the screen, with the status line and a 144dp progress bar
  grouped beneath it and the version line at the bottom. Status is withheld for 700 ms and never
  shown when the gate is ready before then, six phase strings collapse to three, the dot animation
  goes, the icon lands in from the system splash and the gate fades out while the first screen
  rises in. The gate now follows the app theme with a dark `#0D141B` background and a `values-night`
  twin of the window colour; failure hides the bar, prints the HR code on its own line, and uses
  centred 240dp actions; short/landscape screens get a compact row layout. Design contract in
  `docs/DESIGN.md` §5.11 with the mockup under `docs/design/`. Verified with JVM tests and
  Roborazzi goldens only; the device run reached Setup because the test phone had no stored Relay
  configuration, so the on-device splash handoff still needs verification.
- Version 0.1.81 adds per-profile identity personalisation, kept on the phone until account sync
  exists. Each Hermes profile can carry a display name (the profile name moves to the subline on
  the card page and the picker), a photo (system Photo Picker, centre-cropped to a 512px WebP under
  the app's private avatars directory), a colour from the swatches or a hue slider whose saturation
  and lightness stay locked so white initials keep their contrast, and a solid or outline lettered
  style (outline lifts its hue on dark surfaces). A new 身份设置 screen — opened from the pencil on
  each picker row — previews the draft on a 96dp avatar, saves explicitly, and asks before
  discarding; the old palette button and colour sheet are gone. Notifications use the custom
  colour as their accent. Colours chosen in earlier versions migrate automatically. New error codes
  HR-MEDIA-002 (photo could not be read) and HR-STORE-001 (identity settings could not be saved).
- Version 0.1.80 reworks the notification shade into one card per session. WebSocket and Relay
  inbox events fold into the session runtime store first and a single projector turns each
  session's phase into its one card (running → needs approval / needs your answer → done or
  failed), so nothing stacks and an inbox replay of an already delivered completion is a no-op.
  Every card names the task (session title), carries identity · state in the header, tints the
  small-icon circle with the profile's avatar colour, shows the real event time (a live timer
  while running, todo progress and Android 16 Live Update promotion), puts error codes and
  durations on the last body line, and ships a redacted lock-screen version. Approval cards keep
  Allow once / This session / Deny (elevated: Deny / Open); clarify cards with up to two choices
  offer the choices as buttons plus Reply; done cards quote the reply and duration; failures get
  their own channel and settings toggle. The chat being viewed gets no card, cards elsewhere in
  the foreground app are silent, answering anywhere clears the card, swiped cards stay gone until
  the state changes, and shade actions show Working… / HR-NOTIF-001 feedback. Channels are
  renamed (attention, completed, failures, run_progress, service, updates), so per-channel
  settings from earlier versions are reset once. New codes: HR-SYNC-002, HR-NOTIF-001.
- Version 0.1.79 makes every session say which project it belongs to and fixes where new chats
  land. List, archived and search rows share one subline (folder glyph + project · model; the
  default project shows the model alone), and the multi-profile "身份：xxx" text is gone. The
  Projects segment lists the gateway launch directory as the always-first 默认项目 with last-active
  sublines and chevrons; drilling in shows the path and branch · model rows, with a one-time
  notice that the FAB now creates in that project (the Sessions FAB creates in the default
  project). Sessions can be moved between projects from the long-press menu or the new chat
  top-bar subtitle (project · branch, live from session.info) via session.workspace.move, with
  HR-SESS-003/004/005/006 covering missing folders, busy sessions, failures and silent
  fallbacks. Bottom-sheet titles are left-aligned everywhere, including the model selector.
- Version 0.1.78 is the sheet-polish release: the full M3 surfaceContainer family is defined in
  both themes (cool blue-tinted), removing the purple baseline cast from every bottom sheet,
  menu and dialog; all sheets adopt hermesSheetState (skipPartiallyExpanded) so they size to
  content and never open half-way; the model sheet gets a centered title, a merged model +
  reasoning-effort status card, a filled search field, and handle-only dismissal (list scrolling
  can no longer close it); and the sessions list reveals newly promoted 需要你处理 rows —
  auto-scrolling near the top, or floating a tappable ↑ pill when the reader is deep in the list.
- Version 0.1.77 stops covering the status bar and silences injected protocol turns. The app now
  declares edge-to-edge explicitly and drives status/navigation-bar icon appearance from the app's
  own theme (dark icons over the light UI even when the OS is in dark mode), so the clock and
  signal cluster stay visible. Server-injected system turns (async delegation reports, model
  switches, crash resumes) render as one-line centered timeline notes — expandable into a
  collapsed monospace card when they carry a body — instead of half-screen user bubbles;
  display_kind=hidden rows leave the transcript entirely and unknown future markers degrade to a
  quiet generic note.
- Version 0.1.76 rebuilds App updates around one safe recommended version: opening the page checks
  automatically while any local download recovers independently, history is read-only, and only the
  manifest latest can download/install. Downloading now has queued, paused, cancelling, retry and
  cleanup recovery states, APK/index resource limits, precise diagnostics, accessible state changes,
  and light/dark/large-font screenshot coverage. The release pipeline now validates APK minSdk,
  isolates release credentials, pins CI Actions, and serializes publication with a kernel lock.
- Version 0.1.75 fixes the root cause of decision-card answers arriving empty (verified live
  against the production Hermes): newer Hermes emits every clarify as a questions[] batch, so a
  single question is a one-element batch. Answering it without question_id released the agent's
  wait but the batch parser read an empty user_response. The respond now carries question_id
  whenever the wire provided a real qid, regardless of batch size; legacy no-qid payloads keep
  the old shape for older servers.
- Version 0.1.74 makes a lost decision-card answer visible and traceable: clarify.respond's
  server status is now read back, an answer that lands on an expired request appends an
  HR-CLARIFY-001 chat notice (instead of silently looking delivered) and the diagnostics log
  records the request id, answer length, and respond status/failure for every clarify exchange.
- Version 0.1.73 finishes the residual viewport and refresh pass: fold/unfold, rotation, and
  fullscreen-table returns first recover the stable conversation turn, then align the same semantic
  Markdown or table row across width changes and only release the anchor after several stable frames.
  Manual refresh no longer remounts the entire transcript — identical history stays pixel-still,
  changed stable-id rows update in place and restore the reading position, and queued refresh during
  streaming remains intact. The chat header also tightens its back/search/More icon spacing without
  shrinking or overlapping their 48dp touch targets, and shortens the Chinese refresh/copy labels.
- Version 0.1.72 hardens the decision card round-trip: single-select now works in two steps
  (tap to select, explicit Confirm to submit) matching multi-select, a failed clarify.respond
  restores the card for retry instead of silently dropping the answer, the parser falls back to
  clarify_id/requestId when request_id is absent, and the clarify path logs request ids and
  respond outcomes to the diagnostics log for on-device evidence.
- Version 0.1.71 ships the structured decision card: clarify questions render their upstream
  choices (single-tap answer, multi-select confirm, batch step-through with per-question
  locking), the free-text Other path is always present, skip is explicit (no more silent
  empty-answer on outside tap), and clarify notifications preview numbered choices so an
  inline reply of a number selects an option.
- Version 0.1.69 completes the residual chat-motion pass: authoritative history remains behind a
  softly sweeping skeleton until Markdown and reverseLayout stay geometrically stable, then enters
  through a short crossfade without exposing an intermediate user-bubble frame. Fold/unfold and
  full-screen table returns restore the reader's relative position inside the same semantic block
  across several stable frames instead of reusing a stale pixel offset. Live Markdown and the
  background-process slot animate measured height changes from the bottom edge, preventing the
  remaining small bidirectional steps during typewriter output and final settlement.
- Version 0.1.68 stabilizes the entire chat lifecycle: opening conversations is serialized onto one
  canonical navigation destination, back always returns directly to Chats, and profile switches can
  no longer race pending opens. Initial history, live typewriter output, and completion now share one
  stable presentation pipeline, eliminating entry flashes, streaming rewinds, and end-of-run jumps.
  Semantic viewport anchors preserve the exact reading position across fold/unfold, configuration
  changes, and full-screen table viewing; the jump-to-bottom control cannot intercept a fling while
  hidden. Redacted navigation breadcrumbs are also attached to crash reports for future diagnosis.
- Version 0.1.67 refines the session list: the segmented control drops its selected checkmark
  (labels no longer shift on switch), Recent splits into 今天 / 前 7 天 / 更早 (rolling 7-day
  window, newest-first inside each collapsible group), project folders become 1.7dp stroke
  outlines with the project colour on the line, and archived rows get the matching stroke
  archive-box leading icon, lose their dividers and trailing button, and gain a long-press
  sheet with unarchive and delete (restoring the delete action lost in the segment merge).
- Version 0.1.66 fixes the navigation completion guard that incorrectly treated every destination
  as a finished connection-repair screen, so existing chats, New chat, search, models, cron, and
  settings now stay open instead of immediately returning to Chats. First-time pairing now keeps
  the startup gate up until the socket, profiles, and initial session snapshot are ready; later
  foreground disconnects restore the gate and refresh the exact visible chat/list/project/search
  destination before revealing it. Connector-offline responses are also distinguished from Relay,
  URL, and token failures, and connection tests cannot save a stale in-flight result.
- Version 0.1.64 completes the startup experience: phase-based progress now advances continuously
  with a moving highlight and animated status dots; cold and interrupted warm starts stay behind
  the gate until the destination's critical data is ready, while a healthy warm return remains
  instant. Network/Relay failures stay retryable on the gate, invalid URLs or rejected credentials
  open a prefilled repair screen with a masked token, and saving reruns the complete readiness gate
  before cold starts enter Chats or warm starts return to the exact previous screen. The unsupported
  offline bypass is removed and the official product slogan stays English in every brand lockup.
- Version 0.1.61 moves the brand colour from mint to the launcher icon's blue (#0B5FD0 light,
  #A9C7FF dark, hue 215) and re-tints the neutrals cool to sit under it. It also splits status
  colour out of the brand: a shared StatusColors palette backs both the connection traffic light
  — which gains the dark tier it never had, its green and red previously sitting at ~3.2:1 on the
  dark surface — and the session list's completed state, which used to resolve to primary and
  under a blue brand would read the same as the section headers and FAB around it. The startup
  screen was already icon-blue while the app was mint, so the splash no longer changes colour on
  the first frame. Palette values and their contrast floors are pinned by StatusColorsTest.
- Version 0.1.58 settles the card page's density and iconography: shortcut rows drop to 17sp
  text / 22dp icons / 15sp trailing values with tighter padding (user-picked density), the gear
  becomes a proper toothed cog (the hub-and-ticks simplification read as a brightness glyph),
  the update box gains a step of visual weight, and the last two Hermes GO renames land
  (transcript share subject, crash-report notification title).
- Version 0.1.57 fixes the landscape toggle bouncing back when auto-rotate is off (orientation
  reset moved to explicit close paths), makes the fullscreen table viewer edge-to-edge and
  immersive on the dialog's own window, draws a faint full grid on tables, and replaces the
  inference-based send-to-bottom with an action-driven tick so background history reconciliation
  can no longer yank readers to the bottom.
- Version 0.1.55 adds a connection-aware startup gate using the current multicolour icon: configured
  cold starts wait for `gateway.ready`, unconfigured first runs go straight to Setup, healthy warm
  returns preserve the current screen, and interrupted returns show bounded recovery with retry,
  settings and cached-UI actions. It also upgrades the model selector with current/default/session
  scope visibility, collapsible provider groups, search expansion and stable localized model errors;
  updates the public name to Hermes GO without changing the install identity (wordmark, QS tile,
  About, crash report, diagnostics subject, and table-export filename included); fixes card-page
  dark mode (theme-truth via surface luminance, one-step lifted cards and gear button); redraws the
  card icon set as 1.7dp stroke vectors at reference sizes with the avatar initial scaled to 35% of
  its circle; and locks the card page
  to design scale with lockstep stat sizing, near-white cards and reference-proportioned typography.
- Version 0.1.54 upgrades the fit strategy to shrink-then-wrap: values shrink to the minimum
  acceptable size on one line first, and only if the floor still overflows do they wrap to two
  lines at that floor — ellipsis remains solely as a two-line last resort (verified with a
  23-char device name at fontScale 1.3).
- Version 0.1.53 makes the stats card resilient to large system font scales: auto-shrink now
  actually fires under overflow=Ellipsis (isLineEllipsized, not didOverflowWidth), the sub-lines
  and shortcut values shrink too (deep floors), cell padding is trimmed, and latency above 999ms
  formats as seconds — "mac-…"/"已连接 · 2…" truncation is gone.
- Version 0.1.52 re-derives the card page type ramp from the reference by internal ratio
  (label anchored at 15sp): 23sp auto-shrinking stat values (numbers never truncate, floor
  17sp), 20sp shortcut rows with 28dp icons and 16sp trailing values, 32sp wordmark, 24dp page
  margins, warm-neutral containers, a full-height stats divider, the boxed-arrow update glyph,
  and lighter chevrons.
- Version 0.1.51 pins the card page to the reference shot: outlined icons (clock, moon, cube,
  update, gear) replace the filled set, the stats cells use the exact 15/27/15sp type ramp, and
  the usage value drops the "token" suffix.
- Version 0.1.50 rebuilds the card page to the real-device base design: Hermes wordmark with a
  settings gear, an identity card showing only the current profile (tap → a dedicated profile
  picker where each profile's avatar colour is customisable via a 12-swatch sheet, device-local,
  avatar-only), a neutral two-cell stats container per the reference layout, and four shortcut
  rows (scheduled jobs with a neutral badge, theme quick-switch sheet, current default model,
  app updates with the current version).
- Version 0.1.49 fixes the card page's interaction contract: system back closes the card instead
  of finishing the app (drawerState-wired sheet + a belt-and-braces BackHandler), returning from a
  screen opened FROM the card restores the open card, scrim taps only dismiss, and the usage tile
  says "token".
- Version 0.1.48 aligns the card page with the design: the drawer keeps a scrim gap (86% width,
  360dp cap), sits on plain surface with end-only rounded corners, and the settings sub-line
  ellipsizes. The remote-device tile now reads the production edge's /relay-health (bare /health
  belongs to the release server there), fixing the false "connector offline".
- Version 0.1.47 is the single-screen redesign: the session list becomes the only main screen
  (bottom tabs, Home feed, You/Management/Profiles/Session-admin screens removed). A card page off
  the top-left avatar is the app's one profile-switch point, with weekly-usage and remote-device
  tiles plus cron/settings/update entries; switching profiles now scopes EVERYTHING (sessions,
  projects, archived, search, cron, usage, models, skills, messaging) and a refused switch keeps
  the old profile with a retry toast. The list gains 会话/项目/已归档 segments, a collapsible
  needs-you group that jumps approval-blocked runs to the top, and a cron alert strip; search moves
  to its own screen (archived included). Per-profile chrome tinting is retired — identity lives in
  the solid avatar colour alone — and legacy tab deep links land on the list.
- Version 0.1.44 makes background recovery self-healing: a short background switch keeps a 45-second
  socket lease, returning to the foreground reconnects immediately, and interrupted turns reattach
  and reconcile against authoritative history so partial output and stale “generating” state do not
  linger. Connection recovery is visible in chat, failures carry stable error codes with redacted
  diagnostics, and notification/channel/action copy follows the language selected inside the app.
- The production Relay hostname is resolved directly inside the app so Chinese carrier DNS cannot
  break the connection when Tailscale is disabled. HTTPS hostname and certificate checks remain in place.
- Relay requests are bounded so a failed endpoint becomes a retryable error instead of an endless spinner.

## Runtime language contract

- Chinese remains the default, and changing the in-app language updates Compose screens, widgets,
  notifications, setup and crash-recovery surfaces without requiring an app restart.
- ViewModels and background components carry language-independent `LocalizedText` or stable
  `AppError` values; user-facing copy is resolved only at the display boundary.
- Product and protocol names, model or project identifiers, user/assistant content, server release
  notes, command output, and expanded diagnostic details remain in their source language.
- `LocalizationCoverageTest` rejects newly added raw Compose UI literals unless the line includes a
  reviewed `l10n-allow` reason for one of those intentional exceptions.

## Startup and connection recovery

- A configured process-cold launch shows the branded startup gate until the shared WebSocket has
  received `gateway.ready`, the active profile is resolved, and the first session-list snapshot is
  cached. The Sessions destination is created only afterward, so it renders the cached first screen
  instead of replacing the startup gate with a second blocking loader.
- A first launch with no stored Relay configuration skips the custom gate and opens Setup directly.
- Foreground returns keep the existing navigation stack. A healthy connection shows no gate; a
  disconnected connection gets a 200 ms no-flash recovery window before the gate overlays the
  current destination. The overlay remains until the visible Chats/Projects/Archived, conversation,
  or Models ViewModel has committed its critical refresh, then returns to that same destination.
- Device-offline, DNS/timeout, Relay 5xx, and initial-data failures stay on the startup gate with
  retry and connection-settings actions. Invalid URLs and rejected credentials route to a repair
  version of Connection Settings with the stored values prefilled and the token masked; saving
  re-runs the complete startup gate before returning to the previous route. There is no offline
  bypass because the app has no supported offline mode.
- The gate is a brand lockup first (icon 144dp, `HERMES GO` wordmark, slogan) anchored at 22.5%
  of the screen height, with the status line and a 144dp progress bar grouped under it and the
  version line (`0.1.81 · DEBUG`) at the bottom. Status and progress are withheld for 700 ms and
  never appear if the gate reaches READY before then, so a fast launch is a single quiet fade.
  Copy is merged into "Connecting" / "Preparing conversations" / "Connection ready" (recovery:
  "Restoring connection" / "Restoring the current screen"); the phase granularity still drives the
  bar, whose gradient is anchored to the full track width. The moving highlight is the only
  continuous motion. Failure hides the bar, prints the `HR-*` code on its own line, and offers a
  240dp Reconnect button plus a Check-connection-settings text button. The gate follows the
  effective app theme (light `#F8FAFD` / dark `#0D141B`, with a `values-night` twin of
  `startup_background`), lands the icon in from the system splash on cold start, and fades out
  while the first screen rises in. Design contract: `docs/DESIGN.md` §5.11.
- The startup brand lockup keeps `HERMES GO` and the official slogan
  `Your AI agent, in your pocket.` in English; operational status and recovery actions still follow
  the in-app language setting.

## Build

For local development checks, Gradle remains available directly. For every APK distributed to a
user or tester, run the repository release gate instead:

```bash
cd ..
./scripts/package-debug-apk.sh
```

The gate runs Android unit tests and `assembleDebug`, then validates the package metadata, version,
signature, staged filename, and SHA-256 before printing `APK_RELEASE_OK`.

Gradle keeps its canonical APK at `app/build/outputs/apk/debug/app-debug.apk`. After every debug
build, the tester-facing APK is staged automatically as:

```text
app/build/outputs/apk/distribution/debug/Hermes-Remote-0.1.90-debug.apk
```

For every APK distributed to testers, increment `appVersionName` by one patch version and
increment `appVersionCode` by one in `app/build.gradle.kts`. Never distribute the unversioned
canonical APK.

Debug APKs currently use the temporary shared signing identity documented in `../docs/SIGNING.md`.
Every `assembleDebug` verifies that certificate before compiling, so a new Agent or build machine
cannot silently produce an APK with an incompatible signature.

See `UPSTREAM.md` and `../docs/ANDROID_BASE_AUDIT.md` before importing or distributing the derivative app.

## App updates

The You tab links to a manual update page. It uses a credential-free HTTP client to read the internal
channel manifest from `https://mrlgs.net/releases/index.json`, downloads with Android DownloadManager,
and verifies the complete APK before opening Android's user-confirmed package installer. It does not
perform automatic, forced, silent, incremental, or downgrade installs. Release descriptions live in
`releases/<version>.json`; derived hashes, sizes, timestamps, and signing data are generated only by
the release gate and publisher. See `../docs/APP_UPDATE.md`.
