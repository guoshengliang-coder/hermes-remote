package com.hermes.client.data.sync

import com.hermes.client.data.auth.AccountSession
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountTransportMode
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.*
import com.hermes.client.data.repository.favKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

private val MAX_REVISION = BigInteger("18446744073709551615")
val MODEL_REASONING_PRESETS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
fun modelSyncRevision(value: String): BigInteger? = value.takeIf { it.matches(Regex("0|[1-9][0-9]{0,19}")) }
    ?.toBigIntegerOrNull()?.takeIf { it <= MAX_REVISION }
fun modelTuple(provider: String, model: String): String = JsonArray(listOf(JsonPrimitive(provider), JsonPrimitive(model))).toString()
private fun fieldKey(op: ModelPreferenceOperationDto) = modelTuple(op.provider, op.model) + ":" + op.field

data class AccountModelPreferenceState(
    val ownerKey: String = "",
    val accountLabel: String = "",
    val useCloud: Boolean = false,
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val rows: List<ModelPreferenceDto> = emptyList(),
    val pending: List<PendingModelPreference> = emptyList(),
    val error: AppError? = null,
) {
    fun projected(): List<ModelPreferenceDto> {
        val result = rows.associateBy { favKey(it.provider, it.model) }.toMutableMap()
        for (entry in pending) {
            if (entry.errorCode != null) continue
            val op = entry.operation
            val key = favKey(op.provider, op.model)
            val row = result[key] ?: ModelPreferenceDto("0".repeat(64), op.provider, op.model, false, null, "0", "0", "0")
            result[key] = if (op.field == "favorite") row.copy(favorite = op.value.jsonPrimitive.boolean)
                else row.copy(reasoningPreset = if (op.value == JsonNull) null else op.value.jsonPrimitive.content)
        }
        return result.values.toList()
    }
    fun blocked(provider: String, model: String, field: String): Boolean = !enabled || !ready || pending.any {
        it.operation.provider == provider && it.operation.model == model && it.operation.field == field
    }
}

data class LegacyModelPreference(val provider: String, val model: String, val favorite: Boolean, val reasoning: String?)
data class LegacyModelImport(val candidates: List<LegacyModelPreference>, val unresolved: Int)
fun legacyModelImport(favorites: Set<String>, presets: Map<String, String>): LegacyModelImport {
    var unresolved = 0
    val candidates = (favorites + presets.keys).sorted().mapNotNull { key ->
        val parts = key.split('\u0000')
        if (parts.size != 2 || !validIdentifier(parts[0], 256) || !validIdentifier(parts[1], 1024)) { unresolved++; null }
        else {
            val reasoning = presets[key]?.takeIf { it in MODEL_REASONING_PRESETS }
            if (key !in favorites && reasoning == null) { unresolved++; null }
            else LegacyModelPreference(parts[0], parts[1], key in favorites, reasoning)
        }
    }
    return LegacyModelImport(candidates, unresolved)
}
private fun validIdentifier(value: String, limit: Int) = value.isNotBlank() && value.length <= limit && value.none { it.code < 32 || it.code == 127 }
fun validModelPreference(row: ModelPreferenceDto, upper: String = "18446744073709551615"): Boolean {
    val revision = modelSyncRevision(row.revision) ?: return false
    return row.key.matches(Regex("[a-f0-9]{64}")) && validIdentifier(row.provider, 256) && validIdentifier(row.model, 1024) &&
        (row.reasoningPreset == null || row.reasoningPreset in MODEL_REASONING_PRESETS) &&
        revision <= (modelSyncRevision(upper) ?: return false) &&
        modelSyncRevision(row.favoriteRevision)?.let { it <= revision } == true && modelSyncRevision(row.reasoningRevision)?.let { it <= revision } == true
}
private class ModelSyncFailure(val code: AppErrorCode) : Exception(code.value)

