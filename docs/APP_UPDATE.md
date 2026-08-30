# Hermes Remote app updates

This is the authority for the internal Android update channel and the HTTPS version repository.

## Security and architecture

The update service and Gateway share the `mrlgs.net:443` Nginx edge and remain separate upstreams. Android constructs a new,
minimal OkHttpClient for the index request; it has no Gateway cookie jar, authenticator, interceptor,
or token. Only HTTPS URLs on `mrlgs.net` (default/443), `com.hermes.remote`, channel `internal`, and the
certificate digest defined once in `android/app/build.gradle.kts` are accepted. Beta and future stable
application IDs/channels cannot install into internal.

`release-server` is a Node HTTPS listener with no runtime dependency. It reads, but never generates,
the data-root `index.json`; only filenames registered there are served. `/health` and `/ping` support
GET/HEAD, `/releases/index.json` is `no-store`, registered APKs are immutable, byte ranges support
resumable downloads, and `/` redirects to the latest versioned APK. Traversal and arbitrary-file reads
fail closed. TLS is mandatory through `TLS_CERT`, `TLS_KEY`, `RELEASE_DATA_ROOT`, `HOST` (production
`127.0.0.1`), and `PORT` (production `9443`).

## Manifest schema

The root requires `schemaVersion` (currently 1), `channel`, `latestVersionCode`, ISO-8601 `generatedAt`,
and `versions`. Every version requires `versionName`, positive integer `versionCode`, `applicationId`,
`channel`, ISO-8601 `publishedAt`, basename `fileName`, HTTPS `downloadUrl`, positive `sizeBytes`,
64-hex `sha256`, 64-hex `certificateSha256`, positive `minSdk`, string-array `releaseNotes`, and
`sourceCommit`. Missing or invalid required fields reject the whole index. Dates must use canonical UTC
`Z`. The server caps the index at 1 MiB and 100 versions; each release has at most 20 non-empty notes,
each at most 500 characters and free of control characters.

## Client state machine

The page moves through idle → waiting → downloading(progress) → verifying → installable, or failed.
Failure exposes retry. DownloadManager writes to the app-specific external Downloads directory. The
download ID and selected version metadata are kept in private SharedPreferences, then queried when a
new page/ViewModel is created, so navigation and recomposition do not lose the job.

Before installation, the client checks URL policy, byte length, SHA-256, archive application ID,
manifest version code/name, and the sole APK signer certificate SHA-256. Any mismatch is terminal and
does not open an installer. On Android 8+, missing unknown-source permission opens the per-app settings
page; the user can return and press install again. Installation uses a FileProvider content URI and the
system installer, and always requires user confirmation. Older versions remain visible with “in-place
downgrade is not supported.”

## Publishing

1. The integration agent bumps `appVersionName` and `appVersionCode`, updates `android/README.md`, and
   adds `android/releases/<version>.json` containing only channel and release notes.
2. Commit the release, push it to `origin/main`, and confirm the worktree is clean. With the canonical
   key provisioned, run `scripts/publish-android-apk.sh`. The publisher refuses a dirty worktree or a
   `HEAD` different from `origin/main`. Authentication comes only from ssh-agent/key or caller-injected
   SSH configuration. Supported variables are
   `RELEASE_SSH_HOST` (fixed `mrlgs.net`), safe `RELEASE_SSH_USER` (default `kkk`),
   `RELEASE_DATA_ROOT` (fixed `/srv/hermes-releases`), and `RELEASE_PUBLIC_BASE_URL`. Publish only from
   an isolated worktree with no concurrent writer.
3. The script first runs `package-debug-apk.sh` and consumes its atomically written JSON gate output.
   It derives package metadata, uploads temporary APK/metadata files, and calls
   `deploy/publish-release.mjs` remotely.
4. A bounded-wait, stale-recoverable cross-process lock covers the full read/validate/APK/index
   transaction. Temporary data and the parent directory are fsynced, and the prior legal index is
   saved as `index.json.prev`. Only a complete entry with every field equal is idempotent. `publishedAt`
   is the Git HEAD commit timestamp in UTC, making retries deterministic.
