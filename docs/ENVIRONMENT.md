# Deployment environment (sanitized)

Collected on 2026-08-29. Concrete IP addresses, hostnames, SSH users, and credentials are intentionally excluded from source control.

## Mac mini

- Apple M2 / arm64
- macOS Sonoma 14.8.9
- Hermes agent `v0.20.6`, installed from Git with one carried local commit
- Hermes dashboard currently listens on a private-network address at port `9119`
- Dashboard authentication uses Basic Auth, a cookie session, and a short-lived `/api/auth/ws-ticket`
- Connector must support the Hermes private REST + WebSocket protocol; it is not OpenAI-compatible

## HK relay host

- Ubuntu 26.04 LTS, x86_64, 4 CPU, about 7 GiB RAM
- Nginx owns public HTTPS/WSS 443 and performs path-based routing
- Ports `80` and `8443` remain owned by existing services
- Gateway upstream: `8444/TCP`; release upstream: loopback `9443/TCP`
- The Gateway uses a Certbot certificate for `mrlgs.net`, with the legacy hostname retained as a SAN during migration
- Deployment style: Node.js build output managed by systemd

## Production configuration shape

```text
PORT=8444
HOST=0.0.0.0
TLS_CERT_FILE=/etc/letsencrypt/live/<domain>/fullchain.pem
TLS_KEY_FILE=/etc/letsencrypt/live/<domain>/privkey.pem
APP_TOKEN_FILE=/etc/hermes-remote/secrets/app-token
CONNECTOR_TOKEN_FILE=/etc/hermes-remote/secrets/connector-token
# Optional; enables authenticated GET /internal/version and
# GET /internal/account-email-metrics and /internal/account-retention for loopback operations only.
INTERNAL_STATUS_TOKEN_FILE=/etc/hermes-remote/secrets/internal-status-token
DEFAULT_DEVICE_ID=mac-mini
LIFECYCLE_EVENT_STORE_FILE=/var/lib/hermes-remote/lifecycle-events.json
# Optional; structured JSON log verbosity: off | error | info (default) | debug.
GATEWAY_LOG_LEVEL=info
```

The Connector accepts the same optional knob as `CONNECTOR_LOG_LEVEL` (default `info`).

Account authentication is an independent, default-off control plane. I1 introduces the following
configuration, but production must keep `ACCOUNT_AUTH_ENABLED=0` until the account database
migration, Google OAuth clients, rollback rehearsal, and release gate are complete:

```text
ACCOUNT_AUTH_ENABLED=0
ACCOUNT_BINDING_ENABLED=0
ACCOUNT_MULTI_DEVICE_ENABLED=0
ACCOUNT_DEVICE_SHARING_ENABLED=0
ACCOUNT_EMAIL_OTP_ENABLED=0
ACCOUNT_RESEND_WEBHOOK_ENABLED=0
ACCOUNT_GOOGLE_AUTH_ENABLED=0
ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=0
ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED=0
ACCOUNT_WEB_SESSION_ENABLED=0
ACCOUNT_DATABASE_URL_FILE=/etc/hermes-remote/secrets/account-database-url
ACCOUNT_TOKEN_HASH_KEY_FILE=/etc/hermes-remote/secrets/account-token-hash-key
ACCOUNT_EMAIL_OTP_HASH_KEY_FILE=/etc/hermes-remote/secrets/account-email-otp-hash-key
ACCOUNT_EMAIL_OTP_ISSUER=https://<gateway-domain>
ACCOUNT_RESEND_API_KEY_FILE=/etc/hermes-remote/secrets/resend-api-key
ACCOUNT_RESEND_WEBHOOK_SECRET_FILE=/etc/hermes-remote/secrets/resend-webhook-secret
ACCOUNT_EMAIL_FROM=Hermes GO <login@auth.<mail-domain>>
ACCOUNT_SHARING_ACCOUNT_CENTER_ORIGIN=https://<gateway-domain>
ACCOUNT_WEB_ORIGIN=https://<gateway-domain>
# Deferred; configure only with ACCOUNT_GOOGLE_AUTH_ENABLED=1 in a later provider rollout.
# ACCOUNT_GOOGLE_ANDROID_CLIENT_ID_FILE=/etc/hermes-remote/secrets/google-android-client-id
# ACCOUNT_GOOGLE_MACOS_CLIENT_ID_FILE=/etc/hermes-remote/secrets/google-macos-client-id
# ACCOUNT_GOOGLE_WEB_CLIENT_ID_FILE=/etc/hermes-remote/secrets/google-web-client-id
ACCOUNT_DATABASE_SSL=1
ACCOUNT_DATABASE_POOL_SIZE=10
ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS=3000
ACCOUNT_TRUST_LOOPBACK_PROXY=1
ACCOUNT_GATEWAY_ORIGIN=https://<gateway-domain>
ACCOUNT_MAX_PENDING_CONNECTOR_PROOFS=256
ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS=16
ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP=4
MAX_ACCOUNT_LIFECYCLE_EVENTS=10000
ACCOUNT_LIFECYCLE_RETENTION_DAYS=30
ACCOUNT_AUDIT_RETENTION_DAYS=180
```

