package com.hermes.client.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hermes.client.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

fun createUpdateHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .cookieJar(okhttp3.CookieJar.NO_COOKIES)
    .authenticator(okhttp3.Authenticator.NONE)
    .build()

suspend fun fetchUpdateIndex(
    client: OkHttpClient,
    indexUrl: String,
    parser: UpdateManifestParser,
    maxBytes: Long = MAX_UPDATE_INDEX_BYTES,
): UpdateIndex = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(indexUrl).header("Cache-Control", "no-cache").build()
    client.newCall(request).execute().use { response ->
        require(response.isSuccessful) { "Update server returned HTTP ${response.code}" }
        val source = requireNotNull(response.body).source()
        // request() fills the buffer with at most maxBytes+1 bytes and reports whether that many
        // exist, so an oversized body is rejected before it is ever fully buffered in memory.
        require(!source.request(maxBytes + 1)) { "Update index exceeds $maxBytes bytes" }
        parser.parse(source.readString(Charsets.UTF_8))
    }
}

interface UpdateRepositoryContract {
    suspend fun fetch(): UpdateIndex
    suspend fun enqueue(version: UpdateVersion): Long
    /** The persisted job, or null when nothing is pending or the record had to be healed away. */
    suspend fun saved(): Pair<Long, UpdateVersion>?
    suspend fun query(id: Long): DownloadSnapshot?
    suspend fun verify(version: UpdateVersion, localUri: String): File
    /** Remove the DownloadManager job, the persisted metadata, and the residual file. */
    suspend fun cancel()
    fun install(file: File): InstallResult
}

internal suspend fun persistEnqueuedDownload(id: Long, persist: () -> Boolean, rollback: suspend (Long) -> Unit): Long {
    if (!persist()) {
        rollback(id)
        error("Unable to persist download state")
    }
    return id
}

