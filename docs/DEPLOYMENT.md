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
- Connector control-channel heartbeat: bidirectional. The Connector's 15-second ping detects a
  broken network path; the Gateway's 5-second ping with a 15-second timeout also evicts a connected
  Connector whose event loop has stalled, allowing its normal reconnect loop to recover the route.
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

Gateway status and logs. Since the R5-D adoption the Gateway is a container run by the active
slot's systemd unit (the slot names are private configuration; `readlink /opt/hermes-go/current`
and the committed deployment journal say which slot is live). The container's stdout is the
unit's journal, so `journalctl -u <active-slot-unit>` is the log; the retired
`hermes-remote-gateway` unit is stopped and only holds pre-adoption history:

```bash
sudo systemctl status <active-slot-unit>
sudo journalctl -u <active-slot-unit> -n 100 --no-pager
```

From Gateway 0.4.1 on, the Gateway writes one JSON object per line (`{"ts","level","kind",...}`);
`GATEWAY_LOG_LEVEL` selects `off` / `error` / `info` (default) / `debug`, and the managed slot
environment does not set it. The lines an incident needs, all at `info`: `app.tunnel.open` /
`app.tunnel.close` (frame and byte counts both ways, whether the Connector was still online),
`app.tunnel.revalidation_failed` / `app.tunnel.revalidation_exhausted` (why an account tunnel's
5-second authorization recheck failed and how it was classified — since HG-140; transient
failures such as a Connector blink or a database hiccup are tolerated for about 15 seconds and
audited rather than presented to the phone as `4403 "account authorization changed"`),
`connector.online` / `connector.offline`, `lifecycle.received` (with `lagMs` behind the Mac's
stamp), `lifecycle.served` / `lifecycle.acked`, and `http.tunnel` (method, path, outcome, status,
decoded body `bytes`, streamed `chunks`, `ttfbMs` to the Connector's first response message, and
duration — the last four since HG-104). Credential-shaped fields are never written; relayed frames are counted, not quoted.
The 0.4.0 image adopted by R5-D predates these lines; they appear once 0.4.1 is released through
the R5-F1 path below.

```bash
# everything about one conversation, in order
sudo journalctl -u <active-slot-unit> --since "2026-09-05 10:20" -o cat | grep 20260905_102612_6d5fd4
# app sockets opening and closing, with what they carried
sudo journalctl -u <active-slot-unit> -o cat | grep -E '"kind":"app.tunnel.(open|close)"'
```

The Connector writes the same shape (`CONNECTOR_LOG_LEVEL`): `tunnel.open` / `tunnel.close` per app
tunnel, `tunnel.frame` for every terminal Hermes event (`message.complete`, `error`, `session.info`,
`approval.request`, `clarify.request`) naming the tunnel it went to, and `lifecycle.sent` /
`lifecycle.acked` for the observer. See `docs/DIAGNOSTICS.md` for how to line them up.

Connector status and logs on the Mac:

```bash
launchctl print gui/$(id -u)/com.hermesremote.connector
tail -n 100 "$HOME/Library/Application Support/Hermes Remote/connector.log"
tail -n 100 "$HOME/Library/Application Support/Hermes Remote/connector.error.log"
```

The Android app needs only the public Gateway URL and the app token. It must never receive the Connector token or local Hermes password.

Connector deployment record: 2026-09-05, Connector **0.1.2** (structured logging, `main` `7e8e6f2`) went live on
the Mac mini by replacing `connector/dist` with the build from that commit and `launchctl kickstart -k`; the
previous dist is kept as `connector/dist.bak-20260905-structured-logs`. Reconnected within a second; `relay-health`
reported the connector online. The Gateway on the HK host still runs **0.4.0** (`releases/0.4.0-833859aa9afe`):
the 0.4.1 bundle is built by the `Gateway OCI` gate for `7e8e6f2`, but `hermesctl deploy` remains staging-only and the
R5-D entrypoint is a one-time takeover, so promoting 0.4.1 needs a production deploy path (an ops round) before the
Gateway's structured log lines appear in production.

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
DMARC reporting mailbox. An incomplete audit returns `HR-OPS-018`. A pass proves the reviewed
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
configuration/provider/Webhook failure returns `HR-OPS-017`. These commands do not deploy, reload,
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

Every successful `Gateway OCI` push run on `main` retains its exact artifact as
`gateway-bundle-<full-main-commit>` for seven days. It contains the Gateway archive/manifest and the separately
hashed R5-D operator archive/manifest. Pull-request runs still build and verify both bundles but do not retain
them. Download the artifact from the matching successful `main` run, preserve every archive with its manifest,
and use the Gateway manifest as `targetArtifactManifest`; never substitute a hand-written manifest or combine
bundles from different commits. Verify the operator archive with
`scripts/verify-production-baseline-bundle.mjs` before and after transfer.

Gateway bundle manifest schema 3 records both the classic Docker config image ID and the OCI manifest
descriptor ID. The packager proves that both IDs are linked inside the same SHA-256-bound archive. Docker 29's
default containerd image store may report the OCI descriptor ID while classic storage reports the config ID;
Cloud Ops accepts only those two manifest-bound values and pins the value actually loaded on the target host.

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

## PostgreSQL production gate (R5-E7A complete; R5-F pending)

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

The R5-E1 read-only check passed on 2026-09-05. Role/database creation must use the strict
`scripts/postgresql-provision.mjs` entrypoint and `ops/postgresql.provision.example.json`; do not paste SQL or a
password into an interactive shell. The entrypoint accepts only an entirely absent role/database/URL state,
requires PostgreSQL 18 on loopback with statement/audit logging unable to capture the credential, verifies the
least-privilege result, and removes objects created by a failed attempt. It does not migrate schema or alter the
Gateway. Source availability still does not authorize running it in production.

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

As of 2026-09-06, the dedicated least-privilege role/database, schema 7 migration, first encrypted
production capture, Mac off-host PostgreSQL 18 restore, immutable-image account smoke, and atomic status
activation are complete with both account flags still disabled. The private recovery key never left the
Mac. The production monitor is enabled and passed both a real green check and an isolated failure drill.

R5-E7 was installed on 2026-09-06 from the protected `main 6e92ce018a6b` operator/Gateway artifacts. It runs a
03:15 Asia/Hong_Kong production capture timer and an hourly idempotent Mac LaunchAgent poll, with immutable
generations, strict SSH export, disposable PostgreSQL 18 restore on every new generation, evidence-bound status
activation, and acknowledged retention. The automation SSH identity uses a dedicated password-locked account;
its sudo permission names only the fixed root-owned wrapper and does not grant Node, the underlying script, or an
arbitrary config. The initial manual full cycle, idempotent replay, and following production-monitor check passed
without restarting Gateway, PostgreSQL, or Nginx; both account flags remain disabled. The first scheduler-triggered
capture succeeded on 2026-09-07, but the Mac restore failed closed because its LaunchAgent PATH did not contain the
`node` resolved by the disposable PostgreSQL wrapper's `/usr/bin/env` shebang. The encrypted generation remains
available and unacknowledged, temporary restore state was cleaned, and the prior valid production status remains
active. R5-E7A binds that wrapper to the protected operator runtime's absolute Node executable and adds a minimal-PATH
regression. PR #75 and its disposable PostgreSQL 18 rehearsal passed; the protected `main 4d2cc6561826` operator
bundle was then installed as a new immutable Mac directory and the LaunchAgent plist was atomically switched. The
same scheduled generation completed restore, schema 7 verification, immutable-image account smoke, production status
activation, and acknowledgement at 13:56 CST; the naturally scheduled 14:00 production monitor passed. No temporary
container or plaintext remained. Gateway, PostgreSQL, and Nginx retained their original start times and zero restarts;
account and binding capabilities remain disabled while legacy tokens remain accepted. The R5-E scheduler observation
gate is complete, so R5-F may proceed to its separate production go/no-go review.

## Production disk and backup monitoring (R5-C4; deployed)

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
boundary. The immutable production-baseline operator bundle carries that entrypoint, all three unit templates,
and the strict monitor configuration/status contracts so production installation never has to mix a protected
artifact with workspace files. The dedicated entrypoint must be used by systemd; `scripts/hermesctl.mjs` remains
available for interactive compatibility but loads unrelated deployment modules and is not the production timer
entrypoint.

## Production managed baseline (R5-D; adoption completed)

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

The operator packager must also start the staged candidate verifier before creating the archive, proving that
its local module closure is present. The disposable R5-D workflow must package, verify, extract, and execute that
exact operator archive instead of calling the checkout entrypoint. After Connector attachment, the verifier gives
transient connection and 5xx forwarding responses a bounded 20-attempt readiness window; authentication,
response-contract, identity, and capability failures remain immediate. Deployment captures only the verifier's
allowlisted `HR-RELEASE-003`
`smoke_check` value. Unstructured stderr is never copied into the production diagnostic. Private candidate smoke
uses the exact disposable mock-Hermes response contract, while post-switch public smoke accepts a nonempty JSON
status and error-free `session.create` object from the real Mac Hermes path instead of requiring mock-only fields.

Do not generate the production Nginx file from the staging template. Start from the actual production site
file, preserve every unrelated route, replace only the Gateway upstream, review the complete diff, and pin its
SHA-256 in the private configuration. The switch installs that exact file and restores the original bytes if
validation, reload, state handoff, observation, or smoke fails. See `CLOUD_GATEWAY_R5_MANAGED_BASELINE.md` for
the config, topology, disposable test, maintenance-window checklist, and recovery boundary. Source review or a
successful disposable workflow does not authorize running this command on the HK host.

When the active slot already serves the email-OTP gray rollout, routine-release smoke must validate the
preserved Schema/PostgreSQL readiness contract (`database=ok`, `migrations=ok`, `postgresql=supported`) and the
single `email_otp` provider. When Desktop binding is also active, the same smoke must additionally require the
singular binding surface and the `hermes-serve-v1` Desktop bootstrap contract; it must reject multi-device or
sharing capability drift. It must not reuse the account-disabled OCI expectations. Gateway 0.4.11 carries
this correction after 0.4.10 was rejected before traffic switching by the stale disabled-runtime readiness
assertion. If that failure left an audited `candidate_started` journal, use the same-commit operator and Gateway
bundle for the next release, run `production-release.mjs --operation recover`, and then run the normal `deploy`.
Recovery requires the original active slot and release links, byte-identical Nginx site/upstream checkpoint, an
inactive candidate with its port free, the recorded failed audit, and exactly one archived committed journal for
the live release. It archives the failed journal and restores that exact committed journal under the deployment
lock; any ambiguity or drift remains fail-closed. Gateway 0.4.12 introduces this recovery operation. Its first
production retry also proved that a disabled binding route is intentionally different across the two smoke
surfaces: the private Gateway returns `503` because its control dependency is disabled, while public Nginx hides
the route with `404`. Gateway 0.4.13 verifies both exact values instead of applying the public expectation to the
private candidate. The authorized 2026-09-09 production run `348f8a3f-fa25-4bc3-be45-ae210458be5f` committed
0.4.13 to blue with 0.4.9 as `previous`; the site-file hash stayed unchanged, the container was healthy with zero
restarts, the email-only public contract passed, and the production monitor remained green.

The first two authorized production attempts did not complete adoption. The first stopped before candidate start
on Docker 29/containerd image-ID representation. The second loaded the corrected image and started blue, then
stopped before route switch when the packaged smoke verifier could not load an omitted local dependency. Both
attempts left the legacy Gateway and public routes healthy. A `candidate_started` journal may resume only the exact
same plan and artifact after the R5-D6 gates pass; a different plan remains fail-closed.

The third attempt passed the private blue-slot image, readiness, Connector, REST, and WebSocket checks. After the
route switch, its public smoke incorrectly requested the loopback-only `/healthz` endpoint and received HTTP 404.
Automatic recovery restored the legacy service, exact Nginx bytes, release links, lifecycle state, and public
REST/WebSocket paths before returning `HR-OPS-014` at `switch_recovered`. R5-D7 makes the route scope explicit:
private smoke retains `/healthz`, `/readyz`, capabilities, and protected release identity checks; public smoke uses
only `/relay-health`, authentication rejection, authenticated `/api/status`, and `/api/ws`, matching the production
Nginx contract. Production retry remains blocked until the R5-D7 gates and exact-artifact exercise pass.

Those gates subsequently passed for `main` commit `833859aa9afe55f09d2fe8663ab0fd1528447ba4`. The authorized
R5-D7 retry committed run `5403064b-c220-42ab-91e0-d3b605e8c674`: blue now serves Gateway 0.4.0 from the exact
manifest-bound containerd image, Nginx targets `127.0.0.1:18787`, and the old Node service is stopped/disabled.
`current` points to `releases/0.4.0-833859aa9afe`; `previous` retains `releases/0.2.0-54f7aed61172`. Private identity
and readiness plus public Connector, REST, WebSocket, wrong-token, release-health and route-isolation checks passed
after the switch. PostgreSQL remains loopback-only, database/account flags remain disabled, and the production
monitor timer remains off. R5-D is complete; this does not authorize R5-E or R5-F.

