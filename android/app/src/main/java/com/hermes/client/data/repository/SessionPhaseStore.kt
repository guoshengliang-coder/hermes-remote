package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/** Mirror of `ClarifyQuestion`; see the store's KDoc for why the UI model is not annotated. */
@Serializable
data class PersistedQuestion(
    val qid: String = "",
    val question: String = "",
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

/** Mirror of `ClarifyRequest`, including answers already locked server-side. */
@Serializable
data class PersistedClarify(
    val requestId: String = "",
    val questions: List<PersistedQuestion> = emptyList(),
    val lockedAnswers: Map<String, String> = emptyMap(),
)

/** One conversation's run state as last committed by the store. */
@Serializable
data class SessionPhaseRecord(
    /** Format version. A record that does not match [PHASE_RECORD_VERSION] is dropped whole. */
    val v: Int = PHASE_RECORD_VERSION,
    val sessionId: String = "",
    val profile: String? = null,
    val deviceId: String? = null,
    /** [com.hermes.client.data.progress.SessionRunPhase] name; an unknown name drops the record. */
    val phase: String = "",
    /** Active phase held across a reconnect, when [phase] is RECONNECTING. */
    val phaseBeforeReconnect: String? = null,
    val occurredAt: Long = 0L,
    /** Staleness input. Quantized to the minute by the writer so a stream is not a write storm. */
    val lastEventAt: Long = 0L,
    val lastTerminalAt: Long = 0L,
    val runStartedAt: Long? = null,
    val todoDone: Int = 0,
    val todoTotal: Int = 0,
    val clarify: PersistedClarify? = null,
)

const val PHASE_RECORD_VERSION = 1

/** Keep the newest runs; `pruneIdleRuntimes` cannot bound this — its protected set includes unread, which is unbounded. */
const val MAX_PERSISTED_RUNTIMES = 50
/** A pathological batch clarify must not push every other record out of the payload. */
const val MAX_RECORD_CHARS = 8_000
const val MAX_PAYLOAD_CHARS = 64_000

private val phaseJson = Json { ignoreUnknownKeys = true }

/** Decode the stored JSON array; never throws — corrupt/absent/wrong-version → dropped. */
fun decodePhaseRecords(raw: String?): List<SessionPhaseRecord> =
    runCatching { phaseJson.decodeFromString<List<SessionPhaseRecord>>(raw ?: "[]") }
        .getOrDefault(emptyList())
        .filter { it.v == PHASE_RECORD_VERSION && it.sessionId.isNotBlank() && it.phase.isNotBlank() }

/**
 * Encode newest-first under the three size bounds. Records are ordered by [SessionPhaseRecord.lastEventAt]
 * so whatever is dropped is always the least recently active thing.
 */
fun encodePhaseRecords(records: List<SessionPhaseRecord>): String {
    val ordered = records.sortedByDescending { it.lastEventAt }.take(MAX_PERSISTED_RUNTIMES)
    val kept = mutableListOf<SessionPhaseRecord>()
    var budget = MAX_PAYLOAD_CHARS
    for (record in ordered) {
        val size = runCatching { phaseJson.encodeToString(record).length }.getOrNull() ?: continue
        if (size > MAX_RECORD_CHARS) continue
        if (size + 2 > budget) break
        budget -= size + 2
        kept += record
    }
    return runCatching { phaseJson.encodeToString(kept) }.getOrDefault("[]")
}

/**
 * What [com.hermes.client.data.progress.SessionRuntimeStore] needs from the snapshot. Narrow on
 * purpose: a test supplies its own without an Android `Context`, and the runtime store cannot
 * reach `clear()`, which belongs to the identity-change path alone.
 */
interface SessionPhaseSnapshot {
    suspend fun read(): List<SessionPhaseRecord>
    suspend fun write(records: List<SessionPhaseRecord>)
}

private val Context.sessionPhaseDataStore by preferencesDataStore(name = "session_phase_state")

/**
 * Device-local snapshot of what each conversation was doing, so a row's third line and the
 * 「需要你处理」group survive process death (HG-31).
 *
 * The bug this exists for: [com.hermes.client.data.progress.SessionRuntimeStore] calls itself a
 * *process-lifetime* source of truth, so a cold start started from `emptyMap()` — every row lost
 * its status line and every waiting conversation lost the card it was waiting on. The unread dot
 * on the very same row DID survive, because [SessionReadStore] persists it. One field of a row
 * outliving the process while the field that explains it does not is the whole defect.
 *
 * **The approval card is deliberately NOT stored.** `approval.respond` carries only `session_id`
 * and returns nothing (`ChatRepository.respondApproval`), so an approval restored from disk cannot
 * be checked against what Hermes is actually waiting on. Hermes times an unanswered approval out,
 * denies it itself and moves on without telling the client, so the restored card would address
 * whatever approval is pending *now* — possibly a command the user never saw — and `ALWAYS`/
 * `SESSION` would persist that mis-authorization into a pattern allowlist with no layer able to
 * notice. The clarify card is stored because it is checkable: it carries a `request_id` and the
 * server answers `"expired"`, which already lands on `HR-CLARIFY-001`.
 *
 * **One key holding a JSON array, not a key per session.** Preferences DataStore rewrites the
 * whole file on every `edit`, so per-session keys buy no IO and only make wholesale replacement
 * and eviction harder.
 *
 * **Mirror DTOs, not `@Serializable` on the UI models.** `ApprovalRequest`/`ClarifyRequest` live in
 * `ui/chat`; annotating them would pin this disk format to UI field names, and one rename would
 * silently drop every stored card. Same reasoning as [TranscriptStore], which stores the raw REST
 * body rather than mapped messages.
 *
 * Every operation degrades to "no snapshot" on failure. A cache that cannot be read or written is
 * never an error the user should see, so nothing here throws and nothing here carries an `HR-` code.
 */
class SessionPhaseStore(private val context: Context) : SessionPhaseSnapshot {
    private val key = stringPreferencesKey("runtimes")

    override suspend fun read(): List<SessionPhaseRecord> = runCatching {
        context.sessionPhaseDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { decodePhaseRecords(it[key]) }
            .first()
    }.getOrDefault(emptyList())

    override suspend fun write(records: List<SessionPhaseRecord>) {
        runCatching { context.sessionPhaseDataStore.edit { it[key] = encodePhaseRecords(records) } }
    }

    /** Drop everything. Called when the Relay or identity changes — a stored question is user content. */
    suspend fun clear() {
        runCatching { context.sessionPhaseDataStore.edit { it.remove(key) } }
    }
}
