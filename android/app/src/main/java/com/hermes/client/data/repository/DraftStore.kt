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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/** One conversation's unsent composer text. [token] is a [SessionReadStore.token]. */
@Serializable
data class DraftRecord(
    val v: Int = DRAFT_RECORD_VERSION,
    val token: String = "",
    val text: String = "",
    val updatedAt: Long = 0L,
    /** Relay/account identity; older records are claimed once by the upgrade's current owner. */
    val owner: String = "",
)

const val DRAFT_RECORD_VERSION = 2

/** Bounds copied from [SessionPhaseStore]: same file, same rewrite-everything cost. */
const val MAX_PERSISTED_DRAFTS = 50
const val MAX_DRAFT_CHARS = 8_000
const val MAX_DRAFT_PAYLOAD_CHARS = 64_000

private val draftJson = Json { ignoreUnknownKeys = true }

/** Decode the stored JSON array; never throws — corrupt/absent/wrong-version → dropped. */
fun decodeDrafts(raw: String?): List<DraftRecord> =
    runCatching { draftJson.decodeFromString<List<DraftRecord>>(raw ?: "[]") }
        .getOrDefault(emptyList())
        .filter { it.v in 1..DRAFT_RECORD_VERSION && it.token.isNotBlank() && it.text.isNotBlank() }
        .map { it.copy(v = DRAFT_RECORD_VERSION) }

/**
 * Newest-first under the three size bounds. Ordered by [DraftRecord.updatedAt], so what falls off
 * the end is always the draft the user touched longest ago.
 *
 * An over-long draft is TRUNCATED rather than dropped: losing the tail of a very long unsent
 * message is bad, losing all of it is worse, and `MAX_DRAFT_CHARS` is far past anything typed by
 * hand. (Phase records are dropped instead, because half a status record means nothing.)
 */
fun encodeDrafts(records: List<DraftRecord>): String {
    val ordered = records
        .filter { it.token.isNotBlank() && it.text.isNotBlank() }
        .map { it.copy(v = DRAFT_RECORD_VERSION) }
        .sortedByDescending { it.updatedAt }
        .take(MAX_PERSISTED_DRAFTS)
        .map { if (it.text.length <= MAX_DRAFT_CHARS) it else it.copy(text = it.text.take(MAX_DRAFT_CHARS)) }
    val kept = mutableListOf<DraftRecord>()
    var budget = MAX_DRAFT_PAYLOAD_CHARS
    for (record in ordered) {
        val size = runCatching { draftJson.encodeToString(record).length }.getOrNull() ?: continue
        if (size + 2 > budget) break
        budget -= size + 2
        kept += record
    }
    return runCatching { draftJson.encodeToString(kept) }.getOrDefault("[]")
}

/**
 * Set [token]'s draft to [text], or remove it when [text] is blank. Blank means removed, not
 * stored empty: the session list reads this set to decide which rows are marked, and a row marked
 * 「草稿」 for a composer holding one deleted space is a lie.
 */
fun putDraft(records: List<DraftRecord>, token: String, text: String, now: Long, owner: String = ""): List<DraftRecord> {
    val rest = records.filterNot { it.token == token && it.owner == owner }
    return if (text.isBlank()) rest
    else rest + DraftRecord(token = token, text = text, updatedAt = now, owner = owner)
}

internal fun claimLegacyDrafts(records: List<DraftRecord>, owner: String): List<DraftRecord> =
    records.map { if (it.owner.isEmpty()) it.copy(owner = owner) else it }

/**
 * What the chat and the session list need from the draft cache. Narrow on purpose, exactly as
 * [SessionPhaseSnapshot] is: a test supplies its own without an Android `Context`, and neither
 * consumer can reach `clearAll()`, which belongs to the identity-change path alone.
 */
interface DraftSnapshot {
    /** Capture identity at open, so a delayed save cannot write into a newly selected account. */
    fun forOwner(owner: String): DraftSnapshot = this
    /** Which conversations currently hold a draft. The session list's marker reads this. */
    val tokens: Flow<Set<String>>
    suspend fun read(token: String): String?
    /** Blank [text] clears the draft; see [putDraft]. */
    suspend fun save(token: String, text: String, now: Long = System.currentTimeMillis())
    suspend fun clear(token: String)
}