class UpdateRepository(
    private val context: Context,
    private val json: Json,
    private val publicClient: OkHttpClient,
) : UpdateRepositoryContract {
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val downloadDirectory get() = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))

    override suspend fun fetch(): UpdateIndex = withContext(Dispatchers.IO) {
        fetchUpdateIndex(publicClient, BuildConfig.UPDATE_INDEX_URL, UpdateManifestParser(json, BuildConfig.EXPECTED_UPDATE_CERT_SHA256))
    }

    override suspend fun enqueue(version: UpdateVersion): Long = withContext(Dispatchers.IO) {
        validateDownloadUrl(version)
        saved()?.let { (oldId, _) -> downloads.remove(oldId) }
        prefs.edit().remove("download_id").remove("version").commit()
        val target = File(downloadDirectory, version.fileName)
        if (target.exists() && !target.delete()) error("Unable to prepare download")
        val request = DownloadManager.Request(Uri.parse(version.downloadUrl))
            .setTitle("Hermes GO ${version.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, version.fileName)
        val id = downloads.enqueue(request)
        persistEnqueuedDownload(
            id,
            { prefs.edit().putLong("download_id", id).putString("version", json.encodeToString(UpdateVersion.serializer(), version)).commit() },
            { downloads.remove(it) },
        )
    }

    override suspend fun saved(): Pair<Long, UpdateVersion>? = withContext(Dispatchers.IO) {
        val id = prefs.getLong("download_id", -1)
        val raw = prefs.getString("version", null)
        val version = raw?.let { runCatching { json.decodeFromString(UpdateVersion.serializer(), it) }.getOrNull() }
        when (val decision = decideSavedDownload(id, raw != null, version, BuildConfig.VERSION_CODE)) {
            is SavedDownloadDecision.Resume -> decision.id to decision.version
            SavedDownloadDecision.None -> null
            // Half-written or superseded state heals itself instead of being re-verified forever.
            is SavedDownloadDecision.Discard -> {
                discard(id, version?.fileName)
                null
            }
        }
    }

    override suspend fun cancel(): Unit = withContext(Dispatchers.IO) {
        val id = prefs.getLong("download_id", -1)
        val raw = prefs.getString("version", null)
        val fileName = raw?.let { runCatching { json.decodeFromString(UpdateVersion.serializer(), it) }.getOrNull() }?.fileName
        discard(id, fileName)
    }

    /** Remove the queued job, the persisted record, and only the file this record owns. */
    private fun discard(id: Long, fileName: String?) {
        if (id >= 0) downloads.remove(id)
        val safeName = fileName?.takeIf { it == File(it).name && it.isNotBlank() }
        if (safeName != null) {
            val target = File(downloadDirectory, safeName).canonicalFile
            if (target.parentFile == downloadDirectory.canonicalFile && target.isFile) {
                check(target.delete()) { "Unable to delete the update download" }
            }
        }
        check(prefs.edit().remove("download_id").remove("version").commit()) {
            "Unable to clear persisted update state"
        }
    }

    override suspend fun query(id: Long): DownloadSnapshot? = withContext(Dispatchers.IO) { downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        fun int(name: String, fallback: Int = 0) = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let(cursor::getInt) ?: fallback
        fun long(name: String, fallback: Long = -1) = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let(cursor::getLong) ?: fallback
        fun string(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { if (cursor.isNull(it)) null else cursor.getString(it) }
        DownloadSnapshot(int(DownloadManager.COLUMN_STATUS, DownloadManager.STATUS_FAILED), long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, 0), long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), string(DownloadManager.COLUMN_LOCAL_URI), int(DownloadManager.COLUMN_REASON))
    } }

    override suspend fun verify(version: UpdateVersion, localUri: String): File = withContext(Dispatchers.IO) {
        val uri = Uri.parse(localUri); require(uri.scheme == "file")
        val file = File(requireNotNull(uri.path)).canonicalFile
        val root = downloadDirectory.canonicalFile
        require(file.parentFile == root && file.name == version.fileName && file.isFile)
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val buffer=ByteArray(DEFAULT_BUFFER_SIZE); while(true){val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count)} }
        val sha = hash.digest().joinToString("") { "%02x".format(it) }
        val pm = context.packageManager
        @Suppress("DEPRECATION") val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = requireNotNull(pm.getPackageArchiveInfo(file.path, flags))
        val signatures = requireNotNull(if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else legacySignatures(info))
        require(signatures.size == 1)
        val cert = MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()).joinToString("") { "%02x".format(it) }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)
        val minSdk = requireNotNull(info.applicationInfo).minSdkVersion
        verifyApk(file.length(), sha, version, ApkIdentity(info.packageName, code, requireNotNull(info.versionName), cert, minSdk)).getOrThrow()
        require(cert.equals(BuildConfig.EXPECTED_UPDATE_CERT_SHA256, true)); file
    }

    override fun install(file: File): InstallResult = try {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) InstallResult.Failure("Unknown-source settings are unavailable")
            else { context.startActivity(intent); InstallResult.PermissionRequired }
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) InstallResult.Failure("Package installer is unavailable")
            else { context.startActivity(intent); InstallResult.InstallerOpened }
        }
    } catch (error: ActivityNotFoundException) {
        InstallResult.Failure(error.message ?: "Installer activity was not found")
    } catch (error: SecurityException) {
        InstallResult.Failure(error.message ?: "Installer permission was denied")
    }

    private fun validateDownloadUrl(version: UpdateVersion) {
        val uri=Uri.parse(version.downloadUrl)
        require(uri.scheme=="https"&&uri.host=="mrlgs.net"&&(uri.port==-1||uri.port==443)&&uri.query==null&&uri.fragment==null)
        require(uri.path=="/releases/${version.fileName}")
    }
}

data class DownloadSnapshot(val status:Int,val downloaded:Long,val total:Long,val localUri:String?,val reason:Int)
@Suppress("DEPRECATION") private fun legacySignatures(info:PackageInfo):Array<Signature>?=info.signatures
@Suppress("DEPRECATION") private fun legacyVersionCode(info:PackageInfo):Long=info.versionCode.toLong()