5. The publisher downloads the complete public versioned APK and verifies HTTP, size, and SHA-256,
   then checks the public index entry/latest value. Only the printed versioned URL is deliverable.

## Automatic publishing from GitHub

The repository includes `.github/workflows/android-release.yml`. Normal pushes and pull requests run
CI only. Publishing runs when a tag named `android-v<appVersionName>` is pushed, or when the workflow
is manually dispatched from `main`. The job uses the `production` environment and a concurrency lock,
so configure a required reviewer for that environment in GitHub Settings → Environments when the
repository plan supports environment reviewers. The workflow then performs the protocol tests, provisions the canonical debug key,
runs the package gate, publishes through the existing SSH publisher, and verifies the public APK and
`index.json`. A tag whose version does not match `android/app/build.gradle.kts` or whose commit is not
the current `origin/main` is rejected. Even without reviewer protection, the deliberate version tag,
main-commit check, clean-worktree check, and public hash/index verification remain mandatory gates.

Configure these repository secrets once (never commit their values):

- `HERMES_DEBUG_KEYSTORE_BASE64`: base64 of the shared `~/.android/debug.keystore` (password remains
  `android`).
- `RELEASE_SSH_PRIVATE_KEY`: the deployment key allowed to log in as `kkk@mrlgs.net`.
- `RELEASE_SSH_KNOWN_HOSTS`: the pinned `mrlgs.net` SSH host key line(s).

After a release commit is pushed, create and push the matching tag, for example
`git tag android-v0.1.23 && git push origin android-v0.1.23`; GitHub then handles the build, upload,
index update, and public verification after the production approval. This automation publishes only
the Android update artifact; Gateway/Connector service deployment remains an explicit server operation.

`scripts/bootstrap-release-server.sh` creates `/opt/hermes-release-server`, `/srv/hermes-releases`, a
legal empty index, TLS directory, environment, and systemd unit. Test locally with
`SYSTEM_ROOT=$(mktemp -d) scripts/bootstrap-release-server.sh`. Import 0.1.14–0.1.16 by passing each
APK/metadata pair to `scripts/import-android-release-history.sh`; it uses the same `publishRelease`
validation and transaction. SSH authentication remains external and no script contains a password.

`scripts/deploy-edge-router.sh` installs and validates Nginx, moves the release server to loopback 9443,
and claims public 443 only after both upstreams pass health checks. It restores the previous release
environment and service if activation fails.

Replace the old combined Hermes Certbot hook with `deploy/certbot-hermes-services-hook.sh.template`,
but retain the derper hook. It atomically copies the `mrlgs.net` files into `/etc/hermes-remote/tls`
as `root:hermes-remote`, `/etc/hermes-release-server/tls` as `root:kkk`, and the Nginx edge certificate
as `root:root`. It verifies both upstream restarts and reloads Nginx. Services never read the live
Certbot private key. The unit fixes `ReadOnlyPaths=/srv/hermes-releases` and enforces
`UMask=0077`, restricted address families, and native syscall architecture.

## Verification and recovery

Run `npm run build`, `npm test`, Android unit tests, and `git diff --check`. For a distributed APK run
the full package gate. After authorized deployment, check GET/HEAD health, index, APK length/type, the
versioned download hash, and confirm arbitrary/traversal paths return 404.

Publication validates everything before replacing the index, so failures leave the prior index usable.
An APK rename followed by an index failure may leave an unreferenced file; it is not downloadable and
can be removed manually after comparing it with the current index. Never repair by hand-editing the
index: correct the input and rerun the idempotent publisher. Keep the previous data-root/index backup
for operator rollback. To restore, validate `index.json.prev`, copy it to a same-directory temporary,
fsync it, rename it to `index.json`, then fsync the directory; never edit it by hand. Android
intentionally will not overwrite-install an older version.
