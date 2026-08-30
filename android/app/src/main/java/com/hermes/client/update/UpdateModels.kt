package com.hermes.client.update

import android.app.DownloadManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant

const val UPDATE_CHANNEL = "internal"
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
        val index = json.decodeFromString<UpdateIndex>(text)
        require(index.schemaVersion == 1 && index.channel == UPDATE_CHANNEL)
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
        Instant.parse(version.publishedAt)
        require(version.fileName == "Hermes-Remote-${version.versionName}-debug.apk")
        val uri = URI(version.downloadUrl)
        require(uri.scheme == "https" && uri.host == "mrlgs.net" && uri.userInfo == null && uri.port in listOf(-1, 443))
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == "/releases/${version.fileName}")
    }
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

enum class DownloadPhase { IDLE, WAITING, DOWNLOADING, VERIFYING, FAILED, DOWNLOADED, INSTALLABLE }
fun mapDownloadStatus(status: Int) = when (status) {
    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> DownloadPhase.WAITING
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

fun friendlyDownloadError(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage is unavailable"
    DownloadManager.ERROR_CANNOT_RESUME -> "Download could not be resumed"
    else -> "Download failed (code $reason)"
}
