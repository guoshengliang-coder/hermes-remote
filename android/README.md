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
  current destination.
- Recovery stops blocking after 15 seconds and offers retry, connection settings, or temporary
  access to cached UI. Device-offline and connection failures use the registered `HR-CONN-*` codes.
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
app/build/outputs/apk/distribution/debug/Hermes-Remote-0.1.63-debug.apk
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
