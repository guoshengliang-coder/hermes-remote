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

Both `desktop-app-release.yml` (`desktop-v*`) and `connector-release.yml` (`connector-v*`) reference
the protected `desktop-release` environment. The environment must already exist. Configure a
required reviewer who owns Desktop releases and these secrets:

- `DESKTOP_RELEASE_SIGNING_PRIVATE_KEY`
- `DESKTOP_RELEASE_SSH_PRIVATE_KEY`
- `DESKTOP_RELEASE_SSH_KNOWN_HOSTS`

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
