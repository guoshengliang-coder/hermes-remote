package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * One conversation's message that was typed, submitted, and refused. [token] is a
 * [SessionReadStore.token].
 *
 * This is NOT a draft. A draft was never sent; this one was, and upstream said no — which is why it
 * carries the error code that explains the refusal and whether trying again can work.
 */
@Serializable
data class UnsentRecord(
    val v: Int = UNSENT_RECORD_VERSION,
    val token: String = "",
    /** The bubble's id, reused on restore so a retry removes the same turn it replaces. */
    val messageId: String = "",
    val text: String = "",
    /** `AppErrorCode.value`, e.g. `HR-SESS-013`. Stored as its string so this file stays pure data. */
    val code: String = "",
    val retryable: Boolean = true,
    /**
     * How many attachments the refused send carried. The BYTES are not stored (see [UnsentStore]),
     * so a restored record with a non-zero count cannot be replayed as it was — the count is what
     * lets the chat screen say so instead of silently sending the text alone.
     */
    val attachments: Int = 0,
    val updatedAt: Long = 0L,
)

const val UNSENT_RECORD_VERSION = 1

/** Bounds copied from [DraftStore]: same file, same rewrite-everything cost. */
const val MAX_PERSISTED_UNSENT = 50
const val MAX_UNSENT_CHARS = 8_000
const val MAX_UNSENT_PAYLOAD_CHARS = 64_000

private val unsentJson = Json { ignoreUnknownKeys = true }

/** Decode the stored JSON array; never throws — corrupt/absent/wrong-version → dropped. */
fun decodeUnsent(raw: String?): List<UnsentRecord> =
    runCatching { unsentJson.decodeFromString<List<UnsentRecord>>(raw ?: "[]") }
        .getOrDefault(emptyList())
        .filter { it.v == UNSENT_RECORD_VERSION && it.token.isNotBlank() && it.messageId.isNotBlank() }

/**
 * Newest-first under the three size bounds, exactly as [encodeDrafts] does, and for the same
 * reason: what falls off the end is always the one the user touched longest ago.
 *
 * An over-long message is TRUNCATED rather than dropped — losing the tail of something the user
 * wrote is bad, losing all of it is worse.
 *
 * Unlike a draft, blank text is KEPT when the record carries attachments: "six images and no
 * caption" is a real send, and a refused one still has to be reported.
 */
fun encodeUnsent(records: List<UnsentRecord>): String {
    val ordered = records
        .filter { it.token.isNotBlank() && it.messageId.isNotBlank() && (it.text.isNotBlank() || it.attachments > 0) }
        .sortedByDescending { it.updatedAt }
        .take(MAX_PERSISTED_UNSENT)
        .map { if (it.text.length <= MAX_UNSENT_CHARS) it else it.copy(text = it.text.take(MAX_UNSENT_CHARS)) }
    val kept = mutableListOf<UnsentRecord>()
    var budget = MAX_UNSENT_PAYLOAD_CHARS
    for (record in ordered) {
        val size = runCatching { unsentJson.encodeToString(record).length }.getOrNull() ?: continue
        if (size + 2 > budget) break
        budget -= size + 2
        kept += record
    }
    return runCatching { unsentJson.encodeToString(kept) }.getOrDefault("[]")
}

/**
 * Record [record]'s refused send, replacing any earlier one for the same conversation.
 *
 * One per conversation on purpose: the chat screen only ever has one failed turn outstanding,
 * because sending again is what clears the previous one.
 */
fun putUnsent(records: List<UnsentRecord>, record: UnsentRecord): List<UnsentRecord> =
    records.filterNot { it.token == record.token } + record

fun removeUnsent(records: List<UnsentRecord>, token: String): List<UnsentRecord> =
    records.filterNot { it.token == token }

/**
 * What the chat screen and the session list need from the unsent cache. Narrow on purpose, exactly
 * as [DraftSnapshot] is: a test supplies its own without an Android `Context`, and neither consumer
 * can reach [UnsentStore.clearAll], which belongs to the identity-change path alone.
 */
interface UnsentSnapshot {
    /** Which conversations currently hold a refused send. The session list's 未发送 line reads this. */
    val tokens: Flow<Set<String>>
    suspend fun read(token: String): UnsentRecord?
    suspend fun save(record: UnsentRecord)
    suspend fun clear(token: String)
}

private val Context.unsentDataStore by preferencesDataStore(name = "session_unsent")

/**
 * Device-local record of messages that were submitted and refused, keyed by Mac/profile/session
 * (HG-49).
 *
 * Before this, a refused send lived in two places that both die young: the bubble's
 * `DeliveryState.FAILED` inside [com.hermes.client.data.progress.SessionRuntimeStore] (process
 * lifetime, and evictable once the conversation goes idle) and the retry payload in a map on the
 * nav-scoped `ChatViewModel`. Leaving the chat screen destroyed the second one, which is why the
 * bubble came back saying the generic `HR-SESS-007` instead of the 4090 it actually was, and why
 * its 「点按重试」 tap did nothing. The session list never saw any of it.
 *
 * **One key holding a JSON array, not a key per session** — the same rule, and the same reason, as
 * [DraftStore] and [SessionPhaseStore]: Preferences DataStore rewrites the whole file on every
 * `edit`, so per-session keys buy no IO and only make eviction harder.
 *
 * **Text only.** Staged attachments are in-memory bytes (6 MB each, six at a time); persisting them
 * needs a cache directory, an eviction policy and a story for "the file you attached is gone now",
 * which is a different feature — the same ruling [DraftStore] makes about drafts. What this file
 * does instead is remember HOW MANY there were, so the restored bubble can say the attachments are
 * gone (`HR-SESS-015`) and withhold a retry that could only send half of what the user meant.
 * That is a trade-off, not an omission.
 *
 * Every operation degrades to "nothing unsent" on failure. A cache that cannot be read or written
 * is never an error the user should see, so nothing here throws and nothing here carries an `HR-`
 * code — same ruling as [DraftStore] and [SessionPhaseStore]. The code in [UnsentRecord.code]
 * describes the SEND that failed, not this file.
 */
class UnsentStore(private val context: Context) : UnsentSnapshot {
    private val key = stringPreferencesKey("unsent")

    private val records: Flow<List<UnsentRecord>> = context.unsentDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decodeUnsent(it[key]) }

    override val tokens: Flow<Set<String>> = records.map { list -> list.mapTo(mutableSetOf()) { it.token } }

    override suspend fun read(token: String): UnsentRecord? = runCatching {
        records.first().firstOrNull { it.token == token }
    }.getOrNull()

    override suspend fun save(record: UnsentRecord) {
        runCatching {
            context.unsentDataStore.edit { prefs ->
                prefs[key] = encodeUnsent(putUnsent(decodeUnsent(prefs[key]), record))
            }
        }
    }

    override suspend fun clear(token: String) {
        runCatching {
            context.unsentDataStore.edit { prefs ->
                prefs[key] = encodeUnsent(removeUnsent(decodeUnsent(prefs[key]), token))
            }
        }
    }

    /** Drop everything. The Relay or identity changed — an unsent message is user content. */
    suspend fun clearAll() {
        runCatching { context.unsentDataStore.edit { it.remove(key) } }
    }
}
