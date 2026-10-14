# Hermes GO official website (HG-200)

Chinese, mobile-first product site for **https://go.mrlgs.net**. It follows the attached HG-200
“按设备开始” design: Android App download and iPhone/iPad Web App are the primary entries; macOS
Desktop is the setup companion. The slogan is **你的 AGENT，随时在身边。** No desktop-browser
card, header Web App button, mock screenshot or prototype controls ship in this service.

This is a standalone, dependency-free Node 22 service, outside the root npm workspaces. It binds
only to loopback and needs HTTPS termination in production. The approved design stays attached to
HG-200; this directory contains the implementation, not another design variant.

## Run and verify

```bash
cd website
npm run check
npm test
# Set WEBSITE_INVITE_CODE privately first; no code is embedded in frontend assets.
npm start
```

Local defaults: `http://127.0.0.1:4174`, loopback port 4174. A local session key is generated in
memory if unset, invalidating sessions on restart. Set `WEBSITE_ORIGIN` to match a changed port.
No build or npm install is needed. Use `node --env-file=/protected/path/website.env src/server.mjs`
to load protected configuration without shell-history secrets. Never commit that file.

| Variable | Meaning |
|---|---|
| `NODE_ENV` | Must be `production` for production execution |
| `PORT` | Loopback listener port; default 4174 |
| `WEBSITE_ORIGIN` | Exact origin without trailing slash; production default `https://go.mrlgs.net` |
| `WEBSITE_INVITE_CODE` | Required six-digit code, supplied by administrator; no built-in default |
| `WEBSITE_SESSION_SECRET` | Required in production, at least 32 bytes of cryptographically random secret |
| `WEBSITE_SUPPORT_EMAIL` | Optional public contact email; unset hides the entire contact section/button |
| `WEBSITE_TRUST_LOOPBACK_PROXY` | `true` only behind the supplied loopback proxy, which overwrites X-Real-IP |

The initial code is configured privately according to the owner's decision recorded on HG-200.
Rotate the session secret alongside a code change if existing download sessions must be revoked.
Never print configuration values, invitation request bodies, signed cookies or exception payloads.

## Routes and boundaries

- `/`, `/download/`, `/guide/`, `/help/`: server-rendered, linked pages; usable without JavaScript.
- `/api/invite`: same-origin POST, JSON or regular form. Eight attempts per IP per ten minutes;
  a ninth returns `HR-SITE-002` with Retry-After. Success sets a host-only HttpOnly SameSite=Strict
  cookie for 12 hours; production uses Secure and the `__Host-` prefix.
- `/api/releases`: public version metadata, notes and SHA-256, with no artifact URL.
- `/downloads/android`, `/downloads/desktop`: signed-cookie verification, fresh validated release
  resolution, then 303 to the official versioned URL. There is no arbitrary URL parameter.
- `/healthz`, `/robots.txt`, `/sitemap.xml`: health/discovery.

Android reads `https://mrlgs.net/releases/index.json`, using the existing release-server validator
for package/channel/certificate/descending versions/path/hash/size. Desktop reads
`https://mrlgs.net/desktop/apps/internal-index.json` for owner-approved ad-hoc test downloads, and
falls back to `https://mrlgs.net/desktop/apps/index.json` only when that test pointer returns 404.
Both validate architecture, versioned path, hash, size, commit and minimum OS. The test pointer
additionally requires `distribution: "ad-hoc"`, `notarized: false` and the exact `-dev.dmg` filename;
the official validator still rejects that filename. An invalid/unreachable test pointer fails closed
without a stale or official fallback. No APK/DMG or release pointer is published by the running
website service. Indexes are bounded to 1 MiB, fetches time out after 3.5 seconds, redirects are refused,
metadata is cached for 60 seconds, and downloads explicitly refresh. Invalid/unreachable indexes
fail closed with `HR-SITE-004`; 404/empty indexes show **安装包暂未开放** without an enabled link.

The owner authorized website distribution and continued test-release synchronization on 2026-10-14.
Desktop downloads explicitly identify the internal test build, its Mac architecture, pending Apple
notarization and manual installation/update instructions. This does not enable in-app GUI updates.

## Synchronize each approved macOS test publication

Use the unified internal publisher from a clean isolated checkout of current `origin/main` after
the owner-approved package has passed Desktop assets/tests/build and image/signature gates:

