# Desktop component layout migration

Status: GUI and transaction implementation complete; publication and physical migration are separate gates.
Owner approved this direction in the HG-237 dispatch conversation on 2026-10-25.

## Delivery model

Desktop presents one update flow for its GUI and managed Node/Connector components. The GUI app
does not embed those executable archives. Hermes remains the owner's standard installation and
uses its existing home and database. New component releases contain exactly Node and Connector;
they neither package nor install a Hermes archive.

An existing `bundled_release` journal is a separate fact from the Hermes runtime mode: a Mac can
already run its own Hermes while retaining the old Connector layout. Ordinary semver updates must
continue to preserve the journal's layout. Converting that old installation requires an explicit,
separately worded confirmation and a strictly newer signed component release.

## Transaction

`migrateBundledInstallationToComponents` accepts only an account-active bundled installation with
an exact committed account binding, both managed services loaded, no retired Connector, and a
recognized local Hermes agent using the private session-token file. A Mac still running bundled
Hermes must first complete the existing local-runtime switch; this migration never installs or
selects a replacement Hermes. Normal upgrade confirmation cannot authorize layout conversion.
The local entrypoint must still be executable before any snapshot, journal write or service stop;
a loaded process alone cannot prove it could be restarted.

Preparation verifies the signed component preflight and downloads to the private workspace. The
executor records the explicit migration intent in the issued preparation. Cancel or wrong
confirmation leaves the active services, account, journal and component references untouched.

On commit the existing operation lease, durable upgrade snapshot and journal protect the operation:

1. Preserve the exact Hermes and Connector LaunchAgents and previous release/layout in a private
   durable snapshot. Keep account binding ID/generation and private credentials unchanged.
2. Record the target component layout, then write only the Connector agent using the verified
   Node/Connector activation plan. Retain the existing local Hermes agent and launcher bytes,
   including `HERMES_HOME`, profile, token-file path and local entrypoint.
3. Stop Connector, stop Hermes and prove the old loopback listener is gone. Start Hermes, require
   fresh process-specific local readiness, then start Connector and prove fresh Cloud health for
   the same binding ID/generation.
4. Validate the token-file contract, commit `account_active` in `component_store`, then discard
   the snapshot. No binding creation/confirmation or database migration is performed.

Failure or process interruption restores the previous exact agents and journal layout and proves
the original services healthy. The old bundled `current` pointer and archives remain available
for recovery; this change does not delete them. After a successful component migration, ordinary
runtime reconciliation cannot silently return to a kept bundled Hermes, including when the local
runtime preference is disabled. Existing registered runtime-attention errors explain recovery.

## Publication

The standard entry remains `scripts/publish-desktop-release.sh`.
`DESKTOP_RELEASE_COMPONENTS_ONLY=1` selects the new component-only mode for publish and `--rollback`:

- Supply `DESKTOP_COMPONENT_MANIFEST` and the existing component target/signing/public-origin
  configuration. No v1 manifest or remote release root is required in protected pre-staged mode.
- Use an unused `desktop-components-v<version>` tag. The candidate must be strictly newer than
  the current component index and match its channel and architecture.
- Verify the signed envelope and every local archive, bind the signed release/version and artifact
  URLs to the requested index namespace, then compare the entire public manifest and both archives
  byte-for-byte. Recheck clean current `origin/main` immediately before the index can change.
- Switch only the component index. A failed index readback restores its previous bytes. The legacy
  release index is untouched; rollback in component-only mode swaps only component pointers.
- The signing-continuity gate still verifies both supported public manifests independently; their
  versions may differ once the legacy channel is frozen. It rejects a proposed version that is not
  newer than every channel it checks.

The protected signed-candidate workflow defaults to `components_only: true`; explicitly choosing
false retains historical pair generation for recovery. Normal candidates do not fetch the old
Hermes archive. This does not replace environment approval or authorize release-key provisioning.
The paused HG-237 attempt already exposed immutable versioned 0.4.13 files. A future candidate
with different bytes must use a newly allocated version; do not overwrite or reactivate that pair
as an incidental part of implementing migration.

## Validation and remaining integration

Automated coverage exercises explicit confirmation, immutable preparation intent, successful
conversion without binding creation, exact Hermes/token retention, local health failure rollback,
restart recovery, ordinary cross-layout refusal, and post-commit refusal to restore bundled Hermes.
Journal tests require the explicit one-way migration flag before any cross-layout write.

Publisher tests use real signed fixture archives and a local remote-filesystem harness: successful
component-only publication, full-byte archive mismatch, index readback mismatch, current-main
movement, signed-version mismatch, downgrade refusal, independent channel key verification and
component-only rollback. No production services are touched by those tests.

The GUI now discovers the component channel for a bundled installation only with usable local Hermes and a recognized local managed agent. The unified update flow issues the explicit migration preparation and shows the distinct migration confirmation. The owner authorized completion and merge/publication after reviewing the earlier shared-file coordination note; draft compatibility PR #673 remains independent.

Manual acceptance remains required on a real Mac: cancel before commit; migrate bundled/local
installation; verify the same binding, home, token and conversations; confirm single managed
Connector and fresh mobile traffic; inject candidate health failure and restart interruption;
then verify upgrade on an already-component installation and clean setup using the same component
channel. Record the Mac and actual results; automated launchctl fakes do not establish physical
service acceptance.
