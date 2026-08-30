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
- The production Relay hostname is resolved directly inside the app so Chinese carrier DNS cannot
  break the connection when Tailscale is disabled. HTTPS hostname and certificate checks remain in place.
- Relay requests are bounded so a failed endpoint becomes a retryable error instead of an endless spinner.

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
app/build/outputs/apk/distribution/debug/Hermes-Remote-0.1.23-debug.apk
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
