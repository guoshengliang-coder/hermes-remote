# Hermes Go Desktop test plan

## Automated baseline

Run on macOS:

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
```

The current automated suite covers:

- overall status reduction and optional-capability degradation;
- deterministic health-snapshot assembly, latency/issue propagation, and shared menu/window/settings
  presentation for every health and account state;
- legacy Connector config parsing with a strict non-secret allowlist;
- current Gateway WSS to Relay health URL conversion;
- legacy launchd/install/log discovery through injected command execution;
- log/header/query/known-secret redaction;
- profile validation that rejects insecure remote HTTP, URL credentials, queries, and fragments;
- exact Android v1 JSON payload compatibility;
- Core Image QR output decoded back to the exact v1 payload with the native QR detector, including payload-size rejection;
- authenticated `/api/status` request path and header behavior;
- 401/403, offline Connector, and unmapped Relay error classification;
- bilingual error code, retryability, recovery-action, and diagnostic redaction contracts;
- recent-log warning counting independent of current health;
- full SwiftUI target compilation.
- Desktop account capability discovery and default-off behavior;
- PKCE S256, cryptographic state/nonce, provider cancellation, state mismatch rejection, and a real
  ephemeral `127.0.0.1` loopback callback;
- account HTTP paths/headers, bounded responses, stable error mapping, and diagnostic redaction;
- separate account-session and Connector-machine identity stores, Ed25519 challenge signing, access
  refresh, and persisted idempotency-key reuse after a lost refresh response;
- signed-in dashboard reduction, two-phone listing, one-phone removal, and Desktop-only sign-out
  without deleting the machine identity.
- exact scoped-reauthentication, first-bind, replacement, confirmation, and unbind HTTP contracts;
- fail-closed binding capability and response validation, conflict/expiry mapping, cancellation, and
  persisted idempotency-key reuse for every I3-B mutation after a lost response.

Every new phase-0 behavior requires a regression test when its boundary is deterministic. User-visible
error codes additionally require localization, retryability, recovery-action, and redaction tests under
the project-wide `ERROR_HANDLING.md` contract.

## Manual phase-0 matrix

| Case | Expected result | Status |
|---|---|---|
| Existing Connector running | Desktop observes it and does not launch a replacement | Verified on target Mac 2026-09-02; PID and launch count unchanged |
| Existing Connector absent | UI reports not detected and offers no destructive action | Verified 2026-09-02 |
| Gateway available | Gateway layer is healthy with safe latency | Verified on target Mac 2026-09-02; 15–18 ms observed |
| Gateway offline/DNS failure | Only Gateway layer fails; Hermes wording remains accurate | Pending fault injection |
| Hermes returns 2xx | Local Hermes layer is healthy | Verified on target Mac 2026-09-02; 117–128 ms observed |
| Hermes returns 401/403 | Layer says reachable/authentication required, not “down” | Pending real-app check |
| Logs contain credentials | Display/export contains `<redacted>` only | Automated; target redacted UI preview inspected 2026-09-02, credential injection still pending |
| Window closes | Existing Connector remains running | Verified on target Mac 2026-09-02; menu-bar app remained and window reopened |
| Two phones use current Gateway | Existing phone configuration remains usable | Pending device check |
| Light/dark mode | Same cool-blue surface language and semantic states | Light verified 2026-09-02; dark pending |
| App icon | Desktop packaging copy equals canonical Android icon | Verified by packaging gate 2026-09-02 |
| Keychain profile | App Token persists across restart and is never shown in visible UI | Verified locally and on target with disposable test Token 2026-09-02 |
| Invalid App Token | End-to-end check reports `HR-AUTH-001` with recovery guidance | Automated + local/target UI verified 2026-09-02 |
| v1 QR payload | JSON contains only compatible `v`, `url`, and `token` fields | Automated 2026-09-02 |
| QR reveal | Real QR is hidden by default and carries an explicit long-lived-token warning | Local + target UI verified 2026-09-02; Android scan pending |
| End-to-end success | Saved App Token reaches Gateway → Connector → Hermes through `/api/status` | Pending target production-token check |
| Account mode disabled | Account & Devices reports unavailable and legacy connection remains usable | Automated core behavior; packaged UI inspection pending |
| Browser OAuth loopback | Listener binds an ephemeral `127.0.0.1` port and rejects mismatched state | Automated locally; live Google client pending |
| Account session restart | Keychain session refreshes without changing the Connector machine identity | In-memory/store contract automated; packaged Keychain run pending |
| Two account phones | Account & Devices lists both and removes only the selected installation | Controller/API automated; live backend and physical phones pending |

The first real-app check verified that the ad-hoc app launches and remains running. The target Mac run
then verified the installed DMG against a live legacy Connector without changing its PID, launch count,
configuration, log size, or public Relay attachment. The diagnostics UI correctly kept the App-Token
end-to-end check unavailable. Physical two-phone traffic, injected fault cases, and takeover/rollback
remain pending; public Relay health alone is not evidence for a phone UI test.

The `0.2.0-dev` target upgrade additionally verified Keychain persistence across restart, explicit QR
reveal, and the invalid-token UI mapping without restarting the legacy Connector. The disposable Token
and temporary rollback package were removed after the run. A real production Token was deliberately
not retrieved as part of this test, so the successful end-to-end and Android scan rows remain pending.

## Later takeover gate

Managed Agent takeover must not ship until automated and real-machine tests prove:

1. the new Agent does not start while the old Connector is active;
2. configuration validates before the old service stops;
3. the new Agent receives `hello_ack` and passes local/end-to-end checks;
4. failure stops the new Agent and restores the old service;
5. the phone retains its URL, token, sessions, and history after takeover and rollback.
