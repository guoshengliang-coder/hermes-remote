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
 * Where the disk copy of one refused attachment lives, so a restore can put the bytes back.
 *
 * Images are cached by [ChatMediaRepository.cacheOutgoing] when the refused send is remembered;
 * ordinary files were staged to disk before the send was ever attempted. Either way the path is
 * inside this app's private cache directories — a record can only point at what the app itself
 * wrote, never at an external Uri it may no longer read.
 */
@Serializable
data class UnsentAttachment(
    val id: String = "",
    val name: String = "attachment",
    val mimeType: String = "application/octet-stream",
    val path: String = "",
    val sizeBytes: Long = 0L,
)

/** Saved editor state alongside the original refused message; never attachment bytes. */
@Serializable
data class UnsentComposer(
    val withdrawn: Boolean = false,
    val text: String = "",
    val attachments: Int = 0,
    val attachmentMeta: List<UnsentAttachment> = emptyList(),
)

@Serializable
data class PendingSendConversation(
    val accountId: String,
    val deviceId: String,
    val profile: String?,
    val sessionId: String,
    val cwd: String?,
    val localCreation: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val reasoning: String? = null,
)

/**
 * One conversation's refused or delivery-unconfirmed message. [token] is a
 * [SessionReadStore.token].
 *
 * This is not a draft. A refusal has a retry policy; [deliveryUnconfirmed] retains an ambiguous
 * submission with no replay permission. Both survive navigation/process restoration.
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
     * How many attachments the refused send carried. Kept for v1 records and as the count the
     * session row reports; whether a restore can replay them is decided by [attachmentMeta].
     */
    val attachments: Int = 0,
    /**
     * Where each attachment's disk copy lives (HG-208). When every listed file is still readable
     * the restored turn is replayable with its attachments; when any is gone the restore degrades
     * to `HR-SESS-015` exactly as v1 did. v1 records carry none, so they always degrade — the old
     * behaviour is the fallback, not the default.
     */
    val attachmentMeta: List<UnsentAttachment> = emptyList(),
    val updatedAt: Long = 0L,
    /** HG-220: the original remains recoverable until editing is confirmed or cancelled. */
    val composer: UnsentComposer? = null,
    /** HG-237: outcome is unknown, never a refused/retryable send. */
    val deliveryUnconfirmed: Boolean = false,
    val conversation: PendingSendConversation? = null,
)

const val UNSENT_RECORD_VERSION = 3

/** Bounds copied from [DraftStore]: same file, same rewrite-everything cost. */
const val MAX_PERSISTED_UNSENT = 50
const val MAX_UNSENT_CHARS = 8_000
const val MAX_UNSENT_PAYLOAD_CHARS = 64_000
// Match the Gateway's standard app WebSocket frame budget for the durable delivery journal.
private const val DURABLE_SEND_BYTES = 12 * 1024 * 1024

private val unsentJson = Json { ignoreUnknownKeys = true }

/**
 * Decode the stored JSON array; never throws — corrupt/absent/wrong-version → dropped. v1 records
 * are still accepted (they decode with no [UnsentRecord.attachmentMeta], which the restore treats
 * as "the attachments are gone"); anything newer than this build knows is dropped instead.
 */
fun decodeUnsent(raw: String?): List<UnsentRecord> =
    runCatching { unsentJson.decodeFromString<List<UnsentRecord>>(raw ?: "[]") }
        .getOrDefault(emptyList())
        .filter { it.v in 1..UNSENT_RECORD_VERSION && it.token.isNotBlank() && it.messageId.isNotBlank() }

/**
 * Newest-first under the three size bounds, exactly as [encodeDrafts] does, and for the same
 * reason: what falls off the end is always the one the user touched longest ago.
 *
 * An over-long message is TRUNCATED rather than dropped — losing the tail of something the user
 * wrote is bad, losing all of it is worse.
 *
 * Unlike a draft, blank text is KEPT when the record carries attachments: "nine images and no
 * caption" is a real send, and a refused one still has to be reported.
 */
fun encodeUnsent(records: List<UnsentRecord>): String {
    val ordered = records
        .filter { it.token.isNotBlank() && it.messageId.isNotBlank() && (it.text.isNotBlank() || it.attachments > 0) }
        .sortedByDescending { it.updatedAt }
        .sortedByDescending { it.conversation != null }
        .take(MAX_PERSISTED_UNSENT)
        .map {
            if (it.conversation != null) it else it.copy(text = it.text.take(MAX_UNSENT_CHARS), composer = it.composer?.let { composer ->
                composer.copy(text = composer.text.take(MAX_UNSENT_CHARS))
            })
        }
    val kept = mutableListOf<UnsentRecord>()
    var budget = MAX_UNSENT_PAYLOAD_CHARS
    var durableBudget = DURABLE_SEND_BYTES
    for (record in ordered) {
        val encoded = runCatching { unsentJson.encodeToString(record) }.getOrNull() ?: continue
        if (record.conversation != null) {
            val size = encoded.toByteArray(Charsets.UTF_8).size + 2
            if (size > durableBudget) continue
            durableBudget -= size
        } else {
            val size = encoded.length + 2
            if (size > budget) break
            budget -= size
        }
        kept += record
    }
    return runCatching { unsentJson.encodeToString(kept.sortedByDescending { it.updatedAt }) }.getOrDefault("[]")
}

/**
 * Replace only this message. Editing one failed turn must not erase another turn's recovery data.
 */
