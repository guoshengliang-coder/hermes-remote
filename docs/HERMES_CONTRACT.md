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

### 2. REST paths

```
/api/status            /api/config            /api/env  /api/env/reveal
/api/sessions          /api/sessions/{id}     /api/sessions/{id}/messages
/api/sessions/search   /api/sessions/stats    /api/profiles/sessions
/api/profiles          /api/profiles/active
/api/files             /api/files/upload
/api/cron/jobs         /api/cron/jobs/{id}    /api/cron/jobs/{id}/runs
/api/mobile/events     /api/mobile/events/ack /api/mobile/events/read
/api/model/options     /api/model/set         /api/tools/toolsets
/api/skills            /api/skills/toggle     /api/analytics/usage
/api/audio/transcribe  /api/messaging/platforms
```

Authentication is the `X-Hermes-Session-Token` header. The Mac's Hermes credential never leaves the
Mac; the phone holds only its own app token (see `docs/ARCHITECTURE.md`).

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
session.create   session.resume   session.interrupt   session.workspace.move
prompt.submit    slash.exec       complete.path       commands.catalog
approval.respond clarify.respond  config.get          config.set
file.attach      image.attach     image.attach_bytes  pdf.attach
process.list     projects.tree    projects.project_sessions
```

Server events consumed: `message.start` / `message.delta` / `message.complete`,
`tool.start` / `tool.complete`, `session.info` / `session.lifecycle`,
`approval.request`, `clarify.request`, `session.reclaimed`.

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

`cron`, `subagent`, `tool`, `dingtalk`, `feishu`, `telegram`, `discord`, `slack`, `mattermost`,
`matrix`, `signal`, `whatsapp`, `bluebubbles`, `homeassistant`, `email`, `sms`, `webhook`,
`api_server`, `weixin`, `wecom`, `qqbot`, `yuanbao` are hidden from the interactive list
(`SessionRepository.EXCLUDED_SOURCES`). `tui`, `cli`, `desktop`, `hermes-dispatch`,
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
| `handoff.request` / `handoff.state` | refusals 4009 / 4025 / 4026 / 4027 | An unmodelled code degrades to `HR-RPC-001` |
| session row `display_name` | the peer or group a platform session is with. **Measured on a live Hermes: filled for `group` rows, blank on every `dm` row** — it cannot carry a peer label alone | Absent means the transcript falls back to `chat_type` |
| session row `chat_type` | `dm` or `group` on a platform session | Absent means the transcript names the channel and claims nothing about who |

**A directional fact worth not re-deriving:** handoff moves a **local session out to a platform**,
one way. `Platform` does contain `local`, but it is not a configured gateway platform (no home
channel), so `platform=local` is refused with 4025. `handoff.request` also goes through
`_with_session`, which requires a session live in the **dashboard** process — a channel session
lives in the **gateway** process. **A channel conversation cannot be pulled back to the phone.**

`BOT_SOURCES` is derived from the `EXCLUDED_SOURCES` in §6 (minus `cron`/`subagent`/`tool`) rather
than hand-listed a second time: a platform source added upstream then joins the 机器人 segment
instead of belonging to neither surface.

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

## Upgrade checklist

Run this before adopting a new Hermes, and record the outcome by updating the version table above.

1. `cd android && ./gradlew :app:testDebugUnitTest --tests "*HermesContractTest*"` — the mechanical
   pins. A failure here names the exact surface that moved.
2. Diff upstream `gateway/platforms/base.py` `MEDIA_DELIVERY_EXTS` against
   `MEDIA_DELIVERY_EXTENSIONS`.
3. Confirm the RPC method names in section 3 still exist, especially `prompt.submit`,
   `session.create`, `slash.exec`, `complete.path`.
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
  an upgrade is detected only by something breaking — or by this checklist.
