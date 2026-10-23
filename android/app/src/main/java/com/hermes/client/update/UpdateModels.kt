package com.hermes.client.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant

const val UPDATE_CHANNEL = "internal"

// Client-side mirrors of the publisher's caps (docs/APP_UPDATE.md). The server already refuses to
// write a larger index; the client re-enforces them so a compromised or misconfigured origin can
// never make the phone buffer or render an unbounded document.
const val MAX_UPDATE_INDEX_BYTES = 1L * 1024 * 1024
const val MAX_UPDATE_VERSIONS = 100
const val MAX_RELEASE_NOTES = 20
const val MAX_RELEASE_NOTE_CHARS = 500

private val digestPattern = Regex("^[0-9a-fA-F]{64}$")
private val semverPattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")

@Serializable
data class UpdateIndex(
    val schemaVersion: Int,
    val channel: String,
    val latestVersionCode: Int,
    val generatedAt: String,
    val versions: List<UpdateVersion>,
)

@Serializable
data class UpdateVersion(
    val versionName: String,
    val versionCode: Int,
    val applicationId: String,
    val channel: String,
    val publishedAt: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val certificateSha256: String,
    val minSdk: Int,
    val releaseNotes: List<String>,
    val sourceCommit: String,
)

class UpdateManifestParser(private val json: Json, private val expectedCertificate: String) {
    fun parse(text: String): UpdateIndex = try {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_UPDATE_INDEX_BYTES)
        val decoded = json.decodeFromString<UpdateIndex>(text)
        require(decoded.schemaVersion == 1 && decoded.channel == UPDATE_CHANNEL)
        // An over-full catalog degrades to the newest entries instead of rejecting the whole
        // index: the entries are ordered newest-first (enforced below), so a client must never
        // lose the ability to update just because release retention fell behind.
        val index = if (decoded.versions.size > MAX_UPDATE_VERSIONS) {
            decoded.copy(versions = decoded.versions.take(MAX_UPDATE_VERSIONS))
        } else decoded
        Instant.parse(index.generatedAt)
        var previous = Int.MAX_VALUE
        val codes = mutableSetOf<Int>()
        val names = mutableSetOf<String>()
        index.versions.forEach { version ->
            validateVersion(version)
            require(version.versionCode < previous && codes.add(version.versionCode) && names.add(version.versionName))
            previous = version.versionCode
        }
        require(index.latestVersionCode == (index.versions.firstOrNull()?.versionCode ?: 0))
        index
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid update manifest", error)
    }

    private fun validateVersion(version: UpdateVersion) {
        require(semverPattern.matches(version.versionName) && version.versionCode > 0)
        require(version.applicationId == "com.hermes.remote" && version.channel == UPDATE_CHANNEL)
        require(version.sizeBytes > 0 && version.minSdk > 0 && version.sourceCommit.isNotBlank())
        require(digestPattern.matches(version.sha256) && version.certificateSha256.equals(expectedCertificate, true))
        require(version.releaseNotes.size <= MAX_RELEASE_NOTES)
        version.releaseNotes.forEach { note ->
            require(note.isNotBlank() && note.length <= MAX_RELEASE_NOTE_CHARS && note.none { it.isISOControl() })
        }
        Instant.parse(version.publishedAt)
        require(version.fileName == "Hermes-Remote-${version.versionName}-debug.apk")
        val uri = URI(version.downloadUrl)
        require(uri.scheme == "https" && uri.host == "mrlgs.net" && uri.userInfo == null && uri.port in listOf(-1, 443))
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == "/releases/${version.fileName}")
    }
}

/** How many release APKs stay on disk as rollback material (owner decision 2026-09-02). */
const val KEPT_APK_VERSIONS = 5

/**
 * Which locally stored release APKs to delete. Pure: [existingFileNames] is the download
 * directory listing, [keepFileNames] the newest [KEPT_APK_VERSIONS] releases plus the file an
 * active download task owns. Only files matching the release naming scheme are ever touched —
 * downloader scratch files and anything foreign stay untouched.
 */
fun selectApksToPrune(existingFileNames: List<String>, keepFileNames: Set<String>): List<String> =
    existingFileNames.filter { name ->
        name.startsWith("Hermes-Remote-") && name.endsWith("-debug.apk") && name !in keepFileNames
    }

enum class VersionEligibility { CURRENT, UPDATE, OLD, INCOMPATIBLE }
data class UpdateRow(val version: UpdateVersion, val eligibility: VersionEligibility)