## Routine production release (R5-F1; first production run 2026-09-07, Gateway 0.4.1)

R5-D can only run once (`activeSlot: null` → blue) and `hermesctl deploy/rollback` stay staging-only, so until
R5-F1 there was no legitimate way to put a later Gateway version (0.4.1 with the structured logs, and everything
after it) into production. R5-F1 adds `scripts/production-release.mjs`, introduced by operator-bundle manifest
schema 3 and retained by schema 4 (`releaseEntrypoint`). It takes a private configuration of the same shape as R5-D's (in production
`/secure-input/hermes-go/production-release.json`, see the run record below); only `targetArtifactManifest`
changes per release:

```bash
node scripts/production-release.mjs \
  --config /secure-input/hermes-go/production-release.json \
  --confirm production:<configured-hostname> \
  --operation deploy   # or: rollback; recover only for the audited pre-switch case below
```

What it does and refuses, in order: exact `production:<hostname>` confirmation, root, Linux/amd64 and the real
hostname; account and database flags still disabled; the target manifest is schema 2/3 with maintenance and
rollback declared; `current` must be a managed release (schema ≥ 2, never the legacy descriptor) with a
`committed` journal naming the live slot; the live slot unit is active, the retired `hermes-remote-gateway`
unit is inactive, the upstream include names the live slot, and the Nginx site file still satisfies the
production contract (one upstream include, exact `server_name`, `hermes_go_gateway_production`, no 8444 proxy).
The same edge check runs again immediately before the live slot is stopped. Then the R4 machine moves the
release to the other slot: lock, journal, checkpoint, immutable image load, private `/healthz`/`/readyz`/
identity/Connector/REST/WebSocket smoke on the loopback smoke runtime, lifecycle handoff, atomic upstream
rewrite plus `nginx -t`/reload, public smoke, observation window, public smoke again, `current`/`previous`
links, `committed`. **The Nginx site file is never rewritten by a release** — only the upstream include moves —
and a failure after the live slot stopped restores that slot, the upstream, the release links and the
lifecycle state, then re-verifies the public route (`HR-OPS-016`).

Candidate admission first requires successful loopback liveness, readiness and version probes, then waits up
to 75 seconds for Docker's independent health state. The wider bound covers the image's 10-second start period
and two 30-second scheduler intervals on a loaded host; `unhealthy` still fails immediately, and `starting` at
the deadline remains a hard failure before any traffic switch. The managed systemd unit overrides the image's
health command with the same `/readyz` probe derived from the slot's runtime `PORT`. This is required when
adopting or rolling forward an immutable older image whose embedded healthcheck used fixed port `8787`; the
operator does not alter the image and does not weaken the Docker health gate.

The public smoke accepts the two legitimate Legacy states. When a Legacy Connector is already online it is used
unchanged. When an account-mode Desktop has intentionally retired that Connector and `/relay-health` reports zero,
the operator starts a short-lived Connector with the existing production smoke token, verifies the full public
route, and stops it again. A failed post-switch smoke may leave the old slot fully restored while its journal still
records `route_switched` or `draining`; `--operation recover` may restore the archived committed journal only after
the old slot, candidate shutdown, release links, Nginx checkpoint and reverse lifecycle-handoff marker all match.

`--operation rollback` is the same machine pointed at the release behind `previous`; the configuration's
`targetArtifactManifest` must name that exact bundle (keep the previous bundle on the host) and a `previous`
that is still the legacy descriptor is refused — that case is an R5-B recovery, not a slot rollback.

Before an authorized production run: download the Gateway and operator bundles from the same successful
`Gateway OCI` run on `main` (they expire after seven days; a docs-only push does not re-run the workflow, so
match the latest run, not the latest commit), verify both with `scripts/verify-production-baseline-bundle.mjs`
before and after transfer, extract the operator bundle to a fresh directory and run the entrypoint from there,
pick a quiet window (every App and Connector socket on the old slot reconnects once at the switch; no downtime
otherwise), and record the run here. The manual `Gateway R5-D Managed Baseline` workflow rehearses exactly this
sequence on a disposable host: adoption, current-commit release into green, rollback to blue, site file
byte-identical throughout. Source merge, a green rehearsal and this section do not authorize the production run.

### Production run record — Gateway 0.4.1, 2026-09-07 (authorized)

Artifacts: `gateway-bundle-80225d817b4284c69b4142f6312087747c8d9537` from the `Gateway OCI` run on `main`
80225d8 (the R5-F1 merge itself), i.e. Gateway `0.4.1-80225d817b42` (archive SHA-256 `a8d3946f…`, containerd
image `sha256:9da652c723af…`) and operator bundle `Hermes-R5D-Ops-80225d817b42` (schema 3, SHA-256 `706da963…`).
Both verified locally, re-hashed on the HK host after transfer, and the operator bundle verified again from its
extracted copy at `/opt/hermes-go-ops/80225d817b42`. The disposable rehearsal (run 34104753984) had passed on the
same commit earlier that day.

The R5-D private configuration and its inputs were no longer present under `/secure-input/hermes-go`, so a release
configuration `/secure-input/hermes-go/production-release.json` was rebuilt from the live system: hostname `test`,
slots blue 18787 / green 18788, `DEFAULT_DEVICE_ID=mac-mini`, account and database flags off, site file
`/etc/nginx/conf.d/hermes-edge.conf`, token and TLS sources as root-only `0600` copies of the managed files
(byte-identical, so the immutable-file checks pass). The read-only admission preflight then reported
`activeSlot: blue`, source `0.4.0-833859aa9afe`, target `0.4.1-80225d817b42`.

Attempt 1 (09:58:55Z) failed closed at `preflight_tls` before any change: the key had been sourced from
`/etc/hermes-remote/tls/privkey.pem`, which is `0640 root:hermes-remote`, and the input inspector requires no
group/other bits. Recorded in the audit as `HR-OPS-001`; blue kept serving.

Attempt 2 (10:00:03Z → 10:00:52Z, 49 s) committed run `590d6014-87a9-403a-a2b3-101d3bf4b701`:
`production-deploy`, `activeSlot: green`, `previousSlot: blue`, `preparedStage: candidate_verified`,
`rollbackPoint: releases/0.4.0-833859aa9afe`. After the switch: `current` → `releases/0.4.1-80225d817b42`,
`previous` → `releases/0.4.0-833859aa9afe`; green unit active, blue exited 0, legacy unit inactive; upstream
`127.0.0.1:18788`; site file SHA-256 `422182b2…` identical before and after; `/relay-health` reports
`connectors: 1` with `mac-mini` online; green container image `sha256:9da652c723af…` (the manifest's containerd
ID), zero restarts, no warning-or-worse lines. The structured log is live: the first minute already carries
`connector.online`, `app.tunnel.open` and `app.tunnel.close` lines, so the DIAGNOSTICS.md runbook now applies to
production. Account and database flags remain disabled; PostgreSQL, the monitor timer and the R5-E automation were
not touched. Rollback point for the next operation: `--operation rollback` with the 0.4.0 bundle
(`Hermes-Gateway-0.4.0-833859aa9afe-linux-amd64`) as `targetArtifactManifest`.

## Production email-login gray rollout (R5-F2; default off until an authorized run)

Gateway 0.4.2 added the separately confirmed `scripts/production-account-rollout.mjs` entrypoint in operator
bundle schema 4. It is intentionally narrower than the account platform: only email OTP authentication and the
signed Resend callback are enabled. Google, binding, multi-device, sharing, identity management, Web sessions,
account deletion, and Desktop managed installation remain off. Legacy App and Connector tokens stay accepted.
The live site gains exact routes only for capability discovery, email challenge/exchange, refresh, sign-out,
`/v2/account`, and the signed Resend callback; existing exact callback or capability locations are not duplicated. Device, installation, Google and
Web-account routes remain absent. Gateway 0.4.9 is the corrected rollout candidate: it adds capability discovery
and the Resend callback to the narrow include when the already-managed live site does not expose those routes,
uses the legacy token header for both healthy and rejected legacy smoke, accepts the deployed Hermes status contract,
and verifies rollback against the site's original capability and exact email-route shape (including the legacy site's
405 fallback when no email location existed).

The managed blue/green units use Docker host networking because PostgreSQL remains intentionally bound only to
the host loopback interface. Each Gateway process itself binds its slot-specific `127.0.0.1` port, so neither the
Gateway nor PostgreSQL becomes reachable on a public interface and Nginx remains the only public ingress. Do not
restore bridge-mode `--publish` for an account-enabled release: container loopback would no longer reach the
host's `127.0.0.1:5432`, and readiness must fail closed with the database unavailable.
After restarting an account-enabled slot, the rollout first waits for a successful legacy Hermes status before
capturing relay health. This permits the Connector's normal reconnect interval without accepting a disconnected
final state; both the tunneled status and the following `connectors >= 1` check must still pass.
The readiness smoke consumes the Gateway's bounded public contract directly: a current migration is reported as
`checks.migrations=ok`. Do not translate it to the operator-only word `current`; doing so rejects a healthy
PostgreSQL 18/schema-15 runtime and triggers the fail-closed rollback.

Prepare a root-only `0600` configuration from `ops/production.account-rollout.example.json` and validate it
against `ops/hermes-go-production-account-rollout-config.schema.json`. All six source files must be distinct,
absolute, outside the managed install/config/state roots and have no group/other permission bits. The Resend key
must be sending-only, the webhook secret must be the currently active endpoint secret, the sender must end in the
configured verified subdomain, and the PostgreSQL URL must target loopback port 5432. Then run from the verified,
immutable schema-4 operator bundle:

```bash
node scripts/production-account-rollout.mjs \
  --config /secure-input/hermes-go/production-account-rollout.json \
  --confirm production:<configured-hostname>
```

The command proves the active release is exactly the configured rollout artifact with database schema contract 15
and PostgreSQL 18 support; proves the live slot and exact disabled environment; records a private checkpoint;
runs migrations from the immutable loaded image under the advisory lock; installs protected service secrets;
adds the narrow Nginx include; runs `nginx -t` and reload; restarts only the active Gateway; and verifies readiness,
schema currency, exact email-only capabilities, Connector continuity, legacy authentication, wrong-token rejection,
invalid-webhook rejection, a single isolated Resend `delivered@resend.dev` submission with its signed final-delivery
event observed in protected aggregate metrics, public email-route reachability and release identity twice across the observation window.
If any live step fails, it restores the exact prior environment and Nginx site, removes the route include, reloads
Nginx, restarts the Gateway with account mode disabled and verifies that state. `HR-OPS-020` names all failures;
inspect `/var/lib/hermes-go/ops/account-rollout.json` before any retry. Migration is intentionally forward-only,
so a disabled rollback may retain schema 15 while serving no account endpoint.

Do not run this command until a fresh encrypted schema-7 backup has passed off-host restore using the same release
contract. After migration, update both scheduled recovery configurations to schema 15 and the active immutable
artifact, then require a fresh encrypted capture, off-host restore, activation and monitor pass before closing the
maintenance window. Record the actual run, artifact identities, database generation and gray result below this
section after production execution.

### 2026-09-09 authorized production result

PR #111 merged as `main 787bdc9171901316cbe89cfd6d35de9bc371da0c` after the Node/PostgreSQL/network,
encrypted off-host recovery, OCI, Semgrep and Gitleaks gates passed; the post-merge CI/SAST/OCI runs and manual R5-D
run `34265477115` also passed. The immutable Gateway 0.4.9 archive SHA-256 was
`16329f00c0d9f44d651d00fa0bc9c0309ec55f242e2f3d2a1ad0cdc98f760d5d`, with containerd image ID
`sha256:3f85f33beaece61953dc6ebccd745e54df176d4ad04f7c6e5bc7b19d3e0fde9a`; the matching operator archive SHA-256
was `692f1e6647185553ca6a6d3a60c7992d005cd4b5fca389689af40d4a281149b5`.

Authorized production release run `438fdbcc-72c0-420d-923d-eac42f57bd29` committed 0.4.9 to green, retaining
0.4.8 `ebc31545e599` on blue as the rollback point. Authorized account rollout run
`0065f70a-a3f9-4348-918a-d22eaad135b5` then committed PostgreSQL 18/schema 15 and email OTP. Its isolated Resend
acceptance reached the signed final-delivered metric. Final public capabilities expose only `email_otp` for Android
and macOS; Google, binding/replacement, identity management, Web account center and Desktop bootstrap remain off.
Readiness reports config/database/migrations/PostgreSQL healthy, the legacy authenticated status and one online
Connector remain healthy, malformed challenge/webhook probes fail closed, and the green service has zero restarts.

