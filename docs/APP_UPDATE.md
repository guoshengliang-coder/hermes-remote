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

Opening the page independently starts an index check and resumes any persisted task. The task moves
through enqueuing → waiting/paused → downloading(progress) → verifying → installable, cancelling, or
failed. Failure exposes a stage-specific retry/recovery action. DownloadManager writes to the
app-specific external Downloads directory. The download ID and selected version metadata are kept in
private SharedPreferences, so navigation, process recreation, or an index/network failure cannot hide
an already downloaded installable APK. Cancellation owns the task slot until DownloadManager,
metadata, and the residual file have been cleaned up; failures keep the task visible.

Only the manifest's `latestVersionCode` is offered for download/install. History is collapsed and
read-only. An offline restored task remains usable, but once a successful check discovers a higher
latest code, the old task is marked superseded and installation/retry are blocked until it is deleted.

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
   The gate reads `minSdk` from the built APK with `aapt` together with package/version/signature data;
   publication metadata must consume that measured value and must never hard-code it. The publisher
   uploads the APK, metadata, and the reviewed `deploy/publish-release.mjs` plus its schema from the
   same clean `origin/main` commit, then executes that temporary copy remotely. It must not trust an
   independently deployed server-side publisher that may lag the tested source.
4. A bounded Linux `flock` covers the full remote transaction; the kernel releases it when a publisher
   exits or crashes. While holding that fence, the official CLI may clear a directory lock left by the
   prior crashed process. The Node layer never guesses that a lock is stale or steals it: it fails
   closed, records an unguessable owner token, and prevents an old owner from releasing a successor's
   lock. Temporary data and the parent directory are fsynced, and the prior legal index is replaced
   atomically through a temporary `index.json.prev`. Only a complete entry with every field equal is
   idempotent. `publishedAt` is the Git HEAD commit timestamp in UTC, making retries deterministic.
5. The publisher downloads the complete public versioned APK and verifies HTTP, size, and SHA-256,
   then checks the public index entry/latest value. Only the printed versioned URL is deliverable.

## Automatic publishing from GitHub

The repository includes `.github/workflows/android-release.yml`. Normal pushes and pull requests run
unit tests, lint, and source compilation without any signing or deployment secret; they deliberately do
not package an APK. Publishing runs when a tag named `android-v<appVersionName>` is pushed, or when the workflow
is manually dispatched from `main`. The job uses the `production` environment and a concurrency lock,
so configure a required reviewer for that environment in GitHub Settings → Environments when the
repository plan supports environment reviewers. The workflow then performs the protocol tests, provisions the canonical debug key,
runs the package gate, publishes through the existing SSH publisher, and verifies the public APK and
`index.json`. A tag whose version does not match `android/app/build.gradle.kts` or whose commit is not
the current `origin/main` is rejected. Even without reviewer protection, the deliberate version tag,
main-commit check, clean-worktree check, and public hash/index verification remain mandatory gates.

Third-party Actions are pinned to full commit SHAs. Release secrets are scoped only to the trusted
shell steps that consume them, rather than the whole job. The signing step writes the debug key,
produces an atomic package-gate JSON, and removes the key with a shell trap. A separate deployment step
creates only the SSH key, publishes by consuming that existing gate through `APK_RELEASE_GATE_FILE`,
then removes the SSH files and gate with its own trap. Signing and deployment credentials therefore
never coexist on disk, and third-party post-job actions run only after both are gone. Configure these
repository secrets once (never commit their values):

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

The active index is capped at 100 versions. The publisher warns when 10 or fewer slots remain and fails
closed before mutating the data root when the next release would exceed the cap. It never silently
removes a registered APK. Retention is an explicit operator decision: archive the metadata and artifact,
remove only a reviewed non-current entry through a dedicated maintenance change, validate the resulting
index, and preserve rollback data before publishing again.

Publication validates everything before replacing the index, so failures leave the prior index usable.
An APK rename followed by an index failure may leave an unreferenced file; it is not downloadable and
can be removed manually after comparing it with the current index. Never repair by hand-editing the
index: correct the input and rerun the idempotent publisher. Keep the previous data-root/index backup
for operator rollback. To restore, validate `index.json.prev`, copy it to a same-directory temporary,
fsync it, rename it to `index.json`, then fsync the directory; never edit it by hand. Android
intentionally will not overwrite-install an older version.

## Channel rollback (roll-forward runbook)

Android will not overwrite-install an older version, so a bad release is rolled back for the whole
channel by **re-publishing the old code under a new, higher version** — every device then "updates"
into the previous behavior through the normal channel. This is the primary rollback path; the
in-app APK export exists only for single-device emergencies (see the update page's rollback dialog).

Executed by the integration agent, from a clean isolated worktree:

1. Identify the last good release commit (`git log --oneline -- android` and the release notes in
   `android/README.md`). Do NOT reset or rewrite `main`.
2. Create a roll-forward branch from current `main`, then restore the Android sources of the good
   release into it: `git checkout <good-commit> -- android/app/src` (widen the pathspec if the
   regression spans Gradle files; leave version metadata and `android/releases/` alone).
3. Bump `appVersionName`/`appVersionCode` past the broken release as usual, write a release
   description in `android/releases/<version>.json` that names the restored version and the broken
   one, and add the matching `android/README.md` note.
4. Run the full package gate, merge to `main` after review, and publish with
   `./scripts/publish-android-apk.sh` from an isolated worktree — the standard flow; nothing about
   publication changes.
5. Verify `latestVersionCode` on the public index, then confirm on a device that the in-app
   updater installs the roll-forward build.

Keep the broken release registered in the index (clients already validated it; retention is a
separate maintenance decision) — the roll-forward simply supersedes it.

## On-device retention and rollback material

The client keeps the APKs of the newest five releases in its private download directory as
rollback material and deletes older ones during each index check. The update page's version
record offers "Export APK" for old releases whose file survived retention: the file is re-hashed
against the manifest, then copied to the device's public Downloads collection (Android 10+;
older devices get a share sheet). The documented single-device rollback is: export → uninstall
(clears local connection settings; conversations live on the server) → install from the file
manager → reconfigure the Relay URL and App Token.
