# Desktop release channel

## Published internal test distribution: 0.2.42

Owner-authorized publication completed on 2026-10-25 after #666 and version PR #668 and their
main checks passed. Built from clean main `e8e2559f82b63db5c9095dd1da0414819066682d`.
This patch explains custom Git configuration refusal with the existing HR-HERMESUPDATE-002 and a
fixed redacted diagnostic. No configuration is executed/changed, no safety restriction is relaxed,
and one-click Hermes upgrade/recovery remains disabled.

Both public DMGs match **4,013,043 bytes**, SHA-256
`8c1bf3230ace9e3b7538ff1ad918648c8133ec2a74e27e9f8b8aa3fc39529c37`.
Website and app-update discovery report **0.2.42/build 45**. Signed managed-component pointers
remain byte-identical at 0.4.12, and all packaged public trust/update settings match the installed
0.2.41 app. Assets, **660 Swift tests**, **15 publication/update tooling tests**, release app/DMG
build, strict ad-hoc signature, mounted bundle identity/version/build/arm64 and image integrity passed.
The first publisher attempt returned HR-SITE-004 with old app/website pointers unchanged; server and
both full public artifact hashes were then reconciled. One retry using the same package, receipt and
notes passed both unified-publisher success markers. No immutable file was overwritten; the precise
initial failure stage was not established.

The actual installed 0.2.41 client discovered the notes, installed and relaunched as 0.2.42/build 45;
the installed app passed strict signature verification. Actual clicks read Hermes 0.21.3/commit
`17b5df02f2a7`, upstream stable 0.22.5, empty GO recommendation and no upgrade record. Conditions now
show the explicit custom-Git-configuration block rather than generic unknown, with no pending recovery
or official update. No upgrade/recovery execution button is present. Hermes/Connector PIDs, source,
Git config/index and Hermes config remain unchanged; no Hermes upgrade or service restart occurred.
Account restoration initially remained checking. After the owner reported handling the system
confirmation, the account restored and actual Overview showed Desktop 0.2.42 running, local Hermes
ready, Cloud connected and the existing phone-access health passed. This health display does not
replace a physical phone business test; actual phone history/send/receive acceptance remains blocked
by the owner's unavailable device. Live database/WAL metadata changed while Hermes continued
running, so database immutability and full business acceptance are not claimed. This is temporary
ad-hoc internal distribution, not Apple notarization or clean-machine acceptance.

Download: `https://mrlgs.net/desktop/apps/0.2.42/Hermes-Go-Desktop-0.2.42-dev.dmg`.

## Published internal test distribution: 0.2.41

Owner-authorized publication completed on 2026-10-24 after #659 and version PR #661 and their
main checks passed. The package was built from clean main
`66f62ed713042856ecdf2371b3dc85c7e5dd7b83`, including #660's compact account footer.
The three Hermes management functions are read-only. One-click Hermes upgrade/recovery remains
closed; no updater, PM/maintenance experiment or journal writer is packaged.

Both public DMGs match **4,012,102 bytes**, SHA-256
`9f82c98523b8e3bd81a741c48e352e163e588c79ffbb4947abd904f3a85aaa9d`.
Website and app-update indexes report **0.2.41/build 44**; both signed managed-component pointers
remain byte-identical at 0.4.12. Existing installed public trust/update settings were retained in the
mounted package. Assets, **659 Swift tests**, **15 publication/update tooling tests**, release app/DMG
build, strict ad-hoc signature, mounted identity/version/build/arm64 and image integrity passed.
The first unified-publisher attempt failed after artifact upload with old pointers unchanged.
After reconciling those pointers and the full public artifact bytes, one retry with the same package,
receipt and notes passed both unified-publisher success markers. No artifact was overwritten.

