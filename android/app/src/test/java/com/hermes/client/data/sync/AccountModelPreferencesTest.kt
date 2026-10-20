package com.hermes.client.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.auth.*
import com.hermes.client.data.network.*
import com.hermes.client.data.repository.favKey
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AccountModelPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "model-test-${UUID.randomUUID()}.db"
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<SqliteModelPreferenceCacheStore>()
    private val session = AccountSession("https://example.invalid", "account-a", installationId = "install-a",
        installationDisplayName = "Synthetic", accessToken = "synthetic", accessExpiresAt = "2099-01-01T00:00:00Z",
        refreshToken = "synthetic-refresh", refreshExpiresAt = "2099-01-01T00:00:00Z", modelSyncGeneration = "generation-a")
    private val sessions = MutableStateFlow<AccountSession?>(session)
    private val manager = mockk<AccountSessionManager> {
        every { session } returns sessions
        every { transportMode() } answers { if (sessions.value == null) AccountTransportMode.LEGACY else AccountTransportMode.DEVICE_SELECTION_REQUIRED }
        every { accountDataEpoch() } returns 0L
        coEvery { accessToken() } returns "synthetic"
    }
    private class Api : AccountApi(OkHttpClient(), Json { ignoreUnknownKeys = true }) {
        var enabled = true
        var offline = false
        var dropAck = false
        var seq = 0L
        val rows = mutableMapOf<String, ModelPreferenceDto>()
        val operations = mutableListOf<ModelPreferenceOperationDto>()
        val replay = mutableMapOf<String, ModelPreferenceResultDto>()
        fun row(provider: String, model: String) = rows[modelTuple(provider, model)] ?:
            ModelPreferenceDto("a".repeat(64), provider, model, false, null, "0", "0", "0")
        override suspend fun capabilities(baseUrl: String): AccountCapabilitiesDto = Json.decodeFromString("""{"accountAuth":{"enabled":true,"modelPreferencesSync":${if(enabled)"""{"version":1,"maxModels":500,"retentionDays":90}"""else"null"}}}""")
        override suspend fun modelPreferences(baseUrl: String, bearer: String, cursor: String?): ModelPreferencesSnapshotDto {
            if (offline) throw IOException("synthetic offline")
            // Test transport returns all active rows; the coordinator still validates the wire page.
            return ModelPreferencesSnapshotDto(1, "$seq", "cursor-$seq", "synthetic-lease", rows.values.filter { if (cursor == null) it.favorite || it.reasoningPreset != null else it.revision.toLong() > cursor.removePrefix("cursor-").toLong() }.sortedBy { it.revision.toLong() }, false)
        }
        override suspend fun resolveModelPreference(baseUrl: String, bearer: String, provider: String, model: String): ModelPreferenceResolveDto {
            if (offline) throw IOException("synthetic offline")
            return ModelPreferenceResolveDto(row(provider, model))
        }
        override suspend fun setModelPreference(baseUrl: String, bearer: String, operation: ModelPreferenceOperationDto): ModelPreferenceResultDto {
            operations += operation
            if (offline) throw IOException("synthetic offline")
            val result = replay.getOrPut(operation.opId) {
                val before = row(operation.provider, operation.model)
                val base = if (operation.field == "favorite") before.favoriteRevision else before.reasoningRevision
                if (base != operation.baseRevision) ModelPreferenceResultDto("conflict", before)
                else {
                    val revision = "${++seq}"
                    val next = if (operation.field == "favorite") before.copy(favorite=operation.value.jsonPrimitive.boolean,revision=revision,favoriteRevision=revision)
                        else before.copy(reasoningPreset=operation.value.takeUnless{it==JsonNull}?.jsonPrimitive?.content,revision=revision,reasoningRevision=revision)
                    rows[modelTuple(next.provider,next.model)] = next
                    ModelPreferenceResultDto("accepted", next)
                }
            }
            if (dropAck) throw IOException("synthetic lost ACK")
            return result
        }
    }
    private fun cache() = SqliteModelPreferenceCacheStore(context, dbName).also { stores += it }
    private fun sync(api: Api, store: ModelPreferenceCacheStore = cache()): AccountModelPreferences {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return AccountModelPreferences(manager, api, store, scope)
    }
    @After fun cleanup() { scopes.forEach { it.cancel() }; stores.forEach { it.close() }; context.deleteDatabase(dbName) }

    @Test fun revisions_are_exact_and_tuple_legacy_is_unambiguous() {
        assertEquals("18446744073709551615", modelSyncRevision("18446744073709551615").toString())
        for (bad in listOf("01", "-1", "18446744073709551616", "9e18")) assertNull(modelSyncRevision(bad))
        assertNotEquals(modelTuple("a/b","c"),modelTuple("a","b/c"))
        val legacy=legacyModelImport(setOf(favKey("a/b","c"),"old/bad"),mapOf(favKey("a","b/c") to "ultra"))
        assertEquals(2,legacy.candidates.size);assertEquals(1,legacy.unresolved)
    }
    @Test fun sqlite_commits_snapshot_and_outbox_together_survives_reopen_and_rolls_back_owner_change() {
        val store=cache();val key=modelTuple(session.baseUrl,session.accountId)
        store.update(key,{true}){ModelPreferenceCache("generation-a",cursor="cursor",lease="lease")}
        try { store.update(key,{true}) { it!!.copy(revision="1").also { throw IOException("disk transaction failed") } };fail() }catch(_:IOException){}
        store.close();val reopened=cache()
        assertEquals("0",reopened.update(key,{true}){it}!!.revision)
        var checks=0
        try { reopened.update(key,{++checks==1}){it!!.copy(revision="2")};fail() }catch(_:CancellationException){}
        assertEquals("0",reopened.update(key,{true}){it}!!.revision)
        assertNull(reopened.update(modelTuple(session.baseUrl,"account-b"),{true}){it})
    }
    @Test fun offline_queue_reopens_original_operation_and_conflict_requires_new_explicit_intent() = runBlocking {
        val api=Api();val first=sync(api);first.refresh();api.offline=true
        assertTrue(first.setFavorite("p","m",true));val queued=first.state.value.pending.single().operation
        scopes.forEach { it.cancel() };stores.forEach{it.close()}
        api.offline=false;api.seq=1;api.rows[modelTuple("p","m")]=api.row("p","m").copy(revision="1",favoriteRevision="1")
        val second=sync(api);second.refresh()
        assertEquals(queued.opId,api.operations.last().opId)
        assertEquals("HR-SYNC-012",second.state.value.pending.single().errorCode)
        assertFalse(second.state.value.projected().any { it.favorite })
        second.reapply(queued.opId);second.refresh()
        assertNotEquals(queued.opId,api.operations.last().opId)
        assertEquals("1",api.operations.last().baseRevision)
        assertTrue(second.state.value.rows.single().favorite)
    }
    @Test fun lost_ack_replays_original_op_and_never_applies_cached_ack_over_new_state() = runBlocking {
        val api=Api();val first=sync(api);first.refresh();api.dropAck=true
        assertTrue(first.setFavorite("p","m",true));first.refresh()
        val op=first.state.value.pending.single().operation
        scopes.forEach{it.cancel()};stores.forEach{it.close()}
        api.dropAck=false
        // Another installation cancels after the accepted-but-lost response.
        api.seq++;api.rows[modelTuple("p","m")]=api.row("p","m").copy(favorite=false,revision="${api.seq}",favoriteRevision="${api.seq}")
        val next=sync(api);next.refresh()
        assertEquals(op.opId,api.operations.last().opId)
        assertTrue(next.state.value.pending.isEmpty());assertTrue(next.state.value.rows.isEmpty())
    }
    @Test fun no_durable_commit_means_no_mutation_and_no_optimistic_favorite() = runBlocking {
        val api=Api();val real=cache();var fail=false
        val store=object:ModelPreferenceCacheStore {
            override fun clear()=real.clear()
            override fun update(scope:String,active:()->Boolean,transform:(ModelPreferenceCache?)->ModelPreferenceCache?):ModelPreferenceCache? {
                if(fail)throw IOException("disk full")
                return real.update(scope,active,transform)
            }
        }
        val sync=sync(api,store);sync.refresh();fail=true
        assertFalse(sync.setFavorite("p","m",true));assertTrue(api.operations.isEmpty())
        assertTrue(sync.state.value.projected().isEmpty());assertEquals("HR-SYNC-017",sync.state.value.error?.code?.value)
    }
    @Test fun import_is_explicit_base_zero_stable_and_durable_and_capability_off_is_private_read_only() = runBlocking {
        val api=Api();val first=sync(api);first.refresh();assertTrue(api.operations.isEmpty())
        val data=legacyModelImport(setOf(favKey("p","m")),mapOf(favKey("p","m") to "high"))
        first.importLegacy(data,"local-installation");first.refresh()
        assertEquals(2,api.operations.map{it.opId}.distinct().size);assertTrue(api.operations.all{it.baseRevision=="0"})
        val count=api.operations.size;first.importLegacy(data,"local-installation");first.refresh();assertEquals(count,api.operations.size)
        scopes.forEach{it.cancel()};stores.forEach{it.close()};api.enabled=false
        val paused=sync(api);paused.refresh()
        assertTrue(paused.state.value.useCloud);assertFalse(paused.state.value.enabled)
        assertFalse(paused.setFavorite("p","m",false));assertEquals(count,api.operations.size)
    }
    @Test fun new_login_generation_cannot_restore_old_account_cache() = runBlocking {
        val api=Api();val first=sync(api);first.refresh();api.offline=true;first.setFavorite("p","m",true)
        scopes.forEach{it.cancel()};stores.forEach{it.close()}
        val oldOwner=first.currentOwnerKey()
        sessions.value=session.copy(modelSyncGeneration="generation-b")
        api.offline=false;val next=sync(api);next.refresh()
        assertTrue(next.state.value.pending.isEmpty());assertTrue(api.operations.isEmpty())
        assertFalse(next.setReasoning("p","m","high",oldOwner));assertTrue(api.operations.isEmpty())
        sessions.value=null;next.ensureBound();assertFalse(next.state.value.useCloud)
    }
}
