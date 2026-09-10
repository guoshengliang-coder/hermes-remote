# Android account-mode handoff

This is the implementation/acceptance bridge for an Android branch adopting the already deployed
email-first account contract. `ACCOUNT_MODE_API.md` remains the normative API definition; this file
does not redefine fields or error semantics.

## Stable implementation sequence

1. Discover `GET /v2/capabilities`. Enter account onboarding only when `accountAuth.enabled` is true,
   `accountAuth.android` is true, and `email_otp` is present in `accountAuth.providers`. Do not infer
   behavior from the Gateway version string.
2. Persist one UUID `clientInstallationId` for the Android installation. Reuse it for challenge,
   exchange, refresh, and later launches; reinstall/app-data deletion creates a new installation.
3. Request `POST /v2/auth/email/challenges` with email, `platform: "android"`, and that installation
   UUID. Honor the returned `expiresAt` and `resendAfter`; a `202` never proves account existence.
4. Exchange the six-digit code through `POST /v2/auth/email/exchange` with a newly persisted
   `Idempotency-Key`. Reuse the exact key and request after an ambiguous/lost response. Never persist
   the six-digit code.
5. Store only the Hermes GO access/refresh tokens in Android secure storage. Refresh with another
   persist-before-send idempotency key; never send the legacy App Token with an account bearer.
6. Load `GET /v2/account`, then `GET /v2/devices` when device selection is advertised. Keep owned and
   shared devices distinguishable through `access`; do not infer ownership from display names.
7. Persist the exact `deviceId` on each new conversation/task. Route REST through
   `/v2/devices/{deviceId}/api/...` and WebSocket through `/v2/devices/{deviceId}/ws`, both with the
   account Bearer header. A later default-device change affects only new conversations.
8. Use the account Bearer for `/api/mobile/events` and its acknowledgement/read routes so delivery
   cursors remain scoped to this phone installation.

## Compatibility invariants

- Existing legacy URL/App-Token state remains intact until the account path has completed live REST
  and WebSocket acceptance. Capability/network failure never deletes or overwrites it.
- Never attach both `Authorization` and `X-Hermes-Session-Token`; the Gateway rejects mixed mode.
- With multiple accessible Macs, never fall back to unscoped `/api/*` or `/api/ws`; the Gateway
  intentionally returns `HR-BIND-009` instead of guessing.
- `HR-BIND-001` means the account has no active usable Connector. `HR-CONN-005` remains the legacy
  startup/offline presentation and must not be used to diagnose an account device solely from public
  `/relay-health`.
- Token values, OTPs, account IDs, emails, and provider bodies stay out of logs and copied diagnostics.

## Branch test gate

Automate these before physical acceptance:

- capability off/email provider absent preserves legacy mode;
- challenge cooldown and expiry presentation uses server timestamps;
- exact lost exchange/refresh response replays with the persisted idempotency key;
- mixed credential headers are impossible in both REST and WebSocket clients;
- owned and shared device decoding, explicit route escaping, and per-conversation device affinity;
- one-device compatibility versus multi-device `HR-BIND-009` recovery;
- access expiry refreshes once under concurrent REST/WebSocket demand;
- sign-out revokes only this phone session and removes local account tokens without unbinding a Mac;
- every surfaced failure uses the registered localized `HR-*` contract and redacted diagnostics.

Physical cutover then follows `DESKTOP_TEST_PLAN.md` under “Android-account coordinated acceptance”.
No Android branch should deploy Gateway flags or migrate the Mac as part of its own package step.
