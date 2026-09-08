# Account expansion E6 Web-session local test record

Date: 2026-09-07

## Outcome

The local secure Web-session foundation is implemented behind `ACCOUNT_WEB_SESSION_ENABLED=0`.
Schema 12 adds `browser/web` installations; schema 13 attributes new sessions to their establishing
identity and adds safe identity unlinking; schema 14 adds the dedicated account-installation
revocation scope. The implementation includes separate Web Google audience verification, email and
Google exchange, refresh, account read, and sign-out without returning account bearers in JSON. The
same-origin interactive first slice adds email-code sign-in, identity/device discovery, default Mac
selection, account-installation/session management, a metadata-free security audit view, and
create/list/cancel/revoke/accept/leave whole-device sharing with provider-neutral recent reauthentication.
The signed-out view also loads Google's official Identity Services button on demand, binds the proof
to a page-generated nonce, and exchanges it without browser storage.

Access and refresh credentials are host-only Secure/HttpOnly/SameSite=Strict cookies. Mutations fail
closed unless the exact configured HTTPS Origin, same-origin Fetch Metadata when supplied, and a
matching host-only CSRF cookie/header are present. Duplicate, malformed, or oversized Cookie input
is rejected. Browser installations cannot satisfy Desktop-only authorization checks.

No production flag, credential, DNS record, remote service, Android source, installed Mac service,
or deployed public Web page was changed. The local source now contains an interactive page, but no
production host serves it.

## Automated evidence

- `npm run build` passes for Protocol, Gateway, and Connector.
- Full `npm test` with disposable PostgreSQL 18 and network tests passes: Protocol 13, Connector 17,
  Gateway 109, release server 30, and scripts 88 tests (257 total, zero failed/skipped).
- Web session/controller tests cover default-off behavior, bootstrap, Cookie attributes, email and
  Google exchange, bearer-free JSON, Origin/Fetch Metadata/CSRF rejection, duplicate Cookie
  rejection, refresh rotation, account read, sign-out revocation, Cookie clearing, same-origin asset
  policy, fixed Google GIS source/CSP/COOP, nonce-bound proof exchange, unsafe-DOM/browser-storage
  exclusion, Google/email identity linking, identity/device discovery, default selection, recent
  provider-neutral reauthentication, installation/audit discovery, installation revocation, and every sharing
  mutation.
- PostgreSQL 18 integration tests apply all migrations through schema 14 and prove a Web login is
  stored only as `browser/web`; identity unlink rejects the last identity, replays exact requests,
  revokes attributable access/refresh sessions, and serializes a two-identity removal race so only
  one deletion succeeds. Installation revocation rejects the current installation and wrong scope,
  supports exact replay, revokes target access/refresh state, preserves Connector binding, and keeps
  foreign installation IDs isolated. A dedicated PostgreSQL listener connection propagates only
  committed account/session/installation/binding invalidations to every Gateway and rejects malformed
  payloads; rollback emits nothing, exact matching sockets close, and the five-second database check
  remains the fallback. Account, email OTP, control, multi-device, and sharing suites remain green.
- The internal-token-protected, loopback-only email snapshot aggregates a strict one-hour window for
  OTP/share provider acceptance, failures, pending work, and safe completion counts without emitting
  recipient, message, challenge, invitation, account, or device identifiers. Provider acceptance is
  explicitly not represented as final inbox delivery.

## Remaining E6 gates

- physical keyboard/screen-reader/light-dark/narrow-layout browser-matrix acceptance (no compatible
  browser instance was connected during this local run);
- verified-mail-domain live delivery;
- staging soak, rollback, and deployment authorization.

Google button, OAuth-origin, and live identity-flow acceptance moved to the future provider milestone
and is not an email-first E6 gate.

The feature remains unavailable by default and this record is not production-release evidence.

## Email-first rollout addendum

On 2026-09-07 the release order changed to email OTP first. Google is now controlled by the separate
default-off `ACCOUNT_GOOGLE_AUTH_ENABLED` flag. With that flag off, account/Web startup needs no
Google client IDs, capabilities advertise only `email_otp`, Google HTTP routes return the uniform
not-found contract without invoking verification, the Web bootstrap returns
`authentication.google: null`, Google controls stay hidden, and CSP contains no Google origin.
The already implemented Google path and its regression tests remain for a later provider milestone.
Live Google setup and acceptance therefore no longer block the email-first E6 gate.

Standard local verification after the change passed `npm run build` and `npm test`: Protocol 13,
Connector 17, Gateway 112, release server 30, and scripts 88 tests (260 total; 242 passed and 18
environment-gated PostgreSQL/network cases skipped). This configuration-only provider split adds no
database migration. The existing full PostgreSQL/network evidence above remains the latest database
gate and is not restated as a new run.

## Final-delivery webhook addendum

On 2026-09-08 schema 15 added final transactional-email delivery handling without changing any
production flag or service. Resend submissions now persist the bounded provider message ID and add
opaque message-kind/UUID tags. `POST /v2/webhooks/resend` verifies the untouched raw body with the
dedicated Svix secret and unique signature headers, durably deduplicates `svix-id`, and applies only
newer events that match both identifiers. Bounce, complaint, provider failure, and suppression
invalidate unused OTPs and pending sharing invitations; consumed OTPs and accepted invitations are
not rolled back. Only opaque correlation fields are stored. Live verified-domain delivery remains a
separate staging gate.

The final local gate used disposable PostgreSQL 18 plus loopback network integration and passed
Protocol 13, Connector 17, Gateway 121, release-server 30, and script 88 tests (269 total, zero
failed/skipped). It covers signature mutation, duplicate headers, stale/malformed/oversized input,
route isolation, durable deduplication, out-of-order events, the send-response/webhook race, 35-day
opaque receipt retention, terminal invalidation, final aggregate metrics, schema-15 migration
restart safety, and all existing account/legacy compatibility suites.

An additional loopback HTTP boundary test now sends the signed payload through Node's real client,
server, and `GatewayHttpRouter`. It proves that the exact raw bytes are accepted, a one-byte mutation
is rejected, and two physical `Svix-Signature` header lines remain visible and fail closed instead of
being silently treated as one signature. This test is local-only and sends no provider email.

The repository now also includes a staging-only provider acceptance command. Its default preflight
is read-only; the send path requires an exact `staging:<account-host>` confirmation and permits only
Resend's delivered/bounced test addresses. It compares one-hour PII-free counters before and after
the probe and fails on concurrent deltas, timeout, or an absent final callback using `HR-OPS-017`.
The command has deterministic no-network regression tests, but has not been run against Resend or a
deployed staging host in this record.

A separate credential-free domain audit now validates the exact public SPF TXT, Return-Path MX,
DKIM TXT, and DMARC TXT contract before provider staging. It handles split DKIM TXT chunks, rejects
ambiguous SPF/MX/DMARC data, enforces a configurable minimum DMARC policy and aggregate reporting,
and emits only pass/blocked reason codes. Five deterministic DNS-resolver tests pass without network
access. The audit has not been run against the future sending domain, so public propagation, Resend
dashboard verification, and delivered-message authentication headers remain external evidence.

The email privacy-retention addendum now gives challenge hashes the same explicit 35-day correlation
window as opaque Webhook receipts. New OTP issuance deletes at most 1,000 rows per transaction only
when both the creation cutoff and expiry condition pass. A PostgreSQL 18 regression creates 1,001 old
rows, proves the first issuance removes exactly 1,000, proves the next issuance converges the final
row, and retains rows exactly 35 days old plus newer rows. The schema-15 migration adds the
supporting creation-time index; no production database was changed.