On 2026-10-25 the actual installed 0.2.39/build 42 client discovered the update, downloaded it and relaunched as
0.2.41/build 44. Its installed signature passed strict verification. Actual clicks checked Hermes
0.21.3/commit `17b5df02f2a7`, upstream stable 0.22.5, empty GO recommendation and absent upgrade record.
Condition checking conservatively returned unknown local changes with `HR-HERMESUPDATE-002`;
execution stayed unavailable. Hermes/Connector PIDs, source commit, config and absent upgrade record
were unchanged. Account restoration initially remained checking. After the owner reported handling the system
confirmation, the client restored its signed-in account and Overview showed local Hermes ready,
Cloud connected and phone access passed. This is the client health display, not a physical phone
business test. Local-change inspection still returned unknown, with execution blocked.
Live database/WAL metadata changed during the observation interval while Hermes was running, so this
is not evidence of database immutability or a completed account/phone/business test. See the actual
client record in `DESKTOP_TEST_PLAN.md`. No Hermes upgrade or service restart was performed.
This is temporary ad-hoc internal distribution, not Apple notarization or clean-machine acceptance.

Download: `https://mrlgs.net/desktop/apps/0.2.41/Hermes-Go-Desktop-0.2.41-dev.dmg`.

## Published internal test distribution: 0.2.40

