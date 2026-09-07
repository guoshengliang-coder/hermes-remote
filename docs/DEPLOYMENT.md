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
`connector.online` / `connector.offline`, `lifecycle.received` (with `lagMs` behind the Mac's
stamp), `lifecycle.served` / `lifecycle.acked`, and `http.tunnel` (method, path, status,
duration). Credential-shaped fields are never written; relayed frames are counted, not quoted.
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
after it) into production. R5-F1 adds `scripts/production-release.mjs`, carried by the operator bundle (manifest
schema 3, `releaseEntrypoint`). It takes a private configuration of the same shape as R5-D's (in production
`/secure-input/hermes-go/production-release.json`, see the run record below); only `targetArtifactManifest`
changes per release:

```bash
node scripts/production-release.mjs \
  --config /secure-input/hermes-go/production-release.json \
  --confirm production:<configured-hostname> \
  --operation deploy   # or: rollback
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