SQL migrations are applied explicitly, in filename order, with
`npm run account:migrate -w @hermes-remote/gateway`; the migration runner reads the same
`ACCOUNT_DATABASE_URL` or `_FILE` setting as the Gateway. Direct operator use must also provide a
positive `ACCOUNT_DATABASE_MIGRATION_LOCK_ID`, the release's exact
`ACCOUNT_DATABASE_SCHEMA_VERSION`, and its comma-separated `ACCOUNT_DATABASE_SUPPORTED_MAJORS`.
The command first builds the versioned migration payload under `gateway/dist`; schema version `15`
is then recorded in `gateway_schema_state` for readiness checks. R4-F Cloud Ops supplies these values
from the verified target release contract and executes the migrator from that immutable image under a
PostgreSQL advisory lock. Gateway startup never mutates schema.
Google proofs and Hermes GO bearer tokens must not be placed
in these files or logs.

`ACCOUNT_EMAIL_OTP_ENABLED` is an independent, default-off E1 flag and is effective only when
`ACCOUNT_AUTH_ENABLED=1`. Enabling it additionally requires a separate OTP HMAC key, an exact HTTPS
issuer origin, a Resend API key restricted to sending access, a single-line sender on a verified
domain, and `ACCOUNT_RESEND_WEBHOOK_ENABLED=1` with the endpoint's Resend signing secret. The Gateway
sends plaintext-only bilingual OTP messages through Resend's HTTPS API with the challenge UUID as
the provider idempotency key and opaque correlation tags. The API key, webhook secret, and OTP HMAC
key must use protected `_FILE` inputs in hosted environments. No email OTP capability is advertised
while the flag is off.

Create one Resend webhook for `https://<gateway-domain>/v2/webhooks/resend` and subscribe to
`email.sent`, `email.delivery_delayed`, `email.delivered`, `email.complained`, `email.bounced`,
`email.failed`, and `email.suppressed`. Copy its `whsec_...` signing secret into the protected file;
never place it in Nginx config or source. Nginx must forward the request body and all three
`svix-id`, `svix-timestamp`, and `svix-signature` headers unchanged. Do not enable OTP or device
sharing until the callback returns 2xx in staging. The Gateway stores only opaque event/message
identifiers, type, timestamps, and applied state—not recipient, sender, subject, body, or provider
diagnostic text.

Mail DNS configuration is deliberately outside Gateway configuration. Fill a private copy of
`ops/email-domain.example.json` with the exact public values displayed by Resend. SPF verification
includes both the Return-Path TXT and MX records; DKIM must match the complete provider-generated
public value even when DNS returns it as multiple chunks. DMARC belongs at
`_dmarc.<sending-domain>` and starts at monitored `p=none` with aggregate reporting before a later
reviewed move to `quarantine` or `reject`. `npm run ops:email-domain -- --config <path>` reads public
DNS only and returns `HR-OPS-018` until all four records match. Passing this check does not replace
Resend dashboard verification or inbox-header acceptance.

Use `ops/email-staging.example.json` only as a template for the isolated provider acceptance gate.
The filled file and referenced internal-status token remain outside the repository. The read-only
`preflight` command verifies readiness, email-only capability advertisement, and access to the safe
aggregate snapshot. The explicitly confirmed `exercise` command is the only mode that submits a
message, and it is hard-limited to Resend's official delivered/bounced test addresses. See
`docs/DEPLOYMENT.md` for commands and isolation requirements.

`ACCOUNT_IDENTITY_MANAGEMENT_ENABLED` independently exposes authenticated identity listing,
provider-neutral recent authentication, and explicit Google/email linking. Every new identity must
provide fresh provider proof plus a single-use `account.identity.link` grant created by re-verifying
an identity already attached to the current account. `ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED` exposes
the scriptless `/account` shell only when identity management is also enabled.