```bash
node website/scripts/publish-desktop-internal.mjs \
  --receipt /absolute/verified-package.json \
  --dmg /absolute/Hermes-Go-Desktop-<version>-dev.dmg \
  --notes-file /absolute/notes.json \
  --evidence-dir /absolute/new-publication-evidence \
  --ssh-target user@approved-host
```

The package receipt contains `verified: true`, `stage: "internal-test-package-verified"`,
`channel: "internal-ad-hoc"`, `notarized: false`, `version`, `build`, `sizeBytes`, `sha256`,
`sourceCommit`, `verifiedAt`, and `checks` with `assets: "passed"`, a positive `desktopTests` count,
`failures: 0`, `appBuild: "passed"`, `imageIntegrity: "passed"`. Record the actual tests and build;
never manufacture gate evidence. A new candidate's source must match the clean current main checkout.
Retain the approved public bootstrap/component trust settings when packaging. The command does not
allocate a version, build an app, or authorize release by itself.

The publisher also checks the actual mounted DMG's bundle ID/version/build/minimum OS/architecture,
image integrity and strict ad-hoc signature on macOS. It checks the clean current main again before
uploading, verifies server bytes, refuses to overwrite an existing version with different bytes,
installs only that artifact's GET/HEAD route with Nginx validation/rollback, then calls the existing
full public DMG verification and atomic internal-index publication in the same command.
**Website synchronization is automatic and mandatory after DMG publication.** Its final marker is
`DESKTOP_INTERNAL_RELEASE_AND_WEBSITE_SYNC_OK`; an index/readback failure fails the whole command,
so an upload alone must not be reported as a completed publication. Evidence is stored in the new
outside-tree directory. Existing immutable files may remain unreferenced after failure; preserve them.

A verified completed publication receipt (the stage below) is also accepted for idempotent retry of
an already published package. Use the exact same DMG, notes and original receipt time; it retains the
original source commit, which must be an ancestor of current main. Same-version metadata changes are
refused by the pointer transaction. Do not rebuild or overwrite a version when retrying.

The standalone index command remains for recovery or registering an already published package:

```bash
node website/scripts/desktop-internal-index.mjs \
  --receipt /absolute/verified-publication.json \
  --dmg /absolute/Hermes-Go-Desktop-0.2.32-dev.dmg \
  --notes-file /absolute/notes.json \
  --output /absolute/new-internal-index.json \
  --publish --ssh-target user@approved-host
```

Use the actual versioned filename. The receipt must contain `verified: true`,
`stage: "internal-test-published-and-public-readback-verified"`, `channel: "internal-ad-hoc"`,
`notarized: false`, `version`, `build`, `url`, `sizeBytes`, `sha256`, `sourceCommit`, and `verifiedAt`.
Notes are a bounded JSON string array. Architecture defaults to `arm64`, minimum macOS to `14.0`;
override them with `--architecture` and `--minimum-macos` for a different verified package.
Omit `--publish` to prepare the pointer without remote operations. Output must be outside the tree
and must not already exist.

The initial authorized deployment installs the exact route from
`deploy/desktop-internal-index.conf.example` into the existing `mrlgs.net` server, checks `nginx -t`
and reloads Nginx. The publisher does not edit routes or upload/rebuild a DMG. It requires the existing
root-owned `/srv/hermes-desktop-apps/<version>/` file and that public route. It verifies local receipt
bytes and a full public DMG download, then uses a locked atomic test-pointer replacement, verifies
the public pointer verbatim, and restores the previous pointer on a failed readback. A same-version
replacement with different metadata and a version downgrade are refused. Rollback refuses to
overwrite a concurrent newer pointer. Official app and signed managed-component indexes are untouched.

Subsequent approved internal releases use the unified publisher, which runs registration automatically.
No website redeployment is needed: ordinary metadata refreshes within 60 seconds, and clicking Download fetches
the current pointer afresh. This follows verified test publications, not unverified development commits.

**The invitation is only a website navigation/download gate.** The existing release host remains
public, as agreed. A copied final artifact URL can be downloaded directly; this site does not claim
to protect distribution at the storage layer. Web App (`https://mrlgs.net/app/`) and client admission
keep their existing rules. No Mac credential is collected or forwarded by the website.

Error codes/localization/recovery are in `src/errors.mjs` and `../docs/ERROR_HANDLING.md`. Fixed
error stages replace raw exceptions. Browser network failure is `HR-SITE-007`. No analytics,
external fonts or external runtime assets are required. Cookie/IP retention is described on Help.

See [`../docs/WEBSITE.md`](../docs/WEBSITE.md) for publication gates and manual acceptance.
