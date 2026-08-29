# Hermes Remote

Hermes Remote securely connects an Android app to Hermes running on a Mac mini without exposing the Mac to the public internet and without requiring Tailscale or a VPN.

```text
Android App -- HTTPS/WSS --> HK Gateway <-- outbound WSS -- macOS Connector --> localhost Hermes
```

## Status

The repository contains the first relay skeleton:

- `android/` — integration plan for the pinned `adebnar/hermes-android` GPLv3 base
- `gateway/` — public HK relay with app/connector authentication
- `connector/` — outbound-only macOS agent with mock and Hermes HTTP modes
- `protocol/` — shared wire-message types and validation
- `deploy/` — Docker and macOS launchd templates
- `docs/` — architecture, intake checklist, and local smoke test

## Local smoke test

1. Install Node.js 20 or newer.
2. Copy `.env.example` values into your shell or local `.env` files. Never commit real tokens.
3. Run `npm install` and `npm run build`.
4. Start the gateway with `APP_TOKEN=dev-app CONNECTOR_TOKEN=dev-connector npm run dev:gateway`.
5. Start the mock connector with `CONNECTOR_TOKEN=dev-connector HERMES_MODE=mock npm run dev:connector`.

See `docs/SMOKE_TEST.md` for the WebSocket test message.

The initial real host profile uses direct TLS on port `8444` with systemd; see `docs/ENVIRONMENT.md`.

## Security baseline

- The Mac connector creates an outbound connection; no Mac port is exposed.
- App and connector credentials are separate.
- Production traffic must use TLS (`wss://`).
- Secrets are supplied through environment variables and are excluded from Git.
- The MVP is single-user and single-Mac by design; multi-user authorization is deferred.
