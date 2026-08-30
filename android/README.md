# Hermes Remote Android

The selected base is [`adebnar/hermes-android`](https://github.com/adebnar/hermes-android), pinned at tag `v0.1.52` / commit `9f08f39ed2b9fc7cb29a551b1d9b695a409fdb7a`.

The upstream client is Kotlin + Jetpack Compose and already implements Hermes REST, `/api/ws` JSON-RPC, sessions, streaming chat, Markdown, attachments, reconnect, and encrypted credential storage. Hermes Remote retains those foundations and routes them through the HK Relay.

## First UI pass

- Application ID: `com.hermes.remote`
- Default Relay: `https://mrlgs.net:8444`
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
- The production Relay hostname is resolved directly inside the app so Chinese carrier DNS cannot
  break the connection when Tailscale is disabled. HTTPS hostname and certificate checks remain in place.
- Relay requests are bounded so a failed endpoint becomes a retryable error instead of an endless spinner.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

See `UPSTREAM.md` and `../docs/ANDROID_BASE_AUDIT.md` before importing or distributing the derivative app.
