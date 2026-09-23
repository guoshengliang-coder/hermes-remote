# Desktop managed-release contract

Status: local E4-D schema-v1 and componentized C3 schema-v2 implementation contract. No production
release key, artifact, endpoint, or rollout flag is configured by this document.

## Trust boundary

Hermes Go Desktop accepts a managed install only through this sequence:

1. download an HTTPS manifest without following redirects and cap it at 256 KiB;
2. verify the Ed25519 signature over the exact decoded payload bytes with a pinned public key;
3. strictly decode the signed payload and reject unknown fields;
4. download each artifact without redirects, with its signed size as a streaming upper bound;
5. verify the exact filename, byte size, and streaming SHA-256 digest;
6. list the tar archive before extraction and reject absolute/traversing paths, duplicate normalized
   paths, symlinks, hard links, devices, FIFOs, and other non-file/non-directory members; both the
   verbose listing and member count stay bounded at 16 MiB and 65,536 entries so a real Python runtime
   fits without permitting an unbounded archive walk;
7. extract into a new permission-restricted staging directory, validate the executable entrypoint,
   then move the complete release into its immutable version directory;
8. activate with one atomic `current` symlink replacement.

`DesktopReleaseAcquirer` now enforces steps 1–7 as one ordered preparation boundary: it creates an
exclusive UUID workspace and private download/extraction roots before fixing the artifact-verifier
path, processes the required Hermes Server and Connector components in canonical order, and removes
the entire run workspace if any download, digest, archive, or completeness check fails. A successful
result is still inert extracted input; only `DesktopMigrationCoordinator` may stage and activate it
after the separate user-confirmed binding transition.

`DesktopManagedBootstrapExecutor` is the single core handoff between those two boundaries. `prepare`
performs acquisition only and returns an executor-issued identity, exact signed version, and exact
release-specific confirmation text. It cannot invoke migration. `commit` accepts only that same
preparation and confirmation, derives both LaunchAgent executable paths from the verified manifest,
and then passes the exact manifest/sources to migration. Cancel and terminal paths attempt to discard
the workspace. If migration and cleanup both fail, the residual workspace is explicit. If migration
already committed, cleanup failure retains a cleanup-only retry and reports `HR-MIGRATE-005` instead
of pretending that the active Connector rolled back or offering another install.

For an active installation, preparation records an upgrade intent only when the signed release is a
strict semantic-version increase. Commit routes that intent to the coordinator's upgrade transaction;
it does not call binding creation, binding confirmation, credential writing, or session-token creation.
The schema-v2 executor applies the same rule to its verifier-issued component manifest. The version
shown before download for schema-v1 is only a hint parsed from the canonical manifest filename; the
downloaded signed manifest remains authoritative and is compared again before mutation.

A TLS response alone is never an install authorization. The signature and artifact hashes are the
authorization. Desktop never executes an unverified download or extracts into a legacy/Hermes path.

## Packaged enablement and Hermes runtime contract

The packaged app is default-off. `DesktopManagedBootstrapConfiguration` becomes valid only when all
of these build/runtime values are present and valid together:

- `HERMES_GO_MANAGED_BOOTSTRAP_ENABLED=1`;
- an exact HTTPS manifest URL and exact HTTPS artifact origin;
- release channel and supported architecture;
- one pinned key ID plus its canonical unpadded-base64url 32-byte Ed25519 public key;
- `HERMES_GO_DESKTOP_HERMES_RUNTIME_CONTRACT=hermes-serve-v1`.

Gateway must independently advertise `desktopBootstrap.runtimeContract=hermes-serve-v1`, behind
`ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED=1` and account binding. Missing configuration, an invalid
key/URL, an absent server capability, or a contract mismatch closes the install gate before any
download or machine mutation.

The schema-v2 component path has an additional independent capability. Gateway advertises
`desktopBootstrap.componentManifestSchemaVersion=2` only behind the default-off
`ACCOUNT_DESKTOP_COMPONENT_INSTALL_ENABLED=1` flag, which requires the managed-install gate above.
Desktop requires its valid local component-preflight configuration, that exact schema value, and the
same `hermes-serve-v1` contract before treating the component bootstrap runtime as available. The
existing schema-v1 capability alone can never authorize component download or migration.

