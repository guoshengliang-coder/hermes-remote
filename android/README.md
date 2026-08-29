# Android client

The selected base is [`adebnar/hermes-android`](https://github.com/adebnar/hermes-android), pinned for the initial integration at tag `v0.1.52` / commit `9f08f39ed2b9fc7cb29a551b1d9b695a409fdb7a`.

The upstream client is Kotlin + Jetpack Compose and already implements Hermes REST, `/api/ws` JSON-RPC, sessions, streaming chat, Markdown, tool cards, attachments, reconnect, and encrypted credential storage. We will retain those layers where possible.

The HK Gateway should expose a Hermes-compatible facade rather than forcing the app to replace every existing repository. The macOS Connector will transport facade requests to the real loopback Hermes service.

See `UPSTREAM.md` and `../docs/ANDROID_BASE_AUDIT.md` before importing or distributing the derivative app.
