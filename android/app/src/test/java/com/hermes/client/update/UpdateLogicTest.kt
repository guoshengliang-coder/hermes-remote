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

    // Pending ("queued, will start") and paused ("stalled, tell the user why") are different
    // products of the same DownloadManager cursor and must not collapse into one label.
    @Test fun `maps pending paused running and failed statuses and safe progress`() {
        assertEquals(DownloadPhase.WAITING, mapDownloadStatus(DownloadManager.STATUS_PENDING))
        assertEquals(DownloadPhase.PAUSED, mapDownloadStatus(DownloadManager.STATUS_PAUSED))
        assertEquals(DownloadPhase.DOWNLOADING, mapDownloadStatus(DownloadManager.STATUS_RUNNING))
        assertEquals(DownloadPhase.DOWNLOADED, mapDownloadStatus(DownloadManager.STATUS_SUCCESSFUL))
        assertEquals(DownloadPhase.FAILED, mapDownloadStatus(DownloadManager.STATUS_FAILED))
        assertEquals(50, downloadPercent(5,10)); assertNull(downloadPercent(5,-1))
    }

    @Test fun `pause and failure reasons carry localized meaning in both languages`() {
        val network = downloadPauseText(DownloadManager.PAUSED_WAITING_FOR_NETWORK)
        assertTrue(network.zh.contains("网络") && network.en.contains("network"))
        assertTrue(downloadPauseText(DownloadManager.PAUSED_QUEUED_FOR_WIFI).zh.contains("Wi-Fi"))
        assertTrue(downloadPauseText(DownloadManager.PAUSED_WAITING_TO_RETRY).en.contains("Retrying"))
        val space = downloadFailureText(DownloadManager.ERROR_INSUFFICIENT_SPACE)
        assertTrue(space.zh.contains("存储空间") && space.en.contains("storage"))
        // Unknown codes stay diagnosable without leaking a raw platform message.
        assertTrue(downloadFailureText(494).en.contains("494"))
    }

    // A persisted record survives process death; it must never resurrect a job for a build the
    // user already runs, and a half-written record must heal instead of failing forever.
    @Test fun `saved download decision resumes heals corrupt records and drops installed ones`() {
        val version = parser.parse(manifest()).versions.single()
        assertEquals(SavedDownloadDecision.Resume(7L, version), decideSavedDownload(7L, true, version, 18))
        assertEquals(SavedDownloadDecision.None, decideSavedDownload(-1L, false, null, 18))
        assertEquals(SavedDownloadDecision.Discard(DiscardReason.CORRUPT), decideSavedDownload(7L, true, null, 18))
        assertEquals(SavedDownloadDecision.Discard(DiscardReason.CORRUPT), decideSavedDownload(-1L, true, version, 18))
        assertEquals(SavedDownloadDecision.Discard(DiscardReason.ALREADY_INSTALLED), decideSavedDownload(7L, true, version, 19))
        assertEquals(SavedDownloadDecision.Discard(DiscardReason.ALREADY_INSTALLED), decideSavedDownload(7L, true, version, 20))
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

    // Server contract (docs/APP_UPDATE.md): 1 MiB index, 100 versions, 20 notes of 500 chars.
    // The byte cap stays fail-closed (exhaustion guard); the VERSION cap degrades to the newest
    // entries instead (owner decision 2026-09-02) — retention falling behind on the server must
    // never brick every client's update check.
    @Test fun `parser enforces the server index size and version caps`() {
        val padded = " ".repeat(MAX_UPDATE_INDEX_BYTES.toInt()) + manifest()
        assertThrows(IllegalArgumentException::class.java) { parser.parse(padded) }
        assertEquals(19, parser.parse(" ".repeat(64) + manifest()).latestVersionCode)

        val many = (1..MAX_UPDATE_VERSIONS + 1).map { versionEntry(MAX_UPDATE_VERSIONS + 2 - it) }
        val degraded = parser.parse(indexOf(many))
        assertEquals(MAX_UPDATE_VERSIONS, degraded.versions.size)
        assertEquals(degraded.latestVersionCode, degraded.versions.first().versionCode)
        assertEquals(MAX_UPDATE_VERSIONS, parser.parse(indexOf(many.drop(1))).versions.size)
    }

    @Test fun `parser enforces release-note count length and control characters`() {
        val notes = { list: List<String> -> indexOf(listOf(versionEntry(19, list))) }
        assertThrows(IllegalArgumentException::class.java) { parser.parse(notes(List(MAX_RELEASE_NOTES + 1) { "n" })) }
        assertEquals(MAX_RELEASE_NOTES, parser.parse(notes(List(MAX_RELEASE_NOTES) { "n" })).versions.single().releaseNotes.size)
        assertThrows(IllegalArgumentException::class.java) { parser.parse(notes(listOf("n".repeat(MAX_RELEASE_NOTE_CHARS + 1)))) }
        assertEquals(1, parser.parse(notes(listOf("n".repeat(MAX_RELEASE_NOTE_CHARS)))).versions.single().releaseNotes.size)
        assertThrows(IllegalArgumentException::class.java) { parser.parse(notes(listOf("bad" + 1.toChar() + "note"))) }
        assertThrows(IllegalArgumentException::class.java) { parser.parse(notes(listOf("   "))) }
    }

    private fun versionEntry(code: Int, notes: List<String> = listOf("note")): String {
        val name = "0.1.$code"
        val encoded = notes.joinToString(",") { note ->
            "\"" + note.map { if (it.code < 0x20) "\\u%04x".format(it.code) else it.toString() }.joinToString("") + "\""
        }
        return """{"versionName":"$name","versionCode":$code,"applicationId":"com.hermes.remote","channel":"internal","publishedAt":"2026-08-30T00:00:00Z","fileName":"Hermes-Remote-$name-debug.apk","downloadUrl":"https://mrlgs.net/releases/Hermes-Remote-$name-debug.apk","sizeBytes":12,"sha256":"${"a".repeat(64)}","certificateSha256":"$cert","minSdk":26,"releaseNotes":[$encoded],"sourceCommit":"abcdef1"}"""
    }

    private fun indexOf(entries: List<String>): String {
        val latest = Regex("\"versionCode\":(\\d+)").find(entries.first())!!.groupValues[1]
        return """{"schemaVersion":1,"channel":"internal","latestVersionCode":$latest,"generatedAt":"2026-08-30T00:00:00Z","versions":[${entries.joinToString(",")}]}"""
    }

    @Test fun `failed enqueue persistence rolls back new download`() = kotlinx.coroutines.test.runTest {
        var removed:Long?=null
        val failure=runCatching { persistEnqueuedDownload(42,{false}){removed=it} }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(42L,removed)
    }

    @Test fun `apk retention prunes only release files outside the keep set`() {
        val existing = listOf(
            "Hermes-Remote-0.1.70-debug.apk",
            "Hermes-Remote-0.1.71-debug.apk",
            "Hermes-Remote-0.1.75-debug.apk",
            "download-1234.tmp",          // DownloadManager scratch — never ours to delete
            "unrelated.txt",
        )
        val keep = setOf("Hermes-Remote-0.1.75-debug.apk")
        assertEquals(
            listOf("Hermes-Remote-0.1.70-debug.apk", "Hermes-Remote-0.1.71-debug.apk"),
            selectApksToPrune(existing, keep),
        )
    }
}
