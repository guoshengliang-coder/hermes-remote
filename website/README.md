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
`https://mrlgs.net/desktop/apps/index.json` and validates the internal app-pointer schema, architecture,
versioned path, hash, size, commit and minimum OS. No APK/DMG or release pointer is published by this
service. Indexes are bounded to 1 MiB, fetches time out after 3.5 seconds, redirects are refused,
metadata is cached for 60 seconds, and downloads explicitly refresh. Invalid/unreachable indexes
fail closed with `HR-SITE-004`; 404/empty indexes show **安装包暂未开放** without an enabled link.

On 2026-10-11 the Android index was available, while Desktop's app pointer returned 404. The ad-hoc
0.2.30 dev DMG is deliberately not promoted to an official latest download or a notarized release.

**The invitation is only a website navigation/download gate.** The existing release host remains
public, as agreed. A copied final artifact URL can be downloaded directly; this site does not claim
to protect distribution at the storage layer. Web App (`https://mrlgs.net/app/`) and client admission
keep their existing rules. No Mac credential is collected or forwarded by the website.

Error codes/localization/recovery are in `src/errors.mjs` and `../docs/ERROR_HANDLING.md`. Fixed
error stages replace raw exceptions. Browser network failure is `HR-SITE-007`. No analytics,
external fonts or external runtime assets are required. Cookie/IP retention is described on Help.

See [`../docs/WEBSITE.md`](../docs/WEBSITE.md) for publication gates and manual acceptance.
