# Hermes Remote Android

The selected base is [`adebnar/hermes-android`](https://github.com/adebnar/hermes-android), pinned at tag `v0.1.52` / commit `9f08f39ed2b9fc7cb29a551b1d9b695a409fdb7a`.

The upstream client is Kotlin + Jetpack Compose and already implements Hermes REST, `/api/ws` JSON-RPC, sessions, streaming chat, Markdown, attachments, reconnect, and encrypted credential storage. Hermes Remote retains those foundations and routes them through the HK Relay.

## First UI pass

- Application ID: `com.hermes.remote`
- Default Relay: `https://47.239.30.253.sslip.io:8444`
- Setup requires only the Relay URL and a dedicated App Token; Mac credentials never enter the app.
- Calm mint/neutral visual system with a floating, full-width composer inspired by WorkBuddy's layout language.
- Real Markdown rendering, readable JSON output normalization, and collapsed tool-result cards.
- Camera, photo picker, voice input, saved prompts, sessions, and model selection remain available.
- Version 0.1.2 uses a document-style assistant layout with stronger Chinese/Markdown typography,
  compact user bubbles, reply actions, a floating composer, and a WorkBuddy-inspired attachment sheet.
- The production Relay hostname is resolved directly inside the app so Chinese carrier DNS cannot
  break the connection when Tailscale is disabled. HTTPS hostname and certificate checks remain in place.
- Relay requests are bounded so a failed endpoint becomes a retryable error instead of an endless spinner.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

See `UPSTREAM.md` and `../docs/ANDROID_BASE_AUDIT.md` before importing or distributing the derivative app.
