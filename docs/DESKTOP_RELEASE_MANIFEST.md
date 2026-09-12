# Desktop managed-release contract

Status: local E4-D implementation contract. No production release key, artifact, endpoint, or rollout
flag is configured by this document.

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
Desktop preserves during migration. The token file is generated locally at mode `0600`, never enters
a manifest or Cloud request, and neither LaunchAgent contains its value. Readiness is the exact line
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

The manifest lifetime is at most 30 days. Platform, channel, architecture, minimum macOS, artifact
origin, semantic versions, filenames, entrypoints, sizes, and the exact two required components are
validated before use. Artifact URLs must remain on the configured origin and have no credentials,
query, fragment, encoded slash, or traversal segment.

## Key custody and rotation

- Release private keys never ship in the repository, Desktop bundle, Gateway host, artifact host, or
  logs. They belong in an access-controlled signing system used only by the release publisher.
- Desktop bundles only raw 32-byte Ed25519 public keys indexed by key ID.
- Rotation ships a Desktop version containing both old and new public keys before the publisher
  starts signing with the new key. After an observation window, a later Desktop version removes the
  retired key.
- Unknown keys, invalid signatures, expired manifests, incompatible releases, and any unknown JSON
  field fail closed.
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
is bounded to 64 KiB and unsafe/symlinked logs fail closed. A pre-commit failure stops Connector then
Hermes, restores the exact legacy LaunchAgent when applicable, restores the previous managed pointer,
and records the safe terminal state. An ambiguous remote commit stops both managed services and
enters manual attention without guessing that legacy should become authoritative.

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
