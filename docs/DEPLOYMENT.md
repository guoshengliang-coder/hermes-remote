# MVP deployment

The first production-shaped relay was installed and verified on 2026-08-29.

## Installed services

- HK Gateway: `/opt/hermes-remote`, managed by `hermes-remote-gateway.service`
- Gateway configuration: `/etc/hermes-remote`, with separate service-readable token files
- Public endpoint: `https://<gateway-domain>:8444`
- Mac Connector: `~/Library/Application Support/Hermes Remote`
- Connector service: `~/Library/LaunchAgents/com.hermesremote.connector.plist`
- Hermes credentials remain only in the existing `~/.hermes/.env`

The deployment did not alter Xray, DERP, Hermes configuration, UFW, or the host firewall.

## Verification completed

1. A wrong app token returned HTTP 401.
2. Authenticated `/api/status` traversed Gateway → Connector → Hermes and returned Hermes `0.20.6` with overall status `ok`.
3. `/api/ws` returned `gateway.ready`.
4. JSON-RPC `session.create` completed with a real Hermes session ID.
5. Both systemd and launchd were confirmed running after installation.

## Operations

Gateway health:

```bash
curl https://<gateway-domain>:8444/health
```

Gateway status and logs:

```bash
sudo systemctl status hermes-remote-gateway
sudo journalctl -u hermes-remote-gateway -n 100 --no-pager
```

Connector status and logs on the Mac:

```bash
launchctl print gui/$(id -u)/com.hermesremote.connector
tail -n 100 "$HOME/Library/Application Support/Hermes Remote/connector.log"
tail -n 100 "$HOME/Library/Application Support/Hermes Remote/connector.error.log"
```

The Android app needs only the public Gateway URL and the app token. It must never receive the Connector token or local Hermes password.
