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

## Production-promotion audit (read-only)

R5 adds a separate `production-audit` command. It does not relax the staging-only R3/R4 configuration,
does not expose a production `deploy` or `rollback` command, and does not install packages, write managed
files, stop/restart services, or alter routing. Prepare a private config from
`ops/production.audit.example.json`; never commit the filled config or evidence files. The exact confirmation
binds the read-only probe to the configured public hostname:

```bash
node scripts/hermesctl.mjs production-audit \
  --config /secure-input/hermes-go/production-audit.json \
  --confirm production:<configured-public-hostname>
```

Every successful `Gateway OCI` push run on `main` retains its exact artifact as
`gateway-bundle-<full-main-commit>` for seven days. It contains the Gateway archive/manifest and the separately
hashed R5-D operator archive/manifest. Pull-request runs still build and verify both bundles but do not retain
them. Download the artifact from the matching successful `main` run, preserve every archive with its manifest,
and use the Gateway manifest as `targetArtifactManifest`; never substitute a hand-written manifest or combine
bundles from different commits. Verify the operator archive with
`scripts/verify-production-baseline-bundle.mjs` before and after transfer.

The command aggregates every gate instead of stopping at the first missing prerequisite. It checks the exact
Linux/amd64 host identity, minimum free disk and available memory, immutable target bundle, exact hashes of the
currently running legacy Gateway, Nginx/public health, loopback-only legacy and PostgreSQL listeners, Docker,
PostgreSQL 18 client/service availability, and fresh separate-host recovery evidence. A blocked result returns
`HR-OPS-010`; this is an expected no-go report and leaves the live service unchanged.

The two evidence files use strict schema version 1. `hermes-go-legacy-recovery-v1` must record verified checks
`archive_hash`, `files_restored`, and `service_start`. `hermes-go-postgresql-restore-v1` must record
`encrypted_backup_hash`, `database_restore`, `schema_exact`, and `account_smoke`. Both record source and restore
hostnames, UTC creation/restore times, and artifact SHA-256; legacy evidence is bound to the configured runtime
identity digest, while database evidence is bound to the exact schema and PostgreSQL major version. The hosts
must differ and restore verification must be no older than 30 days. R5-A defines and consumes this evidence but does not yet create it: only the isolated
R5-B capture/restore tooling and its produced output may satisfy the production gate. A hand-written manifest is
not deployment evidence.

See `CLOUD_GATEWAY_R5_PLAN.md`. Running the audit on the HK host still requires explicit read-only production
authorization. Resolving any blocker is a separate mutating operation and needs another approval.

## Legacy Gateway recovery baseline (R5-B; production authorization required)

The `legacy-capture` and `legacy-restore` commands create the evidence consumed by the R5-A
`legacy_recovery` gate. Capture streams an allowlisted file inventory directly through authenticated CMS
AES-256-GCM encryption, verifies that the old service stayed active and the source files did not change, and
never writes a plaintext archive on the source host. Restore is required to run on a differently named host;
it verifies and extracts into a disposable private root, binds the recovered process to loopback, runs the
legacy health and Token-routing contract, stops it, removes plaintext, and only then writes evidence.

Use the strict examples `ops/legacy.capture.example.json` and `ops/legacy.restore.example.json`. Filled configs,
the private key, encrypted archive, private archive manifest, restored plaintext, and generated evidence are
operational secrets and must not be committed. Source capture is a production action because it reads live
configuration and state and creates a new encrypted output. It requires a separate explicit approval even
after CI passes; do not infer that approval from a merge or from an R5-A audit. The full key-handling,
invocation, rollback, and verification procedure is in `CLOUD_GATEWAY_R5_RECOVERY.md`.

## PostgreSQL production gate (R5-E code ready; production not executed)

The existing HK host has enough nominal CPU and memory for the initial low-volume Gateway database,
so a second server is not a prerequisite. PostgreSQL must remain a separate system service, listen only
on loopback, and never expose port 5432 through Nginx or the host firewall. Gateway and migration access
use one dedicated least-privilege database role whose URL is stored in a root-owned `0600` Secret file;
the URL must not appear in shell history, unit files, Git, logs, diagnostics, or chat.

