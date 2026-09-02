package com.hermes.client.update

import android.app.DownloadManager
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText
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
 * DownloadManager temp files and anything foreign stay untouched.
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
 * One user-visible download stage.
 *
 * [ENQUEUING] exists purely so the UI has a busy state during the suspend call that hands the job
 * to DownloadManager: without it a second tap lands while the state still reads IDLE and queues a
 * duplicate job. [PAUSED] is deliberately separate from [WAITING] — "queued" and "stalled, here is
 * why" need different copy and different recovery.
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

fun mapDownloadStatus(status: Int) = when (status) {
    DownloadManager.STATUS_PENDING -> DownloadPhase.WAITING
    DownloadManager.STATUS_PAUSED -> DownloadPhase.PAUSED
    DownloadManager.STATUS_RUNNING -> DownloadPhase.DOWNLOADING
    DownloadManager.STATUS_SUCCESSFUL -> DownloadPhase.DOWNLOADED
    else -> DownloadPhase.FAILED
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

/** Why DownloadManager stalled the job, in the user's language (the code stays for diagnostics). */
fun downloadPauseText(reason: Int): LocalizedText = when (reason) {
    DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
        localizedText("已暂停：正在等待网络连接。", "Paused: waiting for a network connection.")
    DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
        localizedText("已暂停：文件较大，正在等待 Wi-Fi。", "Paused: the file is large and is waiting for Wi-Fi.")
    DownloadManager.PAUSED_WAITING_TO_RETRY ->
        localizedText("已暂停：网络不稳定，正在自动重试。", "Paused: the network is unstable. Retrying automatically.")
    else -> localizedText("已暂停（原因 $reason），可稍后重试。", "Paused (reason $reason). You can retry later.")
}

/** Why DownloadManager gave up, in the user's language. */
fun downloadFailureText(reason: Int): LocalizedText = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE ->
        localizedText("设备存储空间不足。", "There is not enough storage space on the device.")
    DownloadManager.ERROR_DEVICE_NOT_FOUND ->
        localizedText("下载目录不可用。", "The download storage is unavailable.")
    DownloadManager.ERROR_CANNOT_RESUME ->
        localizedText("下载无法续传，请重新下载。", "The download could not be resumed. Download it again.")
    DownloadManager.ERROR_HTTP_DATA_ERROR ->
        localizedText("下载数据出错，请重试。", "The download data was corrupted. Retry.")
    DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
        localizedText("更新服务器响应异常，请稍后重试。", "The update server responded unexpectedly. Retry later.")
    else -> localizedText("下载失败（原因 $reason）。", "The download failed (reason $reason).")
}

/** Sanitized, developer-facing form of a DownloadManager reason for copyable diagnostics. */
fun downloadReasonDiagnostic(reason: Int): String = "reason=$reason ${downloadFailureText(reason).en}"

enum class DiscardReason { CORRUPT, ALREADY_INSTALLED }

sealed interface SavedDownloadDecision {
    /** Nothing was persisted; nothing to clean up. */
    data object None : SavedDownloadDecision
    data class Resume(val id: Long, val version: UpdateVersion) : SavedDownloadDecision
    data class Discard(val reason: DiscardReason) : SavedDownloadDecision
}

/**
 * Decide what to do with the persisted download record.
 *
 * A record half-written across process death (id without metadata, or metadata that no longer
 * deserializes) is discarded rather than re-verified forever, and a record for a build the device
 * already runs is discarded too — otherwise the page would keep re-verifying an APK the user has
 * long since installed.
 */
fun decideSavedDownload(
    id: Long,
    rawVersionPresent: Boolean,
    version: UpdateVersion?,
    currentVersionCode: Int,
): SavedDownloadDecision = when {
    !rawVersionPresent && id < 0 -> SavedDownloadDecision.None
    version == null || id < 0 -> SavedDownloadDecision.Discard(DiscardReason.CORRUPT)
    currentVersionCode >= version.versionCode -> SavedDownloadDecision.Discard(DiscardReason.ALREADY_INSTALLED)
    else -> SavedDownloadDecision.Resume(id, version)
}
