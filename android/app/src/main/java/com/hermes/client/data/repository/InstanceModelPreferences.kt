package com.hermes.client.data.repository

import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class InstanceModelRow(val provider: String, val model: String, val favorite: Boolean, val hidden: Boolean?, val favoriteRevision: String, val hiddenRevision: String)
@Serializable
data class InstanceModelSnapshot(val version: Int, val instanceId: String, val revision: String, val rows: List<InstanceModelRow>) {
    fun validated(): InstanceModelSnapshot {
        fun validRevision(value: String) = value.matches(Regex("0|[1-9][0-9]{0,18}")) && value.toBigInteger() < BigInteger.ONE.shiftLeft(63)
        require(version == 1 && instanceId.matches(Regex("[a-fA-F0-9-]{36}")) && validRevision(revision) && rows.size <= 5000)
        require(rows.map { favKey(it.provider, it.model) }.toSet().size == rows.size)
        rows.forEach {
            require(it.provider.isNotEmpty() && it.provider.length <= 128 && it.model.length <= 512)
            require((it.provider + it.model).none { c -> c.code < 32 })
            require(validRevision(it.favoriteRevision) && validRevision(it.hiddenRevision))
            require(it.favoriteRevision.toBigInteger() <= revision.toBigInteger() && it.hiddenRevision.toBigInteger() <= revision.toBigInteger())
        }
        return this
    }
    fun hidden(provider: String, model: String): Boolean = rows.find { it.provider == provider && it.model == model }?.hidden
        ?: rows.any { it.provider == provider && it.model.isEmpty() && it.hidden == true }
}
@Serializable
data class InstanceModelMutation(val instanceId: String, val provider: String, val model: String, val field: String, val baseRevision: String, val value: Boolean?)
data class InstanceModelState(val owner: String = "", val instanceName: String = "Hermes", val status: String = "loading", val snapshot: InstanceModelSnapshot? = null,
    val busy: Boolean = false, val showHidden: Boolean = false, val readOnly: Boolean = true, val error: AppError? = null) {
    val active get() = status != "legacy"
    val favorites get() = snapshot?.rows?.filter { it.favorite }?.map { favKey(it.provider, it.model) }?.toSet().orEmpty()
    fun blocked() = snapshot == null || busy || readOnly
}

/** Acknowledged server state only. Offline/ambiguous writes are never silently replayed. */
@Singleton
class InstanceModelPreferences @Inject constructor(private val rest: HermesRestApi, private val sessions: AccountSessionManager, private val api: com.hermes.client.data.network.AccountApi) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(InstanceModelState())
    val state = mutable.asStateFlow()
    private var accessOwner: String? = null
    private var ownerWritable = false
    private var ownerLabel = "Hermes"
    private fun route(): Pair<String, String>? {
        val session = sessions.session.value ?: return null
        val device = sessions.transportRoutingContext()?.deviceId ?: return null
        return "${session.baseUrl}#${session.accountId}#${session.modelSyncGeneration}#$device" to device
    }
    private fun bind(): Pair<String, String>? {
        val next = route()
        if (next == null) { mutable.value = InstanceModelState(status = "legacy"); return null }
        if (mutable.value.owner != next.first) mutable.value = InstanceModelState(owner = next.first)
        return next
    }
    suspend fun watchRoute() {
        kotlinx.coroutines.flow.combine(sessions.session, sessions.transportRouteEpoch) { _, _ -> route() }.collect { bind() }
    }
    suspend fun refresh() = lock.withLock {
        val owner = bind() ?: return@withLock
        try {
            val snapshot = rest.instanceModelPreferences(owner.second).validated()
            if (accessOwner != owner.first) {
                val connection = sessions.connection(owner.second) ?: throw IllegalStateException("Device unavailable")
                val device = api.devices(connection.baseUrl, connection.bearer).items.find { it.deviceId == owner.second }
                ownerWritable = device?.access == "owner"
                ownerLabel = device?.desktopDisplayName ?: "Hermes"
                accessOwner = owner.first
            }
            if (route() == owner) mutable.value = mutable.value.copy(status = "ready", snapshot = snapshot, readOnly = !ownerWritable, instanceName = ownerLabel, error = mutable.value.error?.takeIf { it.code != AppErrorCode.INSTANCE_MODELS_FAILED })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (route() == owner) mutable.value = mutable.value.copy(status = if (error is HermesApiException && error.code in listOf(404, 405)) "legacy" else "error",
                error = if (error is HermesApiException && error.code in listOf(404, 405)) null else AppError(AppErrorCode.INSTANCE_MODELS_FAILED, true))
        }
    }
    fun instanceLabel(): String = state.value.instanceName
    fun importFailed() { mutable.value = mutable.value.copy(error = AppError(AppErrorCode.INSTANCE_MODELS_FAILED, true)) }
    fun toggleShowHidden() { mutable.value = mutable.value.copy(showHidden = !mutable.value.showHidden) }
    suspend fun set(provider: String, model: String, field: String, value: Boolean?, importing: Boolean = false, expectedOwner: String = state.value.owner): Boolean = lock.withLock {
        require(field == "favorite" || field == "hidden")
        val owner = bind() ?: return@withLock false
        val before = mutable.value
        val snapshot = before.snapshot ?: return@withLock false
        if (before.owner != expectedOwner || before.readOnly) return@withLock false
        val row = snapshot.rows.find { it.provider == provider && it.model == model }
        val base = if (importing) "0" else if (field == "favorite") row?.favoriteRevision ?: "0" else row?.hiddenRevision ?: "0"
        mutable.value = before.copy(busy = true, error = null)
        try {
            val updated = rest.setInstanceModelPreference(InstanceModelMutation(snapshot.instanceId, provider, model, field, base, value), owner.second).validated()
            require(updated.instanceId == snapshot.instanceId)
            if (route() != owner) { bind(); return@withLock false }
            mutable.value = mutable.value.copy(status = "ready", snapshot = updated, busy = false)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (route() == owner) {
                val code = if (error is HermesApiException && error.code == 409) AppErrorCode.INSTANCE_MODELS_CONFLICT
                    else if (error is HermesApiException && error.code == 403) AppErrorCode.INSTANCE_MODELS_READ_ONLY else AppErrorCode.INSTANCE_MODELS_FAILED
                mutable.value = mutable.value.copy(busy = false, readOnly = code == AppErrorCode.INSTANCE_MODELS_READ_ONLY, error = AppError(code, code == AppErrorCode.INSTANCE_MODELS_FAILED))
                try { val read = rest.instanceModelPreferences(owner.second).validated(); if(route()==owner) mutable.value = mutable.value.copy(snapshot=read) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the last acknowledged snapshot and the visible failure. */ }
            } else bind()
            false
        } finally { if (route() == owner && mutable.value.busy) mutable.value = mutable.value.copy(busy = false) }
    }
}