The required post-migration recovery cycle produced generation
`20260908T191154059Z-cecbfc922361` (87,632 encrypted bytes), restored it on the Mac into disposable PostgreSQL 18,
verified schema 15 with the exact 0.4.9 image, returned evidence/status, and activated the production ack. The first
capture safely rejected a `0440` runtime URL copy; the capture configuration was instead pointed at the existing
root-only `0600` protected source. The first Mac attempt safely rejected the absent target image; loading the exact
manifest-bound image allowed the same generation to complete without weakening identity checks. Scheduled configs
now require schema 15 and the 0.4.9 manifest. A stale extra LaunchAgent positional argument found during the
transition was removed; the reloaded hourly job completed idempotently with exit code 0 and left no recovery
container. The production monitor's expected schema was advanced from 7 to 15 and its immediate rerun passed host,
disk and fresh encrypted off-host backup checks. Both HK timers remain enabled and active.

A read-only post-rollout binding preflight on 2026-09-09 found that the 0.4.9 image healthcheck probes fixed port
`8787` while the managed green slot listens on runtime `PORT=18788`. Public traffic, `/readyz`, email login, the
legacy Connector, and PostgreSQL remained available, but Docker correctly reported the failing probe as
`unhealthy`. Source now derives the probe port from `PORT`, and the OCI candidate gate runs the image on a
non-default internal port and waits for Docker `healthy`. Binding rollout remains blocked until that correction is
released through the normal versioned blue/green path and the replacement container reports `healthy`; do not
weaken or bypass the health gate.

The routine production release path now accepts only three exact active-slot environments: the original account-off
managed baseline, the committed email-OTP-only rollout, or the later committed single-Mac binding rollout. In either
account mode it copies the allowlisted environment to the candidate while changing only the slot `PORT`, requires
the source and target manifests to declare the same
database schema, re-hashes the active environment before the source is stopped, and checks both candidate and
public capabilities. Email must remain the sole provider; binding and Desktop managed install must preserve their
exact prior state, while multi-device, sharing, identity management, Web sessions, account deletion and Google must
remain absent or disabled. Any extra field, permission drift, changed value, schema change or wider advertised
surface aborts before traffic movement. A
schema-changing account release still requires the dedicated migration/restore workflow; the routine release must
not be used to bypass it. That workflow is R5-F8 below; R5-F1 now names the refusal
(`production_release_database_schema_change_requires_schema_release`) in every account mode, and refuses a rollback
to a release whose schema is below the database's (`production_release_rollback_below_database_schema`).

## Production single-Mac binding gray rollout (R5-F3; code gate only)

Gateway 0.4.14 adds a second, separately confirmed transition after R5-F2. It does not migrate the database or add
another identity provider. It requires the original committed email-rollout journal, the current schema-15 active
release, and the exact live email-only environment that routine releases preserve; the checkpoint is intentionally
not rewritten to impersonate each later Gateway release. It then enables only Connector binding and Desktop
managed-install capability. The account continues to own at most one active Mac;
multi-device selection, sharing, identity management, Web sessions, deletion, and Google remain disabled. Legacy App
and Connector tokens stay accepted throughout the test window.

Prepare a root-only `0600` configuration from `ops/production.binding-rollout.example.json`, validate it against
`ops/hermes-go-production-binding-rollout-config.schema.json`, and run only from a matching immutable schema-5-or-newer
operator bundle:

```bash
node scripts/production-binding-rollout.mjs \
  --config /secure-input/hermes-go/production-binding-rollout.json \
  --confirm production:<configured-hostname>
```

The operator proves the email-only runtime and committed R5-F2 checkpoint before mutation. It adds an independent
Nginx include for `/v2/connector-binding`, its child routes, and the exact `/v2/connect` WebSocket; runs `nginx -t`;
restarts only the active Gateway; and verifies twice that readiness is schema 15/PostgreSQL 18, email remains the
only provider, binding is singular, Desktop advertises `hermes-serve-v1`, unauthenticated binding is 401, the public
WebSocket upgrades with 101, the legacy Hermes route remains healthy, and release identity is unchanged. Any live
failure restores the previous environment and Nginx file byte-for-byte, removes the binding include, restarts the
Gateway in email-only mode, and verifies public/private binding are again 404/503 and WebSocket is absent.
Each post-restart verification waits first for bounded loopback readiness and then retries the preserved public and
private account surface for bounded Nginx/Gateway convergence; a transient startup 502/503 is not treated as a
binding failure while a persistent mismatch still fails closed.
`HR-OPS-021` names all failures; inspect `/var/lib/hermes-go/ops/binding-rollout.json` before retrying.

Do not execute this transition until the 0.4.14 PR and post-merge CI/OCI/manual gates pass, the signed Desktop
component manifest is hosted at its exact HTTPS paths, and the target Mac has a configured Desktop build. After the
operator commits, perform one explicit target-Mac migration while the legacy Connector rollback point is healthy.
Record artifact identities, the binding run ID, target-Mac journal, account binding generation, and rollback evidence
in this section. Source merge or artifact upload alone does not authorize capability enablement.

## Production multi-device gray rollout (R5-F4; enabled 2026-09-13, physical canary pending)

R5-F4 starts only from the exact committed R5-F3 single-Mac state. It does not migrate the database, create or
remove a binding, enable identity management, or enable sharing. It replaces the existing binding route include
with the reviewed plural-device routes, changes only `ACCOUNT_MULTI_DEVICE_ENABLED=1`, restarts the active Gateway,
and verifies the result twice while preserving email-only authentication, Desktop bootstrap, Legacy traffic and
the exact release identity.

Prepare a root-only `0600` configuration from `ops/production.multi-device-rollout.example.json`, validate it
against `ops/hermes-go-production-multi-device-rollout-config.schema.json`, and run only from the matching immutable
schema-6 operator bundle:

```bash
node scripts/production-multi-device-rollout.mjs \
  --config /secure-input/hermes-go/production-multi-device-rollout.json \
  --confirm production:<configured-hostname>
```

Admission requires the committed binding journal, schema 15/PostgreSQL 18 release identity, exact single-device
environment, exact original binding routes, one matching Nginx include, active service, healthy public/loopback
email and binding surfaces, the captured preflight Legacy state, and the production deployment lock. The Legacy
state is either an authenticated healthy response or the exact `503 {"error":"device_offline"}` response left
after a successful Desktop managed takeover intentionally retires the Legacy Connector. The installed route set exposes the existing
binding endpoints plus `GET /v2/devices`, device detail/default/unbind, explicit device REST, and explicit device
WebSocket traffic. Sharing, installation management and Web routes stay absent at Nginx. Smoke requires
`maxActiveConnectorsPerAccount=3`, `supportsDeviceSelection=true`, no sharing fields, unauthenticated device REST
and WebSocket guards, the existing Connector WebSocket, the unchanged preflight Legacy state, and unchanged release identity.

Any failure during this transition restores the environment and binding route file byte-for-byte, reloads Nginx,
restarts the active Gateway, and re-verifies single-device mode. This automatic rollback is valid only before a
second binding is created. Once an owner completes a second binding, do not disable multi-device mode: forward-fix,
or first remove the canary through the normal owner-authorized unbind flow. `HR-OPS-022` names all operator failures;
inspect `/var/lib/hermes-go/ops/multi-device-rollout.json` before retrying. Source merge and bundle generation do not
authorize production execution.

The first authorized production attempt on 2026-09-13 used schema-8 operator bundle
`Hermes-R5D-Ops-e50c7d070695` and stopped before mutation with `HR-OPS-022`: the active account-mode Desktop had
correctly retired its Legacy Connector, so authenticated `/api/status` returned the exact `device_offline` state,
while the original operator admitted only a healthy Legacy Connector. No multi-device journal was created, the
active blue service stayed running, and the environment and Nginx routes remained unchanged. The corrected gate
captures either legitimate preflight state and requires that exact state after restart and after the observation
window; authentication failures, other 5xx responses and malformed bodies still fail closed.

The authorized retry used the merged `main` operator bundle `Hermes-R5D-Ops-568d4896668d` (schema 8,
archive SHA-256 `f6739f1429d79a31f84f274fb0574cc98eb2d52656a45d972b4a86557064159f`). The bundle was generated
from clean commit `568d4896668de311da3fb4944881ab18e6a4c38f`, verified locally, re-hashed after transfer, verified
again from `/opt/hermes-go-ops/568d4896668d`, and executed only after the merge commit's CI, Gateway OCI and
SAST workflows passed. Run `3b863a6f-b1f2-4901-9eae-a1ff88ec51ae` committed on the blue slot at
2026-09-13T10:50:16Z. The active Gateway identity remained `0.4.15-6b7d60fa6bbf`, its container stayed healthy
with zero restarts after the operator restart, and schema 15/PostgreSQL 18 readiness remained green.

The final environment changed only `ACCOUNT_MULTI_DEVICE_ENABLED=1`; email OTP, binding and Desktop managed
installation remained enabled, while sharing, identity management, Web account center/session, deletion and Google
remained disabled. The plural binding route file matched SHA-256
`56d7ea3c24eee59176b279a939dd77ce0e908771a8171596a8812fae00158494`, `nginx -t` passed, unauthenticated
`/v2/devices` and device WebSocket returned 401, and the account shell and sharing routes remained 404. The exact
preflight Legacy response remained `503 {"error":"device_offline"}`. PostgreSQL contained one active, online
binding and six historical revoked bindings; the operator created no binding. The second-Mac binding, selection,
session-affinity, capacity and recovery exercise therefore remains a physical canary gate for E11 when that Mac is
available. No F5-A identity/Web or F5-B sharing capability was enabled by this run.

## Production identity and Web account-center gray rollout (R5-F5-A; production complete)

F5-A starts only from the exact committed R5-F4 multi-device state and matching release identity. It does not
change database schema, create an identity, send an email, enable Google, delete an account, or enable sharing.
It installs a separate exact-path Nginx allowlist and changes only
`ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1`, `ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED=1`, and
`ACCOUNT_WEB_SESSION_ENABLED=1` before restarting the active Gateway.

Prepare a root-only `0600` configuration from `ops/production.identity-web-rollout.example.json`, validate it
against `ops/hermes-go-production-identity-web-rollout-config.schema.json`, and run only from the matching
immutable schema-7 operator bundle:

```bash
node scripts/production-identity-web-rollout.mjs \
  --config /secure-input/hermes-go/production-identity-web-rollout.json \
  --confirm production:<configured-hostname>
```

Admission requires the committed multi-device journal, independently pins the active server version and source
commit, requires the exact R5-F4 environment and binding routes, proves the new identity-Web route file and include are absent, checks
schema 15/PostgreSQL 18, the captured preflight Legacy state, both device WebSocket guards, and takes the shared deployment lock. The
allowlist exposes only the account shell/assets, Web session and email flows, identity management, installation
management, audit events and default-device selection. It deliberately omits Google, account deletion and every
sharing route.

Post-restart smoke checks the account shell's CSP/no-store boundary, secure SameSite cookies, CSRF/Origin rejection,
unauthenticated identity and installation guards, edge-rejected Google/deletion/sharing routes, preserved multi-device and
Desktop capabilities, the unchanged preflight Legacy state and exact release identity. Any failure restores the previous environment and
site file byte-for-byte, removes the new include file, reloads Nginx, restarts the active Gateway and re-verifies
R5-F4. `HR-OPS-023` names all failures; inspect
`/var/lib/hermes-go/ops/identity-web-rollout.json` before retrying. Source merge and bundle generation do not
authorize production execution.

The first authorized F5-A attempt on 2026-09-13 stopped before mutation with `HR-OPS-023`. The production edge
returned its established 405 method rejection for unconfigured Google exchange, account deletion and sharing
acceptance mutations, while the original operator admitted only a 404 response. No identity-Web journal or route
file was created, all three identity/Web flags remained disabled, and the blue service stayed active. The corrected
gate accepts only 404 absence or 405 method rejection for these forbidden probes; authentication guards, successful
responses, redirects and server failures remain disallowed.

The second authorized attempt admitted the corrected edge contract and entered the live transition, but the enabled
Gateway rejected its generated environment because `ACCOUNT_WEB_ORIGIN` was absent. The operator observed invalid
readiness, restored the exact F4 environment and site, removed the identity-Web route file, restarted blue, and
recorded run `9d9493b7-3544-401b-992f-8a001f83714b` as `rolled_back`. Blue returned healthy on schema 15/PostgreSQL
18 and the account Connector reconnected. The corrected canonical environment carries both
`ACCOUNT_WEB_ORIGIN` and the later-sharing `ACCOUNT_SHARING_ACCOUNT_CENTER_ORIGIN`, each pinned to the configured
production HTTPS origin. It still accepts the exact older pre-F5 environment only while identity-Web and sharing
remain disabled, then writes both origins before either feature can start.

