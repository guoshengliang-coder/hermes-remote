package com.hermes.client.data.auth

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.network.AccountExchangeResponseDto
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local-stack consumer test, not a phone UI/ROM or production acceptance test.
 * Uses dedicated test-prefixed encrypted preferences, never the installed app's saved account.
 * The fixture comes from model-sync-smoke.mjs and is deleted even if a check fails.
 */
@RunWith(AndroidJUnit4::class)
class AccountModelSyncLiveTest {
    @Test fun realGatewayModelsUseDurableQueueAndNativeBearerWithoutHermesWrites() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelSyncLive") == "1")
        // Instrumentation executes as the target UID, not the test APK UID. Keep both fixture
        // and encrypted preferences in a dedicated namespace under that UID.
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(base.filesDir, "hg216-model-sync-live").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("hg216-live-$name", mode)
            override fun deleteSharedPreferences(name: String) = base.deleteSharedPreferences("hg216-live-$name")
        }
        val fixture = File(context.filesDir, "hg216-model-sync.json")
        require(fixture.isFile) { "Install the isolated smoke fixture at ${fixture.absolutePath}" }
        val json = Json { ignoreUnknownKeys = true }
        val input = json.parseToJsonElement(fixture.readText()).jsonObject
        fixture.delete()
        val origin = input.getValue("baseUrl").jsonPrimitive.content
        val uri = java.net.URI(origin)
        require(uri.scheme == "https" && uri.host == "localhost" && uri.port >= 18000 && uri.userInfo == null)
        val exchange = json.decodeFromJsonElement(AccountExchangeResponseDto.serializer(), input.getValue("exchange"))
        // Trust only this run's CA, retaining ordinary hostname verification. No trust-all client
        // or production networking configuration is introduced.
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            input.getValue("caPem").jsonPrimitive.content.byteInputStream(),
        )
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isolated-web-stack", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val trustManager = trust.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trustManager).build()
        val api = AccountApi(http, json)
        val store = EncryptedCredentialStore(context)
        val databaseName="hg216-live-models.db"
        val db=com.hermes.client.data.sync.SqliteModelPreferenceCacheStore(context,databaseName)
        val manager=AccountSessionManager(store,api,modelCache=db)
        val job=kotlinx.coroutines.SupervisorJob()
        val scope=kotlinx.coroutines.CoroutineScope(job+kotlinx.coroutines.Dispatchers.IO)
        val sync=com.hermes.client.data.sync.AccountModelPreferences(manager,api,db,scope)
        var offline=false
        val controlled=http.newBuilder().addInterceptor { chain ->
            if(offline && chain.request().url.encodedPath.startsWith("/v2/account-data/models/")) throw IOException("isolated offline probe")
            chain.proceed(chain.request())
        }.build()
        var reopened:com.hermes.client.data.sync.SqliteModelPreferenceCacheStore?=null
        var nextJob:kotlinx.coroutines.CompletableJob?=null
        try {
            manager.activate(origin,exchange)
            sync.refresh()
            assertTrue(sync.state.value.enabled && sync.state.value.ready)
            val browser=sync.state.value.rows.single { it.provider=="anthropic" && it.model=="claude-opus-5" }
            assertEquals("high",browser.reasoningPreset)
            assertTrue(!browser.favorite)
            assertTrue(sync.setReasoning(browser.provider,browser.model,"ultra"));sync.refresh()
            assertEquals("ultra",sync.state.value.rows.single{it.model==browser.model}.reasoningPreset)
            job.cancel();job.join()
            // Real API client with a transport failure. Capabilities still comes from Gateway.
            val offlineJob=kotlinx.coroutines.SupervisorJob()
            val offlineScope=kotlinx.coroutines.CoroutineScope(offlineJob+kotlinx.coroutines.Dispatchers.IO)
            val queued=com.hermes.client.data.sync.AccountModelPreferences(manager,AccountApi(controlled,json),db,offlineScope)
            queued.refresh();offline=true
            assertTrue(queued.setFavorite(browser.provider,browser.model,true))
            val original=queued.state.value.pending.single().operation
            offlineJob.cancel();offlineJob.join();db.close()
            reopened=com.hermes.client.data.sync.SqliteModelPreferenceCacheStore(context,databaseName)
            val persisted=reopened.update(com.hermes.client.data.sync.modelTuple(origin,exchange.account.id),{true}){it}
            assertEquals(original.opId,persisted!!.pending.single().operation.opId)
            nextJob=kotlinx.coroutines.SupervisorJob()
            val resumed=com.hermes.client.data.sync.AccountModelPreferences(manager,api,reopened,kotlinx.coroutines.CoroutineScope(nextJob+kotlinx.coroutines.Dispatchers.IO))
            resumed.refresh()
            assertTrue(resumed.state.value.pending.isEmpty())
            assertTrue(resumed.state.value.rows.single{it.model==browser.model}.favorite)
            assertEquals("ultra",resumed.state.value.rows.single{it.model==browser.model}.reasoningPreset)
            assertEquals(manager.session.value?.modelSyncGeneration,EncryptedCredentialStore(context).loadAccountSession()?.modelSyncGeneration)
            // Sign-out uses the original store, whose lazy helper can reopen this same DB.
            manager.signOut();assertNull(manager.session.value)
            assertNull(reopened.update(com.hermes.client.data.sync.modelTuple(origin,exchange.account.id),{true}){it})
        } finally {
            job.cancel();nextJob?.cancel();nextJob?.join()
            store.clearAccountSession(requireReauthentication=false)
            db.close();reopened?.close();context.deleteDatabase(databaseName)
            controlled.connectionPool.evictAll();controlled.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
        }
    }
}