/** Account-owned preferences only: never changes a live Hermes model or reasoning setting. */
class AccountModelPreferences(
    private val sessions: AccountSessionManager,
    private val api: AccountApi,
    private val store: ModelPreferenceCacheStore,
    private val scope: CoroutineScope,
) {
    private data class Owner(val base: String, val account: String, val installation: String, val generation: String, val epoch: Long) {
        val key get() = modelTuple(base, account)
    }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AccountModelPreferenceState())
    val state: StateFlow<AccountModelPreferenceState> = _state.asStateFlow()
    private var owner: Owner? = null
    private var cache: ModelPreferenceCache? = null
    private var watcher: Job? = null
    private var foreground: Job? = null
    private var capabilityAt = 0L
    private var nextAttempt = 0L
    private var failures = 0

    private fun current(): Owner? {
        val session = sessions.session.value ?: return null
        if (sessions.transportMode() in setOf(AccountTransportMode.SIGNED_OUT,
                AccountTransportMode.REAUTHENTICATION_REQUIRED, AccountTransportMode.ACCOUNT_DELETION_COMMITTED)) return null
        return Owner(session.baseUrl, session.accountId, session.installationId, session.modelSyncGeneration, sessions.accountDataEpoch())
    }
    fun currentOwnerKey(): String = current()?.let { it.key + it.generation }.orEmpty()
    private fun active(expected: Owner): Boolean = current() == expected
    suspend fun ensureBound() = withContext(Dispatchers.IO) {
        synchronized(this@AccountModelPreferences) {
            if (watcher?.isActive != true) watcher = scope.launch { sessions.session.collect { bind() } }
        }
        bind()
    }
    private suspend fun bind(force: Boolean = false) = mutex.withLock { bindLocked(force) }
    private suspend fun bindLocked(force: Boolean = false) {
        val next = current()
        if (next == null) {
            owner = null; cache = null; _state.value = AccountModelPreferenceState(); return
        }
        if (owner == next && !force) return
        if (owner != next) {
            owner = next; cache = null; capabilityAt = 0
            _state.value = AccountModelPreferenceState(useCloud = true, ownerKey = next.key + next.generation, accountLabel = sessions.session.value?.let { it.accountEmail ?: it.accountId }.orEmpty())
            try { cache = store.update(next.key, { active(next) }) { it?.takeIf { r -> r.generation == next.generation } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(next, error = error(AppErrorCode.MODEL_SYNC_STORAGE)); return }
        }
        try {
            val capabilities = api.capabilities(next.base).accountAuth.modelPreferencesSync
            if (!active(next)) return
            capabilityAt = System.currentTimeMillis()
            val enabled = capabilities?.let { it.version == 1 && it.maxModels == 500 && it.retentionDays == 90 } == true
            _state.value = _state.value.copy(useCloud = enabled || cache != null, enabled = enabled, error = null)
            publish(next)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { if (active(next)) publish(next, error = errorFor(e)) }
    }
    fun startForeground() {
        if (foreground?.isActive == true) return
        foreground = scope.launch {
            ensureBound()
            while (isActive) {
                if (System.currentTimeMillis() >= nextAttempt) refresh()
                delay(if (_state.value.enabled && _state.value.pending.any { it.errorCode == null }) 100 else 5_000)
            }
        }
    }
    fun stopForeground() { foreground?.cancel(); foreground = null }
    private fun publish(expected: Owner, error: AppError? = _state.value.error) {
        if (!active(expected)) return
        _state.value = _state.value.copy(ready = !cache?.cursor.isNullOrBlank(), rows = cache?.rows.orEmpty(), pending = cache?.pending.orEmpty(), error = error)
    }
    private fun change(expected: Owner, transform: (ModelPreferenceCache) -> ModelPreferenceCache) {
        try {
            cache = store.update(expected.key, { active(expected) }) { saved ->
                val record = saved?.takeIf { it.generation == expected.generation } ?: throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
                transform(record)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: ModelSyncFailure) { throw known }
        catch (_: Exception) { throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_STORAGE) }
        publish(expected)
    }
    private suspend fun bearer(expected: Owner): String {
        val token = sessions.accessToken() ?: throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
        if (!active(expected)) throw CancellationException("model owner changed")
        return token
    }
    suspend fun setFavorite(provider: String, model: String, value: Boolean, confirmedOwner: String? = null) = set(provider, model, "favorite", JsonPrimitive(value), confirmedOwner)
    suspend fun setReasoning(provider: String, model: String, value: String?, confirmedOwner: String? = null) = set(provider, model, "reasoningPreset", value?.let(::JsonPrimitive) ?: JsonNull, confirmedOwner)
    private suspend fun set(provider: String, model: String, field: String, value: JsonElement, confirmedOwner: String?): Boolean = withContext(Dispatchers.IO) {
        ensureBound()
        if (confirmedOwner != null && currentOwnerKey() != confirmedOwner) return@withContext false
        val expected = owner ?: return@withContext false
        val stored = mutex.withLock {
            if (!active(expected) || !_state.value.enabled || confirmedOwner != null && currentOwnerKey() != confirmedOwner) return@withLock false
            try {
                if (!validIdentifier(provider, 256) || !validIdentifier(model, 1024) || field == "reasoningPreset" && value != JsonNull && value.jsonPrimitive.content !in MODEL_REASONING_PRESETS) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_INVALID)
                val resolved = try { api.resolveModelPreference(expected.base, bearer(expected), provider, model).row.also {
                    if (!validModelPreference(it) || it.provider != provider || it.model != model) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
                } } catch (network: java.io.IOException) { null }
                change(expected) { s ->
                    if (s.lease.isBlank()) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
                    val before = resolved ?: s.rows.find { it.provider == provider && it.model == model }
                    val op = ModelPreferenceOperationDto(UUID.randomUUID().toString(), s.lease, provider, model, field,
                        if (field == "favorite") before?.favoriteRevision ?: "0" else before?.reasoningRevision ?: "0", value)
                    if (s.pending.any { fieldKey(it.operation) == fieldKey(op) }) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_PENDING)
                    if (s.pending.size >= 1000) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_QUOTA)
                    s.copy(pending = s.pending + PendingModelPreference(op))
                }
                publish(expected, error = null); true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { publish(expected, errorFor(e)); false }
        }
        if (stored) scope.launch { refresh() }
        stored
    }
    suspend fun discard(opId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val expected = owner ?: return@withLock
            try { change(expected) { it.copy(pending = it.pending.filterNot { p -> p.operation.opId == opId }) }; publish(expected, null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { publish(expected, errorFor(e)) }
        }
        refresh()
    }
    suspend fun reapply(opId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val expected = owner ?: return@withLock
            if (!_state.value.enabled) return@withLock
            val pending = cache?.pending?.find { it.operation.opId == opId } ?: return@withLock
            try {
                val old = pending.operation
                val row = api.resolveModelPreference(expected.base, bearer(expected), old.provider, old.model).row
                if (!validModelPreference(row) || row.provider != old.provider || row.model != old.model) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
                change(expected) { s -> s.copy(pending = s.pending.map { p -> if (p.operation.opId != opId) p else PendingModelPreference(
                    old.copy(opId = UUID.randomUUID().toString(), lease = s.lease, baseRevision = if (old.field == "favorite") row.favoriteRevision else row.reasoningRevision)) }) }
                publish(expected, null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { publish(expected, errorFor(e)) }
        }
        refresh()
    }
    suspend fun importLegacy(legacy: LegacyModelImport, source: String, confirmedOwner: String = state.value.ownerKey) = withContext(Dispatchers.IO) {
        ensureBound()
        mutex.withLock {
            val expected = owner ?: return@withLock
            if (!_state.value.enabled || _state.value.ownerKey != confirmedOwner) return@withLock
            try {
                val data = JsonArray(legacy.candidates.sortedBy { modelTuple(it.provider,it.model) }.map { c -> JsonArray(listOf(JsonPrimitive(c.provider),JsonPrimitive(c.model),JsonPrimitive(c.favorite),c.reasoning?.let(::JsonPrimitive)?:JsonNull)) })
                val migration = "models-v1-" + hash(JsonArray(listOf(JsonPrimitive(expected.key),JsonPrimitive(source),data)).toString())
                val operations = legacy.candidates.flatMap { c -> listOfNotNull(
                    if (c.favorite) Triple("favorite",JsonPrimitive(true),c) else null,
                    c.reasoning?.let { Triple("reasoningPreset",JsonPrimitive(it),c) },
                ) }.map { (field,value,c) ->
                    val id = UUID.nameUUIDFromBytes("$migration:${modelTuple(c.provider,c.model)}:$field".toByteArray(Charsets.UTF_8)).toString()
                    PendingModelPreference(ModelPreferenceOperationDto(id,"",c.provider,c.model,field,"0",value),migrationId=migration)
                }
                change(expected) { s ->
                    if (s.lease.isBlank()) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
                    val keys = s.pending.map { fieldKey(it.operation) }.toSet()
                    val added = operations.filter { it.operation.opId !in s.completedOps && fieldKey(it.operation) !in keys }
                        .map { it.copy(operation=it.operation.copy(lease=s.lease)) }
                    if (s.pending.size + added.size > 1000) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_QUOTA)
                    s.copy(pending=s.pending+added)
                }; publish(expected,null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { publish(expected,errorFor(e)) }
        }
        scope.launch { refresh() }
        Unit
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        ensureBound()
        mutex.withLock {
            val expected=owner?:return@withLock
            if (System.currentTimeMillis()-capabilityAt>60_000) bindLocked(force=true)
            if (!active(expected)||!_state.value.enabled) return@withLock
            try {
                pull(expected)
                repeat(20) {
                    val p=cache?.pending?.firstOrNull { it.errorCode==null }?:return@repeat
                    val op=p.operation
                    try {
                        val result=api.setModelPreference(expected.base,bearer(expected),op)
                        if (result.status !in setOf("accepted","conflict") || !validModelPreference(result.row) || result.row.provider!=op.provider || result.row.model!=op.model) throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
                        change(expected) { s ->
                            val target=s.pending.find { it.operation.opId==op.opId }
                            s.copy(pending=if(result.status=="accepted")s.pending.filterNot{it.operation.opId==op.opId}
                                else s.pending.map { if(it.operation.opId==op.opId)it.copy(errorCode="HR-SYNC-012")else it },
                                completedOps=if(result.status=="accepted"&&target?.migrationId!=null)(s.completedOps+op.opId).takeLast(10_000)else s.completedOps)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        val error=errorFor(e)
                        if (e is AccountApiException && e.statusCode in setOf(401, 403)) throw e
                        if(error.retryable)throw e
                        change(expected){s->s.copy(pending=s.pending.map{if(it.operation.opId==op.opId)it.copy(errorCode=error.code.value)else it})}
                    }
                }
                pull(expected);failures=0;nextAttempt=0;publish(expected,null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                val error=errorFor(e)
                if(e is AccountApiException)sessions.handleRestRejection(e.statusCode,e.errorCode,null)
                publish(expected,error);nextAttempt=System.currentTimeMillis()+minOf(60_000L,5000L*(1L shl minOf(failures++,4)))
            }
        }
    }
    private suspend fun pull(expected: Owner) {
        repeat(64) {
            val cursor=cache?.cursor?.takeIf{it.isNotBlank()}
            val page=try { api.modelPreferences(expected.base,bearer(expected),cursor) }
                catch(e:AccountApiException){if(e.errorCode=="HR-SYNC-013"&&cursor!=null){
                    val reset=api.modelPreferences(expected.base,bearer(expected));validate(reset,true);install(expected,reset);return
                }else throw e}
            validate(page,cursor==null)
            if(cursor==null){install(expected,page);return}
            change(expected){s->
                if(s.cursor!=cursor)s else {
                    val rows=s.rows.associateBy{favKey(it.provider,it.model)}.toMutableMap()
                    page.rows.forEach{r->val k=favKey(r.provider,r.model);if(modelSyncRevision(rows[k]?.revision?:"0")!!<=modelSyncRevision(r.revision)!!){
                        if(r.favorite||r.reasoningPreset!=null)rows[k]=r else rows.remove(k)
                    }}
                    s.copy(rows=rows.values.toList(),cursor=page.cursor,lease=page.lease,
                        revision=if(page.hasMore)page.rows.lastOrNull()?.revision?:s.revision else page.revision)
                }
            }
            if(!page.hasMore)return
        }
        throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
    }
    private fun install(expected:Owner,page:ModelPreferencesSnapshotDto) {
        try {
            cache=store.update(expected.key,{active(expected)}){saved->
                val previous=saved?.takeIf{it.generation==expected.generation}
                if(previous!=null&&modelSyncRevision(previous.revision)!!>modelSyncRevision(page.revision)!!)previous
                else ModelPreferenceCache(expected.generation,page.revision,page.cursor,page.lease,page.rows,previous?.pending.orEmpty(),previous?.completedOps.orEmpty())
            };publish(expected)
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_STORAGE)}
    }
    private fun validate(page:ModelPreferencesSnapshotDto,full:Boolean) {
        if(page.version!=1||modelSyncRevision(page.revision)==null||page.cursor.isBlank()||page.cursor.length>512||page.lease.isBlank()||page.lease.length>512||
            full&&page.hasMore||full&&page.rows.map{modelTuple(it.provider,it.model)}.distinct().size!=page.rows.size||page.rows.size>(if(full)500 else 200)||page.rows.any{!validModelPreference(it,page.revision)}||
            !full&&page.rows.zipWithNext().any{(a,b)->modelSyncRevision(a.revision)!!>=modelSyncRevision(b.revision)!!})throw ModelSyncFailure(AppErrorCode.MODEL_SYNC_RESET)
    }
    private fun error(code:AppErrorCode)=AppError(code,retryable=code==AppErrorCode.MODEL_SYNC_STORAGE,stage="account_model_sync")
    private fun errorFor(e:Exception):AppError = when(e){
        is ModelSyncFailure->error(e.code)
        is AccountApiException->AppError(AppErrorCode.fromValue(e.errorCode)?:AppErrorCode.UNKNOWN,e.retryable,stage="account_model_sync")
        else->AppError(AppErrorCode.CONNECTION_FAILED,true,stage="account_model_sync")
    }
}
private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