The authorized retry used schema-8 operator bundle `Hermes-R5D-Ops-8a4f17a1fb80` from merged `main`
`8a4f17a1fb806ef0ee71ae6ed9e4d904dc1d682b` (archive SHA-256
`f14a1f54d07e74717a4e6f50e4b9566ef14331f0c5c3601d6ac6c4a0eb74f0a0`). Its CI, Gateway OCI and SAST workflows
were green; the bundle verified before and after transfer and again from root-only
`/opt/hermes-go-ops/8a4f17a1fb80`. After confirming the earlier journal was `rolled_back`, it was preserved as
`identity-web-rollout.rolled-back-9d9493b7-3544-401b-992f-8a001f83714b.json`. Run
`925f7085-5f48-4d42-b895-fe1e2f1610de` then committed on blue.

The active release remained Gateway `0.4.15-6b7d60fa6bbf`, schema 15/PostgreSQL 18 readiness stayed green, and the
healthy container reported zero restarts after its operator restart. Identity management, Web account center and
Web session are enabled; email OTP, binding, multi-device and Desktop managed installation remain enabled; sharing,
Google and deletion remain disabled. Both Web origins are pinned to `https://mrlgs.net`. The exact identity-Web route
file matched SHA-256 `b7ad31a21b8a7933ef82f196be72989af627a5aac48ebaabbed36b37a5ae6229`, both route includes appeared once,
and `nginx -t` passed. Public verification returned the secured account shell/assets and anonymous session bootstrap,
401 for unauthenticated identity/installation/device access, and 404/405 for every disabled Google/deletion/sharing
probe; Connector WebSocket upgrade remained available and the unauthenticated device WebSocket remained 401. The
operator also preserved the exact preflight Legacy `device_offline` state. PostgreSQL still contained one account,
one external identity, two installations, one active/online binding and six revoked bindings; every newest business
record predated this rollout, so F5-A created no account, identity, installation or binding.

## Production whole-device sharing gray rollout (R5-F5-B; production switch complete, canary pending)

F5-B starts only from the exact committed F5-A identity-Web state, the committed R5-F4 multi-device
journal, and the same active release identity. It changes only
`ACCOUNT_DEVICE_SHARING_ENABLED=1` and adds a separate sharing-route include. It does not create an
invitation or grant, send mail, enable Google, enable account deletion, change the three-owned-device
limit, or touch a Mac.

Prepare a root-only `0600` configuration from `ops/production.sharing-rollout.example.json`, validate
it against `ops/hermes-go-production-sharing-rollout-config.schema.json`, and run only from the
matching immutable schema-8 operator bundle:

```bash
node scripts/production-sharing-rollout.mjs \
  --config /secure-input/hermes-go/production-sharing-rollout.json \
  --confirm production:<configured-hostname>
```

Admission requires both committed prerequisite journals, exact identity-Web environment and route
bytes, one matching identity-Web include, active schema-15/PostgreSQL-18 service, the captured preflight Legacy state and device
guards, matching release identity, and the shared deployment lock. The new allowlist contains only
native and Web list/invite/accept/cancel/revoke/leave sharing paths. Post-restart smoke requires the
fixed capability limits of five grantees per terminal and ten accepted shared terminals per account,
unauthenticated native/Web guards, Web CSRF rejection, the existing identity-Web security boundary,
multi-device routing, the unchanged preflight Legacy state, and unchanged release identity.

An immediate rollout failure restores the identity-Web environment and site file byte-for-byte,
removes the sharing include, restarts the active Gateway, and re-verifies F5-A. This operator must run
before canary invitations are created. After it commits, canary cleanup follows the normal API: cancel
a pending invitation before disabling sharing, or revoke an accepted grant and prove its active socket
closes within five seconds before disabling the flag. Never hide an active grant behind the disabled
capability. `HR-OPS-024` names operator failures; inspect
`/var/lib/hermes-go/ops/sharing-rollout.json` before retrying. Source merge and bundle generation do
not authorize production execution.

The separately authorized production execution reused the verified schema-8
`Hermes-R5D-Ops-8a4f17a1fb80` bundle. The F5-B configuration was validated locally, transferred with exact
SHA-256 `5b9ea7ba41528700a57ca598dd52893422a4c0ad1c75e96c26ee6a8f6f824102`, installed `0600 root:root`, and
revalidated against the exact committed F4/F5-A state before mutation. Run
`eed13c62-ab0d-44ea-b1da-adcb8f117afa` committed on blue with the active Gateway still
`0.4.15-6b7d60fa6bbf` and schema 15/PostgreSQL 18.

The sharing route file matched the locally rendered 4,289 bytes and SHA-256
`12a4a6be42265fdcabd3e41ea75d1eed6781f6780f9474f468b7745eecf8ec07`; binding, identity-Web and sharing
includes each appeared exactly once and `nginx -t` passed. The blue container stayed healthy with zero restarts.
Public checks preserved the secured account shell/session, returned 401 for unauthenticated native and Web sharing
lists, rejected a Web write without CSRF with 403, kept identity/installation/device guards at 401, kept Google and
deletion at 405/404, preserved Connector upgrade and device-WebSocket rejection, and preserved the exact Legacy
preflight state. The environment changed only `ACCOUNT_DEVICE_SHARING_ENABLED=1`; all earlier account flags and both
Web origins stayed exact. PostgreSQL still held one account, one identity, two installations, one active/online
binding and six revoked bindings, with zero share invitations and zero access grants. The remaining F5-B work is
the explicitly user-driven B-to-A invitation/use/revoke/leave canary; do not call that matrix complete from the flag
rollout alone.

## Production Desktop component gray rollout (R5-F6; production complete)

R5-F6 starts only from the exact production sharing state on a Gateway release that includes the schema-v2
capability. It changes only `ACCOUNT_DESKTOP_COMPONENT_INSTALL_ENABLED=1`; it does not edit Nginx, the database,
bindings, identities, sharing state, or Desktop services on any Mac.

Publish the immutable signed component manifest and every declared archive first. Prepare a root-only `0600`
configuration from `ops/production.component-rollout.example.json`, pinning the exact manifest URL, SHA-256,
key ID and Ed25519 public key. Validate it against
`ops/hermes-go-production-component-rollout-config.schema.json`, then run from the matching schema-9 operator
bundle:

```bash
node scripts/production-component-rollout.mjs \
  --config /secure-input/hermes-go/production-component-rollout.json \
  --confirm production:<configured-hostname>
```

Admission verifies the active release identity, schema 15/PostgreSQL 18 readiness, sharing environment, public
manifest headers and hash, manifest signature, and every component's public bytes. The operator takes the shared
deployment lock, snapshots the exact environment, enables only the component flag, restarts the active Gateway,
and verifies the full account/sharing/Legacy/WebSocket surface plus
`desktopBootstrap.componentManifestSchemaVersion: 2` twice across the observation window. Any failure restores
the previous environment and verifies that the component capability disappeared again. `HR-OPS-025` names all
failures; inspect `/var/lib/hermes-go/ops/component-rollout.json` before retrying. Source merge and bundle
generation do not authorize production execution.

The authorized Gateway 0.4.16 deployment on 2026-09-14 first used merge `29abbc9294bd` and schema-9 operator
bundle `Hermes-R5D-Ops-29abbc9294bd` (archive SHA-256 `2234ab054e88c1987cfdd2a5b39a1009787a0eb1a4836f568d38e94a13bea136`).
Run `597ad2fe-ba76-448f-b314-40fc120cb612` reached `route_switched`, then failed with `HR-OPS-016`: account-mode
Desktop had intentionally retired the Legacy Connector, while the routine public smoke still waited for
`connectors: 1`. The same obsolete assumption made the recovery smoke report failure after it had already restored
the blue service, Nginx upstream, release links and lifecycle state. Independent checks found blue 0.4.15 active,
green inactive, upstream `127.0.0.1:18787`, zero restarts and a healthy public route; no failed state was declared
committed.

PR #299 corrected the smoke and recovery boundaries. Its merge `0adccd7b834f1ab3366caf6091c2a3426b5b28f1`
passed the PR and resulting `main` CI, Gateway OCI, off-host recovery and SAST workflows. The paired production
artifacts were Gateway archive SHA-256 `73c3b7de382937ee4745cc19f011cbb517c8e45013ac79647b984dd7dcda9555`
(containerd image `sha256:ccd744d56b6aed32645683a378429eaa13634d3ae3d2e1507cb71405db47b879`)
and schema-9 operator archive SHA-256 `dac61f6e0d5f43b62f07f2f7716461778513d770998422915df78f2f223958e4`.
Both matched locally and after transfer, and the extracted operator verified itself on the production host.
The corrected `production-recover` accepted the failed `route_switched` journal only after every restored-state
gate passed, then restored the archived 0.4.15 committed journal. Retry run
`a1ee338f-3651-4e81-bbc9-25a75d6a7406` committed Gateway `0.4.16-0adccd7b834f` on green with blue inactive,
upstream `127.0.0.1:18788`, a healthy container, zero restarts and rollback point
`releases/0.4.15-6b7d60fa6bbf`.

The separately authorized component run `a310a75c-ada9-4e0a-b64a-010a3fdf0434` then committed release 0.4.0.
It pinned schema 2 manifest SHA-256 `31e85f64d3347cb0302450f400b1357acd440c7dff041e8a896d67ee13dfa47f`,
verified its Ed25519 signature and every declared public archive, changed only
`ACCOUNT_DESKTOP_COMPONENT_INSTALL_ENABLED=1`, restarted green and repeated the complete account/sharing/Legacy/
WebSocket and component-capability checks across the 30-second observation window. Independent verification found
the same healthy image with zero restarts, all preceding account flags still enabled, `/relay-health` healthy with
the expected retired-Legacy `connectors: 0`, and public capabilities advertising
`desktopBootstrap.runtimeContract: hermes-serve-v1` plus `componentManifestSchemaVersion: 2`. Physical Desktop
download, confirmation, installation and managed-service acceptance remain a user-driven Mac gate.

## Production Web app gray rollout (R5-F7; production complete 2026-09-22, iPhone device checks pending)

R5-F7 turns on the browser Web app at `/app/` (docs/ACCOUNT_MODE_API.md §8, docs/ACCOUNT_MODE_SECURITY.md §4).
It starts only from the exact R5-F6 state (`email_sharing_components`) on a Gateway release of at least
**0.4.17**, the first to contain the Web code. It edits only one new Nginx include and two environment flags;
the database, bindings, identities, sharing state and Desktop are untouched. Source merge, bundle generation and
a Web publish do not authorize production execution. The three steps below run in this order, each separately
authorized.

**1. Gateway 0.4.17 through R5-F1.** The release carries two things the rollout needs: the Gateway unit mounts
`<installRoot>/web` read-only at the same path (deploy creates the directory; Docker would otherwise refuse to
start the container), and the canonical environment gains `ACCOUNT_WEB_DEVICE_ACCESS_ENABLED`,
`WEB_APP_ENABLED` and `WEB_APP_DIR=<installRoot>/web/current`. R5-F1 reads the pre-F7 42-line environment and
writes the 45-line form with both flags `0`, so this release changes nothing a user can see. Units are written
only for the candidate slot, so the Web mount exists only on slots redeployed by 0.4.17 or later; R5-F7 checks
the active unit for it. The operator bundle for this release is schema 10 (`webAppRolloutEntrypoint`).

**2. Publish the Web app (dark).** From a clean tree at `origin/main`:

```bash
scripts/publish-web-app.sh            # web: npm ci, typecheck, test, build → package → install → switch current
scripts/publish-web-app.sh --rollback # point current back at the release it replaced
```

The script packages `web/dist` with `scripts/package-web-app.mjs` (a reproducible ustar of regular files plus a
manifest of per-file SHA-256), copies it and the reviewed `deploy/publish-web-app.mjs` from this commit into a
private temporary directory on the host, and runs the installer as root under
`flock <root>/.publish.lock`. The installer verifies the archive hash, refuses any entry that is not a plain
relative regular file or not in the manifest before writing, installs into `releases/<version>-<commit12>/`
through a staging directory, and switches the relative `current` link with one `rename(2)`, recording
`.previous`. Releases are never deleted automatically (a cached `index.html` still fetches its hashed assets).
The Gateway reads files per request, so publishing and rollback never restart it. Before step 3 the edge does not
route `/app/` to the Gateway, so this publish is invisible; afterwards `WEB_PUBLISH_VERIFY_PUBLIC=1` also
compares the public `/app/` with the local build; turn it on for every publish once R5-F7 is live, since a
broken publish otherwise goes unnoticed until a user reports it. The installer runs as root from the private
temporary directory, so the release SSH user needs unrestricted passwordless sudo (`sudo -n`): a sudoers rule
narrowed to one command cannot match the per-run path. Confirm this before the first run. Do not publish or roll
back the Web app while R5-F7 is running; the rollout re-checks the published release after taking its lock and
stops if it changed.

