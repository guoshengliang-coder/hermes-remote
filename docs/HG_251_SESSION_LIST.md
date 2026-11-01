# HG-251: complete filtered Android session lists

On 2026-11-01 the owner's phone showed 10 chats. The actual Mac serve-v1 response to
`/api/profiles/sessions?limit=500&order=recent` held 466 cron rows and 24 other internal
rows. Filtering after that window left exactly 10 interactive chats. Filtering before
the window returned 229 interactive rows, including the reported missing conversation
(397 stored messages, not archived). These are diagnostic snapshots, not expected fixed
production counts. The conversation title remains in the private work item.

The upstream update on October 27 began admitting cron executions before their monitor
prechecks. The existing ten-minute knowledge curation check then left 144 empty sessions
per day; the new Kanban monitor added more. No database deletion or task suspension is
needed for the client fix. Changing upstream's execution ledger is outside this item.

## Behavior and compatibility

- Live Chats and Bots read the complete nonempty, non-internal list using upstream
  `exclude_sources`, `min_messages=1`, `limit` and `offset`. Messaging rows remain in the
  shared cache and notification token inventory; Chats applies its existing final
  interactive filter, Bots keeps its own channel selection. The activity feed retains
  its existing independent cron-inclusive query.
- Archive uses the same paginator with `archived=only` and its existing interactive
  source exclusions. Server-pinned live backfills are filtered out of the archived view.
- Results are deduplicated by normalized profile and session ID. Cursor advancement uses
  the page width, not result length: upstream can append pins outside the page window.
  A short actual page also ends traversal because `total` can include hidden rows.
- The complete list is published atomically. A failed page, profile error, mismatched or
  repeated cursor, or the 200-page safety budget produces the existing retryable
  `HR-RPC-001` list error rather than publishing a partial list. Legacy responses without
  cursor echoes remain readable. Responses from a changed account/Mac route are cancelled.
- All loaded pages participate in the existing phone-local pin lookup. No pin store
  migration, cloud synchronization, pin API, or new display interaction is introduced.
- Integration classification: yellow because Android's REST mirror and DTO are touched.
  This only consumes existing upstream GET parameters/optional fields; Gateway and
  Connector already forward the query, and Desktop/Web need no protocol or code changes.
  There is no server/schema/version/production change in this branch.

## Verification

The initial three HTTP/repository regression tests all failed against the old code:
filters were absent, the 501st local pin was missing, and page-two failure went unnoticed.
All 51 focused repository/HTTP tests passed. The full Android baseline plus lint passed
on 2026-11-01: `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, with 2,933 tests,
zero failures/errors/skips. `git diff --check` also passed. These are local verification
results; remote PR checks and physical-device acceptance remain separate gates.

Physical-device acceptance is still required: no authorized Android device or emulator
was available in the host probe. Install the separately approved official release, then:

1. Open the same Mac and identity. Confirm the conversation named in HG-251 is listed and opens; confirm the
   phone's existing pin records still place the expected chats under Pinned.
2. Refresh and cold-start. Confirm no duplicate/lost rows and unchanged pins.
3. On test data with more than 500 ordinary chats and heavy empty/internal traffic,
   confirm older chats and phone pins from later pages remain visible.
4. Switch account, Mac and profile; confirm no foreign chats/pins. Check Bots and Archive.

No local debug APK is a published release or proof of physical-device verification.
