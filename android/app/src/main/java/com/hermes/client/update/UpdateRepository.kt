package com.hermes.client.update

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
    /** Start a new download, or continue the partial one for [version] from its checkpoint. */
    suspend fun start(version: UpdateVersion)
    /** The persisted task's version, or null when nothing is pending or it had to be healed away. */
    suspend fun saved(): UpdateVersion?
    suspend fun query(): UpdateDownloadSnapshot?
    suspend fun verify(version: UpdateVersion): File
    /** Remove the task state, the partial file, and the residual APK. */
    suspend fun cancel()
    fun install(file: File): InstallResult
    /** Version codes among [candidates] whose release APK is still on disk. */
    suspend fun localApkVersions(candidates: List<UpdateVersion>): Set<Int>
    /** Delete stored release APKs not named in [keepFileNames] (see [selectApksToPrune]). */
    suspend fun pruneDownloads(keepFileNames: Set<String>)
    /**
     * Copy a stored release APK to a user-reachable location for the manual rollback flow
     * (uninstall wipes this app's private directory, taking the APK with it). The file is
     * re-hashed against the manifest before leaving the sandbox.
     */
    suspend fun exportApk(version: UpdateVersion): ExportResult
}

sealed interface ExportResult {
    /** Saved into the device's public Downloads collection. */
    data object SavedToDownloads : ExportResult
    /** Pre-Android-10 fallback: a share sheet was opened for the user to place the file. */
    data object ShareSheetOpened : ExportResult
    data class Failure(val message: String) : ExportResult
}

class UpdateRepository(
    private val context: Context,
    private val json: Json,
    private val publicClient: OkHttpClient,
    private val engine: UpdateDownloadEngine,
) : UpdateRepositoryContract {
    private val downloadDirectory get() = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))

    override suspend fun fetch(): UpdateIndex = withContext(Dispatchers.IO) {
        fetchUpdateIndex(publicClient, BuildConfig.UPDATE_INDEX_URL, UpdateManifestParser(json, BuildConfig.EXPECTED_UPDATE_CERT_SHA256))
    }

    override suspend fun start(version: UpdateVersion): Unit = withContext(Dispatchers.IO) {
        validateDownloadUrl(version)
        engine.prepare(version)
        UpdateDownloadScheduler.start(context)
    }

    override suspend fun saved(): UpdateVersion? = withContext(Dispatchers.IO) {
        val record = engine.restore() ?: return@withContext null
        when (val decision = decideSavedDownload(record, BuildConfig.VERSION_CODE)) {
            SavedDownloadDecision.AlreadyInstalled -> {
                engine.cancel()
                null
            }
            is SavedDownloadDecision.Resume -> {
                // A user-paused or permanently failed task waits on the page; everything else
                // continues now, even if the process/activity that owned it is long gone.
                if (record.status == UpdateDownloadStatus.WAITING || record.status == UpdateDownloadStatus.RUNNING) {
                    UpdateDownloadScheduler.start(context)
                }
                decision.version
            }
        }
    }

    override suspend fun query(): UpdateDownloadSnapshot? = engine.snapshot()

    override suspend fun verify(version: UpdateVersion): File = withContext(Dispatchers.IO) {
        val file = updateDownloadTarget(downloadDirectory, version).canonicalFile
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

    override suspend fun cancel(): Unit = withContext(Dispatchers.IO) {
        engine.cancel()
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

    override suspend fun localApkVersions(candidates: List<UpdateVersion>): Set<Int> = withContext(Dispatchers.IO) {
        candidates.filter { updateDownloadTarget(downloadDirectory, it).isFile }.map { it.versionCode }.toSet()
    }

    override suspend fun pruneDownloads(keepFileNames: Set<String>): Unit = withContext(Dispatchers.IO) {
        val existing = downloadDirectory.listFiles()?.map { it.name }.orEmpty()
        selectApksToPrune(existing, keepFileNames).forEach { name ->
            // Best-effort: a locked file stays for the next sweep; never fail the caller's check.
            runCatching { File(downloadDirectory, name).delete() }
        }
    }

    override suspend fun exportApk(version: UpdateVersion): ExportResult = withContext(Dispatchers.IO) {
        val source = updateDownloadTarget(downloadDirectory, version)
        if (!source.isFile) return@withContext ExportResult.Failure("APK is no longer on disk")
        // Never let a tampered file out of the sandbox: re-hash against the signed manifest.
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(source).use { input -> val buffer = ByteArray(DEFAULT_BUFFER_SIZE); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        val sha = hash.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(version.sha256, true) || source.length() != version.sizeBytes) {
            return@withContext ExportResult.Failure("Stored APK failed integrity re-verification")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, version.fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            }
            val resolver = context.contentResolver
            val target = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext ExportResult.Failure("Could not create the destination file")
            runCatching {
                resolver.openOutputStream(target)?.use { out -> FileInputStream(source).use { it.copyTo(out) } }
                    ?: error("Could not open the destination file")
            }.onFailure {
                resolver.delete(target, null, null)
                return@withContext ExportResult.Failure(it.message ?: "Copy failed")
            }
            ExportResult.SavedToDownloads
        } else {
            // API 26–28: writing public storage needs a runtime permission; the share sheet is
            // the friction-free path and lets the user land the file anywhere (Files, PC, IM).
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, version.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (chooser.resolveActivity(context.packageManager) == null) {
                ExportResult.Failure("No app can receive the file")
            } else {
                context.startActivity(chooser)
                ExportResult.ShareSheetOpened
            }
        }
    }

    private fun validateDownloadUrl(version: UpdateVersion) {
        val uri=Uri.parse(version.downloadUrl)
        require(uri.scheme=="https"&&uri.host=="mrlgs.net"&&(uri.port==-1||uri.port==443)&&uri.query==null&&uri.fragment==null)
        require(uri.path=="/releases/${version.fileName}")
    }
}

@Suppress("DEPRECATION") private fun legacySignatures(info:PackageInfo):Array<Signature>?=info.signatures
@Suppress("DEPRECATION") private fun legacyVersionCode(info:PackageInfo):Long=info.versionCode.toLong()