**3. R5-F7.** Prepare a root-only `0600` configuration from `ops/production.web-app-rollout.example.json`,
validate it against `ops/hermes-go-production-web-app-rollout-config.schema.json`, and run from the matching
schema-10 operator bundle:

```bash
node scripts/production-web-app-rollout.mjs \
  --config /secure-input/hermes-go/production-web-app-rollout.json \
  --confirm production:<configured-hostname>
```

Admission requires: the running release equal to the bundle target, schema 15/PostgreSQL 18 and at least
0.4.17; the active unit carrying the Web mount; `web/current` a relative link into `web/releases/` with an
`index.html`; the environment exactly `email_sharing_components`; the binding, identity-Web and sharing includes
each once with byte-exact route files; no Web app include yet; Legacy, the Connector WebSocket, the device
WebSocket guard and `/internal/version` healthy; and `/app/` not already served as the shell. Under the shared
deployment lock it writes `<configRoot>/account/web-app-routes.conf` (only `location = /app` and
`location ^~ /app/`: every API the Web app calls was already forwarded by R5-F2/F4/F5), includes it after the
sharing include, runs `nginx -t` and reloads, writes the environment with both flags `1`, and restarts the active
Gateway. It then verifies, twice across the observation window, the full component surface plus
`accountAuth.webDeviceAccess`, `/app` → 308 `/app/`, `/app/` 200 HTML with `no-store` and a CSP without
`unsafe-inline`/`unsafe-eval` that allows the service worker, `/app/sw.js` scoped to `/app/`, the cookie-less
device API answering 401, the Connector WebSocket 101 and the device WebSocket 401. Any failure restores the
environment and site bytes, removes the route file, reloads, restarts and re-verifies the component state.
`HR-OPS-026` names all failures; inspect `/var/lib/hermes-go/ops/web-app-rollout.json` before retrying.

Later Web releases repeat step 2 only. Later Gateway releases preserve `email_sharing_components_web` (R5-F1
recognizes it, its candidate smoke requires the capability, and its account smoke requires the `/app/` shell).

**Rollback limits and turning the Web app off.**

- After Gateway 0.4.17 the active environment has 45 lines, which a schema-9 (0.4.16) operator bundle refuses to
  parse, so `--operation rollback` and `recover` must run from the schema-10 bundle. Keep that bundle next to the
  previous one.
- After R5-F7, the Gateway cannot be rolled back below 0.4.17: the candidate smoke requires
  `accountAuth.webDeviceAccess`, fails on the older release, and restores the current one.
- R5-F7 has no reverse command. To turn the Web app off by hand, under the deployment lock:
  1. rewrite the active `gateway.env` with `ACCOUNT_WEB_DEVICE_ACCESS_ENABLED=0` and `WEB_APP_ENABLED=0`, keeping
     all 45 lines;
  2. remove the `include …/web-app-routes.conf;` line from the site file and delete the route file;
  3. run `nginx -t`, reload nginx, and restart the active Gateway;
  4. move `web-app-rollout.json` aside.

  This returns to `email_sharing_components`. The published releases can stay: nothing routes to them. The iPhone checks in docs/SMOKE_TEST.md
("Web app on iPhone") need a real device after step 3.

### 2026-09-22 authorized production result

Owner-authorized merge and deployment. Artifacts from `Gateway OCI` run 35689066912 on `main 86fc250b78a5`
(PR #371, the 0.4.17 bump on top of #363/#368/#369): Gateway `0.4.17-86fc250b78a5` (archive SHA-256
`a8601e6db47d489992454bfea79540f2794df1f1047ab5a0b9109501a8bafe6d`, containerd image
`sha256:5467d23be13c89ee3e79d86fc56dc5ced55443820e95daf69c907e6a3364bdf3`) and schema-10 operator bundle
`Hermes-R5D-Ops-86fc250b78a5` (archive SHA-256 `6f605ce338ed204950dc6d749029095fcee98b9480597c0c9cc18fe996554cb3`),
verified locally and again on the host, extracted at `/opt/hermes-go-ops/86fc250b78a5`.

1. **Gateway 0.4.17 (R5-F1), operator `codex-r5f2`.** Run `90b91434-eb8e-4c39-a358-4e7316946cb9` committed at
   about 05:23Z: blue active, green inactive as the rollback point, `current` → `releases/0.4.17-86fc250b78a5`,
   `previous` → `releases/0.4.16-0adccd7b834f`. The blue environment was written in the 45-line form with both Web
   flags `0` and `WEB_APP_DIR=/opt/hermes-go/web/current`; the blue unit carries
   `--mount type=bind,src=/opt/hermes-go/web,dst=/opt/hermes-go/web,readonly`; deploy created
   `/opt/hermes-go/web/releases`. Public `/v2/capabilities` reported 0.4.17 without `webDeviceAccess`.
2. **Web publish.** `scripts/publish-web-app.sh` from a clean `main c2ab2f79aacf` (Web gate: 338 tests, build)
   installed `releases/0.1.0-c2ab2f79aacf` (8 files, directories 0755, files 0644, root) and pointed `current`
   at it; public `/app/` still answered 404 from the edge.
3. **R5-F7.** From the extracted bundle, with `/secure-input/hermes-go/production-web-app-rollout.json` (root
   `0600`, same shape as the sharing configuration: origin `https://mrlgs.net`, host `test`, observation 30 s),
   run `42997f02-ae40-42d9-8531-6d3e7e4b21f4` committed on blue between 08:22:43Z and 08:23:19Z. Independent
   checks afterwards: capabilities `webDeviceAccess: true`; `/app` 308 → `/app/`; `/app/` 200 HTML, `no-store`,
   CSP `default-src 'none'; script-src 'self'; … connect-src 'self' wss://mrlgs.net; … worker-src 'self'; …
   frame-ancestors 'none'`, `X-Frame-Options: DENY`, bytes identical to the published `index.html`; hashed asset
   200 with `immutable`; `sw.js` `no-cache` + `Service-Worker-Allowed: /app/`; manifest
   `application/manifest+json`; cookie-less device API 401; `/account` 200; legacy `/api/status` without a
   token 401. Blue container healthy, zero restarts, no warning-or-worse log lines in the first two minutes; the
   site file gained only `include /etc/hermes-go/account/web-app-routes.conf;`; DERP, Nginx and the release server
   stayed active.

Unrelated to this rollout and already failing before it: `hermes-go-production-monitor.service` reports
`HR-OPS-012` (`disk_capacity:warning` at about 15 GiB free, `database_backup:critical` because the off-host status
is about 11 days stale). The Mac-side `com.hermesgo.postgresql-offhost` LaunchAgent has failed hourly since about
2026-09-10 with `postgresql_automation_restore_container_start_failed`: Docker Desktop is not running on the Mac
mini, so the disposable restore container cannot start. HK captures continue daily. The iPhone checks in
docs/SMOKE_TEST.md ("Web app on iPhone") remain to be done on a device.

## Production schema-changing release (R5-F8; production complete 2026-09-22)

R5-F8 is the dedicated path for a Gateway release whose `releaseContract.databaseSchemaVersion` is higher than the
running one. It exists because R5-F1 must not change the schema and the older image's readiness requires an exact
schema: after a migration the older image answers `/readyz` with `migrations: "mismatch"` (it keeps serving; nothing
routes on readiness, see the 0.4.9 precedent above), so the migration is forward-only and every failure after it is
fixed forward with a newer release. Source merge and bundle generation do not authorize production execution.

1. **Fresh backup, verified off-host.** Run a capture on the host (the scheduled capture service, or
   `scripts/postgresql-automation.mjs capture` from the operator bundle) and let the Mac off-host cycle export,
   restore-smoke and activate it. The monitor's active status file then names that generation.
2. **Configuration.** Prepare a root-only `0600` file from `ops/production.schema-release.example.json`, validated
   against `ops/hermes-go-production-schema-release-config.schema.json`: the R5-F1 configuration (whose
   `targetArtifactManifest` names the schema-changing bundle), the database URL source (root `0600`) with the
   production `ssl` and `migrationLockId` values used by R5-F2, and the backup gate (`activeStatusFile`,
   `maximumAgeMinutes` 5–720).
3. **Run** from the schema-11 operator bundle built from the same commit as the Gateway bundle:

   ```bash
   node scripts/production-schema-release.mjs \
     --config /secure-input/hermes-go/production-schema-release.json \
     --confirm production:<configured-hostname>
   ```

Admission is R5-F1's admission (with the schema check deferred to R5-F8), an account runtime, a target exactly one
schema step ahead, and an active backup status for this host and the current schema whose off-host hash matches and
whose `backupCompletedAt` is within the bound. Execution is the unchanged R5-F1 machine with the database handed only
to the deployment step: under the deployment lock, after the checkpoint, the target image's migrator runs
(`DATABASE_MIGRATION_OK`, advisory lock, 120 s bound), then the candidate starts and must report schema-matched
readiness, and the switch, observation window and commit proceed as for R5-F1. `assessReleaseTransition` admits the
step only for this entrypoint (`allowDatabaseSchemaAdvance`, deploy, exactly +1); every rollback still needs an
identical schema. `HR-OPS-027` names all failures and its technical cause says what happened:
`schema_release_failed_before_migration` (nothing changed; retry), `schema_release_migrated_degraded` (the database
is ahead and the older release is serving without readiness; fix forward), or `schema_release_migration_state_unknown`
(look before doing anything).

After a successful run, before closing the window: move the three backup pins to the new schema and artifact (the
HK capture-schedule configuration, the Mac off-host configuration including its `targetArtifactManifest`, and the
production monitor's `backup.expectedDatabaseSchemaVersion`), then run a fresh capture → off-host restore → activate
cycle and a monitor pass. Until then the next capture fails `postgresql_database_subject_mismatch` by design.

Not rehearsed: the disposable-host R5-D workflow has no account-mode database path, so R5-F8 is covered by unit tests
(admission, backup gate, delegation, failure classification, the transition allowance) and by the step-by-step
verification of the production run only.

### 2026-09-22 authorized production result (Gateway 0.4.18, schema 15 → 16)

Owner-authorized (HG-94: version gate 0.4.18, no rollback below 0.4.18, the compatibility-check allowance).
Operator `claude-hg94`. Final artifacts from `Gateway OCI` run 35720662485 on `main 1d6322f51558`: Gateway
`0.4.18-1d6322f51558` (archive SHA-256 `e73cc053938ebfdfa72f378c73577ae9718b8f91b4559f48d01664f36084b122`) and
schema-11 operator bundle `Hermes-R5D-Ops-1d6322f51558` (SHA-256
`8d2946676bba1a91b575abe8359f185e072af7a1334573c41121157f5051f2f5`), verified locally and on the host, gateway
bundle at `/secure-input/hermes-go/gateway-0.4.18-1d6322f`, operator bundle extracted at
`/opt/hermes-go-ops/1d6322f51558`; `production-release.json` retargeted (previous copy
`production-release-86fc250b78a5.json`).

1. **Backup.** The Mac off-host cycle had failed hourly since 2026-09-10 (Docker Desktop not running); with Docker
   started it activated generation `20260921T192839006Z`, then a fresh HK capture `20260922T102335821Z` (schema 15)
   was exported, restore-smoked and activated at 10:24:46Z.
2. **Run 1 (bundle 5bf63b2b8d4a), 11:02Z** — stopped at admission, `schema_release_admission_failed:
   production_release_runner_required`; nothing changed. R5-F8 called the R5-F1 admission without the default
   command runner (fixed in #380).
3. **Run 2 (bundle 194115c43b60), 11:12Z** — migrated the database to 16 and verified the 0.4.18 candidate on green,
   then the switch's pre-routing migration re-check failed (`database_migration_container_failed`): it ran the
   manifest's Docker image ID, which names no image on this containerd store. The candidate stopped before the
   source; 0.4.17 kept serving with `migrations: mismatch` readiness (degraded, no user-facing outage) from 11:12Z
   to 11:25Z. HR-OPS-027 classified it `schema_release_migrated_degraded`. Fixed in #381 (re-check with the loaded
   image; `recover` accepts `candidate_verified`).
4. **Recover + run 3 (bundle 1d6322f51558), 11:24Z** — R5-F1 `--operation recover` restored the committed 0.4.17
   journal (recovered run `488eaa10`, stage `candidate_verified`); R5-F8 run `27c7e115-20bb-43fb-a137-090d546ea24b`
   (migrator a no-op at 16) committed: green active, `current` → `releases/0.4.18-1d6322f51558`, `previous` →
   `releases/0.4.17-86fc250b78a5`. Green readiness `ready` (migrations ok), Docker healthy, no warn-or-worse lines;
   public capabilities 0.4.18 without `push`, `/app/` 200. The stopped blue unit shows `failed` (0.4.17 exited 1
   on SIGTERM); it is disabled and only the rollback point, which schema 16 now forbids anyway.
5. **Backup pins moved to 16.** HK `postgresql-capture-schedule.json` and `production-monitor.json`
   (`.pre-schema16` copies kept); Mac `postgresql-offhost.json` to schema 16 with the 0.4.18 gateway manifest from
   `~/.hermes-go/recovery/r5e7/operator-1d6322f51558`, and the LaunchAgent to that operator's
   `postgresql-automation.mjs` (`.pre-schema16` copies kept). The restore smoke needs the target image loaded in
   the Mac's Docker (`docker load` of the 0.4.18 archive; its ID is the manifest's containerd ID). Capture
   `20260922T112754252Z` (schema 16) was restore-smoked and activated at 11:40:44Z; the production monitor then
   passed every check, clearing the long-standing `database_backup:critical`.

