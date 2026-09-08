# Hermes GO

Your AI agent, in your pocket. Hermes GO securely connects the mobile app to Hermes running on your Mac, without exposing the Mac to the public internet or requiring Tailscale or a VPN. The Mac companion is called **Hermes Go Desktop**.

```text
Hermes GO Mobile -- HTTPS/WSS --> HK Gateway <-- outbound WSS -- Hermes Go Desktop --> localhost Hermes
```

## Status

The repository contains the first relay MVP:

- `android/` — Kotlin/Compose client derived from the pinned `adebnar/hermes-android` GPLv3 base
- `gateway/` — public HK relay with app/connector authentication, a Hermes-compatible facade, and a durable mobile event inbox
- `connector/` — outbound-only macOS agent with Basic Auth, Cookie, WS Ticket, REST/WebSocket forwarding, and read-only task lifecycle observation
- `desktop/` — native macOS menu-bar GUI plus a default-off signed bootstrap/migration core; the current packaged UI still safely observes the existing Connector and retains legacy pairing
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

Production clients use a single HTTPS/WSS edge at `https://mrlgs.net` on port 443. Nginx routes
Gateway and release paths to private service ports; see `docs/ENVIRONMENT.md`.

The first Hermes Go Desktop slice is documented in `docs/DESKTOP_PHASE0.md`. Its GUI, tests, design
contract, and concept images live alongside the existing system; it does not modify Hermes or start a
second Connector.

The historical Google-account onboarding, one-account/one-Connector rule, multi-phone client behavior,
legacy migration, and shared acceptance matrix are documented in `docs/ACCOUNT_MODE_DESIGN.md`.
The corresponding work breakdown, dependencies, estimates, test gates, and staged rollout are in
`docs/ACCOUNT_MODE_IMPLEMENTATION_PLAN.md`.
The I0 implementation contracts are split into `docs/ACCOUNT_MODE_API.md`,
`docs/ACCOUNT_MODE_SECURITY.md`, `docs/ACCOUNT_MODE_MIGRATION.md`, and
`docs/ACCOUNT_MODE_TEST_PLAN.md`; all are explicitly non-production until their go/no-go gate passes.
Current local I1 evidence, including the passed disposable-PostgreSQL gate, is recorded in
`docs/ACCOUNT_MODE_I1_TEST_RECORD.md`.
The completed local I2 binding, V2 Connector, account-aware routing, and multi-phone lifecycle backend gate is recorded in
`docs/ACCOUNT_MODE_I2_TEST_RECORD.md`.
The local I3-A Desktop account-client slice and its remaining live OAuth/binding gates are recorded in
`docs/ACCOUNT_MODE_I3_TEST_RECORD.md`.
The accepted next-stage account expansion—an email-code-only first release, up to three owned Macs,
whole-device sharing, a Web account center, and clean-Mac Desktop bootstrap—is specified in
`docs/ACCOUNT_PLATFORM_EXPANSION.md`. It extends the existing local account baseline without enabling
production features or changing Android until its later adoption gate. Google and Apple are deferred
providers; the existing Google implementation remains behind an independent default-off flag.
The Desktop E4-B signed-release, atomic-install, LaunchAgent, and rollback contract is documented in
`docs/DESKTOP_RELEASE_MANIFEST.md`; local automated evidence is in `docs/DESKTOP_E4_TEST_RECORD.md`.
The default-off E5 whole-device sharing implementation and its remaining live-provider, interactive
Web, multi-node, and physical acceptance gates are recorded in `docs/ACCOUNT_MODE_E5_TEST_RECORD.md`.
The default-off E6 secure Web-session foundation, first same-origin interactive account-center
slice, and remaining physical/staging gates are recorded in
`docs/ACCOUNT_MODE_E6_WEB_SESSION_TEST_RECORD.md`.
Credential-free mail-domain DNS audit and explicitly confirmed Resend staging delivery probes are
available through `npm run ops:email-domain` and `npm run ops:email-staging`; neither command deploys
or enables account mode.
The local E7 email-first Desktop flow, scoped phone-removal reauthentication, replay safety, and
remaining live-mail/packaged-device gates are recorded in
`docs/ACCOUNT_MODE_E7_DESKTOP_TEST_RECORD.md`.
The local E8 Android email-code, explicit owned/shared-Mac selection, transport-isolation, and
conversation-affinity evidence is recorded in `docs/ACCOUNT_MODE_E8_ANDROID_TEST_RECORD.md`.
The default-off E9 permanent Cloud-account deletion state machine, Web/Desktop confirmation,
cross-account sharing cleanup, and remaining privacy/physical/deployment gates are recorded in
`docs/ACCOUNT_MODE_E9_TEST_RECORD.md`.
The product/privacy/support sign-off surface is `docs/ACCOUNT_DELETION_REVIEW.md`; it must be complete
before any account-deletion rollout flag is enabled.
The behavior-preserving Cloud Gateway modularization and its staged release gates are tracked in
`docs/CLOUD_GATEWAY_REFACTOR_PLAN.md`.
The staging-only R3 Cloud Ops command contract and its completed ephemeral deployment-test gates are documented in
`docs/CLOUD_GATEWAY_R3_OPS.md` and `docs/CLOUD_GATEWAY_R3_TEST_RECORD.md`.
The staging-only R4 safe-upgrade design and completed isolated staging/fault-injection evidence are documented in
`docs/CLOUD_GATEWAY_R4_PLAN.md` and `docs/CLOUD_GATEWAY_R4_TEST_RECORD.md`.
The production-promotion work is deliberately separate from those staging commands. Its read-only audit contract,
legacy recovery boundary, PostgreSQL/off-host restore gates, and still-pending production actions are tracked in
`docs/CLOUD_GATEWAY_R5_PLAN.md`.

Android now contains a capability-gated email-code account path beside the existing Relay URL +
`APP_TOKEN` mode. Account mode stores its own encrypted phone session, selects owned/shared Macs by
opaque device ID, and sends only the Hermes GO bearer to the public Gateway. Legacy credentials are
kept separately for compatibility and rollback; the Mac Hermes credential remains only on the Mac
Connector. Each conversation also retains its originating Mac, so changing the default device affects
new conversations without silently moving or merging existing history.

## Security baseline

- The Mac connector creates an outbound connection; no Mac port is exposed.
- App and connector credentials are separate.
- Production traffic must use TLS (`wss://`).
- Secrets are supplied through environment variables and are excluded from Git.
- Pull requests run gitleaks plus Semgrep Community Edition in a digest-pinned, read-only CI job;
  Semgrep telemetry is disabled and the job receives no repository secrets.
- The deployed legacy MVP remains single-user and single-Mac; capability-gated multi-device and
  sharing work is local-only until its separate rollout gates pass.
- Attachment uploads are capped and stored transiently on the Mac; output files stream with
  acknowledged backpressure instead of crossing the control channel as one oversized message.