`ACCOUNT_DELETION_ENABLED` is an independent default-off destructive-action gate. It requires
account authentication, identity management, and at least one enabled authentication provider. When
`0`, capabilities omit `accountDeletion`, the Web danger zone remains hidden, and both native/Web
account-deletion routes return the uniform not-found contract. Enabling it exposes permanent Cloud
deletion: recent `account.delete` verification immediately revokes all Cloud access and fixes a
30-day cleanup deadline. Invitations addressed to a verified account email are cancelled and cannot
be recreated while its keyed deletion fingerprint exists; the fingerprint and related email hints
are removed at final cleanup. Unused email codes are invalidated immediately, and a purpose-separated
fingerprint suppresses later provider submissions while preserving the public challenge/cooldown/rate
contract; its OTP correlation rows are removed at the deadline. A separate account-free pair of keyed
hashes remains only to make a long-offline retry deterministic. It never deletes local Hermes data.
Do not enable it until the product copy, privacy policy, support process, disposable-database deletion
test, and packaged Desktop confirmation flow have all been reviewed.

`ACCOUNT_WEB_SESSION_ENABLED` independently gates schema-12 browser sessions and requires the Web
shell, email OTP, and an exact HTTPS `ACCOUNT_WEB_ORIGIN`. It does not require a Google client ID in
the email-first release. Web access
and refresh values use `__Host-` Secure/HttpOnly/SameSite=Strict cookies; every mutation additionally
requires exact Origin, same-origin Fetch Metadata when present, and a matching CSRF cookie/header.
Account mode also opens one separately pooled PostgreSQL connection for transaction-scoped access
revocation notifications. It does not consume `ACCOUNT_DATABASE_POOL_SIZE`; if the listener reconnects
or misses a notification, per-request authorization and the five-second WebSocket recheck remain the
fail-closed source of truth.
`ACCOUNT_GOOGLE_AUTH_ENABLED` is a separate default-off future-provider flag. While it is `0`, Google
is omitted from capabilities, all native/Web Google exchange/link/reauthentication routes are absent,
the Web UI hides Google controls, and its CSP permits no Google origins. Android, macOS, and Web
Google client IDs are not required. If a later rollout sets it to `1`, the Android and macOS client
IDs become mandatory; an enabled Web session additionally requires the distinct Web client ID.
Authorized JavaScript origins and OAuth branding remain Google Console configuration. The email-
first release must keep this flag at `0`.

R2 adds `GET /healthz` for liveness and `GET /readyz` for traffic admission. When account mode is
enabled, readiness requires a reachable certified PostgreSQL 18 database at exact schema version 15.
`GET /internal/version`, `GET /internal/account-email-metrics`, and
`GET /internal/account-retention` are disabled unless
`INTERNAL_STATUS_TOKEN` or `_FILE` is configured and must remain on the private operations path; they
must not be added to the public Nginx routing table. The email snapshot contains one-hour aggregate
counts only. `providerAccepted` means the transactional provider accepted the API request;
`finalDelivered`, `finalHardFailed`, and `finalDelayed` are independently derived from verified
webhook events. No mailbox, challenge/invitation ID, code, provider message ID, or account identifier
is returned.

Account mode also starts a bounded retention scheduler after 60 seconds and repeats it every six
hours. Each sweep removes no more than 1,000 rows per eligible table, uses `SKIP LOCKED` for
multi-Gateway safety, and does not gate readiness or login if maintenance fails. Alert when the
retention snapshot's `lastFailureAt` is newer than `lastSuccessAt` (`HR-OPS-019`). Expired refresh
hashes and unreferenced session tombstones use the same 35-day post-expiry window. Routine
installation and device-access history are not part of this first retention class. A due permanent
account deletion is the explicit exception: it removes every relationship for that account within
the same per-table batch ceiling, including suppressed email-OTP correlation rows. Deletion
immediately invalidates unused codes and suppresses future provider submission behind the same
neutral challenge/cooldown/rate contract, then retains only a non-PII tombstone and deletion audit.
Sanitized lifecycle events use
`ACCOUNT_LIFECYCLE_RETENTION_DAYS` (default 30) in addition to the 10,000-row/account ceiling;
allowlisted audit events use `ACCOUNT_AUDIT_RETENTION_DAYS` (default 180). Each accepts 1–3,650 days.

`ACCOUNT_TRUST_LOOPBACK_PROXY=1` accepts the first `X-Forwarded-For` address only when the immediate
TCP peer is loopback, matching a same-host Nginx upstream. Keep it `0` when Gateway is directly
reachable or the proxy boundary differs; never trust a caller-supplied forwarding header directly.

`ACCOUNT_BINDING_ENABLED` independently gates the I2 installation/binding HTTP surface and its
capability advertisement, including replacement, unbind, and current-phone revocation. It is
effective only when `ACCOUNT_AUTH_ENABLED=1` and remains `0` until
the binding, account-aware routing, Connector V2 handshake, and rollback gates pass. It never
changes or disables the legacy `/v1/connect` or App/Connector Token paths.

