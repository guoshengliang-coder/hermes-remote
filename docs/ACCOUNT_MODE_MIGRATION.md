# Hermes GO legacy-to-account migration and rollback

Status: I0 state-machine contract. Implementation belongs to I5 and is disabled until earlier account
and client gates pass.

## 1. Compatibility objective

Existing Android clients and the installed Mac Connector must keep working while account mode is
introduced. Shipping account-capable code does not overwrite existing Token configuration, restart
the Connector, change Hermes, or disable legacy authentication.

Account sessions, Connector machine credentials, and legacy App/Connector Tokens are distinct. A
client or Connector reports its active mode explicitly; the Gateway never infers mode from a display
name, email, or device ID.

## 2. Rollout phases

| Phase | Gateway | Desktop | Android | Existing users |
| --- | --- | --- | --- | --- |
| M0 Current | Legacy only | Observation + QR/manual | Token/QR | Unchanged |
| M1 Dark launch | Account schema/routes disabled | Current behavior | Current behavior | Unchanged |
| M2 Test accounts | Legacy + allowlisted account auth | Account UI, no takeover | Opt-in account login | Legacy remains active |
| M3 Migration beta | Dual auth/routing | May stage/validate account Connector | Account or legacy | Explicit test users only |
| M4 Account default | Dual auth/routing | Account onboarding default | Google login default | Legacy recovery remains |
| M5 UI retirement | Dual auth for grace period | Legacy hidden/recovery only | Legacy hidden/recovery only | Measured support window |
| M6 Server retirement | Separate later release | Account only | Account only | Requires G5 decision |

M4–M6 are separate decisions. No release both makes account mode the default and removes legacy
server acceptance.

## 3. Connector migration state machine

Durable local state uses these states:

```text
legacy_active
  -> preflight
  -> account_staged
  -> candidate_starting
  -> candidate_authenticated
  -> candidate_healthy
  -> commit_pending
  -> account_active

any state before account_active -> rolling_back -> legacy_active
```

Rules:

- One single-writer migration lock protects the transition.
- Each state and the last known-good mode are written atomically before the next side effect.
- Desktop resolves the exact launchd label, executable, PID, launch count, configuration location,
  and current health before staging.
- Staging writes only Hermes GO/Connector-owned configuration and key material. It never writes
  `~/.hermes`, Hermes source, Hermes dotenv, or Hermes service definitions.
- A candidate cannot become active at Gateway while the legacy process is still serving the same
  logical device in a way that creates ambiguous routing.
- Exactly-one-process and expected-binary checks run before stop/start/commit operations.
- Candidate health requires Connector authentication, Gateway registration, local Hermes probe, and
  an end-to-end Hermes-compatible request within bounded time.
- Binding activation/legacy mapping switch is the remote atomic commit. Local success is recorded
  only after the committed generation reconnects.

## 4. Preflight and snapshot

Before any mutation, Desktop must:

1. Confirm account authentication and the intended account/binding.
2. Confirm the current user explicitly selected **Upgrade connection**.
3. Verify the existing Connector is installed/running or classify its exact absence state.
4. Verify there is exactly one owned Connector process.
5. Probe Gateway, local Hermes, and legacy end-to-end access.
6. Verify available disk space and permissions for a narrow migration workspace.
7. Record safe hashes/metadata of Connector-owned configuration and launchd files.
8. Create a permission-restricted rollback snapshot containing only Connector-owned state.
9. Generate/register the new Connector public key as pending; the private key stays on the Mac.
10. Record a correlation ID and durable migration state without secrets.

If any required preflight fails, migration does not start and the legacy Connector is untouched.

## 5. Candidate validation and commit

The migration implementation chooses a mechanism that never runs two production-serving Connectors
simultaneously. The accepted sequence is:

1. Stage account configuration/key while legacy remains active.
2. Request a bounded maintenance transition from Desktop and mark `candidate_starting` durably.
3. Stop only the exact resolved legacy Connector through its owned launchd label; verify its PID exits.
4. Start the candidate with staged account credentials; verify exactly one process.
5. Complete `/v2/connect` challenge authentication against the pending binding.
6. Probe local Hermes and account-mode end-to-end routing.
7. If healthy, atomically activate the binding/mapping at Gateway.
8. Reconnect/verify the committed generation, persist `account_active`, then age the rollback snapshot
   according to the documented retention.

The maintenance window is visible to phones as Connector reconnecting, not as Hermes failure.

## 6. Automatic rollback

Before remote commit, any timeout, process mismatch, authentication failure, local Hermes probe
failure, end-to-end failure, power-loss recovery uncertainty, or unexpected file ownership triggers:

1. set `rolling_back` durably;
2. stop only the exact candidate process if present;
3. revoke/cancel the pending binding/key generation;
4. restore the narrow Connector-owned snapshot atomically;
5. start the exact legacy launchd service;
6. verify one legacy Connector, Gateway registration, local Hermes, and legacy end-to-end access;
7. persist `legacy_active` and expose a sanitized migration error.

Rollback never restores or changes Hermes because Hermes was never part of the snapshot.

If automatic rollback itself cannot re-establish the legacy path, Desktop enters
`rollback_attention_required`, keeps both candidate and automatic retries stopped to prevent a loop,
and presents `HR-MIGRATE-004` with an operator-safe manual recovery procedure. It still does not touch
Hermes.

## 7. Post-commit rollback

After the account binding has committed, rollback is an explicit authenticated operation rather than
automatic resurrection of an invalid old generation:

1. Require recent account reauthentication and explain the temporary remote interruption.
2. Verify the retained legacy snapshot and that legacy Gateway acceptance is still enabled.
3. Create a pending legacy mapping/credential restoration operation.
4. Stop the account Connector, restore/start legacy, and validate legacy health.
5. Atomically switch/revoke the account binding only after legacy validation succeeds.
6. If legacy validation fails, restore the account Connector generation and leave the account binding
   authoritative.

This operation is available only during the compatibility window and is removed separately from
normal account-mode release work.

## 8. Android migration behavior

- Updating the APK does not delete the saved URL/Token or silently sign the user into account mode.
- Google sign-in creates an account session beside the legacy configuration until account routing is
  verified.
- Android switches its active mode only after it receives a healthy account binding and completes an
  authenticated end-to-end probe.
- If account activation fails, the app restores the previous active mode and retains the new account
  session for retry unless it is invalid/revoked.
- Conversation/session data do not migrate; they remain on the same Hermes.
- “Legacy connection” remains reachable from Remote device details during the compatibility window.
- Sign out on this phone removes account credentials but does not delete legacy configuration without
  a separate explicit action.

## 9. Power-loss recovery

On every Desktop launch, migration recovery reads durable state before starting or managing a
Connector:

- `legacy_active`: observe/start only the configured legacy service under existing policy;
- pre-commit intermediate state: prefer rollback to last known-good legacy unless the recorded remote
  commit is proven complete;
- `commit_pending`: query the binding generation; if old is active, roll back; if new is active,
  complete local account activation; if unknown, stop and require attention rather than guessing;
- `account_active`: start/authenticate only the committed account generation;
- `rolling_back`: continue the idempotent rollback steps;
- `rollback_attention_required`: do not loop or start a second Connector.

Every step is idempotent and checks the exact target before a process or file mutation.

## 10. Migration exit criteria

Migration may enter target-Mac beta only after automated fault injection proves recovery at every
state boundary and local tests prove exactly one Connector process. Legacy retirement requires all of:

- account default has operated through an agreed observation window;
- supported installed clients can use account mode;
- rollback and backup/restore drills pass;
- authentication/binding/migration error rates are understood;
- no unresolved credential leakage or cross-account isolation finding exists;
- operator documentation and support recovery are current;
- explicit G5 approval is recorded.