After those gates pass, Desktop's preflight uses the composed component runtime and retains the exact
verifier-issued schema-v2 token only in memory beside the matching presentation result. A later
preparation action must consume that token directly; it may not reconstruct authority from the release
version, component rows, URLs, or byte counts. Refresh failure, capability withdrawal, and sign-out
discard both the token and its presentation.

The component UI may call `prepare` only with that retained token and only after a fresh Cloud
capability check plus the clean-machine/legacy-service preflight. Preparation downloads and verifies
missing bootstrap components in its private UUID workspace without changing services. The native
confirmation passes the executor's exact release-specific text; immediately before `commit`, Desktop
refreshes the capability and machine preflight again. Schema-v1 and schema-v2 operations are mutually
exclusive. A cleanup failure retains only the executor-issued preparation or exact interrupted run ID
for cleanup retry and never reconstructs another install request.

The packaged Account & Devices surface uses the same split: “下载并验证安装包” is preparation, and a
second native sheet displays the exact signed version before “安装并连接” can commit. The clean-Mac
preflight runs again immediately before commit. Any responder on reserved loopback port 9119,
including an authentication response, blocks the action. On restart, only an `account_active` journal
plus both exact managed LaunchAgents is treated as installed; intermediate state enters recovery and
mismatches fail closed before another installation.

