package com.hermes.client.data.repository

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The part of one conversation this device holds: a contiguous run of Hermes message rows ending
 * at the newest row it has seen, oldest first (HG-104).
 *
 * Before paging, every chat open and every reconciliation pass downloaded upstream's whole answer
 * — its latest 500 rows — and replaced the transcript with it. Now the network only ever brings
 * the newest [HISTORY_PAGE_SIZE] rows (the "tail") or one older page, and this window is what
 * those pages are merged into, so rows loaded earlier (from disk, or by scrolling up) survive.
 *
 * Rows are kept as the raw JSON objects upstream sent, for the reason [TranscriptStore] gives:
 * mapping changes between app versions, raw rows do not need a migration. [payload] is also
 * exactly what gets written to disk, in the same `{"messages": [...]}` shape as a REST body plus
 * [REACHED_START_KEY], so a stored window and a fresh response parse through the same code.
 */
internal data class TranscriptWindow(
    val rows: List<JsonObject>,
    /** True once the oldest row of the conversation is in [rows]: there is nothing older to load. */
    val reachedStart: Boolean,
) {
    /** How many server rows are held — the `offset` of the next older page. */
    val serverRows: Int get() = rows.size

    fun payload(): String = buildJsonObject {
        put("messages", JsonArray(rows))
        put(REACHED_START_KEY, reachedStart)
    }.toString()

    companion object {
        /** Rows per page, for the tail and for each older page. Upstream accepts up to 500. */
        const val HISTORY_PAGE_SIZE = 100

        /**
         * Client-side marker on a stored window. A payload without it is either a REST body or a
         * transcript cached before paging existed; both are treated as "may have older rows", and
         * the first older-page request settles it for the price of one small response.
         */
        const val REACHED_START_KEY = "hr_reached_start"

        private val parser = Json { ignoreUnknownKeys = true }

        /**
         * The rows of a REST body or a stored window. Throws [SerializationException] on anything
         * that is not `{"messages": [ {…}, … ]}`, the same class a DTO decode failure throws, so
         * the error mapping (`historyFailure`) classifies it as an unreadable response.
         */
        fun rowsOf(raw: String): List<JsonObject> {
            val root = parser.parseToJsonElement(raw) as? JsonObject
                ?: throw SerializationException("transcript payload is not an object")
            val messages = root["messages"] ?: return emptyList()
            val array = messages as? JsonArray
                ?: throw SerializationException("transcript messages is not an array")
            return array.map { it as? JsonObject ?: throw SerializationException("transcript row is not an object") }
        }

        /** A window read back from disk; null when the payload no longer parses. */
        fun fromStored(raw: String): TranscriptWindow? = runCatching {
            val root = parser.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
            val reached = (root[REACHED_START_KEY] as? JsonPrimitive)?.booleanOrNull ?: false
            TranscriptWindow(rowsOf(raw), reached)
        }.getOrNull()

        /**
         * Merge a freshly fetched newest page into [known].
         *
         * - A page shorter than [limit] is the whole conversation: it replaces everything.
         * - Otherwise the rows of [known] that are OLDER than the page's first row are kept in
         *   front of it, and the overlapping rows are replaced by the page's (they may have been
         *   rewritten, or the phone's copy may predate the end of a turn).
         * - That is only sound when [known] reaches into the page. If every known row is older
         *   than the page, more rows than one page were added since, and the gap between the two
         *   is unknown: the known rows are dropped rather than shown out of order, and scrolling up
         *   loads the gap like any other older page.
         */
        fun mergeTail(known: TranscriptWindow?, tail: List<JsonObject>, limit: Int): TranscriptWindow {
            if (tail.size < limit) return TranscriptWindow(tail, reachedStart = true)
            val first = tail.first().rowId() ?: return TranscriptWindow(tail, reachedStart = false)
            if (known == null || known.rows.isEmpty()) return TranscriptWindow(tail, reachedStart = false)
            val knownIds = known.rows.map { it.rowId() }
            if (knownIds.any { it == null }) return TranscriptWindow(tail, reachedStart = false)
            if (knownIds.none { it!! >= first }) return TranscriptWindow(tail, reachedStart = false)
            val older = known.rows.filter { it.rowId()!! < first }
            return TranscriptWindow(older + tail, reachedStart = known.reachedStart)
        }

        /**
         * Prepend an older [page] (fetched with `offset = window.serverRows`) to [window].
         *
         * Rows already held are dropped: if the conversation grew since the window was loaded,
         * the offset now points a few rows too new and the page overlaps. Only rows strictly older
         * than the window's first row are taken. Only a page shorter than [limit] means the start
         * has been reached. A full page that adds nothing (more than a page was added upstream
         * since, so it lies entirely inside rows already held) is NOT the start: the caller skips
         * one page further and asks again — see [SessionRepository.olderHistory].
         */
        fun mergeOlder(window: TranscriptWindow, page: List<JsonObject>, limit: Int): OlderMerge {
            val first = window.rows.firstOrNull()?.rowId()
            val held = window.rows.mapNotNullTo(HashSet()) { it.rowId() }
            val fresh = page.filter { row ->
                val id = row.rowId() ?: return@filter false
                id !in held && (first == null || id < first)
            }
            val reached = page.size < limit
            return OlderMerge(TranscriptWindow(fresh + window.rows, reached), added = fresh.size)
        }

        private fun JsonObject.rowId(): Long? = (this["id"] as? JsonPrimitive)?.longOrNull
    }
}

internal data class OlderMerge(val window: TranscriptWindow, val added: Int)
