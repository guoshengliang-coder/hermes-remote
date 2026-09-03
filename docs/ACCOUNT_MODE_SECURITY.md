# Hermes GO account-mode security model

Status: I0 threat model. It covers Hermes GO components only and requires no Hermes change.

## 1. Assets and trust boundaries

Protected assets:

- Google-backed account ownership;
- Hermes GO access/refresh sessions;
- phone-installation authorization and notification cursors;
- the one active Connector binding and its private key;
- legacy App/Connector Tokens during migration;
- the Mac-local Hermes username/password, Cookie, WS tickets, files, prompts, and responses;
- binding/audit data and user display metadata.

Trust boundaries:

```text
Google -> Desktop/Android -> public Nginx -> Gateway/account DB
                                           -> outbound Connector -> localhost/private Hermes
```

- Google is trusted only to sign an identity proof for an exact registered audience.
- Desktop and each phone are separate potentially lost/compromised installations.
- The public edge and Gateway receive Hermes GO credentials but never the Mac-local Hermes password.
- The Connector receives tunnel traffic for its active account binding and alone adds local Hermes
  credentials.
- Hermes is an external local dependency. Hermes GO observes/calls its existing APIs but never patches
  its code or data.

## 2. Security invariants

1. A verified Google subject maps to one internal account; email/name/avatar cannot authorize access.
2. Account A cannot address, observe, revoke, or route through account B's Connector or phones.
3. There is at most one active Connector binding per account, enforced transactionally.
4. A second Mac cannot replace the active binding without recent reauthentication and a single-use
   explicit confirmation.
5. Phone revocation affects only that installation unless an account-wide operation is explicitly
   selected.
6. Google proof is never reused as a Hermes GO session or Connector credential.
7. Connector proof is possession-based, connection-bound, short-lived, and replay-resistant.
8. The Mac Hermes credential never leaves the Mac or appears in logs/diagnostics/account storage.
9. Legacy authentication stays isolated from account authentication; ambiguous dual credentials are
   rejected.
10. A failed migration leaves the last known-good Connector active and does not touch Hermes.

## 3. Threats and required controls

| Threat | Impact | Required controls | Verification |
| --- | --- | --- | --- |
| Forged/tampered Google token | Account takeover | Official signature/JWKS verification; exact issuer/audience/expiry/nonce; TLS; bounded cache | Invalid signature/issuer/audience/nonce tests |
| Email change or duplicate display email | Cross-account join | Key only by provider issuer+subject; email display-only | Same email/different subject isolation test |
| OAuth callback interception | Desktop account takeover | System browser, PKCE S256, state, nonce, loopback-only ephemeral listener, short timeout | State/verifier/port/cancel tests |
| Stolen phone access token | Temporary account/Hermes access | 15-minute access expiry, installation binding, revocation, rate limits | Revoke and expiry tests |
| Stolen/replayed refresh token | Persistent phone access | 256-bit opaque token, hash at rest, rotate every use, family reuse detection/revocation | Parallel/reuse/restart tests |
| Stolen Mac Connector key | Persistent Connector impersonation | Non-exportable/protected key where possible, signed challenge, generation/revoke/replace, no bearer copy in logs | Challenge/replay/old-generation tests |
| Malicious second Mac | Redirect phones to attacker Hermes | Recent reauth, explicit replacement, pending candidate, atomic commit, old binding preserved on failure | Concurrent/cancel/failure replacement tests |
| Cross-account object ID guessing | Data/control leak | Authorize every query by session account; uniform 404; opaque UUIDs | Cross-account matrix |
| WebSocket replay or pre-auth resource exhaustion | Impersonation/DoS | Random connection challenge, five-second timeout, single use, per-IP/global unauth limits, max payload | Replay/timeout/capacity tests |
| Token in URL/referrer/log | Credential leak | Authorization header only; reject query tokens in account mode; central redaction | Log/trace/proxy inspection |
| Ambiguous legacy + account credentials | Wrong-tenant routing | Reject requests containing both authentication modes | Dual-header test |
| Compromised Gateway/database | Account metadata/credential attack | Token hashes; authenticated-encrypted, bounded idempotency responses; public Connector key only; encrypted backups; least-privilege service role; rotation procedures | Storage inspection/restore drill |
| Connector routing bug | Cross-phone/account response leak | Owner maps include account+installation+request/tunnel; response only to exact owner | Adversarial concurrent routing tests |
| Multi-phone cursor sharing | Lost/leaked notifications | Per-installation cursor/ack; local read state remains local | Two-phone event tests |
| Logs/support bundle leak | Secret or content exposure | Strict allowlist; redact tokens/cookies/headers/queries; exclude prompts/output/files/provider claims | Canary-secret scans |
| Account/Google outage | Unnecessary service loss | Connector machine credential independent; cached valid sessions until expiry; clear layer-specific status | Dependency-outage tests |
| Migration interruption/power loss | Remote outage/duplicate Connector | Staged config, single-writer lock, exactly-one-process checks, durable state machine, automatic rollback | Kill at every state boundary |

