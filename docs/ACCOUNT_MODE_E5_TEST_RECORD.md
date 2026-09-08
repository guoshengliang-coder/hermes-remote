# Account expansion E5 local test record

Date: 2026-09-07

## Outcome

The local whole-device sharing slice is implemented behind
`ACCOUNT_DEVICE_SHARING_ENABLED=0`. No production configuration, remote service, Android source,
installed Mac service, real account, DNS record, or transactional-email credential was changed.

Implemented locally:

- PostgreSQL schema 11 invitations and operator grants with hashed tokens/mailbox lookup material,
  masked display hints, expiry/status/audit state, self-grant protection, and immutable active grant
  identity;
- owner invite/list/cancel/revoke and operator accept/leave contracts with exact idempotency;
- atomic five-active-grantees-per-device and ten-shared-devices-per-account enforcement;
- shared-device discovery/default selection and owner-or-active-operator REST/WebSocket routing;
- exact live-tunnel revocation in one Gateway process plus immediate post-upgrade and five-second
  authorization revalidation as the multi-process fail-closed fallback;
- Resend adapter support for a plaintext-only bilingual 72-hour whole-device invitation, using the
  invitation UUID as provider idempotency material;
- Desktop capability decoding, owned/operator presentation, share management, fresh Google
  reauthentication, whole-device disclosure, pasted invitation acceptance, and Keychain-preserved
  retry material;
- scriptless Web shell handoff. Interactive Web sharing intentionally remains unavailable until
  Secure HttpOnly cookie sessions and CSRF-protected mutations exist.

## Automated evidence

- `npm run desktop:test`: 96 tests passed. Coverage includes sharing routes, disclosure,
  classification, invite-link parsing, redaction, and lost-response grant/idempotency reuse.
- Focused Gateway sharing service, HTTP, database, and WebSocket revocation tests passed.
- Full `npm test` with a disposable local PostgreSQL 18 schema-11 database and network tests passed:
  Protocol 13, Connector 17, Gateway 91, release server 30, and scripts 88 tests (239 total).

The PostgreSQL race suite verifies six simultaneous accepts produce five grants and one
`HR-SHARE-002`, while an account with nine grants accepting two invitations produces one success and
one `HR-SHARE-003`. It also verifies plaintext destination/token absence, owner/operator permission
separation, shared default cleanup, revocation, self-grant rejection, and immutable active grant
ownership.

## Release blockers still open

- verified sender domain plus SPF, DKIM, DMARC and a real restricted Resend key;
- live email identities and a two-account invitation acceptance run; Google is deferred from the
  email-first release;
- packaged Desktop light/dark/keyboard/VoiceOver inspection;
- interactive Web account-center acceptance on top of the completed local Cookie/CSRF foundation;
- staging soak, rollback rehearsal, production secrets/flag enablement, and deployment authorization;
- Android physical acceptance and its separate APK version/package/release gate.

The later E6 implementation supersedes the original cross-Gateway blocker: committed account,
session, installation, and binding revocations now publish through PostgreSQL to every Gateway,
while the five-second authorization recheck remains the fail-closed fallback.

These are external or later-iteration gates. The local E5 feature remains unavailable by default.

## Final local gate

- `npm run build`: passed for Protocol, Gateway, and Connector.
- `ACCOUNT_TEST_DATABASE_URL=<disposable-local-postgresql> RUN_NETWORK_TESTS=1 npm test`: 239 tests
  passed, zero failed/skipped. The network test includes grantee REST/WebSocket access through the
  owner's Connector followed by immediate owner revocation and post-revoke denial.
- `npm run desktop:test`: 96 tests passed, zero failed.
- `npm run desktop:build`: passed.
- `npm run desktop:app`: passed and produced a locally signed application bundle under
  `desktop/build/`; no installer was produced or distributed.
- `npm run desktop:assets:test`: passed.
