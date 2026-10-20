package com.hermes.client.data.auth

import android.graphics.BitmapFactory
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.network.AccountExchangeResponseDto
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
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
 * The fixture comes from profile-sync-smoke.mjs and is deleted even if a check fails.
 */
@RunWith(AndroidJUnit4::class)
class AccountProfileSyncLiveTest {
    @Test fun realGatewayProfileSurvivesStoreReopenAndOfflineThenClearsOnSignOut() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("profileSyncLive") == "1")
        // Instrumentation executes as the target UID, not the test APK UID. Keep both fixture
        // and encrypted preferences in a dedicated namespace under that UID.
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(base.filesDir, "hg216-profile-sync-live").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("hg216-live-$name", mode)
            override fun deleteSharedPreferences(name: String) = base.deleteSharedPreferences("hg216-live-$name")
        }
        val fixture = File(context.filesDir, "hg216-profile-sync.json")
        require(fixture.isFile) { "Install the isolated smoke fixture at ${fixture.absolutePath}" }
        val json = Json { ignoreUnknownKeys = true }
        val input = json.parseToJsonElement(fixture.readText()).jsonObject
        fixture.delete()
        val origin = input.getValue("baseUrl").jsonPrimitive.content
        val uri = java.net.URI(origin)
        require(uri.scheme == "https" && uri.host == "localhost" && uri.port >= 18000 && uri.userInfo == null)
        val expected = input.getValue("expected").jsonObject
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
        val cache = AccountProfileAvatarCache(File(context.filesDir, "profile-sync-live-avatars"))
        val manager = AccountSessionManager(store, api, profileAvatars = cache)
        try {
            manager.activate(origin, exchange)
            manager.refreshAccountProfile()
            val current = requireNotNull(manager.session.value)
            assertEquals(expected.getValue("displayName").jsonPrimitive.content, current.accountDisplayName)
            assertEquals(expected.getValue("revision").jsonPrimitive.content, current.accountProfileRevision)
            assertEquals(expected.getValue("avatarRevision").jsonPrimitive.content, current.accountAvatarRevision)
            val avatar = File(requireNotNull(current.accountAvatarFile))
            assertTrue(avatar.isFile)
            assertNotNull(BitmapFactory.decodeFile(avatar.path))
            val hash = MessageDigest.getInstance("SHA-256").digest(avatar.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(expected.getValue("avatarSha256").jsonPrimitive.content, hash)

            val reopened = EncryptedCredentialStore(context)
            assertEquals(current, reopened.loadAccountSession())
            val offlineHttp = http.newBuilder().addInterceptor { throw IOException("isolated offline probe") }.build()
            val offline = AccountSessionManager(reopened, AccountApi(offlineHttp, json), profileAvatars = cache)
            assertEquals(current, offline.session.value)
            offline.refreshAccountProfile()
            assertEquals(current, offline.session.value)
            assertTrue(avatar.isFile)
            offlineHttp.connectionPool.evictAll()
            offlineHttp.dispatcher.executorService.shutdown()

            // Real native revocation; this must not sign the independent browser installations out.
            manager.signOut()
            assertNull(manager.session.value)
            assertNull(EncryptedCredentialStore(context).loadAccountSession())
            assertTrue(!avatar.exists())
        } finally {
            store.clearAccountSession(requireReauthentication = false)
            cache.clear()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
