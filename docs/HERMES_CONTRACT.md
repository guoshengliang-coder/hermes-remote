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

```
session.create   session.resume   session.interrupt   session.workspace.move
prompt.submit    slash.exec       complete.path       commands.catalog
approval.respond clarify.respond  config.get          config.set
file.attach      image.attach     image.attach_bytes  pdf.attach
process.list     projects.tree    projects.project_sessions
```

Server events consumed: `message.start` / `message.delta` / `message.complete`,
`tool.start` / `tool.complete`, `session.info` / `session.lifecycle`,
`approval.request`, `clarify.request`.

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

### 5. Mirrored constant

`MEDIA_DELIVERY_EXTENSIONS` in `android/.../domain/Mappers.kt` is a hand-copy of
`gateway/platforms/base.py` `MEDIA_DELIVERY_EXTS`. Pinned item-for-item by
`HermesContractTest.media_delivery_extensions_mirror_the_upstream_delivery_whitelist`.

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
6. Run the attachment and streaming smoke tests in `docs/SMOKE_TEST.md` against the upgraded Hermes.
7. **Read the source, not the notes.** See below.

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
