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
    /** Held raw rows, including tools/hidden carriers; older reads overlap one boundary row. */
    val serverRows: Int get() = rows.size

    fun payload(): String = buildJsonObject {
        put("messages", JsonArray(rows))
        put(REACHED_START_KEY, reachedStart)
        put("hr_history_view", "compacted-v1")
    }.toString()

    companion object {
        /** Rows per page, for the tail and for each older page. Upstream accepts up to 500. */
        const val HISTORY_PAGE_SIZE = 100

        /**
         * Client-side completeness marker on the versioned compacted-display window. Legacy
         * active-only windows are invalidated, including any old reached-start marker.
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

        /** Null for unreadable or legacy active-only payloads; the caller refetches. */
        fun fromStored(raw: String): TranscriptWindow? = runCatching {
            val root = parser.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
            // Active-only windows may have holes in their middle, not just a missing prefix.
            // Refetch them instead of trusting their row count or old reached-start marker.
            if ((root["hr_history_view"] as? JsonPrimitive)?.content != "compacted-v1") return@runCatching null
            val reached = (root[REACHED_START_KEY] as? JsonPrimitive)?.booleanOrNull ?: false
            TranscriptWindow(rowsOf(raw), reached)
        }.getOrNull()

        /**
         * The server orders logical messages, not representative row IDs: compaction copies the
         * protected tail under new IDs. Join at the first row's identity and keep page order.
         * With no boundary overlap the gap is unknown, so discard the old window and page again.
         */
        fun mergeTail(known: TranscriptWindow?, tail: List<JsonObject>, limit: Int): TranscriptWindow {
            if (tail.size < limit) return TranscriptWindow(tail, reachedStart = true)
            val first = tail.firstOrNull()?.rowId() ?: return TranscriptWindow(tail, false)
            val boundary = known?.rows?.indexOfFirst { it.rowId() == first } ?: -1
            if (boundary < 0) return TranscriptWindow(tail, false)
            val tailIds = tail.mapNotNullTo(HashSet()) { it.rowId() }
            val older = known!!.rows.take(boundary).filterNot { it.rowId() in tailIds }
            return TranscriptWindow(older + tail, known.reachedStart)
        }

        /**
         * Older pages are already in display order. Only their prefix before the first held
         * identity is older; rows after that boundary may be newer than the whole window when
         * offsets drift during a run. A disjoint page is the preceding page, regardless of IDs.
         */
        fun mergeOlder(window: TranscriptWindow, page: List<JsonObject>, limit: Int): OlderMerge {
            val held = window.rows.mapNotNullTo(HashSet()) { it.rowId() }
            val fresh = page.takeWhile { it.rowId() !in held }.filter { it.rowId() != null }
            return OlderMerge(TranscriptWindow(fresh + window.rows, page.size < limit), fresh.size)
        }

        private fun JsonObject.rowId(): Long? = (this["id"] as? JsonPrimitive)?.longOrNull
    }
}

internal data class OlderMerge(val window: TranscriptWindow, val added: Int)
