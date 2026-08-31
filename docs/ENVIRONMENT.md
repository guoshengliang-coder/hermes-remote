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
DEFAULT_DEVICE_ID=mac-mini
LIFECYCLE_EVENT_STORE_FILE=/var/lib/hermes-remote/lifecycle-events.json
```

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