Before installing or changing anything, an explicitly authorized read-only preflight must record free
disk, memory pressure, existing PostgreSQL/packages/listeners, filesystem ownership, current Gateway
health, and the exact current release identity. Installation, database creation, migration, service
restart, and account-feature enablement each require production authorization and a recorded rollback
point. R4-F prepares schema while both account flags remain `0`; it does not authorize Google login or
make PostgreSQL authoritative for existing Token clients.

A same-host database is also a same-host failure domain. Before production migration, create an
encrypted logical backup, copy it off the HK host, restore it into a separate disposable database, and
run schema plus account smoke checks against the restored copy. Daily backup retention, failure alerts,
periodic restore rehearsal, disk thresholds, and credential rotation must be in place before account
mode is enabled. Keeping the only backup on the HK disk does not satisfy this gate.

The R5-E implementation and three separately authorized phases are documented in
`CLOUD_GATEWAY_R5_DATABASE_RECOVERY.md`. Its dedicated entrypoint performs encrypted capture,
off-host restore verification with the immutable Gateway image, and evidence-bound atomic status
activation. No production database, migration, capture, transfer, restore, status activation, or
timer enablement is implied by the source implementation.

## Production disk and backup monitoring (R5-C4; not deployed)

R5-C4 adds a separate `production-monitor` command. It reads only the root filesystem capacity and one
strict PostgreSQL encrypted-backup status file; it does not run `pg_dump`, connect to PostgreSQL, remove
files, restart a service, or change routing. Create the private config from
`ops/production.monitor.example.json` and bind the invocation to the configured production hostname:

```bash
node scripts/production-monitor.mjs \
  --config /etc/hermes-remote/production-monitor.json \
  --confirm production:<configured-hostname>
```

The command exits nonzero with `HR-OPS-012` when free disk is below either configured threshold or the
off-host backup status is missing, invalid, stale, in the future, undersized, or bound to the wrong host,
PostgreSQL major, or database schema. Warning-level disk results also exit nonzero so the timer cannot
silently ignore them. Output excludes the status path, artifact hash, and off-host storage identifier.

The status file follows `ops/postgresql-backup-status.schema.json`. It may be atomically replaced only by
the later R5-E backup job after encryption, off-host copy, full remote byte/hash verification, and final
metadata binding all succeed. It is an operational freshness signal, not a substitute for the separate-host
restore evidence required by `production-audit`.

The three `deploy/hermes-go-production-monitor*.template` units schedule the read-only check every 15
minutes and convert a failed run into a local `daemon.alert` journal event. They deliberately contain no
network notification credential or auto-remediation. Installation and timer enablement require explicit
production authorization; this source change does not deploy them. See `CLOUD_GATEWAY_R5_MONITORING.md`
for the dependency-free code snapshot, status-writer contract, validation sequence, and external-notification
boundary. The dedicated entrypoint must be used by systemd; `scripts/hermesctl.mjs` remains available for
interactive compatibility but loads unrelated deployment modules and is not the production timer entrypoint.

## Production managed baseline (R5-D; production not executed)

R5-D keeps the ordinary `hermesctl deploy/rollback` commands staging-only. Its dedicated
`scripts/production-baseline.mjs` entrypoint accepts only a strict production configuration, an exact
`production:<hostname>` confirmation, the immutable target bundle, unchanged legacy identity files, and fresh
R5-B off-host recovery evidence. It requires full candidate, public, and legacy compatibility smoke callbacks
and fixes database/account features off for this transition.

Use only an R5-D operator manifest with schema version 2 for a new production adoption. The entrypoint starts
its own one-time Hermes smoke service on an operating-system-assigned `127.0.0.1` port, passes only an allowlisted
environment with generated credentials to the bundled Connector, and removes its listener, child processes,
and private temporary directory on success or failure. Never copy Mac Hermes credentials to the HK host and
never replace the bundled smoke runtime with an ad-hoc script. Schema version 1 remains readable solely to audit
artifacts produced before this boundary was closed.

Do not generate the production Nginx file from the staging template. Start from the actual production site
file, preserve every unrelated route, replace only the Gateway upstream, review the complete diff, and pin its
SHA-256 in the private configuration. The switch installs that exact file and restores the original bytes if
validation, reload, state handoff, observation, or smoke fails. See `CLOUD_GATEWAY_R5_MANAGED_BASELINE.md` for
the config, topology, disposable test, maintenance-window checklist, and recovery boundary. Source review or a
successful disposable workflow does not authorize running this command on the HK host.
