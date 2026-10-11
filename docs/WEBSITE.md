# Hermes GO website — implementation and publication gate

HG-200 adds `website/`, an independent Node 22 service for **https://go.mrlgs.net**. Source and local
verification do not authorize DNS changes, TLS provisioning or a production restart. The existing
Web App stays at **https://mrlgs.net/app/** and existing release services remain unchanged.

## Product contract

- Chinese first, English error copy prepared centrally; a full English site is deferred.
- Brand: **你的 AGENT，随时在身边。** / **Your AI agent, in your pocket.**
- Android download and iPhone/iPad Web App lead the homepage. Desktop is secondary preparation
  for the user's Mac. No iOS native-app claim or desktop-browser card.
- Public introduction and guide; invitation verification only gates website client download links.
  Invitation configuration stays private. Client/Web login and access are independent.
- Free invitation testing, with separate model/API costs. Mac/Hermes must stay online.
- Latest version/size/date/OS/notes come from the existing official indexes. Unpublished platforms
  have an explicit unavailable state; missing/invalid upstream metadata never enables a download.
- First-time and existing-Hermes onboarding are separate. Follow actual Desktop capabilities and
  keep the existing Mac installation and credentials. Do not promise automatic model configuration.
- Support email is a configuration item; hide the contact block when unset (owner confirmed).

## Publication preparation (not executed by HG-200 source work)

1. Obtain explicit owner authorization for deployment. Re-read `docs/DEPLOYMENT.md` and the private
   environment context only for that authorized deployment. Do not guess server paths/services.
2. Select a clean merged commit, copy `website/` plus `release-server/src/schema.mjs` preserving
   their relative paths, or deploy a clean repository checkout. Run `npm run check` and `npm test`
   in website. No build output, Android package or Desktop version bump is required.
3. Provision a dedicated `go.mrlgs.net` HTTPS virtual host and protected environment file. Keep the
   service on loopback, with `NODE_ENV=production`, the privately agreed initial invitation code,
   a random session secret (at least 32 bytes), exact canonical origin, and the chosen public
   support email if available. The supplied service/proxy files are templates, not live settings.
4. Reverse proxy with X-Real-IP overwritten by the trusted loopback proxy. If enabling that trust
   option, ensure the website cannot be reached except through that proxy. Limit body size and
   HTTP request duration; do not log POST bodies or cookies. Avoid changing Gateway, DERP or Xray.
5. Mainland-China-first acceptance: test the actual DNS/TLS/page/index/APK download path from a
   mainland mobile network and an overseas network. No CDN or mainland hosting is claimed by source.
6. Verify `/healthz`, canonical metadata and the Web App link. Privately test wrong/correct code,
   expired session, throttling, release availability and no-JavaScript form fallback. Verify the
   full public Android download byte size/SHA-256 against its official manifest. Desktop remains
   unavailable until its own authorized release publication provides a valid app pointer.
7. Record exact commit/host/service/origin, support-email readiness, network/device results and
   rollback instructions. Only after verified publication may HG-200 move to “待验证”.

Rollback: restore the prior website service/reverse-proxy configuration, verify health and Web App
reachability, and rotate the website cookie secret if necessary. Website rollout does not modify
existing client or release authentication. No live service was changed by this implementation.

## Manual acceptance

Local visual/browser checks support source review; they do not substitute for these production and
physical-device checks:

- Android Chrome: homepage primary CTA selects Android; invitation keyboard is numeric; input,
  error/retry, unlock and actual APK installation work. Never uninstall existing user data to test.
- iPhone/iPad Safari (including iPad desktop user agent): primary CTA goes to existing Web App;
  Safari “添加到主屏幕” instructions match the device; all pages fit at 320–430 CSS px.
- macOS desktop browser: no desktop-browser card or header Web App button; both mobile entries are
  visible and Desktop's secondary state follows the official app index.
- Keyboard/screen reader: skip link, current-page navigation, real headings/links, labeled invitation
  input, announced result, focus-visible outlines, FAQ disclosure and 200% text zoom.
- JavaScript disabled: navigation, invitation form and gated download remain functional.
- Unset support email: no placeholder address/mailto/button; configured email opens the expected address.
- Unknown/failed release index: no stale/fabricated package URL and clear `HR-SITE-004` recovery.
- Copy/share links: real `/download/`, `/guide/`, `/help/` paths, canonical go.mrlgs.net, working sitemap.

Automated tests exercise invitation/session/CSRF/throttling, protected redirects, manifest poisoning,
bounded index bodies, fresh-download fail-closed behavior, HTML escaping, route isolation, config
validation, localized errors, cookie attributes, omitted contact and regular-form fallback.

## Local source evidence — 2026-10-11

- `cd website && npm run check && npm test`: 15 passed, no skips. A browser-observed hash-only
  navigation bug after invitation verification was fixed; the regression checks that successful
  verification actually reloads the server-rendered download links.
- Root `npm run build`: passed. `PATH=/opt/homebrew/opt/openssl@3/bin:$PATH npm test`: 784 passed,
  30 skipped, zero failures across Protocol/Connector/Gateway/release-server/scripts. Optional
  network/PostgreSQL cases were not enabled locally. The initial macOS LibreSSL run failed two
  existing legacy-recovery decrypt tests; all six recovery tests and the full baseline passed with
  the installed OpenSSL 3. No recovery code was changed.