## Production FCM push rollout (R5-F9; production complete 2026-09-22, device delivery pending)

R5-F9 turns on FCM wake hints (docs/ARCHITECTURE.md, "Push wake hints"). It requires Gateway ≥ 0.4.18 (schema 16,
so R5-F8 first) running in `email_sharing_components_web`. From 0.4.18 on, R5-F1 writes the 47-line environment:
the 45-line form plus `ACCOUNT_PUSH_ENABLED=0` and `ACCOUNT_FCM_SERVICE_ACCOUNT_FILE=/run/hermes-go/secrets/fcm-service-account`;
the Gateway reads the key only while the flag is `1`.

Place the Firebase service-account JSON for project `hermesgo-94bbc` at a root-owned `0600` path outside `/opt`,
`/etc` and `/var/lib/hermes-go` (e.g. `/secure-input/hermes-go/fcm-service-account.json`, ≤ 16 KiB). Prepare a
root-only `0600` config from `ops/production.push-rollout.example.json`, validate it against
`ops/hermes-go-production-push-rollout-config.schema.json`, and run from the schema-11 operator bundle:

```bash
node scripts/production-push-rollout.mjs \
  --config /secure-input/hermes-go/production-push-rollout.json \
  --confirm production:<configured-hostname>
```

Admission refuses any other mode, an older or schema-15 release, drifted binding/identity-Web/sharing/web-app
routes, an existing `account/push-routes.conf` or `secrets/fcm-service-account`, and a key that is not a
service-account JSON of the configured project. It verifies the Web state (no `capabilities.push`; the public
push-registration route is not forwarded; the Gateway answers 404 on loopback). Under the deployment lock it installs
the key as `secrets/fcm-service-account` (`0440 root:1000`), adds `location = /v2/installations/current/push-registration`
(8k body) right after the web-app include, runs `nginx -t`, reloads, writes the 47-line environment with
`ACCOUNT_PUSH_ENABLED=1`, and restarts the active slot. It then verifies twice across `observationSeconds`:
`capabilities.push` is exactly `{providers:["fcm"]}` publicly and on loopback, unauthenticated PUT/DELETE on the
route return 401, and `/app/`, Legacy status, version identity and both WebSockets are unchanged. Any failure
restores the environment and site bytes, removes the route file and the key, reloads, restarts and re-verifies the
Web state. `HR-OPS-028` names all failures; inspect `/var/lib/hermes-go/ops/push-rollout.json` (it holds no key
material) before retrying. After R5-F9 routine releases preserve `email_sharing_components_web_push` (R5-F1 and the
candidate smoke require the capability).

To turn push off by hand: under the deployment lock set `ACCOUNT_PUSH_ENABLED=0` (keep 47 lines), remove the push
include line and route file, run `nginx -t`, reload, restart, then delete the key file and move `push-rollout.json`
aside. That returns to `email_sharing_components_web`; phones fall back to their periodic inbox check.

### 2026-09-22 authorized production result

Owner-authorized. The service-account key (project `hermesgo-94bbc`) was copied by the owner to the host and
installed as `/secure-input/hermes-go/fcm-service-account.json` (root `0600`); configuration
`/secure-input/hermes-go/production-push-rollout.json` (origin `https://mrlgs.net`, host `test`, observation 30 s).
From `/opt/hermes-go-ops/1d6322f51558`, run `1956d7a7-4a94-4d47-9536-1a4509d551b1` committed on green between
11:49:56Z and 11:50:30Z. Independent checks afterwards: public capabilities `push: {"providers":["fcm"]}` on 0.4.18
with `webDeviceAccess` still true; unauthenticated PUT and DELETE on `/v2/installations/current/push-registration`
401; `/app/` 200; green readiness ready, Docker healthy, no warn-or-worse lines; the 47-line environment with
`ACCOUNT_PUSH_ENABLED=1`; the key installed `0440` for the container group. Known: this key was exposed in a
screenshot during setup and the owner chose to keep it for now; rotate it (new key → re-run the key install by
hand under the lock, restart) before relying on push long-term. Delivery to a real phone is not yet verified: it
needs an APK built with the Firebase client values and a phone with Google Play services (docs/SMOKE_TEST.md,
"HG-94").

## Routine release: Gateway 0.4.19 with Web batch 4 (R5-F1; production complete 2026-09-22)