On 2026-10-24 the owner authorized publication and confirmed **0.2.40/build 43**.
Includes structured Hermes/model status, account/sidebar/settings polish, first-install guidance
and bounded network retry with safe component-download recovery (#654/#656). Preserve the existing
Hermes profiles/data and installed public trust settings. Continue the temporary ad-hoc internal
channel, building from clean current main after the version PR and main checks pass. Publish through
the unified internal publisher and verify complete public DMGs, website and app-update discovery.
Signed managed-component pointers remain unchanged. Fresh-machine installation, upstream dependency
outage recovery and phone/model tasks require separate acceptance; publication is not proof of those
flows or Apple notarization.

Publication completed on 2026-10-24 after PRs #656/#657 and their main checks passed,
from clean main `935f42a901b5e254fe7186354d9d4461979f7a76`. Both unified-publisher success markers and independent
complete public downloads passed. The internal `-dev.dmg` and compatible `.dmg` alias match
**3,899,546 bytes**, SHA-256 `896f068982a096ff5bea7b4d1439298c086aaef149f72f3cac95ba76683febc1`.
Website and app-update indexes both report 0.2.40/build 43; signed managed-component indexes remain
byte-identical at 0.4.12. Assets, 629 Swift tests, 15 publication/update tooling tests, release app/DMG
build, strict ad-hoc signature, mounted identity/version/build/arm64 and image integrity passed.
Native fixtures cover light/dark/compact layout, long email, model states and installation guidance.
No user-installed app, service, Hermes profile or data was replaced. Real clean-machine installation,
upstream dependency outage recovery, upgrade/relaunch and phone/model tasks remain separate acceptance.
This is temporary-signed internal distribution, not Apple notarization.

Download: `https://mrlgs.net/desktop/apps/0.2.40/Hermes-Go-Desktop-0.2.40-dev.dmg`.

## Published internal test distribution: 0.2.39

On 2026-10-24 the owner authorized this iteration's merge/publication and confirmed
**0.2.39/build 42**. Includes first-run and signed-out recovery, independent optional account
panels, unified device/invitation management, model configuration guidance and update/settings
polish (#650). Preserve existing Hermes profiles/data and runtime settings for installed Macs.
Continue the existing temporary ad-hoc internal channel and installed public trust settings.
Build from clean current main after #650 and this version PR pass all PR/main checks; use the
unified internal publisher, verify full public DMGs and synchronize website/app-update indexes.
Physical first-install, login, phone access and model-task acceptance remain separate from
package/publication verification. This is not an Apple-notarized release.


Publication completed on 2026-10-24 after PRs #650/#651 and their main checks passed,
from clean main `c431b041a1b5f9c46329a0047858b4f137c833c7`. Both unified-publisher success markers and
independent complete public downloads passed. The internal `-dev.dmg` and compatible `.dmg`
alias match **3,830,604 bytes**, SHA-256 `cf3b8527bfd5ba8e2908a7b43adcefffff32cbbd6573095040ef9c5af272f3df`.
Website and app-update indexes both report 0.2.39/build 42; signed managed-component indexes
remain byte-identical at 0.4.12. Assets, 620 Swift tests, 12 update/publication tooling tests,
release app/DMG build, strict ad-hoc signature, mounted identity/version/build/arm64 and image
integrity passed. Native fixtures cover light/dark devices, empty-account onboarding, signed-out
states, settings and model setup. No user-installed app, local services or Hermes data were replaced.
Physical first registration/install, upgrade/relaunch, phone access and model inference still need
separate acceptance. This remains temporary-signed internal distribution, not Apple notarization.

Download: `https://mrlgs.net/desktop/apps/0.2.39/Hermes-Go-Desktop-0.2.39-dev.dmg`.

## Published internal test distribution: 0.2.38

On 2026-10-24 the owner confirmed **0.2.38/build 41** for the updater routing fix (#646).
Continue the existing temporary ad-hoc internal channel with unchanged public trust settings.
Build from clean current main, publish with the unified internal publisher, verify the full public
DMG, and synchronize website/app-update discovery. Preserve the signed managed-component pointers.
Old clients blocked by this updater defect require one direct DMG replacement of the GUI app;
this does not reset or reinstall the owner's Hermes or change account bindings. Physical background
commit/relaunch and mobile/data-preservation acceptance remain separate from publication checks.


Publication completed on 2026-10-24 after PR #647 and main checks passed, from clean main
`0db655edd022cdae3e9db477c5b175e3f3b1876b`. Both unified-publisher success markers and independent full public downloads
passed. The `-dev.dmg` and compatible `.dmg` alias match **3,796,718 bytes**, SHA-256
`0f43335e63654dc46162c0bf17dab1fa29812c1b93a6029c306621d0cc6dd352`. Website and app-update discovery report 0.2.38/build 41; signed managed-component
indexes remain byte-identical at 0.4.12. Assets, 612 Swift tests, 41 Desktop packaging/tooling tests,
app/DMG packaging, strict ad-hoc signature, mounted bundle identity/version/build/arm64 and image
integrity passed. The affected Mac's read-only source/manifest verification is recorded under the
update-routing regression in DESKTOP_TEST_PLAN.md. No installed user app, service, account binding
or Hermes data was replaced. Physical background commit/relaunch and mobile acceptance remain
pending; this is a temporary-signed internal build, not an Apple-notarized release.

## Published internal test distribution: 0.2.37

On 2026-10-24 the owner authorized merge/publication and confirmed **0.2.37/build 40**.
This includes the overview visual hierarchy, the previously merged diagnostics/log viewer and
interface cleanup (#642), and the existing per-Mac transfer-budget override support (#641; default
unchanged). Continue temporary ad-hoc internal distribution with the existing public trust settings.
Publish from clean current main through the unified internal publisher, verify the complete public
DMG and synchronize website/app-update discovery. Preserve signed managed-component pointers.
Physical upgrade and mobile/data-preservation acceptance remain separate from package/publication
verification. This is not an Apple-notarized release.


Publication completed on 2026-10-24 after PR #643 and main checks passed, from clean main
`a9a812198cf4913fc2e7c86731f7a062731cb7ed`. Both unified-publisher success markers and independent full public downloads
passed. The `-dev.dmg` and compatible `.dmg` alias match **3,794,849 bytes**, SHA-256
`79ae595664f76ccb38c9a74f664ef33b88d4c1c86af59aed7b60f5ec61a26a44`. Website and app-update discovery report 0.2.37/build 40; signed managed-component
indexes remain byte-identical at 0.4.12. Assets, 606 Swift tests, 41 Desktop packaging/tooling tests,
app/DMG packaging, strict ad-hoc signature, mounted bundle identity/version/build/arm64 and image
integrity passed. Native light/dark/compact, long-name, expanded, signed-out, stale and failed-state
fixtures were reviewed. No installed user app or local service was replaced. Physical upgrade and
mobile/data-preservation acceptance remain pending; this does not establish Apple notarization.

## Published internal test distribution: 0.2.36

On 2026-10-24 the owner confirmed **0.2.36/build 39** for the existing-Hermes/profile repair
and recovery UI iteration (#639). Continue temporary ad-hoc internal distribution, preserving
public trust settings. Publish from clean current main through the unified internal publisher;
verify the exact DMG and synchronize website/app-update discovery. Signed managed-component
pointers stay unchanged. Live Mac repair/mobile acceptance is separate and remains pending.

Publication completed on 2026-10-24 from clean main `4ee653e488dd96a8d6d9ea0b6efba3d0746a2227`
after PR #639 and main checks passed. Both unified-publisher success markers and independent
public verification passed. The `-dev.dmg` and compatible `.dmg` alias both match **3,681,884 bytes**,
SHA-256 `fd3add77b6524cd5507160ef00d778d7935232c3229ec7ca9bb0244193dfac8d`.
Website and app-update discovery report 0.2.36/build 39; signed managed-component indexes remain
byte-identical at 0.4.12. Assets, 592 Swift tests, strict ad-hoc signature, mounted bundle
identity/version/build/arm64 and image integrity passed. No installed user app or local service was
replaced. Physical upgrade, live repair and mobile/data-preservation acceptance remain pending;
this does not establish Apple notarization.

## Published internal test distribution: 0.2.35

On 2026-10-24 the owner authorized merge/publication and confirmed **0.2.35/build 38**.
The overview separates cloud connectivity, this Mac's Desktop and authenticated local Hermes
usability, with a concrete impact summary. Account login and management move to the global sidebar
footer. Retain the existing public trust settings and temporary-signing internal channel; build from
clean current main and use the unified publisher to verify the exact DMG and synchronize website
and app-update discovery. Managed-component pointers remain unchanged. Publication and physical
upgrade acceptance are separate gates; publication evidence follows.

Publication completed on 2026-10-24 from clean main `6de547e1071b343a307fb0d79756b53dafcbcab0` after PR #636 and
main checks passed. Both unified-publisher success markers and independent public verification
passed. The `-dev.dmg` and compatible `.dmg` alias both match **3,694,300 bytes**, SHA-256
`ccb22051b8b333c1e0fff901344fb6dc7b83221aedaf60bd56df0951cab8cf1e`. Website and app-update discovery report 0.2.35/build 38;
signed managed-component indexes remain byte-identical at 0.4.12. Assets, 586 Swift tests,
strict ad-hoc signature, mounted bundle identity/version/build/arm64 and image integrity passed.
No installed user app or local service was replaced. Physical upgrade/login/data-preservation
acceptance remains pending; this does not establish Apple notarization.

## Published internal test distribution: 0.2.34

On 2026-10-24 the owner authorized publication and confirmed **0.2.34/build 37** on the
existing temporary-signing internal channel. This release includes HG-234 onboarding recovery
and HG-235 Desktop product/navigation/update/diagnostic integration and retirement of the old
Desktop connection mode. Build from clean current main with the existing public trust settings.
Use the unified internal publisher for the exact immutable `-dev.dmg`, full public size/hash
readback and both website and app-update indexes. This does not change the signed managed-component
pointers, claim Apple notarization, or replace physical upgrade acceptance.

Publication completed with both unified-publisher success markers on 2026-10-24. Source commit:
`5c83ce9ae446a1fd3bf2c16bc34b716894693646`. The public `-dev.dmg` and compatible `.dmg` alias
both match **3,611,681 bytes**, SHA-256
`ffb29645613c6c235d824317690986fbb18174c6a3bd97ba64aad857fb6be6c9`.
The website and app-update indexes report 0.2.34/build 37; signed managed-component indexes
remain unchanged at 0.4.12. Physical old-Mac update and task/data-preservation acceptance are pending.

## Internal app-update discovery (owner decision, 2026-10-22)

After publishing 0.2.33, the owner reported that Desktop did not discover it and explicitly requested
completing the update chain. This extends the earlier manual-install-only decision below: approved
ad-hoc internal packages also populate `/desktop/apps/index.json`, consumed by already installed
Desktop clients. Temporary signing does not require disabling their existing user-confirmed updater.
The internal publisher retains the `-dev.dmg` artifact and creates an exact versioned `.dmg` HTTP
alias to those same bytes because old clients require that filename. It generates the exact legacy
app-index schema, preserves source/version/build/date/notes/hash, and serves the index with `no-store`.
This alias and schema compatibility do not establish Developer ID signing or notarization.

Use `website/scripts/publish-desktop-internal.mjs` with the completed 0.2.33 publication receipt,
original DMG and notes to repair discovery without rebuilding, replacing bytes or allocating a new
version. The unified publisher verifies both complete public download URLs before switching the
website and app indexes under one lock; either failed index readback restores both old pointers.
It refuses another channel, changed same-version metadata, downgrade and concurrent rollback.
Both synchronization success markers and the receipt's `appUpdateSynchronized: true` are required.
Signed managed-component pointers and official Apple signing/notarization workflows remain unchanged.
Run the physical old-Mac check/confirm/install/relaunch acceptance separately; publication must not
silently replace the owner's installed app.

The sections below record earlier publication decisions and the separately protected Apple channel.

## Explicitly approved internal test distribution: 0.2.33

On 2026-10-22 the owner confirmed that the remaining Desktop artifact should continue to use
temporary signing. **0.2.33/build 36** includes HG-226's managed-setup progress and completion
behavior. Build its `Hermes-Go-Desktop-0.2.33-dev.dmg` from clean current `origin/main`, retain
the installed public release trust/settings, and verify the strict ad-hoc signature, bundle
identity/version/build, arm64 architecture and image integrity. Run the unified internal publisher
described below to upload the immutable DMG, verify the full public bytes and automatically switch
the website's internal-test index. Preserve the signed managed-component pointers and the official
app-update index. This authorization is for manual-install internal distribution; it does not
claim Developer ID signing, notarization or physical-device acceptance.

## Explicitly approved internal test distribution: 0.2.32

On 2026-10-12 the owner explicitly approved distributing **0.2.32/build 35** as an internal
ad-hoc test build after reviewing the missing Developer ID/notarization gates. This authorization
applies to `Hermes-Go-Desktop-0.2.32-dev.dmg`; it does not relax the official DMG or app-update
publisher's gates. Do not call this artifact notarized or create an official update index for it.

Build from clean current `origin/main` in the isolated release worktree, retain the installed
public release trust/settings, and verify the inner bundle's version/build/identifier, strict ad-hoc
signature and image integrity. Serve only the exact versioned path under
`https://mrlgs.net/desktop/apps/0.2.32/`, using the existing root-owned app store and exact
GET/HEAD-only Nginx route pattern. Do not overwrite a versioned file. Validate Nginx before reload,
then download the complete public DMG and compare its bytes, size and SHA-256 with the release build.
Keep the previous app files and both managed component pointers unchanged. Record the actual source
commit and readback proof in the release receipt; upload alone is not publication evidence.

`/desktop/apps/index.json` remains unchanged (404 at preparation time); this build is installed
manually from the test download. The managed manifests remain independently signed and verified.
Customer PM update/reconnect and iPhone PWA plus notebook SSH acceptance are still required; no
customer installation or service restart is implied by this test publication.

## Website internal-test downloads (owner decision, 2026-10-14)

The owner requested that the website offer the macOS internal test installer and stay synchronized
with later test publications. Publish every approved ad-hoc DMG through
`website/scripts/publish-desktop-internal.mjs`; it uploads the immutable package, installs the exact
GET/HEAD route, completes public DMG readback, and automatically registers and verifies the separate
`/desktop/apps/internal-index.json` pointer before reporting success. There is no skip-sync option.
See `website/README.md` for package receipt and operator inputs. The standalone
`website/scripts/desktop-internal-index.mjs` remains a recovery tool for already published packages.
This pointer accepts only the exact immutable `-dev.dmg` URL with explicit ad-hoc/notarized-false
metadata. Registration verifies the actual DMG's local/public size and hash and atomically switches
the pointer with public readback and rollback. No version bump or DMG rebuild is needed to register
an already verified release. A website synchronization failure fails the complete publication command
and restores the previous pointer when safe. Do not report an uploaded DMG as a completed release
before this command's final success marker.

The website displays the test version, notes, Mac architecture and manual-install/update guidance,
retaining its invitation gate. Its 60-second metadata cache follows this pointer; each download
resolves it afresh. The official `/desktop/apps/index.json`, official publisher, Apple signing gates,
and signed managed-component pointers remain independent. This website registration does not enable
Desktop in-app GUI updates or establish installation/device acceptance.

Desktop release discovery uses three mutable, cache-disabled pointers:

- `/desktop/releases/index.json` for the Desktop application release envelope;
- `/desktop/components/index.json` for the Node + Connector component envelope;
- `/desktop/apps/index.json` for the Desktop application binary update (see "App update channel").

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

The manually dispatched `desktop-app-release.yml`, `connector-release.yml`, and
`desktop-managed-release-package.yml` candidate workflows reference
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

The signed-pair candidate job accepts a new managed release version and the exact Connector version.
From clean current `main`, it builds schema-v1 and schema-v2 Connector archives, reuses the immutable
0.4.3 Hermes Server archive only after checking its pinned SHA-256, and signs both new manifests with
the environment-scoped Ed25519 private key. It verifies that the corresponding public key still
authenticates both live indexes and that the proposed version is strictly newer before signing,
removes its temporary private-key file before running
the independent public-key verifiers, and uploads only the two complete signed release directories.
It does **not** stage Nginx routes, change public indexes, create a tag, or publish a DMG. These remain
the explicitly reviewed transaction below; a successful Actions artifact is not a release.

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

## App update channel

The Desktop application binary has its own discovery pointer: `/desktop/apps/index.json`, a schema-1
document naming the latest `appVersion`, its exact DMG URL under
`/desktop/apps/<version>/Hermes-Go-Desktop-<version>.dmg`, byte size, SHA-256, minimum macOS, bounded
`releaseNotes`, and source commit. `DESKTOP_RELEASE_MANIFEST.md` carries the exact schema and the
client-side rules.

`desktop-app-release.yml` builds and notarizes the DMG, sets the app update build settings, and
generates the index with `scripts/desktop-app-update-index.mjs`; both the DMG and the index are
uploaded only as short-lived workflow artifacts. That job does not publish.

Publication is the separate, explicitly reviewed transaction in
`scripts/publish-desktop-app-update.sh`. It requires a clean worktree at current `origin/main`, the
notarized DMG, and its generated index; it re-checks that the index names exactly that DMG's version,
size, SHA-256, and URL. It uploads one immutable version directory, downloads the public DMG and
compares it byte-for-byte, switches `index.json` through a `.next` file, reads the public index back
and compares it, preserves the previous index for `--rollback`, and only then creates and pushes the
`desktop-app-v<version>` tag. The protected pre-staged mode (`DESKTOP_RELEASE_PRESTAGED_PROTECTED=1`
with `DESKTOP_RELEASE_REMOTE_APP_ROOT`) never uploads into a root-owned store.

An Actions artifact, a tag, or an updated `index.json` is not by itself proof that an installed Mac
received the update; only the public readback plus a real update run closes that gate.