- Chrome desktop and DevTools 390px/320px: homepage, real Android metadata, unavailable Desktop,
  invalid invitation and successful unlock checked. The iPhone 16 preset sent the homepage primary
  CTA to the existing Web App; mobile guide and FAQ rendered, and unset email had no contact block.
  This is Chrome emulation, **not physical iPhone/Safari or Android verification**. The translation
  extension's injected font was blocked by CSP; no website script error was observed.
- No Android/Desktop/Web App source change, version allocation, package delivery or production
  deployment occurred. Mainland/overseas networks, physical installation and public website TLS
  remain the publication acceptance gate above.

## First production publication — 2026-10-11

The owner authorized publication after implementation
[#571](https://github.com/guoshengliang-coder/hermes-remote/pull/571) merged as
`7ee61c4de3432099cffff3d16543be39a523205b`; its main Website, SAST and CI workflows passed.
A fresh, clean isolated worktree at that commit passed website syntax checks and **15 tests**.
Only the **website** artifact was deployed, identified as **0.1.0-7ee61c4de343**. No Android
package, Desktop package, existing Web App bundle, Gateway or Connector was published.

- Origin: **https://go.mrlgs.net**. Cloudflare holds an A record for `go` pointing to the existing
  HK host, in DNS-only mode. No CDN or mainland-hosting guarantee is claimed.
- Immutable release: `/opt/hermes-go/website-releases/7ee61c4de3432099cffff3d16543be39a523205b`;
  `/opt/hermes-go/website` links to its `website` directory, preserving the sibling release schema.
  All **13** deployed files matched the source manifest by size and SHA-256.
- Service: `hermes-go-website.service`, enabled, running as the dedicated `hermes-website` user,
  listening only on **127.0.0.1:4174**. Protected configuration is `/etc/hermes-go/website.env`
  (root-owned `0600`); invitation and session secret values are not recorded here. Support email
  remains unset and the contact block is hidden.
- Edge: `/etc/nginx/conf.d/hermes-go-website.conf`, with a dedicated TLS virtual host, overwritten
  X-Real-IP, body/time limits and access logging disabled. `X-Hermes-Website-Commit` identifies the
  served source commit. Nginx configuration checks passed; changes used graceful reloads.
- TLS: dedicated `go.mrlgs.net` Let's Encrypt certificate, valid until **2027-01-09**. Its separate
  config/work/log roots are `/etc/letsencrypt-hermes-website`,
  `/var/lib/letsencrypt-hermes-website` and `/var/log/letsencrypt-hermes-website`.
  Webroot validation uses `/var/lib/hermes-go-website-acme`; the existing DERP HTTP redirect
  forwards challenges to the HTTPS virtual host. No DERP stop or existing certificate hook ran.
  `hermes-go-website-cert-renew.timer` schedules independent twice-daily renewal with a randomized
  delay; the certificate-specific deploy hook checks and reloads Nginx.
  The isolated `certbot renew --dry-run --run-deploy-hooks --no-directory-hooks` rehearsal
  passed, including HTTP challenge validation and the Nginx check/reload hook.

Independent public verification from this Mac matched all **three assets** in full and exercised
the four content pages, health, robots and sitemap. Wrong invitation, blocked unsigned download,
Secure/HttpOnly/host-only cookie, successful unlock, exact versioned Android redirect, cross-origin
rejection and no-JavaScript form flow passed. The full existing Android **0.1.171** APK download
matched **33,374,063 bytes** and SHA-256
`2263c3c79e9a826929166c72d697215fb4094f5440dc4104dd561886b3f6b8b6`.
Desktop remains explicitly unpublished. Existing `/app/` and `/relay-health` returned 200;
HK public health and invitation throttling passed, including spoofed X-Real-IP attempts.

Archive SHA-256: `dcbfb3b408b8baceadbf2d313c680c902063222914636deda745aa5611d9086d`.
Public verification receipt SHA-256:
`c20c90d385dc4ca904ffbb4d8c2fb1f8e4c722406dedc65a40a3749e6e2084bc`.
Evidence and local operator scripts are outside Git at
`/Users/bs/.codex/artifacts/hg200-website-release/`.

The Mac's direct HTTPS probes passed. Its existing system-proxy route to the new hostname
terminated the connection; the same proxy succeeded when the origin IP was supplied explicitly.
This remains a client proxy/DNS-path limitation, not a successful Chrome acceptance result.
Physical Android/iPhone Safari/PWA, mainland mobile and a separate overseas client network
remain unverified. Publication does not establish device installation or acceptance.

Rollback for this first release: retain the valid dedicated TLS virtual host and ACME challenge
location, replace only its website proxy location with a localized `HR-SITE-006` maintenance
503, check/reload Nginx, then stop only `hermes-go-website.service`. Keep the DNS record and
certificate-renewal timer. For later releases, switch the website symlink to the retained prior
immutable release and restart only the website service. Never restore the bootstrap virtual
host's temporary certificate or change the existing Web App/Gateway/release routes.
