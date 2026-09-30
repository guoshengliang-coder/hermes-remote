# HG-114 · Browser default-model read security design

Status: approved by the owner on 2026-09-23; implemented in the HG-110–HG-114 batch.

## Purpose and response contract

The Web app needs the selected Mac's profile default to label the sidebar and to offer a
conversation-scoped “restore default” action. A successful read returns exactly:

```json
{"model":"model-id","provider":"provider-id"}
```

Both values are nonempty strings. No config object, path, credential, endpoint URL, profile
metadata or diagnostic text is returned. If either value cannot be established, the request fails
with a registered, localized `HR-*` error; it never guesses a provider. The browser treats the
result as untrusted text. “Restore default” is offered only when both identifiers pass the existing
`/model <model> --provider <provider> --session` validator. It changes the effective model of the
current conversation; it does not clear the upstream override or change the global default.

## Route and trust boundary

Use an exact Connector-owned `GET /api/hermes-remote/default-model` route, reached by the browser
through the existing device-scoped Gateway path. The Gateway browser allowlist admits only `GET`
and `HEAD` on that exact path and only zero or one validated `profile` query value. It rejects a
request body, duplicate `profile`, encoded path separators, and any other query key. It continues to
reject every method on `/api/config` and direct
browser JSON-RPC reads of configuration. The existing browser cookie, same-origin read rule,
account/device authorization, and Connector routing run before forwarding. This original read contract remains unchanged; the HG-166 extension below adds a separate bounded write.

The Connector obtains the profile-scoped default from local Hermes
`GET /api/model/info?profile=…` using its existing Mac-only Hermes credential. That upstream
response includes the configured `model` and `provider` plus context and capability metadata;
the whole response stays on the Mac. The Connector validates and extracts only those two string
fields, then sends the two-field JSON through the tunnel. It bounds upstream response size and
identifier length, and refuses missing or malformed values. It does not log raw responses or
include them in errors. This avoids putting the full upstream response into the Gateway's
streaming response path, where headers are currently the only transform. It also avoids reading
the full `/api/config` or building a provider catalog merely to label the default.
The Connector answers `HEAD` with the same status and headers as `GET`, without a body.

This source shape was checked against the local upstream Hermes source:
`hermes_cli/web_routers/models.py:get_model_info` and `_main_model_fields`. The upstream version
and test fixture must be pinned in `docs/HERMES_CONTRACT.md` when implemented.

## Cache and failure behavior

The response carries `Cache-Control: private, no-store`; it is excluded from the service worker
cache and browser persistent storage. The UI fetches it for the selected Mac/profile and discards
it on device, profile or account changes. It cannot fall back to another Mac's value. A missing
Connector, an upstream error, an unresolved provider or malformed data produces a stable localized
error with a retry action where useful; the model labels and restore action stay hidden rather
than showing stale data. Diagnostics retain only the error category and selected opaque device ID.

## Required tests before merge

1. Gateway allowlist: exact GET/HEAD and valid profile only; reject `/api/config`, sibling paths,
   encoded separators, duplicate/extra query keys, request bodies, all mutations,
   missing/foreign-origin cookie reads and
   unauthorized device access before reaching the Connector.
2. Connector: inject an upstream response canary containing credentials and paths; prove the
   tunneled success body has exactly two string fields and no canary. Cover missing, oversized
   and malformed upstream responses, profile isolation and upstream errors.
3. Web: display and restore only for the current device/profile; verify stale-response cancellation,
   the existing session-only slash command, and graceful failure. The read path does not write a model.
4. Build and test the Web, Gateway and Connector, then run the repository Cloud baseline. Smoke
   test the browser route on an isolated stack before any production rollout.

This design changes a device REST contract. `docs/ACCOUNT_MODE_SECURITY.md`,
`docs/ACCOUNT_MODE_API.md`, `docs/HERMES_CONTRACT.md`, and the cross-client assessment required by
`docs/INTEGRATION.md` must be updated with implementation. Merging the PR does not deploy Gateway
or Connector; production rollout has its own authorization and gate.

## HG-166 bounded main-model write (approved 2026-09-30)

The owner approved complete Web/Android model alignment in the dispatch session. The original
read remains two-field/no-store. A separate exact `POST /api/hermes-remote/default-model/set` accepts
only model/provider and optional explicit `confirm_expensive_model:true`, with one validated query
profile and ≤4 KB JSON. Gateway checks same origin, CSRF and device authorization before forwarding;
Connector independently checks the path/body, adds `scope:main` and uses its Mac-only credential for
`POST /api/model/set`. It bounds the response to 16 KB and returns only `{model,provider}` on an
`ok:true,scope:main` response, or `{confirm_required:true}` on a main-scope confirmation response.
Upstream endpoints, cron impact, warning prose and credentials never enter the browser response.
The UI supplies its own bilingual confirmation text and never assumes HTTP 200 means applied.

This extends the previous read-only product decision only for main-model assignment. `/api/config`,
raw `/api/model/set`, global config RPC, credentials, endpoints and auxiliary assignments stay blocked.
Changing the default affects new sessions; existing conversations change only through their own
session selector. Browser favorites and reasoning presets are local and do not sync to Android.

Tests: `connector/src/tunnel-routes.test.ts`, Gateway browser route/RPC tests and disposable-DB
integration, Web model parser/session/selection/API tests. Required deployment order is Connector,
Gateway, Web; production remains a separate owner-authorized gate.
