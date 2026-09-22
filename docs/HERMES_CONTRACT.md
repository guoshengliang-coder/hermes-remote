# Hermes upstream contract

Hermes Remote is a **client of upstream Hermes' private API**. Nothing in this repository modifies
Hermes, and there is no version negotiation on the wire: the Android app speaks Hermes' own REST and
WebSocket RPC, and the Gateway and Connector relay those bytes without parsing them
(`tunnel.ws.frame` carries an opaque base64 payload).

That makes upstream renames the largest un-instrumented upgrade risk in the project. This document
is the inventory of what we consume, and the checklist to run before adopting a new Hermes.

`HermesContractTest` (Android unit tests) pins the mechanically checkable parts. When an upgrade
legitimately changes one of them, update the code, that test, and the version recorded here in the
same change.

**Consumers.** Two clients now speak this contract: the Android app (the reference implementation
cited throughout) and, since 2026-09-21, the browser Web app in `web/` (`web/src/hermes/`, ported
from the Android DTOs, RPC framing, server-request handling, `MEDIA:` parsing and search quoting).
The Web app uses a subset: the REST routes and RPC methods on the Gateway's browser allowlists
(`docs/ACCOUNT_MODE_API.md` §8) — nothing outside `HERMES_REST_CONTRACT` and
`docs/hermes-rpc-params.json`. Its unit tests check every RPC param it can emit against that params
file. A surface change here therefore needs both clients addressed in the same change, and the Web
app's allowlists in the Gateway (`gateway/src/account/web-device-access.ts`,
`web-rpc-filter.ts`) when it adds a route or method. Since Web batch 4 (2026-09-22) the Web app also
calls `PATCH`/`DELETE /api/sessions/{id}`, `GET /api/model/options`, `session.workspace.move`,
`slash.exec` (only `/model <id> --provider <id> --session`), `config.get`/`config.set` (only the
session's `reasoning`), `process.list` and `session.access`. The Gateway admits those **by parameter
shape**, so an upstream change to their params (a renamed key, a new required key, new reasoning
values) must update the Gateway validators as well as both clients, or the Web app gets `HR-WEB-001`.

## Adapted upstream version

| Field | Value |
|---|---|
| Hermes version | **0.21.0** |
| Commit | `f159e581c7` |
| Verified | 2026-09-05, against the production install on the Mac mini (`~/.hermes/hermes-agent`) |

## Consumed surfaces

### 1. Session wire fields (`GET /api/sessions`, `/api/profiles/sessions`)

`id` (**not** `session_id`), `title`, `model`, `provider`, `last_active`, `message_count`,
`profile`, `is_default_profile`, `archived`, `cwd`, `source`, `git_branch`, `git_repo_root`.

Pinned by `HermesContractTest.session_wire_field_names_match_upstream` via the kotlinx-serialization
descriptor, so a renamed `@SerialName` fails the build rather than silently deserializing to null.

### 1b. Message wire fields (`GET /api/sessions/{id}/messages`)

The endpoint returns rows straight out of Hermes' SQLite `messages` table —
`hermes_state_messages.py` `_row_to_message_dict` is `dict(row)`, and the router passes the list
through untouched — so **every column is a wire field under its column name**.

The time field is **`timestamp`**, a `REAL NOT NULL` holding Unix **seconds** (a float). It is
**not** `created_at`, and it is not an ISO-8601 string. Every message has one: the column is NOT
NULL.

This cost us a whole class of missing data. The client modelled `created_at` as an ISO string,
which matches nothing upstream, so `timestamp` came back null for every message loaded from
history; only messages streamed live in the current session carried a locally applied stamp. The
「我的提问」list therefore showed times on recent prompts and nothing on older ones — exactly
backwards from what is useful (HG-4). `docs/DESIGN.md` compounded it by recording "the gateway
history has no `created_at`" as fact and filing the fix as connector work; no connector change was
ever needed.

Also consumed from the same rows: `id`, `role`, `content`, `reasoning` / `reasoning_content`,
`tool_calls`, `tool_call_id`, `tool_name`, `display_kind`, `display_metadata`. A column Hermes
renames disappears silently — deserialization yields null, never an error.

**`content` is not always a string.** A turn that carried attachments comes back as a list of
content blocks — `[{"type":"text","text":"…"}, {"type":"image_url", …}]` — observed on
2026-09-18 in session `20260918_204034_16def7` (HG-64). A renamed column fails softly; this one does
not: kotlinx abandons the whole document rather than the row, so ONE such turn made the entire
`/api/sessions/{id}/messages` response unreadable and the chat screen showed 无法加载历史消息 for a
conversation whose other fifty rows were fine. It also disabled the finished-run self-heal, which
reads the transcript before retiring a stale phase, so those conversations kept showing 正在运行中
(HG-61). `data/network/Dtos.kt` `MessageContentSerializer` accepts string, null and block list, joins
the `text` blocks, keeps a block whose path/URL it can render as `@image:`, and skips block types it
does not know. The block vocabulary is upstream's — do not assume this list is complete.

**An attachment's bytes are stored inline, and every read transmits them.** A turn that carried
images is persisted with the images already base64'd into `messages.content`: measured on
`20260918_204034_16def7`, one row is 27,483,342 bytes holding 75 `data:image` payloads, and the
session's rows total 110,160,145 bytes. `session.resume` and `GET /api/sessions/{id}/messages` both
return that, ~26 MiB on the wire.

> **Corrected 2026-09-20.** This paragraph previously said the row held 151 bytes of `@image:`
> references that Hermes re-expanded *at read time*. It does not: the expansion has already
> happened when the row is written. The 151-byte figure came from `sqlite3`'s `length()`, which
> counts characters up to the first NUL — and these rows begin with `\x00json:`. **Do not size these
> columns with `sqlite3 length()`**; read them in a language that returns the whole value. Everything
> downstream of that number was wrong for a day, including an upstream issue.

What the read path does control is whether those stored bytes are *sent*.
`session_history.py`'s `_coerce_message_text` called `_history_dict_text(part, image_urls=True)` at
both of its call sites, and the `False` branch — which renders `[image]` in place of the payload —
was not reachable from any API parameter. So a remote client could not ask for the small form, and
paid the full transfer on every read, including the `session.resume` that follows each reconnect.
Patch `020-bounded-inline-images` makes it reachable (see below); until a release carrying that
patch is activated, this is still what this Mac serves. One
37-page PDF sent through `pdf.attach` (7.5 s to rasterise, 9.44 MiB of PNG, **12.59 MiB** once
inlined) was on its own past the relay's 12 MiB frame ceiling.

That is the fact behind HG-65, and it splits into two problems with different owners. **Transfer**
is reachable from the read path and therefore patchable by us. **Storage** is decided when the row
is written, which is write-side — outside what a managed-Hermes patch may touch
(`docs/MANAGED_HERMES_STRATEGY.md`), and not investigated here. The client-side mitigations stand on
their own: send documents as `file.attach` references rather than page images, and survive an answer
that cannot be delivered instead of reconnecting into it forever.

Making `image_urls` reachable from the API — an `inline_images=false` on `session.resume` and
`GET /messages` — fixes the transfer for plain photographs too. Patch
`020-bounded-inline-images.patch` does exactly that on the staged managed copy, with the existing
default unchanged; it is read/render-side only and is tracked upstream in #116511 / PR #116677. `file.attach`
already demonstrates the durable shape: it returns `@file:` and read-back does **not** expand it.

**Two upstream behaviours we depend on, and the exact lines they live on.** Both are correct for a
Hermes serving a local desktop and wrong for one behind a relay, which is why they are recorded here
rather than reported as upstream bugs:

| Behaviour | Where (at the pinned commit) | Why it costs us |
|---|---|---|
| History reads transmit every stored attachment payload | `tui_gateway/session_history.py` — `_coerce_message_text` passes `image_urls=True` at two call sites; the `False` branch that renders `[image]` already exists and is unreachable from the API | 26.30 MiB for one conversation (HG-65). The bytes are already in the row (section 1b); this decides whether they go on the wire. Free between local processes, not free over a relay |
| Message rows are read with `SELECT *` and passed to the response encoder | `hermes_state_messages.py`, 5 sites | Any column upstream adds travels into the JSON response. On 2026-09-19 a new `display_identity BLOB` made `GET /messages` return 500 for every affected session (see docs/DESKTOP_E4_TEST_RECORD.md) |

Both were reported upstream on 2026-09-20, against `8a92051f`:
[NousResearch/hermes-agent#116510](https://github.com/NousResearch/hermes-agent/issues/116510) for
the `SELECT *` reads and
[#116511](https://github.com/NousResearch/hermes-agent/issues/116511) for the `image_urls` switch;
both read-side changes are proposed in
[#116677](https://github.com/NousResearch/hermes-agent/pull/116677).
Check their state before assuming either still needs a local workaround — and before writing a new
one, because a merged upstream fix removes the reason for it.

Both are small — two lines and five call sites. That matters for the open question of whether the
managed Hermes should stay a verbatim pinned copy of upstream or carry a thin read-side patch set;
see the design note referenced from `docs/INTEGRATION.md`. Either way the rule is the same: **a patch
may change what is read or rendered, never what is written or the schema**, because the owner's own
Hermes reads the same database and must keep understanding it.

### 1c. History and session-list paging (HG-104, verified 2026-09-23)

Verified against `hermes_cli/web_routers/sessions.py` and `hermes_cli/web_routers/profiles.py` at
both `f159e581c7` and `17b5df02f2`; the shapes below are identical in the two.

**History.** Clients no longer read a whole transcript in one call. They open a conversation with
`GET /api/sessions/{id}/messages?order=latest&limit=100&offset=0` and load each older page with the
same request and `offset=K`, where `K` is the number of rows already loaded (the sum of `returned`).
What upstream does with these parameters:

- `order=latest` pages **backwards from the newest row**, so `offset` counts rows skipped from the
  end, but each page is still returned in **chronological (ascending) order** —
  `hermes_state_messages.py` `get_messages(latest=True)` re-sorts ascending. A client prepends the
  page; it must not reverse it.
- The response carries `pagination: {limit, offset, order, returned}`. `returned` is the row count
  after `_project_for_display`; `returned < limit` means there is no older page. `limit` is capped at
  500 server-side (`min(limit, 500)`) and echoed as applied.
- **`order` must be sent explicitly.** With a `limit` and no `order`, upstream answers the *oldest*
  page (`order` defaults to `"oldest"` whenever `limit` is present); only a request with neither
  defaults to the latest 500. Any `order` other than `oldest`/`latest` is `400`.
- Offsets shift when a row is appended between two page reads (a turn finishing while the person
  scrolls up), so the boundary row can arrive twice; a client must de-duplicate by message `id`.
- `include_compacted` stays at its default (`false`); the paging above is over active rows.

**Session list.** `GET /api/sessions` declares `limit: Query(20, ge=0, le=100)`: a limit above 100
is **rejected with `422`, not clamped**. The clients' fallback read of this endpoint (instead of
the cross-profile `/api/profiles/sessions`) therefore now asks for `limit=100`. `/api/profiles/sessions` allows up
to 500 (`le=500`) and is unchanged.

Neither change adds or removes a route, so `HERMES_REST_CONTRACT` and the §2 table are unchanged;
the parameters are not part of that contract (see §2) and are covered by checklist item 8m instead.

**Client assumptions the Android merge rests on** (`data/repository/TranscriptWindow.kt`, HG-104),
to re-check on every upgrade alongside 8m:

- **Message `id`s increase monotonically with time** within a conversation (SQLite `INTEGER PRIMARY
  KEY` on `messages`). The client decides which of two pages is older, whether a refreshed newest
  page overlaps what it already holds, and which rows of an older page to prepend by comparing ids
  numerically — not only by equality.
- **`offset` counts every row the endpoint returns, tool/function rows included.** The client
  keeps the raw rows as returned (tool rows are only dropped later, when rows are mapped to turns)
  and sends `offset` = the number of raw rows it holds. If upstream ever counted `offset` over a
  different set than it returns, older pages would overlap (harmless, de-duplicated) or skip rows
  (a silent gap).

### 2. REST paths

Every upstream REST call the app makes. **The source of truth is `HERMES_REST_CONTRACT` in
`connector/src/hermes-contract.ts`**; this table is its rendering, and `hermes-contract.test.ts`
fails the Connector build when the two differ, so change both in the same commit. Parameter names are
not part of the contract (`{id}` matches upstream's `{session_id}`, `{job_id}`, `{name}`).

**Tier** decides what a missing entry means on the phone. `required` is the app's reason to exist —
seeing the Mac, listing conversations, reading one — and its absence is **breaking**
(`HR-COMPAT-001`). Every other entry is one feature, and its absence **degrades** only that feature
(`HR-COMPAT-002`). A missing cron route must never be reported as if chat were down.

| Method | Path | Tier | Feature |
|---|---|---|---|
| `GET` | `/api/status` | required | status |
| `GET` | `/api/sessions` | required | sessions |
| `GET` | `/api/profiles/sessions` | required | sessions |
| `GET` | `/api/sessions/{id}/messages` | required | history |
| `PATCH` | `/api/sessions/{id}` | optional | sessions |
| `DELETE` | `/api/sessions/{id}` | optional | sessions |
| `GET` | `/api/sessions/stats` | optional | sessions |
| `GET` | `/api/sessions/search` | optional | search |
| `GET` | `/api/profiles` | optional | profiles |
| `GET` | `/api/profiles/active` | optional | profiles |
| `POST` | `/api/profiles/active` | optional | profiles |
| `GET` | `/api/fs/list` | optional | projects |
| `GET` | `/api/fs/git-root` | optional | projects |
| `GET` | `/api/fs/default-cwd` | optional | projects |
| `GET` | `/api/config` | optional | config |
| `PUT` | `/api/config` | optional | config |
| `GET` | `/api/env` | optional | config |
| `PUT` | `/api/env` | optional | config |
| `POST` | `/api/env/reveal` | optional | config |
| `GET` | `/api/cron/jobs` | optional | cron |
| `POST` | `/api/cron/jobs` | optional | cron |
| `GET` | `/api/cron/jobs/{id}` | optional | cron |
| `PUT` | `/api/cron/jobs/{id}` | optional | cron |
| `DELETE` | `/api/cron/jobs/{id}` | optional | cron |
| `GET` | `/api/cron/jobs/{id}/runs` | optional | cron |
| `POST` | `/api/cron/jobs/{id}/pause` | optional | cron |
| `POST` | `/api/cron/jobs/{id}/resume` | optional | cron |
| `POST` | `/api/cron/jobs/{id}/trigger` | optional | cron |
| `GET` | `/api/cron/delivery-targets` | optional | cron |
| `GET` | `/api/model/options` | optional | models |
| `POST` | `/api/model/set` | optional | models |
| `PUT` | `/api/profiles/{id}/model` | optional | models |
| `GET` | `/api/tools/toolsets` | optional | tools |
| `GET` | `/api/skills` | optional | skills |
| `PUT` | `/api/skills/toggle` | optional | skills |
| `GET` | `/api/analytics/usage` | optional | analytics |
| `POST` | `/api/audio/transcribe` | optional | voice |
| `GET` | `/api/messaging/platforms` | optional | messaging |
| `PUT` | `/api/messaging/platforms/{id}` | optional | messaging |
| `POST` | `/api/messaging/platforms/{id}/test` | optional | messaging |
| `POST` | `/api/gateway/restart` | optional | messaging |

Deliberately **not** in the table, because upstream's `openapi.json` cannot vouch for them:

- `/api/files`, `/api/files/upload` — upstream has routes with these names, but **this Connector
  answers them itself** (`handleFileRequest`, bounded by `FILES_ROOT`) and never forwards them to
  Hermes. They are our surface, not upstream's.
- `/api/ws` — a WebSocket route; OpenAPI does not describe WebSocket routes, so it is absent from
  every Hermes schema. The Connector's session observer opens exactly this socket on every
  (re)connect, and its RPC surface is §3.
- `/api/mobile/events*` — the account service's (below).
- `/api/hermes-remote/contract` — the Connector's own contract report (below).

#### Connector contract check (added 2026-09-21)

Hermes GO no longer decides which Hermes runs (`docs/MANAGED_HERMES_STRATEGY.md`, principle 2), so
the Connector checks this table against the running Hermes instead of trusting a pin:

1. **What it fetches.** `GET /openapi.json` — FastAPI's default schema URL, outside `/api/`, sent
   with the same credentials as every other Connector→Hermes call (session token and/or Basic Auth
   cookie; `HermesAuth.openApiDocument`) — and the `version` from the public `GET /api/status`.
   0.21.3 publishes ~250 KB; the Connector refuses a body over 8 MiB.
2. **When.** At Connector startup, every time the session observer's socket to Hermes opens (a
   Hermes restart — `hermes update` kickstarts the serve job — looks exactly like that from the
   Connector), and whenever `/api/status` reports a different `version` than the cached result was
   taken against (read cheaply on each phone request, on each Relay reconnect and in the account
   preflight). Otherwise the result is cached: `openapi.json` is not fetched per request. A cached
   `unknown` is retried after 60 s. A trigger that arrives while a check is running queues one
   follow-up check rather than sharing the running one, which may have read the process Hermes is
   restarting away from. `HermesContractMonitor` (`connector/src/hermes-contract-monitor.ts`); the
   schema fetch and its credentials are in `connector/src/hermes-auth.ts`, the local route in
   `connector/src/tunnel-routes.ts`.
3. **What it concludes.** `compatible`; `degraded` (optional entries missing → `HR-COMPAT-002`, or
   Hermes older than `MINIMUM_HERMES_VERSION` → `HR-COMPAT-003`); `breaking` (a required entry
   missing → `HR-COMPAT-001`); or `unknown` when it could not look — Hermes unreachable, a non-2xx,
   a body that is not an OpenAPI document, or one that describes none of Hermes' routes. **`unknown`
   is never `breaking`** and shows nothing on the phone: a check that could not look must not claim
   it saw a missing route. A path present with a different method counts as missing for that method.
4. **What it does about it.** Nothing but report. It never refuses to relay: a phone whose optional
   route vanished keeps every other feature. The Connector logs one `hermes.contract` line per
   *change* of result (level `error` for degraded/breaking), naming each missing `METHOD path`.
5. **How it reaches the phone.** The Connector serves the cached report itself at
   `GET /api/hermes-remote/contract` (Connector-owned like `/api/files`; never forwarded to Hermes),
   so it rides the existing HTTP tunnel in both legacy and account mode with no Gateway, protocol
   or database change. The app reads it after a healthy `/api/status` probe
   (`GatewayHealthMonitor`), when the Hermes version changes, when the user taps 「重新检查」, or at
   most every five minutes; the verdict is keyed on the Mac it describes (account, device, gateway)
   and cleared the moment that changes. 401/404/405 mean "no report"; a Relay 5xx or timeout keeps
   the last verdict and asks again on the next probe. A
   `degraded`/`breaking` report lights the existing health strip with the code; its sheet names the
   affected features. An older Connector forwards the path to Hermes, which answers 401/404 — the
   app treats any non-2xx or unreadable answer as "no report" and shows nothing.

The payload (`schema: 1`): `status`, `code` (only when there is something to show), `retryable:
false`, `hermesVersion`, `minimumHermesVersion`, `versionBelowMinimum`, `missing[]` of `{method,
path, tier, feature}` (required first), `checkedPaths`, `reason` and `httpStatus` for `unknown`,
`checkedAt`. It carries no upstream text: the version is dropped unless it is short and printable,
and `reason` is a fixed vocabulary.

Authentication is the `X-Hermes-Session-Token` header. The Mac's Hermes credential never leaves the
Mac; the phone holds only its own app token (see `docs/ARCHITECTURE.md`).

**`/api/mobile/events`, `/ack` and `/read` were listed here until 2026-09-20 and did not belong.**
They are not upstream Hermes paths at all — no Hermes on this Mac has ever served them, pinned or
rolling. The app sends them to the *account service* with `Authorization: Bearer`, against
`account.baseUrl`, which is our own surface and versioned by us
(`android/.../HermesRestApi.kt` routes them explicitly before the Hermes branch). Listing them here
inverted the one thing this document is for: it told a reader that something we control is something
we must negotiate with upstream, and an upgrade check would have gone looking for a path upstream
never had. Verified against the running managed copy's `openapi.json` and the owner's own checkout —
absent from both.

**The three cron action paths and `/api/cron/delivery-targets` were added to this list on
2026-09-14 (HG-51). They were not new** — the app has been calling
`POST /api/cron/jobs/{id}/{pause|resume|trigger}` all along, and §7 already discussed
`delivery-targets` in prose. They were simply never written into the inventory, which is the exact
failure mode this document exists to prevent: an upstream rename of `trigger` would have surfaced
as 「操作失败」 and nothing else. At the time nothing pinned this list (`HermesContractTest` covers
names in text grammars, not routes); since 2026-09-21 the Connector contract check above does.

**The same thing had happened again by 2026-09-21**, found while turning this list into code:
`PATCH`/`DELETE /api/sessions/{id}` (rename, archive, delete), cron create/update/delete,
`/api/fs/list`, `/api/fs/git-root`, `/api/fs/default-cwd`, `PUT /api/profiles/{name}/model`,
`PUT`/`POST .../messaging/platforms/{id}[/test]` and `POST /api/gateway/restart` were all called by
`HermesRestApi.kt` and absent from this list (the last three were mentioned only in §7's prose). All
were present in 0.21.3's `openapi.json`. The table now lists methods as well as paths, because an
upstream that keeps a path but drops the method the app sends (say, `PUT /api/skills/toggle`
becoming `POST`) breaks the app just the same.

### 3. WebSocket RPC methods

`/api/ws` is not anonymous on Hermes 0.21.0, including a loopback-only `hermes serve` process.
In loopback mode the upgrade requires the process's exact session token as the `token` query
parameter. When `dashboard.public_url` names a non-loopback host, the managed private backend must
also start with `HERMES_DESKTOP=1` plus an operator-supplied `HERMES_DASHBOARD_SESSION_TOKEN`; those
three facts activate Hermes' Desktop-owned loopback exemption instead of the public ticket gate.
Hermes GO therefore generates one private installation-local token, starts Hermes with that token,
and lets only the local Connector read the same token. The value never crosses Gateway and is never
placed in the signed release.

```
session.create   session.resume   session.access*    session.interrupt   session.workspace.move
prompt.submit    slash.exec       complete.path       commands.catalog
approval.respond clarify.respond† clarify.lock‡      client.capabilities‡  request.answer‡
config.get       config.set
file.attach      image.attach     image.attach_bytes  pdf.attach
process.list     projects.tree    projects.project_sessions
```

† old question protocol only (f159e581); gone in 17b5df02. ‡ new question protocol; an older Hermes
answers `client.capabilities` with -32601, which is expected and only logged. See
"How Hermes asks the phone a question" below.

`session.access*` is optional until upstream #116651 / PR #116677 lands in the owner's local Hermes.
The retired managed copy no longer supplies a patch; clients must tolerate method-not-found and retain
the submit-time 4090 fallback.

#### WebSocket RPC params: what 17b5df02 accepts (audited 2026-09-21)

17b5df02 validates every call's params against a pydantic model whose base `Params` is
`extra="forbid"` (`tui_gateway/contracts/base.py`; `registry.py::validate_params`, applied in
`rpc_dispatch._handle_admitted_request`). **One undeclared key fails the whole call with 4000**;
missing or mistyped keys are left to the handler. f159e581 validates nothing — an unused key was
silently ignored there, which is how three undeclared keys accumulated unnoticed.

This table is every WebSocket RPC this repository sends — every `client.call(…)` in the Android app
plus the Connector observer's one call — against the 17b5df02 model, inherited
`SessionParams`/`ProfileParams` fields included. "Accepted keys" is generated from
`tui_gateway/contracts/` into `docs/hermes-rpc-params.json`, which `RpcParamContractTest` enforces
at build time (and checks this table against) and which the dev mock enforces in strict mode.
`?` marks a key sent only when set.

| Method | Params model (17b5df02) | Accepted keys | Keys the app sends | f159e581 |
|---|---|---|---|---|
| `session.create` | `SessionCreateParams` | `close_on_disconnect`, `cols`, `cwd`, `fast`, `follow_profile_config`, `hidden`, `messages`, `model`, `parent_session_id`, `profile`, `provider`, `reasoning_effort`, `room_plumbing`, `source`, `title` | `source`, `profile`?, `cwd`? | present, unvalidated |
| `session.workspace.move` | `SessionWorkspaceMoveParams` | `cwd`, `profile`, `session_key` | `session_key`, `cwd`, `profile`? | present, unvalidated |
| `session.resume` | `SessionResumeParams` | `close_on_disconnect`, `cols`, `defer_history`, `eager_build`, `lazy`, `omit_messages`, `profile`, `session_id`, `source` | `session_id`, `source`, `omit_messages`, `profile`? | present, unvalidated |
| `prompt.submit` | `PromptSubmitParams` | `confirm_empty_truncate`, `confirm_truncate`, `display_kind`, `hosted_task`, `hosted_terminal_callback`, `interrupted`, `profile`, `queued`, `rebind_survivor_row_ids`, `session_id`, `surface`, `text`, `title_preview`, `truncate_before_message_id`, `truncate_before_row_id`, `truncate_before_user_ordinal`, `turn_author`, `voice_context` | `session_id`, `text` | present, unvalidated |
| `slash.exec` | `SlashExecParams` | `command`, `profile`, `session_id` | `session_id`, `command` | present, unvalidated |
| `complete.path` | `CompletePathParams` | `cwd`, `profile`, `session_id`, `word` | `session_id`, `word` | present, unvalidated |
| `config.get` | `ConfigGetParams` | `cwd`, `key`, `profile`, `session_id` | `key`, `session_id` | present, unvalidated |
| `config.set` | `ConfigSetParams` | `confirm_expensive_model`, `key`, `profile`, `scope`, `session_id`, `value` | `key`, `session_id`, `value` | present, unvalidated |
| `commands.catalog` | `CommandsCatalogParams` | `profile`, `session_id` | — | present, unvalidated |
| `session.interrupt` | `SessionInterruptParams` | `expected_hosted_task_id`, `profile`, `session_id` | `session_id` | present, unvalidated |
| `image.attach_bytes` | `ImageAttachBytesParams` | `content_base64`, `data`, `ext`, `filename`, `profile`, `session_id` | `session_id`, `content_base64`, `ext`? | present, unvalidated |
| `image.attach` | `ImageAttachParams` | `path`, `profile`, `session_id` | `session_id`, `path` | present, unvalidated |
| `pdf.attach` | `PdfAttachParams` | `content_base64`, `data`, `filename`, `first_page`, `last_page`, `path`, `profile`, `session_id` | `session_id` + `content_base64`, `filename` \| `path` | present, unvalidated |
| `file.attach` | `FileAttachParams` | `data_url`, `name`, `path`, `profile`, `session_id` | `session_id`, `name` + `data_url` \| `path` | present, unvalidated |
| `process.list` | `ProcessListParams` | `profile`, `session_id` | `session_id` | present, unvalidated |
| `approval.respond` | `ApprovalRespondParams` | `all`, `choice`, `profile`, `request_id`, `session_id` | `session_id`, `choice` | present, unvalidated |
| `clarify.lock` | `ClarifyLockParams` | `answer`, `profile`, `question_id`, `request_id` | `request_id`, `question_id`, `answer` | absent (-32601; only sent for a server-request card, which f159e581 never raises) |
| `request.answer` | `RequestAnswerParams` | `id`, `profile`, `result` | `id`, `result` | absent (only sent for a server-request card, which f159e581 never raises) |
| `client.capabilities` | `ClientCapabilitiesParams` | `server_requests` | `server_requests` | absent (-32601, tolerated) |
| `projects.tree` | `ProjectsTreeParams` | `preview_limit`, `profile`, `session_limit` | `preview_limit` | present, unvalidated |
| `projects.project_sessions` | `ProjectsProjectSessionsParams` | `profile`, `project_id`, `session_limit` | `project_id` | present, unvalidated |
| `projects.create` | `ProjectsCreateParams` | `board_slug`, `color`, `description`, `folders`, `icon`, `name`, `primary_path`, `profile`, `slug`, `use` | `name`, `folders`?, `icon`?, `color`? | present, unvalidated |
| `projects.update` | `ProjectsUpdateParams` | `board_slug`, `color`, `description`, `icon`, `id`, `name`, `profile` | `id`, `name`?, `icon`?, `color`? | present, unvalidated |
| `projects.add_folder` | `ProjectsAddFolderParams` | `id`, `is_primary`, `label`, `path`, `profile` | `id`, `path` | present, unvalidated |
| `projects.remove_folder` | `ProjectFolderParams` | `id`, `path`, `profile` | `id`, `path` | present, unvalidated |
| `projects.set_primary` | `ProjectFolderParams` | `id`, `path`, `profile` | `id`, `path` | present, unvalidated |
| `projects.delete` | `ProjectIdParams` | `id`, `profile` | `id` | present, unvalidated |
| `session.active_list` | `SessionActiveListParams` | `current_session_id`, `profile` | — (Connector observer, `session-observer-runner.ts`) | present, unvalidated |
| `session.access` | — (-32601 on 17b5df02) | `live_session_id`, `profile`, `session_id` | `session_id`, `profile`?, `live_session_id`? | absent upstream; caller fails open |
| `clarify.respond` | — (-32601 on 17b5df02) | `answer`, `question_id`, `request_id`, `session_id` | `session_id`, `request_id`, `answer`, `question_id`? | present (old question protocol) |

What the audit found and how each was fixed — every fix is one params form **both** servers accept,
so nothing here depends on guessing the version:

| Call | Was | 17b5df02 | Now |
|---|---|---|---|
| `session.resume` | `inline_images: false` (managed patch 020) | 4000 — every conversation open failed | `omit_messages: true`, identical in f159e581 and 17b5df02 (below) |
| `image.attach_bytes` | `mime_type` | 4000 — every photo upload failed | `ext` only when the phone's own magic-byte check says what the bytes are (png/jpg/gif/webp/bmp/tiff), else omitted. In both versions `_sniff_image_ext` lets the hint **win** over its magic-byte sniff, so a hint must never be a guess; HEIC/HEIF are not in `cli._IMAGE_EXTENSIONS` in either version, and naming them would turn upstream's fallback into a 4016 |
| `approval.respond` | `approved` | 4000 | dropped; no version ever read it |
| `session.access` | — | method absent, -32601 | unchanged: the caller already fails open |
| `clarify.respond` | — | method absent, -32601 | unchanged: only sent for an old-protocol card, which only f159e581 raises |

Because no call needs a key only one side accepts, **there is no retry-without-key tolerance** — a
4000 is always a client bug to fix here, never something to paper over at runtime.

**Why `omit_messages` and not `defer_history` or `lazy`.** The app takes exactly two things from a
`session.resume` answer: the live `session_id` and (new protocol) `open_requests`. The transcript
comes from the paged REST endpoint. So the answer should carry no transcript at all — which is what
HG-65/HG-69 needed, since the transcript is where every historical base64 image was re-inlined.
- `omit_messages: true` does exactly that on both versions (`_Resume.messages` / `read_history` /
  `display_prefix` all short-circuit; `message_count` is still reported). It is the flag upstream's
  own Desktop sends for the same reason ("Desktop hydrates over REST"). Two side effects, both
  benign here: the model-fed history is loaded as the tip segment's own conversation
  (`child_history`, the same path Desktop takes), and the runaway-transcript guard
  (`_resume_guard`, 4130) counts the tip segment only instead of the whole lineage.
- `defer_history` answers `status: "resuming"` / `hydrating: true` and loads the history on a
  background worker; a `prompt.submit` sent meanwhile blocks on that load (`_await_resume_history`,
  up to 300 s). It exists to keep a *transcript* off the response path — `omit_messages` already
  removes the transcript, so it would only add a hydration phase and a wait the app gains nothing from.
- `lazy` is upstream's watch mode for Desktop subagent windows: it registers the session without
  enabling gateway prompts (`mint(prompts=False)`) and without an agent until `prompt.submit`
  upgrades it, and on its own it still returns the child transcript. Not meant for a conversation
  the user drives.

**Not covered by this table — REST.** `GET /api/sessions/{id}/messages` still sends
`inline_images=false`. FastAPI ignores an unknown query parameter, so 17b5df02 does not reject it —
it just ignores it and returns images inlined (patch 020's REST half is what honoured it). 17b5df02
does page that endpoint (at most 500 rows per call), but how large an image-heavy page gets there
has not been measured; checklist item 8g still applies before adoption.

Server events consumed: `message.start` / `message.delta` / `message.complete`,
`tool.start` / `tool.complete`, `session.info`, `approval.request` / `clarify.request` (old question
protocol), `request.cancel` (new question protocol), `session.reclaimed`, `sessions.changed`.

`session.lifecycle` used to be listed here and is **not a Hermes event**: neither f159e581 nor
17b5df02 emits or declares it (`git grep -F '"session.lifecycle"'` finds nothing; the loose pattern
only hits the `session_lifecycle` module, and `contracts/events.py` has no such event). It is this
repository's own Relay wire type — the Connector's observer derives it from `session.active_list`
polls (`connector/src/session-observer.ts`) and the app reads it from the Relay inbox
(`LifecycleEventRepository`), never from `/api/ws`.

**How Hermes asks the phone a question — two protocols, both supported (verified 2026-09-21).**
Between f159e581 and 17b5df02 upstream replaced the paired notification/respond protocol with
server→client JSON-RPC requests (`tui_gateway/server_requests.py`). The app speaks both and decides
per card from what actually arrives, never from a version guess (`ServerRequests.kt`):

| | Old (f159e581) | New (17b5df02) |
|---|---|---|
| Ask | event `approval.request` / `clarify.request` | request frame `{jsonrpc, id:"srq-<12 hex>", method:"approval"\|"clarify", params:{session_id, …}}` |
| Approval answer | `approval.respond {session_id, choice}` — resolves the *oldest* approval | `request.answer {id, result:{choice}}` → `{status:"ok"\|"expired"}` — exactly that request |
| Single clarify answer | `clarify.respond {session_id, request_id, answer}` | `request.answer {id, result:{answer}}` (`""` = skip) → `ok`/`expired` |
| Batch clarify answer | `clarify.respond` + `question_id`, one lock at a time | `clarify.lock {request_id, question_id, answer}` → `{status:"ok"\|"expired", remaining}`; the last lock resolves the request |
| Cancel-all | `clarify.respond` without `question_id`, `answer:""` | `request.answer {id, result:{answer:""}}` (no `answers` key = cancel-all) |
| Withdrawn | `clarify.expire {request_id}` (not consumed) | event `request.cancel {id, method, reason}` — the card with that id is torn down |
| After a reconnect | not replayed to this app | `session.resume` returns `open_requests: [{id, method, params}]` (omitted when empty); a batch's params carry the locked `answers`; server-request cards not listed are dropped |
| Several approvals open at once | one card; the newest replaces the older | queued by id, shown oldest first |

**Which clarify answer path a card takes is decided by the question id, not by how many questions
there are.** A question that carries a non-empty `qid` is locked with `clarify.lock`, even when
`questions[]` has a single element; only a card without a `qid` answers with
`request.answer {answer}`. Answering a one-element batch that way would send no `answers` key, which
upstream reads as cancel-all. Android (`ChatRepository.kt`) and the Web app (`web/src/hermes/requests.ts`)
both follow this rule. Several open approvals are queued in arrival order, deduplicated by id; the
`srq-` ids are random hex, so "oldest first" means arrival order, not id order.

Load-bearing facts, all from the 17b5df02 source:

- **Nothing is asked unless the connection says it can answer.** A WebSocket client must send
  `client.capabilities {server_requests: true}` once per connection. Until it does, an approval is
  *withdrawn* ("the attached client cannot answer approval requests") and a clarify returns nothing —
  silently; the phone just never sees a card. The app sends it as the first frame after
  `gateway.ready`, before the readiness gate lets any other RPC out, because Hermes reads a socket's
  frames in order and a session resumed before the advertisement would lose its questions. The
  advertisement is per transport (`server_requests._answering_clients`), and a request is sent when
  *any* live WebSocket client attached to the session advertised (`_session_client_answers_requests`);
  with no client attached it waits in `open_requests`.
- **Answers are resolved by id, globally.** `rpc_dispatch.dispatch` sends every frame with an `id`,
  a `result`/`error` and no `method` to `server_requests.resolve_response`, which looks the id up in
  one process-wide table. An answer therefore works from a different connection than the one the
  question was sent on — which is what a notification-shade answer after a reconnect is — and an
  answer to a request that has already ended is dropped without a reply (hence `request.cancel`).
- **Answers go through `request.answer`, not a bare response frame.** A response frame for an id
  no longer open is dropped by `resolve_response` without a reply, and a request answered on
  *another* surface is settled there without any `request.cancel` (upstream emits cancel only on
  timeout, interrupt, session close and shutdown). With a bare frame the phone therefore showed
  success, moved the run to 思考中 and nothing happened. `request.answer {id, result}`
  (`methods_prompt.py`) resolves the same way and answers `{status:"ok"|"expired"}`, so an expired
  clarify surfaces `HR-CLARIFY-001` again (as `clarify.respond` did on f159e581) and an expired
  approval surfaces `HR-APPROVAL-003`. Approval uses it too: the extra round trip costs nothing
  and replaces HR-APPROVAL-001's after-the-fact inference with Hermes' own answer.
  **`request.answer` is therefore required** of any Hermes that sends server→client requests — the
  app treats 17b5df02 as the minimum for the new protocol, and there is deliberately no fallback to
  a bare response frame (that is exactly the silent path this replaced). A Hermes that sends
  requests but answers `request.answer` with -32601 fails the answer like any RPC error: the in-app
  approval sheet shows `HR-RPC-001`, a clarify card is put back for a retry, a notification action
  shows `HR-NOTIF-001`.
- **Stale cards are pruned on resume.** Because "answered elsewhere" sends no cancel, and a card
  restored from `SessionPhaseStore` may name a request that timed out while no socket was attached,
  every `session.resume` answer on a connection that advertised the capability is followed by a
  client-internal `hr.open_requests` snapshot, and server-request cards whose id it does not list
  are dropped. Absent `open_requests` means none: `_live_session_payload` only sets non-empty values,
  and a cold resume mints a fresh live handle that owns no requests. The snapshot is queued on the
  socket's reader thread, so it is ordered after every frame that arrived before the answer and
  before every frame after it — a request raised just after the resume is never pruned. A request
  raised *during* the resume is not pruned either: `_resume_reuse_live` snapshots `_open_requests`
  before the pool worker writes the answer, and `server_requests._register` does not take
  `_session_resume_lock`, so its frame can arrive before an answer that does not list it. Every
  server-request id received on the socket between sending `session.resume` and reading its answer
  is kept open in the snapshot.
- **Notification-shade answers settle their own card.** The shade answers by the id it was built
  with and then removes that id (`SessionRuntimeStore.settleShadeAnswer`), never "whatever is on
  screen now": between the notification and the tap, its request may have been answered elsewhere
  and the next queued approval taken its place. An `expired` answer from the shade leaves
  `HR-APPROVAL-003` / `HR-CLARIFY-001` in the conversation, like the in-app sheets.
- **An error answer means "no handler".** For methods the phone has no card for (`sudo`, `secret`,
  `vault.*`, `terminal.read`, `preview.*`, `window.read`, `tour`) it answers
  `{jsonrpc, id, error:{code:-32601, …}}` at once. Upstream reads any error response as `None` —
  `_ask` returns `""`, an approval is withdrawn — instead of waiting the request's full deadline
  (300 s for a prompt). `contracts/liveness.py` names -32601 as exactly this signal.
  **Trade-off:** a session can have several clients attached (`FanoutTransport`); the request frame
  goes to all of them and the first response wins. If Desktop is attached too and *could* answer a
  `sudo` / `secret` / `vault.*` / `tour` prompt (17b5df02 has no `mcp.setup` server request), the phone's immediate -32601 can win that race and
  the prompt resolves as "no answer" before the person at the Mac sees it. This is upstream's
  documented expectation for a client without a handler, and waiting silently instead would stall
  the agent for the full deadline whenever the phone is the only client — so the phone keeps
  answering -32601, and those prompts need the Mac.
- **Newer Hermes rejects unknown params keys with 4000** — see "WebSocket RPC params" above for the
  full audit and fixes.

The Gateway and Connector need no change for any of this: `tunnel.ws.frame` relays each WebSocket
frame as opaque base64 in both directions, whatever its shape, and each phone socket gets its own
Hermes socket (`openTunnelSocket` per `tunnel.ws.open`), so the capability advertisement is per phone
as upstream intends. The Connector's own observer socket only polls `session.active_list` and never
attaches to a session, so it cannot make a session look "unanswerable". The dev mock emulates the new
protocol with `HR_MOCK_SERVER_REQUESTS=1` (`scripts/dev/mock-hermes-stream.mjs`).

**`sessions.changed` is the only one of these that names no session.** It is a list-level broadcast
— "something in the session list moved" — so its payload carries no `session_id` or
`stored_session_id`, and the session-scoped resolver could only ever guess at one. Until HG-57 every
single one was therefore dropped, while upstream sent hundreds of them during a conversation that
ran on the Mac with nothing showing on the phone. It is now matched by type before resolution and
treated as "go and ask": the session list refreshes, and the conversations actually on screen get a
probe. Nothing is inferred from it about any particular session, because nothing may be.

There is no version negotiation here either. If a future Hermes renames or drops it, the symptom is
not an error — it is a session list that stops refreshing by itself, and a run whose state is only
corrected when the user opens or refreshes the conversation.

**`session.reclaimed` is the only warning that a conversation died while nobody was looking.**
Upstream broadcasts it (`tui_gateway/session_lifecycle.py`, `_announce_session_reclaimed`) whenever
its own housekeeping ends a session the client never asked to close —
`_RECLAIM_END_REASONS = {idle_timeout, lru_evict, ws_orphan_reap}`. The reason it exists is stated
in its own source comment: "else its next prompt fails". `ws_orphan_reap` fires 120 s after the
socket carrying a session drops, and `docs/DIAGNOSTICS.md` records it as the *dominant* way mobile
sessions end — so this is routine, not an edge case. It is a **global** broadcast carrying the
durable session id, not the short live handle; match it against the stored id.

Ignoring it costs more than a wasted round trip. Afterwards `prompt.submit` answers **4001** and the
`session.resume` the client retries with answers **4007**, and those two look identical on the wire
("session not found") while meaning different things: 4001 is a stale live handle that resuming
fixes, 4007 is the durable lookup missing from the profile's `state.db` — terminal. HG-29 was
exactly this, surfaced to the user as a tap-to-retry that could never succeed.

**Upstream enforces one live owner per session, and says so with 4090.** While another surface is
running a conversation, `prompt.submit` is refused — its stated reason being that a second surface
would reason from a transcript missing the first one's work. Unlike 4001/4007 this is neither stale
nor terminal: the same send succeeds once the other side lets go, which is why the phone keeps its
retry and only names the cause (`HR-SESS-013`, HG-30). Note it is *not* 4009 "busy" — that is the
session running a turn of its own.

Upstream #116651 / PR #116677 proposes read-only `session.access`; the retired managed copy no longer
patches it in. Where the owner's local Hermes provides the method, it reads the cross-process lease
registry without creating, pruning or rewriting it and
combines that with this gateway process's live `running` bit. The result is
`available|owned_by_requester|owned_elsewhere|unknown`, nullable `running`, nullable `writable`, and
an optional owner surface; no pid crosses the wire. Registry uncertainty is `unknown`, not a grant.
The method neither resumes nor activates a session and never acquires ownership; `prompt.submit`
remains the enforcement boundary.

**Nothing routes a PDF to `pdf.attach` any more.** The client sends every document — PDFs
included — through `file.attach`, which returns an `@file:` reference that Hermes expands at submit
time. `pdf.attach` rasterises every page unconditionally and those pages are persisted as base64, so
every read of the conversation carries them: a single 37-page report produced a 12.59 MiB
`session.resume` answer and the conversation became undeliverable through the relay (HG-65; the
full measurement is in section 1b). The reference form costs a few extra tool
round-trips and loses page geometry, and it is the one that does not grow without bound.

The paragraph below therefore describes a path the client no longer takes. It is kept because
`HR-SESS-016` is a released code and must keep its meaning, and because `pdf.attach` is still part
of upstream's surface: anything that routes to it again inherits exactly this behaviour.

**`pdf.attach` answers 5028 when it cannot rasterise a PDF**, and its message —
"pdftoppm not installed (poppler-utils package required)" — must not be repeated to a user. It
describes upstream's own `PATH`, not the disk. On the machine that reported HG-58 the binary had
been installed four and a half hours earlier, in `/opt/homebrew/bin`, invisible to a managed Hermes
that launchd had started with the bare `/usr/bin:/bin:/usr/sbin:/sbin`; a literal translation would
have sent the user to install something they already had. The phone classifies on **5028** alone and
says the Mac cannot find its PDF rendering dependency (`HR-SESS-016`, terminal — nothing the phone
does changes the answer). The Desktop side of that fix is `DesktopHermesRuntimeContract.searchPath`.

These are the upstream numbers we depend on — **4001**, **4007**, **4090** on `prompt.submit`, and
**5028** on `pdf.attach` — and the dependency is on the numbers only. The message upstream attaches
to 4090 names the owning surface and its pid; we deliberately do not parse it. There is no version
negotiation here (see the end of this document), so that prose can change under us at any time, and
a user-facing sentence must not be hostage to it. If a future Hermes renumbers these, the symptom is
a send failure falling back to the generic `HR-SESS-007` — check `ChatViewModel`'s constants first.

**Hermes 0.21.0 has no wire-level missing-capability event.** Optional dependency failures are not a
JSON-RPC error code that Desktop can safely intercept. `tools.lazy_deps.FeatureUnavailable` formats
English prose, `agent/tool_executor.py` wraps thrown tool failures as `Error executing tool ...`, and
some capability paths deliberately catch installation/import errors and fall back or return no
optional result (for example document extraction). Consequently Hermes GO must not map error strings
to browser, speech, or document downloads, and it cannot safely retry a turn from such text. The
Desktop coordinator accepts a closed capability kind in preparation for a future versioned upstream
event; adopting that event requires updating this inventory and the upgrade checklist before wiring
the production request path.

**Upstream strips its own repo root out of every child process's `PYTHONPATH`.**
`tools/environments/local.py` builds the environment for anything Hermes spawns, and
`_strip_hermes_owned_pythonpath` (`tools/environments/local_pythonpath.py`) removes the entries it
recognises as Hermes-owned — the repo root and the runtime's site-packages — so a child Python of a
different version cannot load the backend's C extensions. This is deliberate upstream behaviour, it
applies to children started with `sys.executable` too (the slash worker: `tui_gateway/server.py`,
`[sys.executable, "-m", "tui_gateway.slash_worker", …]`), and we cannot turn it off.

The consequence for us is a hard constraint on packaging: **anything the managed bundle needs a
child process to import must be importable without `PYTHONPATH`.** Hermes GO's bundle keeps the
Hermes sources in `app/`, which IS the repo root, so `PYTHONPATH` was its only route — and the strip
removed it. Every slash command died with `ModuleNotFoundError: No module named 'tui_gateway'`
(HG-28, managed release 0.3.0). The bundle now also carries a `.pth` in the interpreter's own
site-packages, a channel the strip does not reach; see `docs/DESKTOP_RELEASE_MANIFEST.md`.

**A new session has no REST row until its first message persists.**
`GET /api/sessions/<id>/messages` answers `404 {"detail":"Session not found"}` for a zero-message
session and only turns into `200` once a turn lands. That 404 is not evidence the create failed and
must not be reported as a history error; it is the normal opening seconds of every new conversation.

**`session.create` and `session.resume` both accept a caller-supplied `source`.** Upstream's
`_resolve_session_source` (`tui_gateway/server.py`) returns the explicit value unchanged and never
rewrites it; only an empty value falls back to the environment-derived platform, which on this host
is `tui`. Resume resolves the runtime source through the same `_new_runtime_ids(params)`, so passing
it there gives sessions stored before this shipped the right platform too.

This app sends **`source = "hermes_remote"`** on both calls (`ChatRepository.clientSource`). The
capability text for that platform lives on the Mac in `~/.hermes/config.yaml` under
`platform_hints.hermes_remote` — Hermes' own supported override (`agent/system_prompt.py`
`_resolve_platform_hint`, covered by upstream `tests/agent/test_platform_hint_overrides.py`), so no
Hermes source is patched and an upgrade cannot clobber it. An override for one platform provably
does not affect another, so the laptop's `desktop` sessions are untouched.

### 4. Text grammars in message content

| Grammar | Direction | Notes |
|---|---|---|
| `MEDIA:/absolute/path.ext` | Hermes → client | The canonical outbound attachment grammar. Extension must be in the delivery whitelist below. |
| `@file:` / `@image:` | client → Hermes | Attachment references staged by the client and passed on `prompt.submit`. The Android parser also renders them in assistant messages, but that is tolerance, not the contract. |
| `[screenshot]` and friends, alone on a line | Hermes → client | A bare placeholder left where an attachment was consumed: one line per file, no caption, no path. Unlike §4b's notes it says nothing at all, so it is stripped (`ui/chat/TimelineNote.kt` `ATTACHMENT_BARE_PLACEHOLDER`) rather than shown. HG-60: three of these appeared under images the person could already see, the moment the phone accepted upstream's copy of their own turn. The exact trigger is not established — the label never appears in the client log — so the pattern covers the labels seen in production and is anchored to a whole line, because `[screenshot]` quoted mid-sentence is a real thing to write. |
| `[image]`, alone on a line, and `"url": "[image]"` in a content block | managed Hermes → client | **Ours, not upstream's.** Managed patch `020-bounded-inline-images` puts this in place of an inline `data:` image URL on the bounded read paths, because upstream re-inlines the whole image in base64 on every read: one message measured 27,479,595 characters, the session behind HG-65 105.07 MiB, of which this client decodes none — it fetches the bytes by path instead. The line form is stripped by the same `ATTACHMENT_BARE_PLACEHOLDER` pattern as `[screenshot]`; the block form is dropped by `MessageContentSerializer.attachmentReference`, which keeps only `/` and `http(s)` references. Both behaviours predate the patch and are now pinned by tests, because the patch lives in another program and nothing else ties the two sides together. Delete the patch and this row together if upstream accepts [#116511](https://github.com/NousResearch/hermes-agent/issues/116511) / [PR #116677](https://github.com/NousResearch/hermes-agent/pull/116677). |

### 4b. Server-injected `role=user` scaffolding

Hermes injects several notices as **user-role turns** so the model reads them as context. They are
not something the person said, and the app renders them as one-line timeline notes
(`ui/chat/TimelineNote.kt`) rather than user bubbles.

| Marker | Upstream source | Notes |
|---|---|---|
| `[ASYNC DELEGATION …` | `_run_prompt_submit` (no `display_kind`) | Background delegation report. |
| `[IMPORTANT: Background process …` | same | Background process report. |
| `[Your active task list was preserved across context compression]` | `tools/todo_tool.py` `TODO_INJECTION_HEADER` | **See the hazard below.** |
| `[PRIOR CONTEXT — for reference only; not a new message]` | `agent/context_compressor.py` `_MERGED_PRIOR_CONTEXT_HEADER` | Context-compaction carrier. Projected by `domain/CompactionCarrier.kt`, see §5. |
| `[CONTEXT COMPACTION …` / `[CONTEXT SUMMARY]` | same file | Pure handoff; the turn is dropped when nothing real is merged in. |
| `[Skills pruned during compression — reload before acting on these tasks]` | `agent/conversation_compression.py` `_PRUNED_SKILL_RELOAD_NOTICE_HEADER` | Only ever appended after the header above (`todo_snapshot = f"{todo_snapshot}\n\n{_reload_notice}"`), never alone, so cutting at that header removes both. |
| `[The user sent a document: …]`, `[The user sent a text document: …]` | `gateway/run.py` `_build_document_context_note`, composed in `gateway/run_inbound.py` `_prepend_inbound_document_notes` | Attachment context note. See the attachment hazard below. |
| `[The user sent an audio file attachment: …]`, `[The user sent a video attachment: …]` | `gateway/run_inbound.py` `_prepend_inbound_media_file_notes` | Same family, same shape. |
| `[The user sent a voice message: /path (duration: …)]` | `gateway/run_inbound.py`, the branch taken when STT is disabled | Same family. |
| `[User sent an image: …]` / `[User sent audio: …]` / `[User sent a video: …]` / `[User sent a file: …]` | `gateway/run.py` `_build_media_placeholder` | Emitted for a media-only event that was queued while the agent was busy. |

**Not in this family:** `[The user sent an image~ Here's what I can see: …]` and the sticker notes
(`gateway/run.py`) carry the vision pipeline's *description* of what arrived. That description is
the transcript's only account of the image, so it is content, not scaffolding, and is left alone.

**Hazard — the compression snapshot is not always its own message.** Upstream folds it into the
trailing *real* user turn whenever a standalone insertion would create consecutive user messages
(`_merge_anchor_into_user_message`; `_strip_stale_todo_snapshot`'s own comment says "Snapshots are
appended to the trailing user turn"). Upstream distinguishes the two cases with
`_todo_snapshot_is_only_content`, and so must we: `withoutCompressionScaffolding` cuts at the
marker and keeps whatever the user typed; only a turn left empty becomes a note. Matching the
marker as a whole-message prefix and hiding the message would delete real user text.

**Hazard — an attachment note can sit on either side of what the person typed.** Upstream only
ever prepends it (`message_text = f"{context_note}\n\n{message_text}"`), but the production store
holds both orders: of the DingTalk turns carrying one, most begin with the note and at least one
has it *after* the caption. The cause was not traced — most likely the platform delivers caption
and attachment as separate events that are then merged. So `withoutAttachmentScaffolding`
(`ui/chat/TimelineNote.kt`) removes each occurrence wherever it appears rather than matching a
prefix; a prefix match leaves the note on screen in exactly the reported case (HG-24).

Mirrored constant: `COMPRESSION_SNAPSHOT_HEADER` in `ui/chat/TimelineNote.kt` is a hand-copy of
`TODO_INJECTION_HEADER`. Upstream renaming or rewording it silently returns this app to rendering
the scaffolding as a user bubble — there is no version negotiation and no error.

### 5. Mirrored constants

`MEDIA_DELIVERY_EXTENSIONS` in `android/.../domain/Mappers.kt` is a hand-copy of
`gateway/platforms/base.py` `MEDIA_DELIVERY_EXTS`. Pinned item-for-item by
`HermesContractTest.media_delivery_extensions_mirror_the_upstream_delivery_whitelist`.

`CompactionCarrier` in `android/.../domain/CompactionCarrier.kt` hand-copies three markers from
`agent/context_compressor.py`:

| 常量 | 上游 |
|---|---|
| `PRIOR_CONTEXT_HEADER` | `_MERGED_PRIOR_CONTEXT_HEADER` |
| `SUMMARY_DELIMITER` | `_MERGED_SUMMARY_DELIMITER` |
| `SUMMARY_END_MARKER` | `_SUMMARY_END_MARKER` |

They delimit the context-compaction handoff that Hermes carries through the **user-role** channel.
Upstream strips it before showing a transcript (`agent/compaction_display.py` →
`ContextCompressor._strip_context_summary_handoff_message`), but the dashboard REST history we read
does **not**, so the client must project it itself. The projection mirrors upstream's shape — keep
the prior tail before the delimiter, keep the live message after the end marker, drop a pure
handoff — because a carrier can hold real conversation, and dropping the turn on sight would lose
it. Matching is anchored at content start, as upstream's own detector is.

**If these strings change upstream, the scaffolding reappears verbatim in the chat and the bot
transcript.** There is no version negotiation and no error; `HermesContractTest` pins them.

**Do not align it with `gateway/run.py`'s `_TOOL_MEDIA_RE`.** That regex only auto-tags output from
`text_to_speech` / `image_generate` (`_AUTO_APPEND_MEDIA_TOOL_NAMES`), carries a much shorter list,
and aligning to it would silently drop `html` and `md` attachments.

### 6. Session `source` values

`cron`, `subagent`, `tool`, `kanban`, `oneshot`, `dingtalk`, `feishu`, `telegram`, `discord`, `slack`,
`mattermost`, `matrix`, `signal`, `whatsapp`, `bluebubbles`, `homeassistant`, `email`, `sms`,
`webhook`, `api_server`, `weixin`, `wecom`, `qqbot`, `yuanbao` are hidden from the interactive list
(`SessionRepository.EXCLUDED_SOURCES`). Upstream's human-facing pickers exclude `kanban`, `tool`,
and `oneshot`; the app additionally keeps `cron` in its dedicated surface and retains `subagent`
for older data. `tui`, `cli`, `desktop`, `hermes-dispatch`,
**`hermes_remote`** (this app's own) and any unknown value stay visible. A new upstream value is
safe by default; a removed one is not — and a future upstream value colliding with `hermes_remote`
would make the phone hide every session it created, so `HermesContractTest` asserts it stays out of
the excluded set.

### 7. Messaging channels and cron delivery (added 2026-09-07)

Surfaces this app started depending on after 0.1.102. None of them are version-negotiated:

| What we read | What upstream gives | If upstream changes it |
|---|---|---|
| `GET /api/messaging/platforms` → `state` | `connected` / `pending_restart` / `startup_failed` / `gateway_stopped` / `not_configured` / `disabled` | An unknown value degrades to 状态未知; it can **never** degrade to connected |
| same → `needs_attention` / `error_message` / `home_channel` | `hermes_cli/web_routers/messaging.py` `_messaging_platform_payload` | Absent means not shown; the main status is unaffected |
| `POST /api/gateway/restart` | `hermes_cli/web_routers/actions.py` | 404 surfaces as `HR-MSG-005` |
| `POST /api/messaging/platforms/{id}/test` | its `message` is a diagnosis, shown as detail | Absent means the test fails |
| cron `deliver` | `local` (server default) / `origin` / any connected channel id | An unknown value renders as its own target name |
| cron `last_status = delivery_failed` + `last_delivery_error` | ran fine, never delivered; `last_error` is null here | A rename makes that failure silent again |
| `GET /api/cron/delivery-targets` | `{id, name, home_target_set, home_env_var}`; upstream calls it the single source of truth for UIs | On failure the picker offers only 只存不发 |
| ~~`handoff.request` / `handoff.state`~~ | refusals 4009 / 4025 / 4026 / 4027 | **No longer consumed** — HG-34 (2026-09-12) deleted 转到消息渠道, so this repo has no caller. Upstream may change it freely without affecting us |
| session row `display_name` | the peer or group a platform session is with. **Measured on a live Hermes: filled for `group` rows, blank on every `dm` row** — it cannot carry a peer label alone | Absent means the transcript falls back to `chat_type` |
| session row `chat_type` | `dm` or `group` on a platform session | Absent means the transcript names the channel and claims nothing about who |

**A directional fact worth not re-deriving** (kept although we no longer call handoff, because it
is the kind of thing that gets re-proposed): handoff moves a **local session out to a platform**,
one way. `Platform` does contain `local`, but it is not a configured gateway platform (no home
channel), so `platform=local` is refused with 4025. `handoff.request` also goes through
`_with_session`, which requires a session live in the **dashboard** process — a channel session
lives in the **gateway** process. **A channel conversation cannot be pulled back to the phone.**

`BOT_SOURCES` is derived from the `EXCLUDED_SOURCES` in §6 minus the complete
`INTERNAL_SESSION_SOURCES` set rather than hand-listed a second time: a platform source added
upstream then joins the 机器人 segment instead of belonging to neither surface, while a newly
excluded internal source cannot accidentally appear as a bot.

### 7c. Manual cron fire is synchronous, and `fire_claim` is the only run signal (verified 2026-09-20)

`POST /api/cron/jobs/{id}/trigger` does not schedule a run and answer — it **runs the job inside the
request** and answers when the run has finished (`hermes_cli/web_routers/cron.py`
`_trigger_cron_job_sync` → `cron/scheduler_provider.py` `fire_due` → `cron/scheduler.py`
`run_one_job`). Measured on this Mac: a job fired at 20:59:33 answered at 21:05:38 — **six
minutes**. The app's REST timeout is 20 seconds (`HermesRestApi.REST_TIMEOUT_SECONDS`), so every
job slower than that times out on the wire **while running to completion on the Mac**.

| What we read | What upstream gives | If upstream changes it |
|---|---|---|
| job `fire_claim` | `{at, by}` while a scheduler holds the durable claim, `null` otherwise (`cron/jobs.py` `claim_job_for_fire`) | Renamed or dropped → a timed-out 「立即运行」 falls back to comparing `last_run_at`, which only moves when the run **ends**; a long run then reports HR-CRON-003 again |
| claim lifetime | TTL 300 s, refreshed by `heartbeat_fire_claim` while the run lasts | A shorter TTL makes a long run look finished; a longer one keeps the button disabled after a crash |
| a fire that loses the claim | the action fails, `error = "Fire claim was not acquired"` | This is what a second tap during a run gets, and why the UI disables the button instead of retrying |

`state` stays `scheduled` for the whole run and `last_run_at` is stamped only at the end, so
**`fire_claim` is the only field that says "running right now."** `GET /api/cron/jobs/{id}` does
return it (verified against the live 0.21.0 managed server).

If upstream ever makes the trigger asynchronous (claim, answer `202`, run off-thread), the timeout
path disappears and `CronDetailViewModel.trigger`'s second question becomes dead code — delete it
then, do not keep both.

### 7b. Outbound boundary: which process can reach a platform (verified 2026-09-09)

Four facts, written down because we got this wrong once by generalising from DingTalk — the one
platform whose out-of-process send is degenerate — to all 33.

1. **The dashboard cannot reach a platform at all.** `hermes dashboard` / `hermes serve` runs
   agents in-process (`tui_gateway.ws → server._make_agent`, noted at `hermes_cli/main.py`) but
   **loads no platform adapters**. Outbound delivery is `handle_message` → `self.send()` inside
   `gateway/platforms/base.py`, which lives only in the `hermes gateway run` process. `qqbot` and
   `raft` override `handle_message`; both overrides are still adapter-internal.
   **So a `prompt.submit` from the phone is never delivered to the channel — on any platform.**
2. **Nothing retries it later either.** `gateway/delivery_ledger.py` records a durable delivery
   obligation, but only from inside the adapter's own send path. A message the dashboard writes to
   `state.db` creates no obligation, so no sweep will pick it up.
3. **Out-of-process sends (`_standalone_send`, used by cron `deliver=` and `hermes send`) split in
   two.** Feishu, Slack, Telegram, WeCom and ~16 others take a **real `chat_id`** and can reach any
   conversation. **DingTalk cannot**: it posts to one static `DINGTALK_WEBHOOK_URL` and **ignores
   `chat_id`**, because the live adapter uses per-conversation webhooks that arrive with each
   inbound message and do not exist outside that process.
4. **We cannot use (3) from the app anyway.** There is no send endpoint — every `/api/messaging/*`
   route is configuration or pairing, and `/test` sends nothing; this repository does not modify
   upstream; and the session row carries no `chat_id`, only `source` / `display_name` / `chat_type`.

Consequence for any future "send into the channel from the phone" work: it needs **both** a
`chat_id` on the session row (or an upstream endpoint that replies into the session) **and** a path
that reaches the live adapter. Until then, cron `deliver=<channel>` is the only delivery we have.
This is also why the 机器人 conversation carries a one-time dialog rather than a promise
(`docs/DESIGN.md` §5.16).

### 8. Local runtime mode: the owner's install itself (added 2026-09-21; installer 2026-09-22)

When Desktop runs the Mac's own Hermes instead of its bundled copy (`docs/DESKTOP_PHASE0.md`, "Local
Hermes runtime"), it depends on the shape of the install, not only on the wire. Since 2026-09-22 it
also installs that Hermes on a Mac that has none, by driving upstream's installer and its stage
protocol (`docs/DESKTOP_PHASE0.md`, "Installing Hermes when the Mac has none"); the last four rows
are that surface. The protocol carries a `protocol_version`; nothing else here is versioned by
upstream:

| Surface | What Desktop relies on | If upstream changes it |
|---|---|---|
| `~/.hermes/hermes-agent/venv/bin/hermes serve --host 127.0.0.1 --port 9119` | the standard installer's layout and the headless serve's `HERMES_BACKEND_READY port=<n>` stdout line | detection reports `incompleteInstallation`, or readiness times out and the switch is rolled back |
| `.git/HEAD`, loose refs, `packed-refs`; `hermes_cli/__init__.py` `__version__ = "x.y.z"` | read as files to identify the code on disk | `unreadableIdentity` — surfaced, nothing switched |
| `venv/lib/python*/site-packages/hermes_agent-<version>.dist-info` | its version must equal `__version__` before Desktop starts the checkout's code | Desktop waits (`dependenciesPending`) and never switches or restarts |
| `~/.hermes/.hermes-update-in-progress` (`hermes_cli/update_lock.py`, 20-minute ceiling) | restarts wait while it is fresh | a restart could land mid-update |
| `~/.hermes/profiles/`, `~/.hermes/active_profile` | a profile makes local mode unsupported | a new profile mechanism would be missed |
| `ai.hermes.*.plist` `EnvironmentVariables.HERMES_HOME` and `ProgramArguments[0]` | how the owner actually runs Hermes | a custom home or second install could be missed |
| `HERMES_DESKTOP=1` (§3) | the `/api/ws` loopback exemption, and a cron ticker gated per tick on the owner's gateway for one or more profiles (0.21.3; 0.21.0 gated only for more than one) | the ticker could race the owner's gateway again — check `_start_desktop_cron_ticker` |
| `hermes update` → `_kill_stale_dashboard_processes` | restarts a launchd job via `launchctl kickstart` when `shlex.join(ProgramArguments)` contains `hermes serve`, instead of respawning a detached copy | Desktop's own staleness check still restarts the serve, one refresh later |
| `https://hermes-agent.nousresearch.com/install.sh` (source `scripts/install.sh`), added 2026-09-22 | install-when-missing downloads it over HTTPS (every redirect hop on that host or exactly `raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh`), at most 2 MiB, starting with `#!`; upstream publishes no checksum or signature (`docs/MANAGED_HERMES_STRATEGY.md`, "Installer trust decision") | a moved URL or a redirect off the origin is `HR-MIGRATE-015`/`017`; if upstream starts publishing a checksum, verify it |
| `install.sh --manifest` → `{"protocol_version":1,"stages":[{"name","title","category","needs_user_input"}…]}` as the last JSON line of stdout | the stage list and order Desktop runs and shows; `protocol_version` must be exactly 1; stage names `^[a-z][a-z0-9-]{0,31}$`; Chinese titles exist for `prerequisites`, `repository`, `venv`, `python-deps`, `node-deps`, `path`, `config`, `setup`, `gateway`, `complete` | any other version or shape is `HR-MIGRATE-017` and nothing runs; a new stage runs with upstream's English title |
| `install.sh --stage <name> --non-interactive --json --dir <checkout> --hermes-home <home> --branch main` → last stdout line `{"ok":bool,"stage":name,"skipped":bool,"reason"?}` | success, skip (`needs_user_input` stages under `--non-interactive`) or failure of each stage; the stage runs in its own subshell so a failure still prints the frame | no frame is `HR-MIGRATE-017`; a changed flag name would fail every stage |
| `$INSTALL_DIR/.hermes-bootstrap-complete` (`write_bootstrap_marker`, written only by the `complete` stage) | a Desktop-started install counts as finished — for setup, for the offer and for `freshInstallProvider` — only once it exists | a renamed or earlier-written marker would leave the install pending forever (setup blocked, the owner can choose the bundled copy) or let a half-installed Hermes through |
| The installer's default layout for a non-root macOS user (`INSTALL_DIR=$HERMES_HOME/hermes-agent`, `venv/`, `~/.local/bin/hermes` shim naming the checkout) and its repository stage updating an existing checkout | detection accepts the result as the standard install; a retry resumes an unfinished one | detection reports it unsupported → `HR-MIGRATE-018` |

## Upgrade checklist

Run this before adopting a new Hermes, and record the outcome by updating the version table above.

1. `cd android && ./gradlew :app:testDebugUnitTest --tests "*HermesContractTest*"` — the mechanical
   pins. A failure here names the exact surface that moved.
1a. `cd web && npm test` — the Web app's parser, RPC-params and server-request tests, the browser
   client's equivalent of the Android pins.
1b. **Run the Connector contract check against the new Hermes before adopting it**: save its schema
   (`curl -s http://127.0.0.1:9119/openapi.json`, read-only) over
   `connector/fixtures/hermes-openapi/hermes-<version>-complete.json` (keep paths and methods only,
   drop `/api/plugins/*`) and run `npm test -w @hermes-remote/connector`. A route the app calls that
   the new Hermes no longer serves fails there, with its tier. If the app started calling a new
   route, or stopped calling one, change `HERMES_REST_CONTRACT` **and** the §2 table in the same
   commit (the test compares them); if the adapted version below moves, raise `MINIMUM_HERMES_VERSION`
   with it. On a running Mac the same result is in `connector.log` (`hermes.contract`) and on the
   phone's health strip.
2. Diff upstream `gateway/platforms/base.py` `MEDIA_DELIVERY_EXTS` against
   `MEDIA_DELIVERY_EXTENSIONS`.
3. Confirm the RPC method names in section 3 still exist, especially `prompt.submit`,
   `session.create`, `slash.exec`, `complete.path`.
3b. Re-check how Hermes spawns its slash worker and how `tools/environments/local.py` builds that
   child's environment. If the spawn switches away from `sys.executable`, or the `PYTHONPATH`
   stripping changes shape, the managed bundle's import path assumption moves with it. Cheapest
   proof, against an extracted release: with `PYTHONPATH` unset, `<root>/runtime/python/bin/
   python3.11 -s -c "import tui_gateway.slash_worker"` must succeed.
4. Confirm `PLATFORM_HINTS` (`agent/prompt_builder.py`) still describes the client surfaces the
   same way — it is what tells the model whether it can deliver attachments at all.
5. Confirm the `platform_hints` config override still resolves: on the Mac, `_resolve_platform_hint`
   must return the `hermes_remote` text and must leave the `desktop`/`tui` defaults untouched.
   Without it this app's platform silently has no capability block at all.
6. Diff upstream `tools/todo_tool.py` `TODO_INJECTION_HEADER` against `COMPRESSION_SNAPSHOT_HEADER`
   (section 4b), and re-check that the skills-reload notice is still appended after it rather than
   emitted on its own. A silent reword here brings the scaffolding back into the transcript as a
   user bubble.
7. Confirm the `messages` table still exposes `timestamp` (section 1b): `sqlite3 ~/.hermes/state.db
   ".schema messages"`. A rename silently empties every history timestamp again.
8. Run the attachment and streaming smoke tests in `docs/SMOKE_TEST.md` against the upgraded Hermes.
8h. **Regenerate the params allowlist** (section 3, "WebSocket RPC params") from the new Hermes'
    `tui_gateway/contracts/` into `docs/hermes-rpc-params.json` — read the source with `ast`, never
    import it — then run `./gradlew :app:testDebugUnitTest --tests "*RpcParamContractTest*"`. A key
    the app sends that the new contract dropped fails there, before a user's call answers 4000. Update
    the table in section 3 in the same change (the test compares them), and re-check that every
    params form still works on the Hermes being replaced.
8a. Run "Approval and clarify against a real Hermes" in `docs/SMOKE_TEST.md`. The question protocol
    (section 3) has no version negotiation beyond `client.capabilities`, and every way it breaks is
    silent — the phone simply never shows the card. Also re-grep
    `tui_gateway/contracts/server_requests.py` for new request methods: one the app should answer
    must get a card, anything else keeps its -32601. And diff every params key the app sends against
    `tui_gateway/contracts/` — an extra key is a 4000 there, not a warning.
8b. Confirm whether Hermes exposes a versioned missing-capability event with a closed capability kind.
    Never substitute parsing `FeatureUnavailable` or tool-error prose for that event.
8c. Confirm the four numbers the phone classifies on are still those conditions: `prompt.submit`
    4001 / 4007 / 4090, and `pdf.attach` **5028** (kept for its registered meaning although nothing
    routes to it now — see section 3). They are in `ChatViewModel`'s companion object
    and nowhere else. A renumber does not error — the failure quietly becomes the generic
    `HR-SESS-007`, which offers a retry that cannot work.
8f. Confirm `POST /api/cron/jobs/{id}/trigger` still runs the job **inside the request** and that
    `GET /api/cron/jobs/{id}` still returns `fire_claim` (section 7c):

    ```bash
    curl -s -H "Authorization: Bearer $(cat "<token file>")" \
      http://127.0.0.1:9119/api/cron/jobs/<id> | python3 -c "import json,sys;print('fire_claim' in json.load(sys.stdin))"
    ```

    If the trigger became asynchronous, delete the timeout branch in `CronDetailViewModel.trigger`
    rather than leaving two answers to the same question. If `fire_claim` disappeared, a long run's
    「立即运行」 starts reporting HR-CRON-003 again.
8g. Measure what one read of a conversation with attachments actually returns. Attach a document
    and two photographs, then measure both the stored row and the answer.

    **Not with `sqlite3 length()`** — it counts characters up to the first NUL, and these rows begin
    with `\x00json:`, so it reports 0 for a 27 MB row. That mistake cost a day on 2026-09-20. Use
    something that returns the whole value:

    ```bash
    python3 -c "import sqlite3;d=sqlite3.connect('file:$HOME/.hermes/state.db?mode=ro',uri=True);\
    print(max(len(c or '') for (c,) in d.execute('select content from messages')))"
    ```

    A stored row in the megabytes means the images are inlined at write time and every read carries
    them (section 1b). (`session.resume` no longer depends on this: it sends `omit_messages` and
    carries no transcript — section 3.) If a new Hermes accepts `inline_images=false` on `session.resume` or
    `GET /messages`, the transfer half is fixed upstream and patch 020 should be removed after the
    pinned commit adopts it. Until then, verify both calls still honour `false` and keep the default
    byte-for-byte compatible.
8d. Confirm `sessions.changed` is still broadcast, still carries no session id, and is still sent
    when the list moves. It is the only event the app treats as "go and ask" rather than as news
    about one conversation; losing it is silent (a list that stops refreshing itself), so the proof
    is a diagnostic log showing the line during an upstream-started run.
8e. Confirm what shapes `content` comes back in (section 1b). The client accepts a string, null and
    a block list; a new block *type* is skipped safely, but a new container — content as an object,
    or blocks nested one level deeper — is not covered, and a shape the parser refuses costs the
    whole transcript rather than the row. Cheapest proof: send one turn with an image from each
    client, then read the stored rows back with `sqlite3 ~/.hermes/state.db "select content from
    messages order by id desc limit 5"`.
8i. Confirm the bare attachment placeholders (section 4) still use the labels the client strips.
    A new label is not an error; it reaches the person as a stray `[something]` line under their own
    message, which is what HG-60 was.
8j. If any Mac runs in local runtime mode, re-read the table in section 8 against the new commit —
    in particular `_start_desktop_cron_ticker`'s `profile_gate`, `_desktop_loopback_auth_exempt`,
    `update_lock.MARKER_NAME`, and `_loaded_launchd_backend_jobs` — and raise
    `DesktopLocalHermesDetector.minimumVersion` if a behaviour Desktop relies on moved.
8k. Confirm `hermes_cli/__init__.py` `__version__` and the version in `pyproject.toml` still move
    together. Desktop's local mode treats them disagreeing (the installed `hermes_agent-*.dist-info`
    version against `__version__`) as "dependencies not reinstalled": after 10 minutes it shows
    HR-MIGRATE-008 and refuses switches and commit-change restarts, though a stopped Hermes is still
    restarted. Upstream maintains the two literals separately, so a release that bumps only one
    would trip this on every local-mode Mac; if that happens, fix the comparison rather than tell
    owners to reinstall.
8l. **Installer protocol** (install-when-missing, section 8). Read the new commit's
    `scripts/install.sh` (`git show <commit>:scripts/install.sh`, never run it): `emit_manifest` still
    prints `protocol_version` 1 and the stage names `DesktopHermesInstallerStage.titleChinese` knows;
    `run_stage_protocol` still skips `needs_user_input` stages under `--non-interactive` and prints
    `emit_stage_json`'s frame even when a stage fails; `--dir`, `--hermes-home` and `--branch` keep
    their meaning; `resolve_install_layout` still puts a non-root macOS install at
    `$HERMES_HOME/hermes-agent`; `write_bootstrap_marker` still writes `.hermes-bootstrap-complete`
    in the checkout from the `complete` stage only; no macOS path added `sudo`; and upstream still publishes no checksum
    or signature (if it does, verify it and update the trust decision). Then paste the new
    `--manifest` line into `testTheManifestParserReadsUpstreamsRealManifestLine`. A version bump of
    the protocol is a Desktop change before it is an adoption: until Desktop speaks it, every
    install is `HR-MIGRATE-017` and falls back to the bundled copy.
8m. **Paging parameters** (section 1c). In the new commit's `hermes_cli/web_routers/sessions.py`,
    confirm `get_session_messages` still accepts `order=latest` with `limit`/`offset`, still returns
    `pagination` with `limit`, `offset`, `order` and `returned`, and that `get_messages(latest=True)`
    still returns the page in ascending order; and read the `le=` bound on `/api/sessions`'
    `limit` and `/api/profiles/sessions`' `limit`. A lowered bound is a `422` on every list load, and
    a latest page that comes back newest-first renders each page of history upside down. Cheapest
    live proof, read-only on the Mac:

    ```bash
    curl -s -H "Authorization: Bearer $(cat "<token file>")" \
      "http://127.0.0.1:9119/api/sessions/<id>/messages?order=latest&limit=3&offset=0" \
      | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['pagination'],[m['id'] for m in d['messages']])"
    ```

    The ids must be ascending and `pagination.order` must be `latest`.
9. **Read the source, not the notes.** See below.

## Known hazards

- **`source=tui` is not evidence of a terminal.** Until 2026-09-05 this app sent no source, so its
  sessions were recorded as `tui` with `origin_json` NULL — identical to a real terminal — and
  upstream's `PLATFORM_HINTS["tui"]` block told the agent it had no attachment channel and that
  `MEDIA:` tags were not intercepted. Both are false here, and the agent obeyed the prompt rather
  than the reality: it withheld deliveries and printed local paths. Sessions stored before the
  change keep `tui` in the database; only their runtime platform is corrected, by the `source` sent
  on resume.
- **Prose notes about upstream have been wrong twice.** During the 2026-09-05 investigation, a
  project reference claimed a `_TOOL_MEDIA_RE` patch that the source did not contain, and framed the
  two media regexes as a "drift" they are not. Both claims survived because nobody re-read upstream.
  Verify against `~/.hermes/hermes-agent` source; treat second-hand notes as leads only.
- **No version negotiation exists.** Nothing on the wire tells us which Hermes we are talking to, so
  an upgrade is detected only by something breaking — or by this checklist. Since 2026-09-21 the
  Connector contract check (§2) catches the REST half at runtime; the WebSocket RPC surface (§3),
  text grammars (§4) and mirrored constants (§5) still have no runtime check.
