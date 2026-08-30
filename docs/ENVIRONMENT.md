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
- No Docker, Caddy, or Nginx currently installed
- Ports `80`, `443`, and `8443` are occupied by existing services
- Selected Hermes Remote port: `8444/TCP`
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
```

The deployment copies the certificate into `/etc/hermes-remote/tls` with narrowly scoped permissions and refreshes it from a Certbot deploy hook. Because DERP owns port 80, the `mrlgs.net` renewal configuration stops DERP before the standalone HTTP-01 challenge and starts it again afterward. The actual environment file belongs at `/etc/hermes-remote/gateway.env`; tokens are separate files readable only by the service group. None of them may be committed.

## Security items outside this repository

- A temporary Python file server on the Mac is listening on all interfaces; restrict it to loopback or stop it after confirming it is no longer needed.
- Restrict or disable the public remote-desktop port on the HK host.
- Add host firewall rules carefully, allowing SSH before enabling the firewall to avoid lockout.

The Mac Connector reads only the two Basic Auth values it needs from the existing local Hermes dotenv file. The values are never copied into launchd, the HK server, or the Android app.
