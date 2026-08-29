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
- An existing Certbot certificate can be referenced by the Gateway after granting the dedicated service account read access
- Deployment style: Node.js build output managed by systemd

## Production configuration shape

```text
PORT=8444
HOST=0.0.0.0
TLS_CERT_FILE=/etc/letsencrypt/live/<domain>/fullchain.pem
TLS_KEY_FILE=/etc/letsencrypt/live/<domain>/privkey.pem
APP_TOKEN=<generated secret>
CONNECTOR_TOKEN=<different generated secret>
```

The actual environment file belongs at `/etc/hermes-remote/gateway.env`, mode `0600`, and must never be committed.

## Security items outside this repository

- A temporary Python file server on the Mac is listening on all interfaces; restrict it to loopback or stop it after confirming it is no longer needed.
- Restrict or disable the public remote-desktop port on the HK host.
- Add host firewall rules carefully, allowing SSH before enabling the firewall to avoid lockout.

No remote-system changes are performed by this repository documentation.
