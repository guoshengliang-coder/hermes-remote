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
