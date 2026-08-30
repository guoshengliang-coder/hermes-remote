package com.hermes.client.update

import android.app.DownloadManager
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UpdateLogicTest {
    private val cert = "06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5"
    private val parser = UpdateManifestParser(Json { ignoreUnknownKeys = true }, cert)
    private fun manifest(url: String = "https://mrlgs.net/releases/Hermes-Remote-0.1.18-debug.apk") = """{"schemaVersion":1,"channel":"internal","latestVersionCode":19,"generatedAt":"2026-08-30T00:00:00Z","versions":[{"versionName":"0.1.18","versionCode":19,"applicationId":"com.hermes.remote","channel":"internal","publishedAt":"2026-08-30T00:00:00Z","fileName":"Hermes-Remote-0.1.18-debug.apk","downloadUrl":"$url","sizeBytes":12,"sha256":"${"a".repeat(64)}","certificateSha256":"$cert","minSdk":26,"releaseNotes":["note"],"sourceCommit":"abcdef1"}]}"""

    @Test fun `parser enforces strict URL policy`() {
        assertEquals(19, parser.parse(manifest()).latestVersionCode)
        listOf("http://mrlgs.net/releases/Hermes-Remote-0.1.18-debug.apk","https://evil.test/releases/Hermes-Remote-0.1.18-debug.apk","https://mrlgs.net:444/releases/Hermes-Remote-0.1.18-debug.apk","https://mrlgs.net/releases/Hermes-Remote-0.1.18-debug.apk?q=1","https://mrlgs.net/other/Hermes-Remote-0.1.18-debug.apk").forEach { bad -> assertThrows(IllegalArgumentException::class.java) { parser.parse(manifest(bad)) } }
    }

    @Test fun `maps paused and failed statuses and safe progress`() {
        assertEquals(DownloadPhase.WAITING, mapDownloadStatus(DownloadManager.STATUS_PAUSED))
        assertEquals(DownloadPhase.FAILED, mapDownloadStatus(DownloadManager.STATUS_FAILED))
        assertEquals(50, downloadPercent(5,10)); assertNull(downloadPercent(5,-1))
    }

    @Test fun `APK pure verification covers size hash and identity`() {
        val version=parser.parse(manifest()).versions.single(); val identity=ApkIdentity("com.hermes.remote",19,"0.1.18",cert,26)
        assertTrue(verifyApk(12,"a".repeat(64),version,identity).isSuccess)
        assertTrue(verifyApk(11,"a".repeat(64),version,identity).isFailure)
        assertTrue(verifyApk(12,"b".repeat(64),version,identity).isFailure)
        assertTrue(verifyApk(12,"a".repeat(64),version,identity.copy(applicationId="other")).isFailure)
        assertTrue(verifyApk(12,"a".repeat(64),version,identity.copy(minSdk=25)).isFailure)
    }

    @Test fun `minimum SDK eligibility includes exact boundary`() {
        val version=parser.parse(manifest()).versions.single()
        assertEquals(VersionEligibility.UPDATE,classifyVersion(version,18,"com.hermes.remote","internal",cert,26))
        assertEquals(VersionEligibility.INCOMPATIBLE,classifyVersion(version,18,"com.hermes.remote","internal",cert,25))
    }

    @Test fun `failed enqueue persistence rolls back new download`() = kotlinx.coroutines.test.runTest {
        var removed:Long?=null
        val failure=runCatching { persistEnqueuedDownload(42,{false}){removed=it} }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(42L,removed)
    }
}