`hermes-serve-v1` freezes the current official headless interface as
`hermes serve --host 127.0.0.1 --port 9119`. The managed launcher supplies
`HERMES_HOME=<absolute non-root path>`, `HERMES_DESKTOP=1`, and the path of one installation-local
session-token file. Its signed wrapper validates that the file is regular, current-user owned,
private, bounded, and canonical before exporting the value to Hermes; the Connector validates and
reads the same file for REST headers and the `/api/ws?token=` handshake. Both readers accept the
generated 43-character base64url format and the historical 64-character lowercase-hex format that
Desktop preserves during migration. Component packaging executes the staged Connector reader against
both accepted formats and an uppercase-hex rejection before creating its archive. The token file is
generated locally at mode `0600`, never enters a manifest or Cloud request, and neither LaunchAgent
contains its value. Readiness is the exact line
`HERMES_BACKEND_READY port=9119`; the distinct
port collision line is `BACKEND_PORT_IN_USE port=9119`. No provider/model secret belongs in the
LaunchAgent: Hermes continues reading its profile-scoped state and private `.env` beneath
`HERMES_HOME`. This contract follows the official
[Hermes CLI reference](https://github.com/nousresearch/hermes-agent/blob/main/website/docs/reference/cli-commands.md),
[Desktop guide](https://github.com/nousresearch/hermes-agent/blob/main/website/docs/user-guide/desktop.md),
and [backend readiness parser](https://github.com/NousResearch/hermes-agent/blob/main/apps/desktop/electron/backend-ready.ts).

Managed release 0.3.1 is the first published immutable release that satisfies this token-file
contract on both components. The historical 0.3.0 Connector accepts only `HERMES_SESSION_TOKEN` and
must retain its inline LaunchAgent value; Desktop must not infer current-runtime support merely from
an embedded manifest URL that points to a newer release.

### Import path: the bundle must not depend on PYTHONPATH

The staged `hermes_server` component keeps the Hermes sources under `app/` and its dependencies
under `runtime/site-packages/`, and `bin/hermes-server` puts both on `PYTHONPATH`. That is enough
for the process the launcher starts and **not** enough for the processes that process starts.

Hermes spawns children — the slash worker among them — through
`tools/environments/local.py`, which deliberately strips the Hermes repo root back out of the
child's `PYTHONPATH`. In the bundle `app/` is that repo root, so the child was left with no route to
`tui_gateway` at all: managed release 0.3.0 could not run a single slash command, and the Android
model picker, which applies a selection with `/model … --session`, failed every time (HG-28).

The bundle therefore also writes `_hermes_go_managed_paths.pth` into the interpreter's own
site-packages (`runtime/python/lib/python3.11/site-packages/`). `site` processes `.pth` files for
real site directories on every start of that interpreter, and no `PYTHONPATH` edit can remove them.
The line derives the bundle root from `sys.prefix` at run time — never a baked-in absolute path,
which would not survive extraction on another machine — and guards each entry with `isdir` so a
partially extracted bundle degrades instead of breaking every interpreter start.

**Constraint for anything added later:** if a child process must import it, it has to be reachable
without `PYTHONPATH`. `scripts/test/desktop-managed-python-path.test.mjs` holds that line, and
asserts the pre-fix failure first so it cannot pass for the wrong reason.

## Envelope

The UTF-8 JSON envelope has exactly four fields:

```json
{
  "algorithm": "Ed25519",
  "keyId": "desktop-release-2026-a",
  "payload": "<unpadded canonical base64url JSON bytes>",
  "signature": "<unpadded canonical base64url 64-byte signature>"
}
```

`payload` is signed byte-for-byte. Re-encoding, whitespace normalization, or signing a parsed object
is not allowed. Key IDs contain only ASCII letters, digits, hyphen, period, and underscore.

## Signed payload

The payload has exactly these fields:

```json
{
  "schemaVersion": 1,
  "releaseVersion": "1.2.3",
  "channel": "internal",
  "platform": "macos",
  "architecture": "arm64",
  "minimumMacOS": "14.0",
  "createdAt": "2026-09-07T00:00:00Z",
  "expiresAt": "2026-09-21T00:00:00Z",
  "artifacts": [
    {
      "component": "hermes_server",
      "version": "0.20.6",
      "fileName": "Hermes-Server-0.20.6-arm64.tar.gz",
      "entrypoint": "bin/hermes-server",
      "downloadURL": "https://downloads.example/desktop/releases/Hermes-Server-0.20.6-arm64.tar.gz",
      "sizeBytes": 123,
      "sha256": "<64 lowercase hex characters>"
    },
    {
      "component": "connector",
      "version": "0.2.0",
      "fileName": "Hermes-Connector-0.2.0-arm64.tar.gz",
      "entrypoint": "bin/hermes-connector",
      "downloadURL": "https://downloads.example/desktop/releases/Hermes-Connector-0.2.0-arm64.tar.gz",
      "sizeBytes": 456,
      "sha256": "<64 lowercase hex characters>"
    }
  ]
}
```

`createdAt` and `expiresAt` remain signed compatibility fields, and `expiresAt` must be later than
`createdAt`; Desktop no longer rejects an otherwise valid manifest because wall-clock time passed.
Stable, cache-disabled indexes provide discovery while Ed25519 remains the authorization boundary.
Platform, channel, architecture, minimum macOS, artifact origin, semantic versions, filenames,
entrypoints, sizes, and the exact required components are validated before use. Artifact URLs must
remain on the configured origin and have no credentials, query, fragment, encoded slash, or traversal
segment.

## Key custody and rotation

- Release private keys never ship in the repository, Desktop bundle, Gateway host, artifact host, or
  logs. They exist only as secrets of the protected `desktop-release` GitHub environment and are
  exposed only to its approved tag-triggered publishing step.
- Desktop bundles only raw 32-byte Ed25519 public keys indexed by key ID.
- Rotation ships a Desktop version containing both old and new public keys before the publisher
  starts signing with the new key. After an observation window, a later Desktop version removes the
  retired key.
- Unknown keys, invalid signatures, incompatible releases, and any unknown JSON field fail closed.
- Developer ID signing/notarization of the Desktop app and Ed25519 signing of component manifests are
  independent checks; a public release requires both.

## Local layout and rollback

The managed root contains immutable `releases/<version>` directories, a `current` relative symlink,
a private Connector credential file, a separate private Hermes session-token file, logs, staging,
and the migration journal. The account Connector
uses user LaunchAgent label `com.hermesgo.connector`; the legacy label remains
`com.hermesremote.connector`. The managed Hermes Server uses the separate exact label
`com.hermesgo.hermes-server`. Its plist executes only the signed Hermes entrypoint with the frozen
`serve` arguments and `HERMES_HOME`; it contains no provider or model secret and deliberately omits
`KeepAlive` so a port conflict cannot become an endless launchd restart loop.

Each fully staged release receives a private, strict managed-release marker before its final move. If
a candidate later rolls back, a same-version retry may replace that inactive directory only after a
new signed release has been completely staged and only when the old marker, ownership, permissions,
and version match. An active `current` target and any unmarked/unknown directory are never replaced.

Before any process mutation, Desktop records a run UUID, intended release, binding/generation, state,
and last-known-good mode. It refuses duplicate Connector states. Candidate proof and health precede
remote binding confirmation. The coordinator starts managed Hermes first and starts Connector only
after two independent checks pass: an exact `HERMES_BACKEND_READY port=9119` line appended after a
private-log checkpoint, and a healthy loopback HTTP probe. A stale marker or an unrelated process
already occupying port 9119 cannot satisfy both process-specific evidence requirements. New log data
is bounded to 64 KiB and unsafe/symlinked logs fail closed. When the Cloud binding is already
committed, the coordinator also records its server-provided `endToEnd.checkedAt` immediately before
starting Connector and requires a strictly newer healthy timestamp for the exact binding/generation.
The same rule protects restoration after a failed token-file migration, so cached booleans cannot
prove either the candidate or rollback Connector. A pre-commit failure stops Connector then
Hermes, restores the exact legacy LaunchAgent when applicable, restores the previous managed pointer,
and records the safe terminal state. An ambiguous remote commit stops both managed services and
enters manual attention without guessing that legacy should become authoritative.

An upgrade first stores the exact two existing LaunchAgent byte streams and the previous bundled
`current` target beneath the owner-only managed state directory. It then records an upgrade journal
whose last-known-good mode is `account`, while the snapshot retains both the previous and target
release layouts and the journal retains the exact binding.
After launchd unloads the old labels, a direct TCP loopback probe must observe the old listener
gone; label removal alone is not sufficient because a draining Hermes process can retain port 9119.
Only then may the new Hermes start. Successful commit requires new Hermes readiness and a strictly
newer Cloud health timestamp after the same Connector binding restarts. Before commit, any failure or
restart recovery stops the candidate, restores the snapshot and pointer, restarts the old Hermes then
Connector, proves the same binding healthy, and restores the old `account_active` journal. The private
snapshot is removed only after commit or proven rollback.

## Publication and rollout gates

### Offline packaging and verification

Build the two component inputs from clean, full-commit-pinned Hermes and Hermes GO sources before
signing. `desktop/Packaging/component-archives.example.json` documents the inputs. The Hermes builder
copies only an explicit source-directory/metadata allowlist, root Python modules, the selected Python
runtime, and its site-packages; it never copies `HERMES_HOME`, `.env`, Git data, tests, Node build
trees, or Desktop releases. The Connector builder carries the production JavaScript only, its two
runtime dependencies, and an architecture-matched Node executable. Both launchers resolve their
bundled runtimes relative to the signed release and do not depend on launchd `PATH`.

```bash
npm run desktop:components:package -- \
  --config /absolute/protected/path/component-archives.json \
  --output /absolute/empty/component-output
```

When only Connector changes, first verify the immutable Hermes Server archive against its current
signed manifest, then reuse that exact archive. Build the new bundled Connector alone from a clean
Hermes GO source with `desktop/Packaging/connector-only.example.json`:

```bash
npm run desktop:connector:package -- \
  --config /absolute/protected/path/connector-only.json \
  --output /absolute/empty/component-output
```

The signing input names both the verified existing Hermes Server archive and the new Connector
archive. The Connector-only command applies the same source, architecture, token, archive-safety,
and deterministic packaging gates as the two-component command.

The component gate refuses dirty or mismatched Git identities, mismatched semantic versions or Mach-O
architectures, symlink/special-file inputs, more than 65,536 staged entries, more than 2 GiB of staged
bytes, and existing targets. `BUILD-IDENTITY.json` inside each archive records only public component,
version, architecture, and full source commit data.

The repository provides an offline publisher for the signed envelope and its exact two archives. It
does not upload, deploy, enable flags, or alter a Mac. Start from
`desktop/Packaging/managed-release.example.json`, keep the real configuration and Ed25519 private key
outside the repository, and give the key file owner-only permissions (`0600`). Both source archives
must be absolute, regular, non-symlinked tar-gzip files with only files/directories and the declared
regular-file entrypoint. The publisher refuses existing output targets and removes only files it
created if a later gate fails.

```bash
npm run desktop:managed-release:package -- \
  --config /absolute/protected/path/publisher.json \
  --output /absolute/empty/output/directory
```

Success prints `DESKTOP_MANAGED_RELEASE_OK`, the manifest/artifact paths and hashes, and the derived
unpadded-base64url public key. The private key and its path are never printed. Before upload, verify
the copied files independently using only the public key printed by the packaging gate:

```bash
npm run desktop:managed-release:verify -- \
  --manifest /absolute/output/Hermes-Desktop-0.3.0-arm64.manifest.json \
  --artifacts /absolute/output \
  --key-id desktop-internal-2026-a \
  --public-key '<unpadded-base64url-public-key>' \
  --origin https://downloads.example \
  --channel internal \
  --architecture arm64
```

The verifier rechecks the Ed25519 signature over the exact payload bytes, strict field set, lifetime,
origin, archive names and entrypoints, byte sizes, and SHA-256 digests. Any publisher or verifier
failure is emitted as the bilingual, retryable `HR-RELEASE-004` diagnostic. The example values are
documentation placeholders and are not approved production identities.

Build schema-v2 inputs from `desktop/Packaging/component-archives-v2.example.json` with the separate
default-inert command:

```bash
npm run desktop:components-v2:package -- \
  --config /absolute/protected/path/component-archives-v2.json \
  --output /absolute/empty/component-output
```

It produces exactly two archives: `node_runtime` and `connector`. Connector depends on the exact Node
content identity, and Desktop supplies `HERMES_NODE_RUNTIME_ROOT` during activation. Hermes itself is
the owner's standard local installation and is never packaged in a new release. Success prints each
archive's size, byte SHA-256, normalized extracted-content SHA-256, entrypoint and dependencies. The
output identities can be copied directly into the schema-v2 publisher input.

Schema v2 uses separate commands and cannot enter the schema-v1 acquisition/install types. Start
from `desktop/Packaging/component-release-v2.example.json`. Each component input supplies the content
identity produced by the component builder; the publisher safely extracts the archive, normalizes the
declared entrypoint to owner-executable, and recomputes relative paths, file bytes and executable bits
before signing. A dependency names both its component kind and exact content identity. New manifests
are accepted for installation only when their bootstrap graph is exactly Node plus Connector.
Historical 0.4.0/0.4.1 fields and component kinds remain strictly decodable so their signed bytes can
still be inspected and verified, but the retired on-demand runtime is never activated.

```bash
npm run desktop:component-release:package -- \
  --config /absolute/protected/path/component-publisher.json \
  --output /absolute/empty/component-output

npm run desktop:component-release:verify -- \
  --manifest /absolute/component-output/Hermes-Desktop-Components-0.4.0-arm64.manifest.json \
  --artifacts /absolute/component-output \
  --key-id desktop-internal-2026-a \
  --public-key '<unpadded-base64url-public-key>' \
  --origin https://downloads.example \
  --channel internal \
  --architecture arm64
```

The independent verifier repeats signature, field, dependency, compressed-file and extracted-content
checks. Desktop maps the same verified entries directly to the component preflight model. Its v2
downloader keeps an owner-only partial file after an interrupted transfer, accepts a resumed response
only with the exact `206` and `Content-Range`, and re-reads the completed file for the full signed size
and SHA-256 before exposing the final archive name. These commands create and verify local candidates;
they do not replace the production schema-v1 endpoint.

After the four bootstrap archives have been committed, Desktop's v2 activation planner reopens their
receipts, recomputes every content identity, validates executable entrypoints and health probes, and
requires the exact builder dependency graph. It then derives Hermes and Connector LaunchAgents using
the immutable content-store paths and the signed Python/Node identities. The schema-v1 writer refuses
these runtime-root fields, while the schema-v2 writer accepts them only when their roots match the same
managed layout and content hashes, which it recomputes immediately before persisting the LaunchAgent.
This planner is not yet wired into the shipping install transaction.

The local v2 installer accepts a non-forgeable installation token produced by that strict verifier,
then orders safe download, extraction, immutable store commit, final activation validation, and release
reference publication. Network interruption keeps a private UUID workspace for exact-manifest resume;
an explicit cancel may remove that exact validly marked workspace, other failures remove it, and a
reference is never published for an incomplete activation plan. The returned plan remains preparation
input only and does not itself modify credentials, `current`, LaunchAgents, or running services.

Installed optional components receive a separate immutable
`capability-references/<release>/<kind>.json` reference only after the base release reference exists
and the exact managed content has been revalidated. Garbage collection validates and snapshots both
reference classes, keeps content shared by multiple releases, and refuses orphaned or malformed
capability-reference trees. This contract does not itself download or activate an optional component.

The local first-use installer accepts the same verifier-only token plus an exact signed
`onDemandTrigger`. It requires the release's bootstrap reference and revalidates the complete
bootstrap activation plan before host scanning or network access, then resolves the trigger's
optional dependency closure in topological order. Healthy managed content is reused, a compatible
system browser is path-validated again, and missing components use the resumable downloader, safe
extractor, immutable store, and capability references. Interrupted transport is resumable only from
a private UUID workspace bound to both manifest and trigger. This remains default-inert and returns
resolved paths without changing credentials, `current`, LaunchAgents, or running services.

The optional-runtime writer can combine the returned speech and document roots into a deterministic,
read-only `.pth` projection after probing the exact managed Python ABI. The LaunchAgent model exposes
that projection through `HERMES_LAZY_INSTALL_TARGET` and exposes a revalidated browser through
`AGENT_BROWSER_EXECUTABLE_PATH`, both of which upstream Hermes already understands. Existing
projections are accepted only when their ownership, permissions, file set, ABI, and paths match
exactly. This primitive remains default-inert: it does not persist the LaunchAgent or restart Hermes.

The default-inert activation coordinator applies that environment only while holding the migration
operation lease and only for the exact `account_active` base release with both managed services
loaded. It atomically replaces the Hermes LaunchAgent, restarts Hermes alone, and requires a fresh
readiness marker plus healthy loopback status. A failed activation restores the exact prior plist and
re-proves the old Hermes service after any stop; a successful activation permits one caller-supplied
capability retry. Its original interface required the complete active optional-component set so a
later trigger could not remove an earlier capability; the resolver below now owns that aggregation.

The default-inert active-component resolver supplies that aggregation boundary. It accepts the
verifier-only manifest token and the exact component closure returned for the current trigger, then
revalidates all managed capability references for that base release against signed identities,
owner-only receipts, full content hashes, executable entrypoints, and bounded health probes. A
currently configured external browser is retained only when its exact LaunchAgent path passes the
signed compatibility rule and a fresh allowlisted scan. The activation transaction owns the resolver
and calls it under the migration operation lease before preparing a replacement LaunchAgent; an
external caller can no longer supply an arbitrary active-component array. Production signal-to-trigger
wiring remains separate; the enclosing default-inert coordinator is described next.

The default-inert capability coordinator now supplies the enclosing transaction. Its input is a fixed
browser, speech, or document type; the corresponding trigger is read only from the verifier-backed
manifest. It installs that dependency closure, regenerates the exact bootstrap activation plan, then
hands the result to the locked resolver/activation path and one-shot retry. Unsupported manifest
capabilities, failed installs, and duplicate concurrent requests stop before LaunchAgent or service
mutation. Hermes 0.21.0 exposes no structured producer for this input, so no shipping request path is
wired and no error text is treated as a capability signal.

The component preflight presentation now has a default-inert trusted loading boundary. A fixed HTTPS
manifest URL is fetched with the existing bounded, no-redirect downloader; the exact envelope must
pass the schema-v2 Ed25519 verifier before the managed-store or external-environment scanner receives
anything. Failed downloads and signatures are inert, and overlapping refreshes are rejected. The
result remains a read-only preflight value: production configuration and install-flow wiring are
separate release work.

The packaged app now reserves a separate default-false `HermesGoComponentPreflightEnabled` gate and
an empty `HermesGoDesktopComponentManifestURL`. Enabling the gate requires a complete unambiguous HTTPS
v2 URL plus the already pinned release origin, channel, architecture, key ID, and Ed25519 public key;
every partial or malformed combination is invalid. The v1 manifest URL remains distinct, preventing a
schema-v1 bootstrap envelope from being routed into the component verifier.

Before that transaction, the local component preflight coordinator accepts the same verifier-only
token and combines rehashed managed-store candidates with allowlisted external observations. It emits
the per-component reuse/download/defer decisions and exact bootstrap/deferred byte totals. A matching
Python or Node version is never sufficient for reuse, and a managed component that exists but fails
its health probe blocks the plan instead of being mislabeled as safely replaceable.

A real release still requires all of the following outside this local implementation:

- provision and approve the release-signing key and pinned production public key;
- publish signed, versioned Hermes Server and Connector archives at the approved HTTPS origin;
- prove the packaged Hermes Server artifact actually satisfies `hermes-serve-v1`, preserves
  `HERMES_HOME` provider configuration, and passes cold-start/port-conflict/restart tests before
  exposing the packaged install action;
- Developer ID sign, notarize, and staple the Desktop application and executable payloads;
- wire the final manifest URL/channel into a release build;
- run clean-install, legacy migration, interrupted migration, upgrade, and rollback on every supported
  architecture/macOS combination;
- explicitly authorize staging flags, soak, production deployment, and later default enablement.

No source-code merge implicitly performs any of these operations.