## 4. Credential lifecycle

### Phone/Desktop account session

1. Client generates nonce and completes platform Google sign-in.
2. Client sends provider ID token once to `/v2/auth/google/exchange`.
3. Gateway verifies it and issues a Hermes GO access/refresh pair for one installation.
4. Access expires after 15 minutes. Refresh rotates both values.
5. Sign-out revokes the session; phone removal revokes all sessions for that phone.
6. Refresh reuse revokes the family and forces interactive sign-in on only that installation.

Desktop account UI logout does not implicitly revoke/stop the separately enrolled Connector. The UI
must say whether the user is signing out of management or unbinding remote access. Unbind is a
separate recent-reauthenticated destructive action.

### Connector binding

1. Desktop generates a Connector key pair on the Mac.
2. Authenticated Desktop registers only the public key as a pending or active binding.
3. Gateway challenges each outbound `/v2/connect` connection.
4. Connector signs the challenge with the private key; Gateway verifies active account/generation.
5. Replacement atomically activates a new generation and invalidates the old generation.
6. Key rotation creates a new generation and commits only after candidate health validation.

The private key is never uploaded, copied into the DMG, stored in source control, or returned by a
diagnostic API.

## 5. Authorization matrix

| Operation | Phone | Desktop UI | Connector |
| --- | ---: | ---: | ---: |
| Read own account | Yes | Yes | No |
| Read active remote-device status | Yes | Yes | Own binding during proof only |
| Use Hermes facade | Yes | Optional diagnostic probe | Tunnel only |
| Sign out current session | Yes | Yes | No |
| Revoke current phone installation | Yes | No | No |
| List/revoke all phones | No in V1 | Yes | No |
| Create first Connector binding | No | Yes | Proves key after creation |
| Replace/unbind Connector | No | Yes + recent reauth | No |
| Read prompts/responses/files from account DB | Never | Never | Never; only transient tunnel access |
| Change Hermes source/config/update | Never | Never | Never |

## 6. Privacy and retention

Store only what V1 needs:

- account UUID and provider subject tuple;
- display email/name/avatar only for UI, refreshable and deletable;
- installation names/platform/app versions/last seen;
- binding public key/fingerprint/generation/status/last seen;
- credential hashes and lifecycle timestamps;
- allowlisted security/audit events.

Do not store Hermes prompts, responses, session history, tool output, local file paths/content,
approval payloads, Hermes Cookie/password, provider ID token, raw OAuth claims, or exact provider
responses in the account database or audit log.

Retention values are an I1 operator configuration with documented defaults and cleanup jobs. Audit
retention must be finite; revoked/expired token rows remain only as long as needed for replay defense
and incident review.

## 7. Failure behavior

- Provider verification unavailable: no new login; existing valid Hermes GO/Connector credentials
  continue according to their own expiry.
- Account DB unavailable: fail closed for new authorization/mutations; do not silently fall back from
  an account credential to a global legacy token.
- Connector proof failure: reject only that connection; do not modify the binding or Hermes.
- Replacement failure before commit: old binding remains active.
- Replacement commit succeeds but new Connector disconnects: show the new binding offline; never
  resurrect the old generation without an explicit rollback operation.
- Refresh reuse: revoke the family and require sign-in; do not reveal whether another device used it.
- Unknown account-owned resource: return the same not-found response as a cross-account identifier.

## 8. Security review gate

I0 is accepted only when:

- all invariants map to API/database constraints and tests;
- no credential class has ambiguous ownership or storage;
- account mode adds no public Mac listener;
- replacement and migration have explicit commit/rollback boundaries;
- logs/diagnostics use allowlists rather than best-effort redaction alone;
- the old App/Connector Token path cannot grant account-management privileges;
- no Hermes modification is needed for authentication, routing, diagnostics, or rollback.
