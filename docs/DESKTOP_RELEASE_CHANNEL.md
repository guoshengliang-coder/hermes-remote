# Desktop release channel

Desktop release discovery uses two mutable, cache-disabled pointers:

- `/desktop/releases/index.json` for the Desktop application release envelope;
- `/desktop/components/index.json` for the Node + Connector component envelope.

Each pointer names one immutable, versioned manifest, including its byte length and SHA-256. The
pointer is discovery data only: Desktop checks its same-origin HTTPS path and identity, then still
requires the referenced envelope to pass strict Ed25519 verification. `createdAt` and `expiresAt`
remain signed compatibility fields, and `expiresAt` must be later than `createdAt`; neither field is
used as a wall-clock expiry gate.

## Release topology

New schema-v2 releases contain exactly two bootstrap components: `node_runtime` and `connector`.
Connector depends on the exact Node content hash. Hermes is the standard local installation under
the owner's `~/.hermes`; Desktop does not package a Python runtime, Hermes core, optional first-use
components, patches, or a schema baseline. Old 0.4.0/0.4.1 envelopes and their fields
(`installPhase`, `onDemandTrigger`) remain strictly decodable and signature-verifiable for legacy
inspection and rollback, but are not accepted as the topology for a new installation.

An active installation records its release layout. Ordinary semver upgrades stay within the same
layout. Moving from the historical bundled layout to the component store is a separate migration;
the reverse direction is recovery, not an ordinary update.

## Signing-key rotation

Packaged apps may carry `HERMES_GO_DESKTOP_RELEASE_SIGNING_KEYS`, a JSON object whose keys are key
IDs and whose values are canonical unpadded base64url Ed25519 public keys. The historical single-key
pair remains accepted when the JSON trust set is absent. The two forms must not be configured at the
same time.

Rotate keys in this order:

1. ship an app that trusts both the old and new public keys;
2. wait until that app is the supported floor;
3. sign new manifests with the new key ID;
4. ship a later app that removes the old key.

Never replace the old key and start signing with the new key in one release; older clients would
have no authenticated path forward.

## GitHub environment

The manually dispatched `desktop-app-release.yml` and `connector-release.yml` candidate workflows reference
the protected `desktop-release` environment. The environment must already exist. Configure a
required reviewer who owns Desktop releases and these component-publisher secrets:

- `DESKTOP_RELEASE_SIGNING_PRIVATE_KEY`
- `DESKTOP_RELEASE_SSH_PRIVATE_KEY`
- `DESKTOP_RELEASE_SSH_KNOWN_HOSTS`

The official Desktop DMG candidate job additionally needs these environment secrets:

- `DESKTOP_APP_SIGNING_P12_BASE64` and `DESKTOP_APP_SIGNING_P12_PASSWORD` (the Developer ID
  Application certificate and its export password);
- `DESKTOP_APP_SIGNING_IDENTITY` and `DESKTOP_APP_SIGNING_TEAM_ID`;
- `DESKTOP_APP_NOTARY_KEY_BASE64`, `DESKTOP_APP_NOTARY_KEY_ID`, and
  `DESKTOP_APP_NOTARY_ISSUER_ID` (App Store Connect **Team API key** notarization credentials).

Configure `DESKTOP_RELEASE_SIGNING_KEY_ID` and `DESKTOP_RELEASE_SIGNING_PUBLIC_KEY` as environment
variables, not private secrets. The public key must be the one trusted by the currently supported
Desktop app. A key rotation first ships an app trusting both keys, as described above.

Dispatch the candidate job from current `main` with the exact `desktop/Packaging/Info.plist` version.
Before touching the Apple certificate, it verifies that the configured Ed25519 public key validates
both live signed manifests and their index hashes; this blocks an accidental one-step key rotation.
It creates a fresh temporary keychain on a macOS arm64 runner, builds the DMG in one invocation,
submits it to Apple notarization, staples it, verifies the exact mounted app's Developer ID team,
bundle version/build and both stable index URLs, then uploads the notarized DMG as a short-lived
workflow artifact. An ad-hoc build cannot use the `official` DMG name. **This job does not publish
the DMG or switch the component indexes.** Clean-Mac launch acceptance, the signed component
manifests, public DMG upload/readback, and the paired-index publisher are separate release gates.
Do not mistake a green candidate job or a downloaded Actions artifact for a user-visible release.

The Connector candidate job runs from current `main` on macOS arm64 with exact Node 22.23.2. It
builds and tests the repository, checks `connector/package.json` against the dispatch input, then
creates the legacy schema-v1 Connector archive and the schema-v2 Node + Connector archives. Those
unsigned archives are uploaded only as short-lived Actions artifacts. They must still be signed into
both manifests, independently verified, staged behind exact public routes, and passed through the
paired-index publisher before they are a release. Neither candidate workflow is tag-triggered.

Repository CI and ordinary branch pushes must not receive these secrets. A workflow reference does
not create or protect an environment; a repository administrator must configure the reviewer and
secrets in GitHub settings.

## Publisher transaction

`scripts/publish-desktop-release.sh` requires a clean worktree at current `origin/main` and two
already packaged signed manifests. It uploads immutable version directories, downloads both public
copies and checks their byte hashes, replaces both indexes through `.next` files, reads the public
indexes back, preserves the previous pair for `--rollback`, restores it automatically on failure,
and only then creates and pushes the `desktop-managed-v<version>` tag. It requires
`DESKTOP_RELEASE_VERSION`, `DESKTOP_RELEASE_ARCHITECTURE`, `DESKTOP_RELEASE_CHANNEL`, both manifest
paths, `DESKTOP_RELEASE_SIGNING_KEY_ID`, `DESKTOP_RELEASE_SIGNING_PUBLIC_KEY`, the SSH target/root,
and the public HTTPS origin. The public key is used for local verification; the private key is never
passed to this upload step. Do not bypass the readback or use the script from a shared development
worktree.

For an existing root-owned static store with exact Nginx routes, stage the signed files as root-owned
mode-0644 files in new version directories and add only their exact GET/HEAD routes first. Check
`nginx -t`, reload, and verify the routes before switching the indexes. Set
`DESKTOP_RELEASE_PRESTAGED_PROTECTED=1` with `DESKTOP_RELEASE_REMOTE_RELEASE_ROOT` and
`DESKTOP_RELEASE_REMOTE_COMPONENT_ROOT` to the two existing absolute store roots. In this mode the
publisher uses passwordless `sudo` for index operations, verifies every pre-staged file by full
public download and byte comparison, then performs the same two-index transaction and tag gate.
It never uploads into the root-owned store or changes Nginx routes itself. If verification fails,
leave the old indexes in place; the new immutable files may remain unreferenced for inspection.