Owner-authorized (merge and deployment, 2026-09-22). Operator `claude-webapp`. The release carries Web batch 4
(#385): the browser allowlist admits session rename / archive / delete, workspace move, the session-scoped
`/model … --session` switch, the session's reasoning effort, `process.list` and `session.access`, each in one
parameter shape, and `/v2/capabilities` adds `accountAuth.webDeviceFeatures`. Database schema 16 unchanged, no
migration, mode `email_sharing_components_web_push` preserved, so this is a routine R5-F1 release; rollback to
0.4.18 stays possible (same schema). The HG-94 session was told before the run and held its own production work.

Artifacts from `Gateway OCI` run 35729541374 on `main 81d7d5de9524` (the version bump, #386): Gateway
`0.4.19-81d7d5de9524` (archive SHA-256 `a2bb97e30f4df2ac86e9374d3a40a4a84b8952b8af79b224cdbe6d4f37c42e4d`, containerd
image `sha256:57c8464a8b10…`) and operator bundle `Hermes-R5D-Ops-81d7d5de9524` (SHA-256
`d7ed9efcc1cf188a04446b06067c80919936eb4e34c1e8e4c8a9ab3db5e2427e`), both verified locally, re-hashed on the host,
and the operator bundle verified again from its extracted copy at `/opt/hermes-go-ops/81d7d5de9524`. Bundles and
the operator archive live in `/secure-input/hermes-go/gateway-0.4.19-81d7d5d/` (root `0600`).
`production-release.json` was retargeted and its operator set to `claude-webapp` (previous copy
`production-release-1d6322f51558.json`).

Run `7a44374f-3af6-438e-a9ef-83db5ed75414` (`production-deploy`) committed: `activeSlot: blue`, `previousSlot: green`,
`preparedStage: candidate_verified`, `current` → `releases/0.4.19-81d7d5de9524`, `previous` →
`releases/0.4.18-1d6322f51558`. Independent checks afterwards: public capabilities `server.version` 0.4.19,
`push: {"providers":["fcm"]}` unchanged, `webDeviceAccess: true` and `webDeviceFeatures` listing
`session-manage`, `session-delete`, `workspace-move`, `model-select`, `process-list`, `session-access`; `/app/` and
`/account` 200; a cookie-less device API call 401; blue readiness `ready` (migrations ok), Docker healthy, zero
restarts, no warn-or-worse lines; green inactive (rollback slot); nginx and DERP active. `xray.service` has been
inactive since 2026-08-30 and was not touched. Backup pins (schema 16) and the Mac off-host configuration are
unchanged — the restore smoke's 0.4.18 image still matches the schema.

Web package `0.1.0-81d7d5de9524` was published right after the switch (`scripts/publish-web-app.sh`, previous
`0.1.0-5bf315338d4b`); the public shell's assets are byte-identical to the local build. Real-iPhone checks for
batches 2–4 remain open (`docs/SMOKE_TEST.md` items 11–13). Rollback: `--operation rollback` with the 0.4.18
bundle (`/secure-input/hermes-go/gateway-0.4.18-1d6322f/`) as `targetArtifactManifest`; the Web package hides the
batch-4 features by itself once `webDeviceFeatures` is gone, and `scripts/publish-web-app.sh --rollback` returns
the previous Web package.

## Routine release: Gateway 0.4.20 with Web batches 5–6 and the HG-104 edge work (R5-F1; production complete 2026-09-23)

Owner-authorized (merge, version gate and deployment, 2026-09-23). Operator `claude-hg104`. The release carries
HG-104 (permessage-deflate on the Connector control sockets, `status`/`bytes`/`chunks`/`ttfbMs` on `http.tunnel`
logs, and the client-side session-list debounce plus chat-history paging), the HG-101/102/103 push fixes (#388)
and Web batches 5 (#389) and 6 (#395). Database schema 16 unchanged, no migration, mode
`email_sharing_components_web_push` preserved — a routine R5-F1 release; rollback to 0.4.19 stays possible
(same schema).

Artifacts from `Gateway OCI` run 35804527315 on `main 2b8cbc5c6f2f` (the version bump, #398): Gateway
`0.4.20-2b8cbc5c6f2f` (archive SHA-256 `8dc617a909d91524d050d36fc8ea05cb07aaf29897f02bb7d1d245d58660f873`,
image `hermes-remote-gateway:0.4.20-2b8cbc5c6f2f`) and operator bundle `Hermes-R5D-Ops-2b8cbc5c6f2f` (SHA-256
`1a6bc1787c43f4c222c27943cd80070fae6fa20902f566bdf21a00b2a5ecedde`), both verified locally, re-hashed on the host,
and the operator bundle verified again from its extracted copy at
`/opt/hermes-go-ops/2b8cbc5c6f2f7f1240062d5ca1b890a7e75efe24`. Bundles live in
`/secure-input/hermes-go/gateway-0.4.20-2b8cbc5c/` (root `0600`). `production-release.json` was retargeted and its
operator set to `claude-hg104` (previous copy `production-release-81d7d5de9524.json`).

Run `d0bd0a29-55f9-4087-931d-6205bb55a048` (`production-deploy`) committed: `activeSlot: green`,
`previousSlot: blue`, `preparedStage: candidate_verified`, `current` → `releases/0.4.20-2b8cbc5c6f2f`, `previous` →
`releases/0.4.19-81d7d5de9524`. Independent checks afterwards: public capabilities `server.version` 0.4.20;
loopback `/internal/version` `serverVersion` 0.4.20 with `sourceCommit` `2b8cbc5c…` and `sourceDirty: false`;
`/internal/account-connectors` showed both account Connectors reconnected (generations 7 and 8) within a minute of
the switch, which is also the permessage-deflate check — the ws client negotiates the extension by default;
`/relay-health` ok; an unauthenticated device WebSocket upgrade still 401; blue inactive as the rollback slot.
`xray.service` has been inactive since 2026-08-30 and was not touched. Backup pins (schema 16) are unchanged.

Web package `0.1.0-de9be41d71e0` was published right after the switch (`scripts/publish-web-app.sh` with
`WEB_PUBLISH_VERIFY_PUBLIC=1`, previous `0.1.0-22d05ddfc1e2`). Android 0.1.141 (code 142) was published through
`scripts/android-release-train.mjs` in the same window. Rollback: `--operation rollback` with the 0.4.19 bundle
(`/secure-input/hermes-go/gateway-0.4.19-81d7d5d/`) as `targetArtifactManifest`, and
`scripts/publish-web-app.sh --rollback` for the Web package.

## Routine release: Gateway 0.4.21, session window 180 days (R5-F1; production complete 2026-09-23)

Owner-authorized (merge and deployment, 2026-09-23). Operator `claude-webapp`. The release carries one
behaviour change: the refresh window goes from 30 to 180 days, still rolling — every rotation restarts it
(#402). It followed two Web-only publishes the same morning: the cold-start session resume (#400, the actual
reason the owner kept being asked for an email code) and the no-page-zoom fix (#397). Database schema 16
unchanged, no migration, mode `email_sharing_components_web_push` preserved, so this is a routine R5-F1
release; rollback to 0.4.20 stays possible (same schema). All peer sessions were idle at the time, so no
other production work was in flight.

Artifacts from `Gateway OCI` run 35811630564 on `main 09499b505ce7` (the version bump, #403): Gateway
`0.4.21-09499b505ce7` (archive SHA-256 `b56c941da431d3e997f055a6f16e26c17c4ac33b68a4cc8905992faa427ea89d`) and
operator bundle `Hermes-R5D-Ops-09499b505ce7` (SHA-256
`81f983a7b3a4386c877fac33f1853be5a5b0b22a143a525fb6c90f6ab7595a8a`), both verified locally, re-hashed on the
host after transfer, and the operator bundle verified again from its extracted copy at
`/opt/hermes-go-ops/09499b505ce7ed2bb18793d4ba262ed4621dac6d`. Bundles live in
`/secure-input/hermes-go/gateway-0.4.21-09499b50/` (root `0600`). `production-release.json` was retargeted and
its operator set to `claude-webapp` (previous copy `production-release-2b8cbc5c6f2f.json`).

Run `e61a3f37-7e1b-4e61-8cb8-3f3e8151784e` (`production-deploy`) committed: `activeSlot: blue`,
`previousSlot: green`, `preparedStage: candidate_verified`, `current` → `releases/0.4.21-09499b505ce7`,
`previous` → `releases/0.4.20-2b8cbc5c6f2f`. Independent checks afterwards: public capabilities
`server.version` 0.4.21 with `push: {"providers":["fcm"]}` and `webDeviceFeatures` unchanged; `/app/` and
`/account` 200; an unauthenticated `GET /v2/devices/<id>/ws` upgrade still 401; loopback `/readyz` 200
(migrations ok); Docker healthy with zero restarts and no warn-or-worse lines; both account Connectors
reconnected within a second of the switch (02:54:52Z and 02:54:53Z); green inactive as the rollback slot;
the retired `hermes-remote-gateway` unit inactive; nginx and DERP active. The short-lived `mac-mini` legacy
Connector in the log is the release's own public smoke, the same pattern the previous slot shows.
`xray.service` has been inactive since 2026-08-30 and was not touched. Backup pins (schema 16) are unchanged.

Web package `0.1.0-14e3405d5aaa` was published before the Gateway switch (`scripts/publish-web-app.sh` with
`WEB_PUBLISH_VERIFY_PUBLIC=1`, previous `0.1.0-de9be41d71e0`); it is independent of the Gateway version.
Rollback: `--operation rollback` with the 0.4.20 bundle
(`/secure-input/hermes-go/gateway-0.4.20-2b8cbc5c/`) as `targetArtifactManifest`, and
`scripts/publish-web-app.sh --rollback` for the Web package. Sessions issued before the switch keep their
30-day expiry until their next rotation.

## Routine release: Gateway 0.4.23, HG-120–122 diagnostics (R5-F1; production complete 2026-09-24)

Owner-authorized merge and release. The change adds cross-end request correlation and timing diagnostics;
the database remains at schema 16, the release contract and minimum clients are unchanged, and no Web
package was published. PR #419 merged the behavior, and red-light version PR #420 allocated Gateway
0.4.23 alongside Connector 0.1.9 and Desktop 0.2.27. This Gateway deployment does not itself publish a
signed Desktop or Android artifact.

The matching successful `Gateway OCI` run 35980031711 on `main 6420120b6712` supplied Gateway
`0.4.23-6420120b6712` (archive SHA-256
`cee92ef5e5f5575871e63cbe50f5982bc22de5fcb5c24aeba490b6f731d770c1`) and operator bundle
`Hermes-R5D-Ops-6420120b6712` (SHA-256
`35372a7a265fbbbbd4784f6b02660199b91aba095dd2b26afd666a3a713a4605`). Both manifests and
archives were verified locally, re-hashed on the host, and the operator bundle was verified again from
the extracted copy at `/opt/hermes-go-ops/6420120b67125651baa8bd644141be7d33e89e10`. The new
private bundle is at `/secure-input/hermes-go/gateway-0.4.23-6420120b/`; a separate release config
pins it without overwriting the prior config.

R5-F1 run `9c4977ab-7375-4535-b454-d84e82d384bf` committed with `activeSlot: blue`,
`previousSlot: green`, `preparedStage: candidate_verified`, `current` →
`releases/0.4.23-6420120b6712`, and `previous` → `releases/0.4.22-566538d87e38`. Independent
checks found public capabilities `server.version` 0.4.23 and unchanged client minima, `/account` and
`/app/` 200, `/relay-health` healthy, loopback `/readyz` 200, the blue container healthy with zero
restarts, no warning-or-worse blue unit journal lines in the five-minute window, green inactive,
and `nginx -t` successful. The previous 0.4.22 bundle and slot remain available for the R5-F1
rollback operation; no rollback was needed.

## Routine release: Gateway 0.4.24, HG-140 tunnel-revalidation fix (R5-F1; production complete 2026-09-26)

Owner-authorized merge and release. The change stops the 5-second account-tunnel revalidation from
presenting transient failures (a Postgres hiccup, a Mac connector blink) to the phone as
`4403 "account authorization changed"`: authorization and binding end-states still close 4403
immediately, configuration errors close 1013, transient failures are tolerated for three
consecutive ticks (~15 s) and audited as `app.tunnel.revalidation_failed` /
`app.tunnel.revalidation_exhausted` (failureKind, accountErrorCode, underlying error — the old code
swallowed the error), then closed as `1013 "account service unavailable"`. The access-revocation
bus path is unchanged. No release-contract, minimum-clients, or database change; no Web package
was published.

PR #445 (merge `8ba80163`) carried the behavior; red-light version PR #447 allocated Gateway
0.4.24 (merge `0fb01cc9`) so the public `server.version` distinguishes the fix from
0.4.23-6420120b. The matching successful `Gateway OCI` run 36237632257 on `main 0fb01cc9` supplied
Gateway `0.4.24-0fb01cc91f20` (archive SHA-256
`9c3f54c0ff038b17a3973bde4a1e698a8ac514351040b527b9037ae1fb0f28f3`) and operator bundle
`Hermes-R5D-Ops-0fb01cc91f20` (SHA-256
`b5fd2b2c434f5d28c9c4c396449651eef7894cd785884be48e221f72f8ede797`). Both manifests and archives
were verified locally, re-hashed on the host after transfer, and the operator bundle verified
again from the extracted copy at
`/opt/hermes-go-ops/0fb01cc91f201ed20fdc6b6ba1f388242af527d0`. The private Gateway bundle is at
`/secure-input/hermes-go/gateway-0.4.24-0fb01cc9/`; the release config is
`production-release-hg140.json` (copied from the hg120-122 config, operator `claude-hg140`).

R5-F1 run `00a9c81a-bc47-4d1d-a9cf-a9c2231330ca` committed with `activeSlot: green`,
`previousSlot: blue`, `preparedStage: candidate_verified`, `current` →
`releases/0.4.24-0fb01cc91f20`, `previous` → `releases/0.4.23-6420120b6712`. Independent checks
found public capabilities `server.version` 0.4.24 with unchanged client minima, `/account` and
`/app/` 200, `/relay-health` healthy with the expected retired-Legacy `connectors: 0`, loopback
`/readyz` 200, the green container healthy with zero restarts, no warning-or-worse green unit
journal lines, blue inactive (exit 0), and `nginx -t` successful. The 0.4.23 bundle and blue slot
remain available for the R5-F1 rollback operation; no rollback was needed.

## Edge JSON compression (2026-09-07, authorized)

Nothing on the path compressed anything. Hermes returns no `Content-Encoding` even when asked for gzip, the
Gateway's `selectResponseHeaders` forwards only four headers (`content-encoding` is not one of them), and the edge
had `gzip on` from `nginx.conf` with `gzip_types` and `gzip_proxied` still commented out — so the default
`gzip_types text/html` and `gzip_proxied off` meant `application/json` was never compressed. The cross-profile
session list (`?limit=500`, 195 sessions) had grown from 204 KB on 08-30 to 302 KB and is re-fetched on the
sessions screen's 250 ms / 1.25 s / 3 s refresh ladder; on 09-06 it alone accounted for 40.6 MB across 139
requests, 71% of the app's non-APK traffic.

Five directives were added at the top of `location ^~ /api/` in `/etc/nginx/conf.d/hermes-edge.conf`:

```nginx
gzip_types application/json;
gzip_proxied any;
gzip_vary on;
gzip_min_length 1024;
gzip_comp_level 6;
```

**Only `application/json`, and never a streaming type.** gzip makes Nginx buffer a chunked response until it ends:
a 5-chunk `application/json` stream that arrived at 202/403/603/804/1005 ms uncompressed arrived as a single
block at 1006 ms once compressed. `text/event-stream` is unaffected *because it is not listed* — it kept arriving
incrementally under the same config. The Gateway has no SSE or chunked-JSON endpoint today (`/api/mobile/events`
returns immediately; chat streams over WebSocket), so nothing regressed, but adding a streaming content-type here
later would silently destroy incremental delivery.

`/releases` is deliberately left out so the APK verification chain is untouched: `UpdateRepository` checks the
downloaded file's size and SHA-256 against the signed manifest, and `scripts/publish-android-apk.sh` fetches
`index.json` with curl. The cost is that `index.json` (117,961 B) is now the largest uncompressed item in a cold
start. Compressing it needs its own evaluation against `APP_UPDATE.md`, not a widened `gzip_types`.

Verified before the change on an isolated Nginx 1.28.3 on the same host (loopback ports, own prefix and pid, a
Node origin serving the real payloads; production never touched): JSON compressed and byte-identical after
decoding, binary artifacts and sub-1 KB bodies untouched, a client that sends no `Accept-Encoding` served
identical bytes, the `/api/ws` upgrade still `101` with a valid `Sec-WebSocket-Accept` and its frame intact, and
200 × 305 KB costing 0.84 s of worker CPU (4.2 ms/request). A control server proved the directives do not escape
the location: it inherits `gzip on` but, without `gzip_types`, still served uncompressed with `Content-Length`
intact — which is why `missiongo.mrlgs.net` and the release site are unaffected.

Applied at 22:23 with `nginx -t` then `systemctl reload nginx` (never `restart`): `worker_shutdown_timeout` is
unset, so old workers keep existing connections until they close and the Connector's `/v1/connect` tunnel and the
app's `/api/ws` both survived — `/relay-health` still reported `connectors: 1` with `mac-mini` online, and the
green container stayed up with zero restarts. DERP (`derper`, ports 8443 and 80) and `missiongo.conf` are on
different ports and a different server block and were not involved.

The site file's SHA-256 is now `4c67d49d…`, superseding the `422182b2…` recorded in the R5-F1 run above; the
pre-change bytes are kept at `/root/hermes-edge.conf.bak-20260907-2213`. This is expected: R5-F1 never rewrites
the site file — it only moves the upstream include — and its gate is the semantic
`satisfiesProductionNginxContract`, re-checked after the edit (one upstream include, exact `server_name`,
`proxy_pass http://hermes_go_gateway_production`, no 8444 proxy). The per-run hash in
`journal.checkpoint.nginxConfigSha256` is captured from the live file at the start of each run
(`ops/lib/deploy.mjs`), so it guards against the file changing *during* a release, not across releases. Do not
edit this file while a release is in flight; check `deploy-state.json` reports `committed` first. Rollback:

```bash
sudo cp -a /root/hermes-edge.conf.bak-20260907-2213 /etc/nginx/conf.d/hermes-edge.conf \
  && sudo nginx -t && sudo systemctl reload nginx
```

Production measurements, same endpoints, before and after (Nginx `body_bytes_sent`):

| Endpoint | Before | After | |
|---|---:|---:|---|
| `/api/profiles/sessions` | 314,219 | 26,969 | 11.7× |
| `/api/messaging/platforms` | 49,170 | 8,727 | 5.6× |
| `/api/cron/jobs` | 39,346 | 13,392 | 2.9× |
| `/api/config` | 28,368 | 10,325 | 2.7× |
| `/api/analytics/usage` | 27,088 | 5,542 | 4.9× |
| `/api/model/options` | 8,688 | 1,516 | 5.7× |
| `/api/sessions/<id>/messages` | 503,620 | 146,297 | 3.4× |
| `/api/status` | 1,464 | 671 | 2.2× |
| `/releases/index.json` | 117,961 | 117,961 | excluded by design |

A cold start that moved ~862 KB now moves ~200 KB, 118 KB of which is the untouched `index.json`. `/api/ws` and
`/api/mobile/events` are not compressed — a WebSocket is a `101` upgrade and an empty poll body is 77 B, below
`gzip_min_length`; their access-log numbers move for unrelated reasons and must not be read as a compression
ratio. The owner confirmed the app renders normally after a cold start and a session open.

After account mode starts, query `GET /internal/account-retention` only through the protected
loopback operations path. The first attempt may remain null for 60 seconds. Thereafter, alert on
`lastFailureAt` newer than `lastSuccessAt` (`HR-OPS-019`) or on a missing success across more than two
six-hour intervals. Deleted totals are process-local and may reset after a restart; they are evidence
of activity, not durable accounting. Do not publish this endpoint through Nginx or treat a cleanup
failure as authorization to restart/deploy—the scheduler preserves login availability and retries.

During an account-Connector migration, query `GET /internal/account-connectors` through the same
protected loopback path and internal Bearer token. Before stopping legacy, record
`legacyOnline >= 1`; after the Desktop reports `account_active`, require the intended binding UUID,
device ID, and generation in `connectors`, with `accountOnline >= 1`. `connectedAt` identifies the
current process-local WebSocket registration and resets after reconnect or Gateway restart. Pair it
with the signed-in account's `/v2/devices` `lastSeenAt` and end-to-end health; neither the public
`/relay-health` legacy count nor this live snapshot alone proves Android REST/WebSocket traffic.
The endpoint is read-only and does not authorize a restart, migration, or production deployment.

## HG-104 payload compression and edge timing (applied to production 2026-09-23)

**Status: applied to production on 2026-09-23, owner-authorized, alongside the Gateway 0.4.20 release.**
The timing `log_format` went in first (`/etc/nginx/conf.d/hermes-edge.conf`, backup
`/root/hermes-edge.conf.bak-20260923-0922`, new site-file SHA-256
`ecc23e5f2e174d96772143bacda068d38e93c2e7f9a062a90eb5570d4ef87c66`, superseding `4c67d49d…`), then the
release, then the device-API gzip (`/etc/hermes-go/account/binding-routes.conf`, backup
`/root/binding-routes.conf.bak-20260923-0927`, live file now hashes
`8fee179adbbb73cead70ccfaa87119645648af3d546afa0db3581129e0ff5b21` — the rollout-preflight mismatch described
below is therefore resolved). The steps below remain the procedure; re-read them before repeating any of it,
and note that a source merge or a new operator bundle still does not authorize a further application.

Pre-change baseline from `/var/log/nginx/hermes-edge.access.log` on 2026-09-23 between 00:36 and 09:27 CST:
`/v2/devices/*/api/profiles/sessions` 112 requests, 50,961,500 bytes, about 455 KiB each;
`/v2/devices/*/api/sessions/*/messages` 15 requests, 7,976,061 bytes, about 532 KiB each. The same window after
the change, taken from `/var/log/nginx/hermes-edge.timing.log`, is the after side of the comparison.

The 2026-09-07 edge compression covers only `location ^~ /api/` in the hand-maintained site file.
Account-mode clients (the Web app, and Android in account mode) read the same Hermes JSON through
`/v2/devices/<id>/api/…`, which lives in the rollout-managed `/etc/hermes-go/account/binding-routes.conf`
and had no gzip — so the session list and every history page crossed the edge uncompressed on that
path. HG-104 changes three things:

1. **Device API gzip (edge).** `renderMultiDeviceNginxRoutes()` now puts the same five directives as
   the `/api/` block (`gzip_types application/json; gzip_proxied any; gzip_vary on;
   gzip_min_length 1024; gzip_comp_level 6;`) inside `location ~ ^/v2/devices/[^/]+/api(?:/|$)` and
   nowhere else. The `/v2/devices/<id>/ws`, `/v2/connect` and binding/selection locations are
   unchanged. The same streaming caveat as above applies: only `application/json` is listed, so a
   streamed download (`application/octet-stream`, the only streamed type the tunnel carries today)
   still arrives incrementally. The rendered file's SHA-256 moves from
   `56d7ea3c24eee59176b279a939dd77ce0e908771a8171596a8812fae00158494` (live since R5-F4) to
   `8fee179adbbb73cead70ccfaa87119645648af3d546afa0db3581129e0ff5b21`; the only difference is the
   seven added lines (a two-line comment and the five directives).
2. **Connector hop deflate (Gateway).** The WebSocketServers behind `/v1/connect` and `/v2/connect`
   now negotiate `permessage-deflate` (threshold 1024 B, level 6, `memLevel` 7, both
   no-context-takeover flags so no per-connection zlib window outlives a message). The app-facing
   `/api/ws` and `/v2/devices/<id>/ws` explicitly do not. Tunnelled REST bodies travel base64-encoded
   inside JSON frames, so this is the hop where the Mac's uplink pays for every history page. The
   Connector's `ws` client offers the extension by default, so no Connector or Desktop release is
   needed; a client that does not offer it keeps working uncompressed. `maxPayload` still bounds the
   *inflated* size. This ships with the next Gateway image through the routine R5-F1 release and is
   rolled back with it; it involves no Nginx change (Nginx passes `Sec-WebSocket-Extensions`
   through).
3. **`http.tunnel` log fields (Gateway).** Each line now carries `status` (for a stream, the status
   of `response.start`, kept even if the stream later fails), `bytes` (decoded body bytes handed to
   the client, before edge compression), `chunks` (0 for a buffered response), `ttfbMs` (forward to
   the Connector's first response message) and `durationMs`; client aborts and out-of-order chunks
   are now logged too (`outcome` `client_aborted` / `error:invalid_response_chunk_sequence`).

### Why no rollout command re-applies the route file

`binding-routes.conf` is written only by R5-F3 (`renderBindingNginxRoutes`) and R5-F4
(`renderMultiDeviceNginxRoutes`), and R5-F4 admits only from the single-device `email_binding` state —
production left it on 2026-09-13, so re-running it fails preflight by design. Every later rollout
(R5-F5-A identity/Web, R5-F5-B sharing, R5-F7 Web app, R5-F9 push) only *checks* that the live file is
byte-identical to `renderMultiDeviceNginxRoutes()` of its own bundle. Two consequences:

- Applying gzip to production is a hand edit under the deployment lock, like the 2026-09-07 site-file
  edit, installing exactly the renderer's output.
- Until that edit is made, any of those rollouts run from an operator bundle built at or after this
  change stops in preflight with its `*_previous_routes_invalid` / `*_binding_routes_invalid` code
  (the live file still hashes `56d7ea3c…`). Routine R5-F1 releases and the R5-F8 schema release do not
  read this file and are unaffected. `satisfiesProductionNginxContract` checks only the site file and
  is unaffected either way.

### Applying the device-API gzip (only after explicit owner authorization)

From the extracted operator bundle of the commit that carries this change (the same
`/opt/hermes-go-ops/<commit>` layout the rollouts run from), with `deploy-state.json` reporting
`committed` and no release in flight, under the deployment lock:

```bash
cd /opt/hermes-go-ops/<commit>
node --input-type=module -e 'import { renderMultiDeviceNginxRoutes } from "./ops/lib/production-multi-device-rollout.mjs"; process.stdout.write(renderMultiDeviceNginxRoutes());' > /root/binding-routes.conf.hg104
sha256sum /etc/hermes-go/account/binding-routes.conf /root/binding-routes.conf.hg104
diff -u /etc/hermes-go/account/binding-routes.conf /root/binding-routes.conf.hg104
```

Proceed only if the live file hashes `56d7ea3c…`, the new one `8fee179a…`, and the diff is exactly
the seven added lines inside the `/api(?:/|$)` location. Then:

```bash
sudo cp -a /etc/hermes-go/account/binding-routes.conf /root/binding-routes.conf.bak-<yyyymmdd-hhmm>
sudo install -m 0644 -o root -g root /root/binding-routes.conf.hg104 /etc/hermes-go/account/binding-routes.conf
sudo nginx -t && sudo systemctl reload nginx
```

Always `reload`, never `restart` (see the 2026-09-07 note on `worker_shutdown_timeout`): existing
Connector tunnels and app sockets survive. Rollback is the same three lines with the backup as the
source. Afterwards verify with the commands below and record the new hash here.

### Recommended edge timing log (hand-maintained site file)

The existing `hermes_edge` format logs `$uri` (no query string) and no timings, so page sizes
(`limit`/`offset`), compression ratio and latency cannot be read back from it. Add a second format
and a second `access_log` next to the existing one in `/etc/nginx/conf.d/hermes-edge.conf` (same
backup / `nginx -t` / `reload` procedure as the 2026-09-07 edit; the existing log is unchanged):

```nginx
# Legacy clients may still put their app token in the /api/ws query string
# (app-websocket-authorizer.ts accepts ?token=); never log such a query.
map $request_uri $hermes_log_uri {
    ~*[?&]token=  $uri;
    default       $request_uri;
}

log_format hermes_edge_timing '$time_iso8601 $status $request_method $hermes_log_uri '
                              '$body_bytes_sent $request_time $upstream_response_time $gzip_ratio';
```

```nginx
    # inside the mrlgs.net server block, beside the existing access_log line
    access_log /var/log/nginx/hermes-edge.timing.log hermes_edge_timing;
```

Fields are space-separated in a fixed order: 1 time, 2 status, 3 method, 4 URI with query,
5 `body_bytes_sent`, 6 `request_time`, 7 `upstream_response_time`, 8 `gzip_ratio` (`-` when the
response was not compressed). The device routes in `binding-routes.conf` (and the other rollout
includes) declare no `access_log` of their own, so they inherit both server-level logs; no route file
needs changing. If a location ever declares its own `access_log`, it stops inheriting and must repeat
this line. The file sits under `/var/log/nginx/`; confirm the host's logrotate rule for
`/var/log/nginx/*.log` covers it before relying on it for long windows.

### Verification after applying

```bash
# JSON over the device API is compressed and varies on Accept-Encoding
curl -sS -o /dev/null -D - -H 'Accept-Encoding: gzip' -H 'Origin: https://mrlgs.net' -H "Cookie: <web session cookie>" \
  "https://mrlgs.net/v2/devices/<device-id>/api/sessions?limit=100" | grep -iE '^(HTTP|content-encoding|vary)'
# expect: 200, Content-Encoding: gzip, Vary: Accept-Encoding

# without Accept-Encoding: no Content-Encoding (identical decoded body)
curl -sS -o /dev/null -D - -H 'Origin: https://mrlgs.net' -H "Cookie: <web session cookie>" \
  "https://mrlgs.net/v2/devices/<device-id>/api/sessions?limit=100" | grep -iE '^(HTTP|content-encoding)'

# the device WebSocket location is untouched: the unauthenticated upgrade still answers 401
curl -sS -o /dev/null -w '%{http_code}\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H "Sec-WebSocket-Key: $(openssl rand -base64 16)" \
  "https://mrlgs.net/v2/devices/<device-id>/ws"
```

A cookie-authenticated device read must look same-origin (`Sec-Fetch-Site: same-origin`, or an exact
`Origin`), hence the `Origin` header. Keep the cookie out of shell history (read it from a root-only
file). Then open a conversation in the
Web app and send a prompt: the answer must still stream incrementally (the `/ws` path is not
compressed), and `/relay-health` / `/internal/account-connectors` must still show the Connector online.
For the Gateway half, after the R5-F1 release that carries it, the Connector must reconnect and stay
online; `docs/SMOKE_TEST.md` "HG-104" lists the client-side checks, including history paging.

### Measuring before and after

Apply the timing log first — it changes no response — and collect a baseline window (at least one
normal day) before the gzip edit and before the Gateway release; then collect the same window after.
Compare per endpoint class, not per request:

```bash
LOG=/var/log/nginx/hermes-edge.timing.log
# request count and total bytes for history pages on the device API
sudo awk '$4 ~ /^\/v2\/devices\/[^\/]+\/api\/sessions\/[^\/?]+\/messages/ {n++; b+=$5} END {print n, b}' "$LOG"
# P50 / P95 of request_time for the same class
sudo awk '$4 ~ /^\/v2\/devices\/[^\/]+\/api\/sessions\/[^\/?]+\/messages/ {print $6}' "$LOG" | sort -n \
  | awk '{a[NR]=$1} END {if (NR) print "n=" NR, "p50=" a[int((NR+1)*0.50)], "p95=" a[int((NR-1)*0.95)+1]}'
# median compression ratio actually achieved
sudo awk '$4 ~ /^\/v2\/devices\/.*\/api\// && $8 != "-" {print $8}' "$LOG" | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}'
```

Repeat with `/api/sessions\?`, `/api/profiles/sessions` and the legacy `/api/sessions/<id>/messages`
patterns. Read the numbers together with the client change: the history request count *rises* with
paging (one request per 100 rows scrolled) while bytes per request and P95 fall, so compare bytes and
latency to first render, not request count alone. `request_time` minus `upstream_response_time` is
the edge-to-client transfer; the Gateway's `http.tunnel` `ttfbMs` and `durationMs` split the upstream
part into Mac response time and tunnel transfer. For weak networks, measure in the Web app with the
browser's network throttling (for example Chrome DevTools "Slow 4G" / "3G"): record the Network
panel's transferred vs resource size for the history request and the time from opening a long
conversation to the first rendered message, before and after, on the same conversation.