private val Context.draftDataStore by preferencesDataStore(name = "session_drafts")

/**
 * Device-local unsent composer text, keyed by Relay/account and Mac/profile/session (HG-197).
 * On upgrade, unscoped HG-41 records are claimed once by the current connection. Bound snapshots
 * retain their owner even if the shell switches accounts while a debounced write is pending.
 *
 * Before this, the draft was a `rememberSaveable` in `ChatScreen` and nothing else. It survived
 * rotation, and died the moment the user pressed back — because back POPS the chat destination,
 * taking its saved state and its ViewModel with it. "I typed half a message, went to check
 * something, came back and it was gone" was the whole of the bug.
 *
 * **One key holding a JSON array, not a key per session** — the same rule, and the same reason, as
 * [SessionPhaseStore]: Preferences DataStore rewrites the whole file on every `edit`, so
 * per-session keys buy no IO and only make eviction harder.
 *
 * **Text only.** Staged attachments are in-memory bytes (6 MB each, nine at a time); persisting
 * them needs a cache directory, an eviction policy and a story for "the file you attached is gone
 * now", which is a different feature. HG-197 retains pending attachments in SessionRuntimeStore
 * for navigation within the same account/Relay; they are not persisted across process death.
 *
 * Every operation degrades to "no draft" on failure. A cache that cannot be read or written is
 * never an error the user should see, so nothing here throws and nothing here carries an `HR-`
 * code — same ruling as [SessionPhaseStore].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DraftStore(private val context: Context) : DraftSnapshot {
    private val key = stringPreferencesKey("drafts")
    private val migratedOwner = stringPreferencesKey("legacy_draft_owner")
    private val activeOwner = MutableStateFlow<String?>(null)

    private val records: Flow<List<DraftRecord>> = context.draftDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decodeDrafts(it[key]) }

    fun bindOwner(owner: String) { activeOwner.value = owner }

    private fun tokensFor(owner: String): Flow<Set<String>> = flow {
        migrate(owner)
        emitAll(records.map { list -> list.filter { it.owner == owner }.mapTo(mutableSetOf()) { it.token } })
    }

    override val tokens: Flow<Set<String>> = activeOwner.flatMapLatest { owner ->
        if (owner == null) kotlinx.coroutines.flow.flowOf(emptySet()) else tokensFor(owner)
    }

    override fun forOwner(owner: String): DraftSnapshot {
        bindOwner(owner)
        return object : DraftSnapshot {
            override val tokens = tokensFor(owner)
            override suspend fun read(token: String) = readFor(owner, token)
            override suspend fun save(token: String, text: String, now: Long) = saveFor(owner, token, text, now)
            override suspend fun clear(token: String) = save(token, "")
        }
    }

    private suspend fun migrate(owner: String) {
        runCatching {
            context.draftDataStore.edit { prefs ->
                if (prefs[migratedOwner] == null) {
                    prefs[key] = encodeDrafts(claimLegacyDrafts(decodeDrafts(prefs[key]), owner))
                    prefs[migratedOwner] = owner
                }
            }
        }
    }

    private suspend fun readFor(owner: String, token: String): String? = runCatching {
        migrate(owner)
        records.first().firstOrNull { it.token == token && it.owner == owner }?.text
    }.getOrNull()

    override suspend fun read(token: String): String? = activeOwner.value?.let { readFor(it, token) }

    override suspend fun save(token: String, text: String, now: Long) {
        activeOwner.value?.let { saveFor(it, token, text, now) }
    }

    private suspend fun saveFor(owner: String, token: String, text: String, now: Long) {
        migrate(owner)
        runCatching {
            context.draftDataStore.edit { prefs ->
                prefs[key] = encodeDrafts(putDraft(decodeDrafts(prefs[key]), token, text, now, owner))
            }
        }
    }

    override suspend fun clear(token: String) = save(token, "")

    /** Drop everything. The Relay or identity changed — a stored draft is user content. */
    suspend fun clearAll() {
        runCatching { context.draftDataStore.edit { it.remove(key) } }
    }
}