`ACCOUNT_MULTI_DEVICE_ENABLED` is the independent, default-off E3 switch and requires
`ACCOUNT_BINDING_ENABLED=1`. When off, the transactional ownership limit and advertised capability
remain one active Connector. When on, one account may reserve/activate at most three owned Macs;
plural device discovery and explicit device selection are advertised. The limit is enforced under
the existing per-account transaction lock, so simultaneous fourth-device attempts cannot overbook.

`ACCOUNT_DEVICE_SHARING_ENABLED` is the independent, default-off E5 switch and requires account
authentication, binding, multi-device selection, and identity management. Enabling it also requires
the existing transactional email settings plus an exact HTTPS
`ACCOUNT_SHARING_ACCOUNT_CENTER_ORIGIN` (origin only, without a path). It advertises whole-device
sharing, caps each owned Mac at five active grantees and each account at ten shared Macs, and sends
72-hour invitation links through the configured provider. Production remains `0` until the Web
cookie-session/CSRF gate or the approved Desktop acceptance flow, provider-domain verification,
multi-instance revocation decision, and manual two-account end-to-end test all pass.

When binding is enabled, `ACCOUNT_GATEWAY_ORIGIN` is required and must exactly match the public
`http://` or `https://` origin (no path, query, credentials, or fragment). It is signed into the V2
Connector challenge to prevent a proof from being replayed at another Gateway. Pending proofs and
unauthenticated `/v2/connect` sockets are independently bounded globally and per source IP; these
limits do not reduce the legacy protocol's configured compatibility semantics.

`MAX_ACCOUNT_LIFECYCLE_EVENTS` bounds retained sanitized lifecycle transitions per account, while
`ACCOUNT_LIFECYCLE_RETENTION_DAYS` supplies the time ceiling; whichever is reached first removes the
event and cascades its per-phone receipt. Account events and receipts live in PostgreSQL; the legacy
Token mode continues to use `LIFECYCLE_EVENT_STORE_FILE` and `MAX_LIFECYCLE_EVENTS` unchanged.

The I3-A Desktop alpha reads two public client settings from its packaged `Info.plist`, with process
environment overrides for local development:

```text
HERMES_GO_ACCOUNT_GATEWAY_URL=https://<gateway-domain>
HERMES_GO_GOOGLE_MACOS_CLIENT_ID=<google-desktop-oauth-client-id>
```

`desktop/scripts/build-app.sh` embeds supplied values into the built app. The OAuth client ID is a
public identifier, never a client secret. Default packages leave it empty, so Google sign-in remains
unavailable even if a user opens the unfinished account screen. The app also obeys the Gateway's
independent capability flags and never infers enablement from the presence of a client ID.

The deployment copies the certificate into `/etc/hermes-remote/tls` with narrowly scoped permissions and refreshes it from a Certbot deploy hook. Because DERP owns port 80, the `mrlgs.net` renewal configuration stops DERP before the standalone HTTP-01 challenge and starts it again afterward. The actual environment file belongs at `/etc/hermes-remote/gateway.env`; tokens are separate files readable only by the service group. None of them may be committed.

## Security items outside this repository

- A temporary Python file server on the Mac is listening on all interfaces; restrict it to loopback or stop it after confirming it is no longer needed.
- Restrict or disable the public remote-desktop port on the HK host.
- Add host firewall rules carefully, allowing SSH before enabling the firewall to avoid lockout.

The Mac Connector reads only the two Basic Auth values it needs from the existing local Hermes dotenv file. The values are never copied into launchd, the HK server, or the Android app.

Attachment deployments additionally configure `FILES_ROOT` to the narrowest Mac directory that may
be returned to the phone. `UPLOAD_ROOT` must remain inside it; its defaults are
`$HOME/.hermes-remote/uploads`, 6 MiB per upload, 100 MiB per download, 200 cached uploads, 512 MiB
cached total, and seven-day retention. These are operational limits, not secrets.

The Connector lifecycle observer is enabled by default in live Hermes mode. Its safe local state is
stored at `$HOME/.hermes-remote/observer-state.json`; override it with `OBSERVER_STATE_FILE` when the
service account needs another writable directory. `OBSERVER_ACTIVE_POLL_MS` defaults to `2000`,
`OBSERVER_IDLE_POLL_MS` to `20000`, and `OBSERVER_RPC_TIMEOUT_MS` to `10000`. Set
`SESSION_OBSERVER_ENABLED=0` to disable only this optional observer. No Hermes source patch is needed.

The Relay lifecycle inbox defaults to `/var/lib/hermes-remote/lifecycle-events.json` in production
and retains at most 10,000 transitions (`MAX_LIFECYCLE_EVENTS`). Docker Compose mounts that directory
on the `hermes_gateway_data` volume so Relay restarts do not lose pending notifications.
