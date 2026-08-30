# Hermes Remote

Hermes Remote securely connects an Android app to Hermes running on a Mac mini without exposing the Mac to the public internet and without requiring Tailscale or a VPN.

```text
Android App -- HTTPS/WSS --> HK Gateway <-- outbound WSS -- macOS Connector --> localhost Hermes
```

## Status

The repository contains the first relay MVP:

- `android/` — integration plan for the pinned `adebnar/hermes-android` GPLv3 base
- `gateway/` — public HK relay with app/connector authentication and a Hermes-compatible facade
- `connector/` — outbound-only macOS agent with Basic Auth, Cookie, WS Ticket, REST, and WebSocket forwarding
- `protocol/` — shared wire-message types and validation
- `deploy/` — Docker and macOS launchd templates
- `docs/` — architecture, intake checklist, and local smoke test

The first HK + Mac mini deployment and its verified operations are recorded in `docs/DEPLOYMENT.md`.

## Agent collaboration

Hermes may coordinate Codex, Claude Code, and other agents on this repository. Every agent must read
`AGENTS.md` before changing the project. `CLAUDE.md` provides the Claude Code entry point; `AGENTS.md`
remains the single source of truth for concurrency, security, testing, Android versioning, APK delivery,
and deployment rules.

## Local smoke test

1. Install Node.js 20 or newer.
2. Copy `.env.example` values into your shell or local `.env` files. Never commit real tokens.
3. Run `npm install` and `npm run build`.
4. Start the gateway with `APP_TOKEN=dev-app CONNECTOR_TOKEN=dev-connector npm run dev:gateway`.
5. Start the mock connector with `CONNECTOR_TOKEN=dev-connector HERMES_MODE=mock npm run dev:connector`.

See `docs/SMOKE_TEST.md` for the WebSocket test message.

The initial real host profile uses direct TLS on port `8444` with systemd; see `docs/ENVIRONMENT.md`.

For the Android base, configure the public Gateway URL and the Gateway `APP_TOKEN` in token mode. The public token terminates in Hong Kong; the separate local Hermes credential exists only on the Mac Connector.

## Security baseline

- The Mac connector creates an outbound connection; no Mac port is exposed.
- App and connector credentials are separate.
- Production traffic must use TLS (`wss://`).
- Secrets are supplied through environment variables and are excluded from Git.
- The MVP is single-user and single-Mac by design; multi-user authorization is deferred.
