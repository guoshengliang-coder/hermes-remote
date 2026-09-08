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

The edge and staging templates expose exactly `/v2/webhooks/resend` for the provider-authenticated
transactional-email callback, with a 64 KiB edge body limit and short upstream timeouts. They do not
make the remaining account API public. Keep `ACCOUNT_RESEND_WEBHOOK_ENABLED=0` until the schema-15
migration and protected `ACCOUNT_RESEND_WEBHOOK_SECRET_FILE` are present; the disabled Gateway route
returns the ordinary account not-found response. Enabling this route or any account flag in a live
environment remains a separately authorized deployment operation.

### Email-provider staging acceptance

Before deploying the mail-enabled candidate, copy `ops/email-domain.example.json` to a protected
operator directory. Copy the exact SPF TXT, Return-Path MX, and DKIM TXT values shown by Resend;
do not assume the example's AWS region or default Return-Path. Configure DMARC at
`_dmarc.<sending-domain>` with `p=none` plus an aggregate-report mailbox for the initial monitored
rollout. The following gate performs public DNS reads only. It has no credential field, makes no
Resend API call, and never changes DNS:

```bash
npm run ops:email-domain -- \
  --config /secure-input/hermes-go/email-domain.json
```

All four checks—`spf_txt`, `spf_mx`, `dkim_txt`, and `dmarc_txt`—must pass. The checker joins
standards-compliant split TXT chunks, rejects multiple SPF records or multiple Return-Path MX
targets, enforces the configured minimum DMARC policy, and does not echo record contents or the
DMARC reporting mailbox. An incomplete audit returns `HR-OPS-012`. A pass proves the reviewed
records are publicly visible; additionally wait for Resend's dashboard to show the domain verified
and use message headers during physical acceptance to confirm `spf=pass`, `dkim=pass`, and
`dmarc=pass`.

After deploying schema 15 and enabling email OTP plus the signed webhook on an isolated staging
Gateway, copy `ops/email-staging.example.json` to a protected operator directory and point both URLs
at the Gateway's loopback listener when running on the staging host. The internal-status URL is
intentionally restricted to loopback, and its token source must be a regular `0600` file. Start with
the read-only gate; it sends no email:

```bash
npm run ops:email-staging -- preflight \
  --config /secure-input/hermes-go/email-staging.json
```

The mutation gate accepts only Resend's official `delivered@resend.dev` and `bounced@resend.dev`
test classes, generates no account, and never prints a mailbox, challenge ID, or status token. It
requires an exact confirmation bound to `accountApiUrl`'s hostname. Run one case at a time in an
otherwise idle staging environment so the one-hour aggregate delta belongs only to this probe:

```bash
npm run ops:email-staging -- exercise \
  --config /secure-input/hermes-go/email-staging.json \
  --case delivered \
  --confirm staging:127.0.0.1

npm run ops:email-staging -- exercise \
  --config /secure-input/hermes-go/email-staging.json \
  --case bounced \
  --confirm staging:127.0.0.1
```

The delivered case requires exactly one new request, provider acceptance, and verified final
delivery. The bounced case requires exactly one new request, provider acceptance, and verified hard
failure. The latter also exercises unused-OTP invalidation. Any timeout, concurrent metric delta, or
configuration/provider/Webhook failure returns `HR-OPS-011`. These commands do not deploy, reload,
restart, or modify production.

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

Every successful `Gateway OCI` push run on `main` retains its exact bundle as
`gateway-bundle-<full-main-commit>` for seven days. Pull-request runs still build and verify the bundle but do
not retain it. Download the artifact from the matching successful `main` run, preserve both the archive and
manifest together, and use that manifest as `targetArtifactManifest`; never substitute a hand-written
manifest or a bundle from another commit.

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

This generic PostgreSQL restore evidence is deliberately **not** sufficient to enable permanent
account deletion. It does not prove that a deletion committed after the backup timestamp is replayed
before restored data becomes reachable. Keep `ACCOUNT_DELETION_ENABLED=0` after any restore and in
every staging/production promotion until the separately approved recovery design and the older-backup
drill in `ACCOUNT_DELETION_REVIEW.md` both exist. A normal `account_smoke` pass must never be used as a
substitute for that deletion-obligation evidence.

See `CLOUD_GATEWAY_R5_PLAN.md`. Running the audit on the HK host still requires explicit read-only production
authorization. Resolving any blocker is a separate mutating operation and needs another approval.

## PostgreSQL production gate (not yet executed)

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

After account mode starts, query `GET /internal/account-retention` only through the protected
loopback operations path. The first attempt may remain null for 60 seconds. Thereafter, alert on
`lastFailureAt` newer than `lastSuccessAt` (`HR-OPS-013`) or on a missing success across more than two
six-hour intervals. Deleted totals are process-local and may reset after a restart; they are evidence
of activity, not durable accounting. Do not publish this endpoint through Nginx or treat a cleanup
failure as authorization to restart/deploy—the scheduler preserves login availability and retries.
