# MVP deployment

The first production-shaped relay was installed and verified on 2026-08-29. The production
hostname was migrated to `mrlgs.net` on 2026-08-30.

## Installed services

- HK Gateway: `/opt/hermes-remote`, managed by `hermes-remote-gateway.service`
- Gateway configuration: `/etc/hermes-remote`, with separate service-readable token files
- Public endpoint: `https://mrlgs.net` (HTTPS/WSS 443)
- Edge router: Nginx on 443; Gateway upstream `127.0.0.1:8444`, release upstream `127.0.0.1:9443`
- Mac Connector: `~/Library/Application Support/Hermes Remote`
- Connector service: `~/Library/LaunchAgents/com.hermesremote.connector.plist`
- Connector control-channel heartbeat: 15 seconds; a missed pong forces an automatic reconnect after sleep or network changes.
- Hermes credentials remain only in the existing `~/.hermes/.env`

The deployment did not alter Xray, DERP, Hermes configuration, UFW, or the host firewall.

The Gateway certificate contains both `mrlgs.net` and the previous sslip.io hostname so existing
Android installations remain connected during migration. Certbot renewal uses standalone HTTP-01;
its certificate-specific hooks briefly stop and restart DERP around the challenge, and the deploy
hook copies the renewed certificate into `/etc/hermes-remote/tls` before restarting the Gateway.

## Verification completed

1. A wrong app token returned HTTP 401.
2. Authenticated `/api/status` traversed Gateway → Connector → Hermes and returned Hermes `0.20.6` with overall status `ok`.
3. `/api/ws` returned `gateway.ready`.
4. JSON-RPC `session.create` completed with a real Hermes session ID.
5. Both systemd and launchd were confirmed running after installation.

## Operations

Gateway health (the JSON includes `connectors` — the attached-connector count — and `devices`,
a `[{deviceId, online}]` list added for the app's remote-device tile):

```bash
curl https://mrlgs.net/relay-health
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

The separately managed HTTPS Android release repository is documented in `APP_UPDATE.md`. Installing
or restarting it is an explicit deployment operation and must not be inferred from an app/source change.
Its environment file must be installed from `deploy/hermes-release-server.environment.template` at
`/etc/hermes-release-server/environment` with mode `0600`; the service account must have read access to
the configured certificate and key (for example through a narrowly scoped certificate group or ACL).
Install `deploy/hermes-release-server.service.template` as `hermes-release-server.service`, run
`systemctl daemon-reload`, and enable/start it only during an explicitly authorized deployment.
Replace the old combined Hermes hook with `deploy/certbot-hermes-services-hook.sh.template` under
Certbot's `renewal-hooks/deploy/` directory mode `0755`; retain the derper hook. It copies the renewed
`mrlgs.net` certificate to both dedicated service TLS directories before checking both restarts. The
services do not read `/etc/letsencrypt/live` directly.

For the first deployment, the existing `apk-server.service` on 443 is the explicitly authorized
replacement target and Xray is already stopped. With deployment authorization, run
`CONFIRM_PRODUCTION_DEPLOY=mrlgs.net scripts/deploy-release-server.sh`; it stops the old service,
starts and verifies the new one, and restarts the old service on failure. Capabilities do not solve a
port collision.

For the unified standard-port deployment, run `scripts/deploy-edge-router.sh` as root with
`CONFIRM_PRODUCTION_DEPLOY=mrlgs.net`. The script installs Nginx, preserves the current release
configuration for rollback, keeps `/health` and `/releases/*` on the release service, and routes
`/api/*` plus `/v1/connect` to the Gateway without redirects.

## R3 staging-only Cloud Ops

The R3 internal tool installs a verified Gateway OCI bundle only on a new controlled staging host.
It is not the production deployment path and rejects `production` configurations. Prepare private
Token and TLS input files outside the managed roots, copy the versioned archive and manifest produced
by `scripts/package-gateway-bundle.sh`, then use:

```bash
node scripts/hermesctl.mjs preflight --config /secure-input/hermes-go/staging.json
sudo node scripts/hermesctl.mjs bootstrap --config /secure-input/hermes-go/staging.json --confirm staging
node scripts/hermesctl.mjs status --config /secure-input/hermes-go/staging.json
node scripts/hermesctl.mjs doctor --config /secure-input/hermes-go/staging.json --output /secure-output/hermes-go-doctor.json
```

Run `preflight` before granting deployment approval. `bootstrap` installs the exact content-addressed
image, managed configuration, systemd unit and Nginx staging server, then performs loopback and public
route smoke checks. Re-running the identical input is idempotent; a different current release or
deployment digest stops with a structured `HR-OPS-*` error and must use the later R4 deploy/rollback
path. The doctor output deliberately excludes journals, request bodies, environment files, Secret
contents and source paths. See `CLOUD_GATEWAY_R3_OPS.md` for the full contract.

When no separate staging server exists, manually run the `Gateway Ephemeral Staging` GitHub Actions
workflow instead of reusing the HK production host. The workflow uses a disposable Ubuntu x86_64 VM,
one-time generated test material, a private local CA, and a 15-minute timeout. It receives no repository
secrets and has no production hostname or SSH path. Passing this workflow proves the R3 bootstrap path
on an isolated host; it does not authorize or perform a production deployment.