fun putUnsent(records: List<UnsentRecord>, record: UnsentRecord): List<UnsentRecord> {
    val previous = records.firstOrNull { it.token == record.token && it.messageId == record.messageId }
    val replacement = if (record.conversation == null && previous?.conversation != null) {
        record.copy(conversation = previous.conversation)
    } else record
    return records.filterNot { it.token == record.token && it.messageId == record.messageId } + replacement
}

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
    val pendingConversations: Flow<List<UnsentRecord>> get() = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun read(token: String): UnsentRecord?
    suspend fun readAll(token: String): List<UnsentRecord>
    suspend fun save(record: UnsentRecord)
    /** Send admission requires a complete durable record; ordinary cache writes remain best-effort. */
    suspend fun saveForDelivery(record: UnsentRecord) {
        save(record)
        check(readAll(record.token).any { it == record }) { "message recovery record was not saved" }
    }
    /** Confirmed creation adopts the original local record without an intermediate duplicate. */
    suspend fun bindCreation(oldToken: String, record: UnsentRecord) {
        saveForDelivery(record)
        clearMessage(oldToken, record.messageId)
    }
    suspend fun claimCreation(record: UnsentRecord): Boolean = false
    suspend fun clear(token: String)
    suspend fun clearMessage(token: String, messageId: String)
    /** Explicit user removal: return only after durable commit; propagate storage failure. */
    suspend fun removeUnknownNotice(token: String, messageId: String)
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
 * **Text plus attachment PATHS, never attachment bytes.** Staged images are in-memory bytes (6 MB
 * each, nine at a time), and this file does not become their home — the same ruling [DraftStore]
 * makes about drafts. What HG-208 changed is that a refused send now also writes each attachment
 * to the app's own cache directories and records the path (see [UnsentAttachment]), so a restore
 * can put the bytes back from disk and the retry replays what the user actually meant. The
 * previous ruling — count only, restore as `HR-SESS-015`, withhold the retry — survives as the
 * degradation for v1 records and for any disk copy the cache has since evicted. Sending the text
 * alone without the attachments is still never an outcome.
 *
 * Cache operations degrade to "nothing unsent" on failure. Explicit [removeUnknownNotice]
 * propagates failure so the UI retains its notice and reports HR-STORE-003. Other cache operations
 * keep the same best-effort contract as [DraftStore] and [SessionPhaseStore]. The code in
 * [UnsentRecord.code] describes the SEND that failed, not this file.
 */
class UnsentStore internal constructor(
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
) : UnsentSnapshot {
    constructor(context: Context) : this(context.unsentDataStore)
    private val key = stringPreferencesKey("unsent")

    private val records: Flow<List<UnsentRecord>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decodeUnsent(it[key]) }

    override val pendingConversations = records.map { records -> records.filter { it.conversation != null } }

    override val tokens: Flow<Set<String>> = records.map { list ->
        list.filter { it.composer?.withdrawn != true && !it.deliveryUnconfirmed }.mapTo(mutableSetOf()) { it.token }
    }

    override suspend fun read(token: String): UnsentRecord? = runCatching {
        records.first().firstOrNull { it.token == token }
    }.getOrNull()

    override suspend fun readAll(token: String): List<UnsentRecord> = runCatching {
        records.first().filter { it.token == token }
    }.getOrDefault(emptyList())

    override suspend fun save(record: UnsentRecord) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[key] = encodeUnsent(putUnsent(decodeUnsent(prefs[key]), record))
            }
        }
    }

    override suspend fun saveForDelivery(record: UnsentRecord) {
        dataStore.edit { prefs ->
            val next = putUnsent(decodeUnsent(prefs[key]), record)
            val encoded = encodeUnsent(next)
            val saved = decodeUnsent(encoded)
            check(record in saved && next.filter { it.conversation != null }.all { it in saved }) {
                "message exceeds recovery capacity"
            }
            prefs[key] = encoded
        }
    }

    override suspend fun bindCreation(oldToken: String, record: UnsentRecord) {
        dataStore.edit { prefs ->
            val previous = decodeUnsent(prefs[key])
            val withoutLocal = previous.filterNot { it.token == oldToken && it.messageId == record.messageId }
            val encoded = encodeUnsent(putUnsent(withoutLocal, record))
            check(decodeUnsent(encoded).any { it == record }) { "confirmed conversation could not adopt the message" }
            prefs[key] = encoded
        }
    }

    override suspend fun claimCreation(record: UnsentRecord): Boolean {
        var claimed = false
        dataStore.edit { prefs ->
            val current = decodeUnsent(prefs[key])
            if (record.conversation?.localCreation == true && !record.deliveryUnconfirmed && record in current) {
                val awaiting = record.copy(
                    code = "HR-SESS-022", retryable = false, deliveryUnconfirmed = true,
                )
                prefs[key] = encodeUnsent(putUnsent(current, awaiting))
                claimed = true
            }
        }
        return claimed
    }

    override suspend fun clear(token: String) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[key] = encodeUnsent(removeUnsent(decodeUnsent(prefs[key]), token))
            }
        }
    }

    override suspend fun clearMessage(token: String, messageId: String) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[key] = encodeUnsent(decodeUnsent(prefs[key]).filterNot { it.token == token && it.messageId == messageId })
            }
        }
    }

    override suspend fun removeUnknownNotice(token: String, messageId: String) {
        // Unlike best-effort cache cleanup, a user-visible removal must not report false success.
        dataStore.edit { prefs ->
            prefs[key] = encodeUnsent(decodeUnsent(prefs[key]).filterNot {
                it.token == token && it.messageId == messageId && it.deliveryUnconfirmed
            })
        }
    }

    /** Drop everything. The Relay or identity changed — an unsent message is user content. */
    suspend fun clearAll() {
        runCatching { dataStore.edit { it.remove(key) } }
    }
}
