# Android base audit

## Decision

Use `adebnar/hermes-android` as the Android base. The reviewed revision is `v0.1.52` (`9f08f39ed2b9fc7cb29a551b1d9b695a409fdb7a`).

## Reuse

- Kotlin, Jetpack Compose Material 3, MVVM, Hilt, and StateFlow structure.
- OkHttp REST client and `/api/ws` JSON-RPC client.
- Session, profile, model, attachment, reconnect, notification, and encrypted credential flows.
- Existing Markdown renderer and copyable code blocks.
- Existing structured `message.*`, `reasoning.*`, and `tool.*` event reducer.

## Required changes

1. Point setup at the public HK Gateway rather than a Tailscale/LAN Hermes address.
2. Use a Hermes-compatible relay facade so existing REST repositories and JSON-RPC calls survive.
3. Separate the public relay credential from the Mac-local Hermes session token.
4. Normalize nested tool results before rendering. A result may be a JSON object, a JSON string containing `{\"output\": ...}`, or text containing literal `\\n` escapes.
5. Improve Tool cards with collapsed summary, expandable formatted result, copy, and save actions.
6. Use a distinct application ID, name, icon, and signing key for Hermes Remote.
7. Preserve GPLv3 attribution and source-availability obligations for distributed APKs.

## Why the screenshot can still look broken

The reviewed upstream already renders assistant Markdown and limits Tool output to six lines. However, `ServerEvent.str()` intentionally converts object/array results to raw JSON and does not recursively unwrap JSON encoded inside a string. A tool result shaped like `{\"output\":\"line 1\\nline 2\"}` can therefore reach the Tool card as raw serialized content. This is a normalization issue rather than a reason to discard the whole UI.

## Relay direction

The preferred public surface is:

```text
Android Hermes REST + /api/ws
              |
              v
HK Hermes-compatible facade
              |
      authenticated tunnel
              |
              v
macOS Connector -> localhost Hermes
```

The current custom chat envelope remains useful for relay smoke tests, but the next protocol revision should add generic HTTP request/response and WebSocket frame tunnelling.