fun classifyVersion(version: UpdateVersion, currentCode: Int, applicationId: String, channel: String, cert: String, deviceSdk: Int) = when {
    version.applicationId != applicationId || version.channel != channel || !version.certificateSha256.equals(cert, true) || version.minSdk > deviceSdk -> VersionEligibility.INCOMPATIBLE
    version.versionCode == currentCode -> VersionEligibility.CURRENT
    version.versionCode > currentCode -> VersionEligibility.UPDATE
    else -> VersionEligibility.OLD
}

fun classifyVersions(versions: List<UpdateVersion>, code: Int, app: String, channel: String, cert: String, deviceSdk: Int) =
    versions.sortedByDescending { it.versionCode }.map { UpdateRow(it, classifyVersion(it, code, app, channel, cert, deviceSdk)) }

/**
 * The 版本记录 list (docs/DESIGN.md §5.9). It omits [latest] ONLY while the primary card is
 * offering that version as a download — otherwise the same release notes would appear twice on
 * one screen.
 *
 * The old rule dropped [latest] unconditionally, which quietly made the release notes of the
 * build you are actually running unreachable: the primary card degrades to a one-line
 * "you're up to date" with no notes, and the history skipped the row that had them. On 0.1.98 the
 * record started at 0.1.96 and nothing in the app could say what 0.1.98 had changed (HG-13).
 */
fun historyRows(rows: List<UpdateRow>, latest: UpdateRow?): List<UpdateRow> =
    if (latest?.eligibility == VersionEligibility.UPDATE) {
        rows.filter { it.version.versionCode != latest.version.versionCode }
    } else {
        rows
    }

/**
 * One user-visible download stage.
 *
 * [ENQUEUING] exists purely so the UI has a busy state during the suspend call that hands the
 * job to the in-app downloader: without it a second tap lands while the state still reads IDLE
 * and queues a duplicate task. [PAUSED] is deliberately separate from [WAITING] — "queued" and
 * "paused, here is how to continue" need different copy and different recovery.
 */
enum class DownloadPhase { IDLE, ENQUEUING, WAITING, PAUSED, DOWNLOADING, VERIFYING, CANCELLING, FAILED, DOWNLOADED, INSTALLABLE }

/** Phases during which a new download must not be started (a job already owns the slot). */
val BUSY_DOWNLOAD_PHASES = setOf(
    DownloadPhase.ENQUEUING,
    DownloadPhase.WAITING,
    DownloadPhase.PAUSED,
    DownloadPhase.DOWNLOADING,
    DownloadPhase.VERIFYING,
    DownloadPhase.CANCELLING,
    DownloadPhase.DOWNLOADED,
    DownloadPhase.INSTALLABLE,
)

fun mapDownloadStatus(status: UpdateDownloadStatus) = when (status) {
    UpdateDownloadStatus.WAITING -> DownloadPhase.WAITING
    UpdateDownloadStatus.PAUSED -> DownloadPhase.PAUSED
    UpdateDownloadStatus.RUNNING -> DownloadPhase.DOWNLOADING
    UpdateDownloadStatus.COMPLETE -> DownloadPhase.DOWNLOADED
    UpdateDownloadStatus.FAILED -> DownloadPhase.FAILED
}
fun downloadPercent(downloaded: Long, total: Long) = if (total <= 0) null else ((downloaded * 100 / total).coerceIn(0, 100)).toInt()

data class ApkIdentity(val applicationId: String, val versionCode: Long, val versionName: String, val certificateSha256: String, val minSdk: Int)
fun verifyApk(actualSize: Long, actualSha256: String, version: UpdateVersion, identity: ApkIdentity): Result<Unit> = runCatching {
    require(actualSize == version.sizeBytes && actualSha256.equals(version.sha256, true))
    require(identity.applicationId == version.applicationId && identity.versionCode == version.versionCode.toLong())
    require(identity.versionName == version.versionName && identity.certificateSha256.equals(version.certificateSha256, true))
    require(identity.minSdk == version.minSdk)
}

sealed interface InstallResult {
    data object PermissionRequired : InstallResult
    data object InstallerOpened : InstallResult
    data class Failure(val message: String) : InstallResult
}

/**
 * A persisted record the page should restore. A record for a build the device already runs is
 * discarded, otherwise the page would keep re-verifying an APK the user long since installed.
 * Records that no longer deserialize never reach here: the engine drops them at load time.
 */
sealed interface SavedDownloadDecision {
    data class Resume(val version: UpdateVersion) : SavedDownloadDecision
    /** The APK belongs to the running build (or older); remove it instead of showing a task. */
    data object AlreadyInstalled : SavedDownloadDecision
}

fun decideSavedDownload(record: UpdateDownloadRecord, currentVersionCode: Int): SavedDownloadDecision = when {
    currentVersionCode >= record.version.versionCode -> SavedDownloadDecision.AlreadyInstalled
    else -> SavedDownloadDecision.Resume(record.version)
}
