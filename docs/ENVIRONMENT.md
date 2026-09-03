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
# Optional; enables authenticated GET /internal/version for local operations only.
INTERNAL_STATUS_TOKEN_FILE=/etc/hermes-remote/secrets/internal-status-token
DEFAULT_DEVICE_ID=mac-mini
LIFECYCLE_EVENT_STORE_FILE=/var/lib/hermes-remote/lifecycle-events.json
```

Account authentication is an independent, default-off control plane. I1 introduces the following
configuration, but production must keep `ACCOUNT_AUTH_ENABLED=0` until the account database
migration, Google OAuth clients, rollback rehearsal, and release gate are complete:

```text
ACCOUNT_AUTH_ENABLED=0
ACCOUNT_BINDING_ENABLED=0
ACCOUNT_DATABASE_URL_FILE=/etc/hermes-remote/secrets/account-database-url
ACCOUNT_TOKEN_HASH_KEY_FILE=/etc/hermes-remote/secrets/account-token-hash-key
ACCOUNT_GOOGLE_ANDROID_CLIENT_ID_FILE=/etc/hermes-remote/secrets/google-android-client-id
ACCOUNT_GOOGLE_MACOS_CLIENT_ID_FILE=/etc/hermes-remote/secrets/google-macos-client-id
ACCOUNT_DATABASE_SSL=1
ACCOUNT_DATABASE_POOL_SIZE=10
ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS=3000
ACCOUNT_TRUST_LOOPBACK_PROXY=1
ACCOUNT_GATEWAY_ORIGIN=https://<gateway-domain>
ACCOUNT_MAX_PENDING_CONNECTOR_PROOFS=256
ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS=16
ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP=4
MAX_ACCOUNT_LIFECYCLE_EVENTS=10000
```

SQL migrations are applied explicitly, in filename order, with
`npm run account:migrate -w @hermes-remote/gateway`; the migration runner reads the same
`ACCOUNT_DATABASE_URL` or `_FILE` setting as the Gateway.
The command first builds the versioned migration payload under `gateway/dist`; schema version `7`
is then recorded in `gateway_schema_state` for readiness checks. Gateway startup never mutates schema.
Google proofs and Hermes GO bearer tokens must not be placed
in these files or logs.

R2 adds `GET /healthz` for liveness and `GET /readyz` for traffic admission. When account mode is
enabled, readiness requires a reachable certified PostgreSQL 18 database at exact schema version 7.
`GET /internal/version` is disabled unless `INTERNAL_STATUS_TOKEN` or `_FILE` is configured and must
remain on the private operations path; it must not be added to the public Nginx routing table.

`ACCOUNT_TRUST_LOOPBACK_PROXY=1` accepts the first `X-Forwarded-For` address only when the immediate
TCP peer is loopback, matching a same-host Nginx upstream. Keep it `0` when Gateway is directly
reachable or the proxy boundary differs; never trust a caller-supplied forwarding header directly.

`ACCOUNT_BINDING_ENABLED` independently gates the I2 installation/binding HTTP surface and its
capability advertisement, including replacement, unbind, and current-phone revocation. It is
effective only when `ACCOUNT_AUTH_ENABLED=1` and remains `0` until
the binding, account-aware routing, Connector V2 handshake, and rollback gates pass. It never
changes or disables the legacy `/v1/connect` or App/Connector Token paths.

When binding is enabled, `ACCOUNT_GATEWAY_ORIGIN` is required and must exactly match the public
`http://` or `https://` origin (no path, query, credentials, or fragment). It is signed into the V2
Connector challenge to prevent a proof from being replayed at another Gateway. Pending proofs and
unauthenticated `/v2/connect` sockets are independently bounded globally and per source IP; these
limits do not reduce the legacy protocol's configured compatibility semantics.

`MAX_ACCOUNT_LIFECYCLE_EVENTS` bounds retained sanitized lifecycle transitions per account. Account
events and per-phone delivery/read receipts live in PostgreSQL; the legacy Token mode continues to
use `LIFECYCLE_EVENT_STORE_FILE` and `MAX_LIFECYCLE_EVENTS` unchanged.

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
