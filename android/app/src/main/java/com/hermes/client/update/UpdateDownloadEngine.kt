package com.hermes.client.update

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.di.UpdateHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Owns the one in-app update download task and its checkpoint journal (HG-223).
 *
 * The journal lives in the same private preferences file the DownloadManager era used, so an
 * upgrade can clean up an orphaned system job instead of leaving it running beside the new
 * downloader. The record is written before the service starts and after every fsynced range:
 * that ordering is what makes "leave the page and come back" a resume rather than a restart.
 */
@Singleton
class UpdateDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @UpdateHttpClient client: OkHttpClient,
    private val json: Json,
) {
    private val directory: File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "")
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val runner = Mutex()
    private val calls: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap())
    private var job: Job? = null
    private val _record = MutableStateFlow<UpdateDownloadRecord?>(null)
    val record: StateFlow<UpdateDownloadRecord?> = _record.asStateFlow()

    /**
     * Bytes written but not yet committed by a fsynced chunk. Kept apart from [_record] so a
     * failure never persists an optimistic offset: the journal only ever holds durable bytes.
     */
    private val displayProgress = UpdateDownloadProgress(android.os.SystemClock::elapsedRealtime)

    /** A dedicated client: same no-cookie/no-redirect policy as the index client, slower reads. */
    private val downloadClient: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // A 32 MB APK over a long-haul link may take many minutes; cancellation owns its lifetime.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Restore the persisted record, discarding the half-written one of the old downloader. */
    @Synchronized fun restore(): UpdateDownloadRecord? {
        _record.value?.let { return it }
        discardLegacyDownloadManagerJob()
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        val record = runCatching { json.decodeFromString(UpdateDownloadRecord.serializer(), raw) }.getOrNull()
        if (record == null) {
            // A record written by an incompatible build can never become readable; clear it rather
            // than retrying the same decode on every page entry.
            prefs.edit().remove(KEY_RECORD).commit()
            return null
        }
        val healed = when (record.status) {
            // A process death leaves RUNNING behind; nothing is running until the service starts.
            UpdateDownloadStatus.RUNNING -> record.copy(status = UpdateDownloadStatus.WAITING)
            else -> record
        }
        _record.value = healed
        displayProgress.reset(healed.downloadedBytes)
        save(healed)
        return healed
    }

    /** Start a fresh task, or reuse the compatible partial one already on disk. */
    @Synchronized fun prepare(version: UpdateVersion): UpdateDownloadRecord {
        val existing = _record.value ?: restore()
        val reusable = existing != null &&
            existing.version.versionCode == version.versionCode &&
            existing.version.downloadUrl == version.downloadUrl &&
            existing.totalBytes == version.sizeBytes &&
            updateDownloadPartial(directory, version).isFile
        if (reusable) {
            val resumed = existing!!.copy(
                version = version,
                status = UpdateDownloadStatus.WAITING,
                errorCode = null,
            )
            _record.value = resumed
            displayProgress.reset(resumed.downloadedBytes)
            save(resumed)
            return resumed
        }
        updateDownloadPartial(directory, version).delete()
        val record = UpdateDownloadRecord(
            version = version,
            totalBytes = version.sizeBytes,
            segments = planUpdateSegments(version.sizeBytes),
            status = UpdateDownloadStatus.WAITING,
        )
        _record.value = record
        displayProgress.reset(0)
        save(record)
        return record
    }

    /**
     * Run the transfer until it completes, fails, or is paused/cancelled. Returns true when the
     * failure is worth retrying after [RETRY_DELAY_MS]; the committed ranges stay on disk.
     */
    suspend fun run(notify: (UpdateDownloadRecord) -> Unit): Boolean = runner.withLock {
        val initial = _record.value ?: return@withLock false
        if (initial.status != UpdateDownloadStatus.WAITING && initial.status != UpdateDownloadStatus.RUNNING) return@withLock false
        val remaining = initial.totalBytes - initial.downloadedBytes
        val usable = directory.apply { mkdirs() }.usableSpace
        if (usable < remaining + RESERVE_BYTES) {
            val failed = initial.copy(status = UpdateDownloadStatus.FAILED, errorCode = AppErrorCode.UPDATE_STORAGE_FULL.value)
            _record.value = failed
            save(failed)
            notify(failed)
            return@withLock false
        }
        job = currentCoroutineContext()[Job]
        val running = initial.copy(status = UpdateDownloadStatus.RUNNING, errorCode = null)
        _record.value = running
        displayProgress.reset(running.downloadedBytes)
        save(running)
        notify(running)
        val transfer = UpdateDownloadTransfer(
            callFactory = ::call,
            directory = directory,
            commit = ::checkpoint,
            progress = { record ->
                synchronized(this@UpdateDownloadEngine) {
                    if (_record.value?.status == UpdateDownloadStatus.RUNNING) {
                        displayProgress.advance(record.downloadedBytes)
                        notify(record)
                    }
                }
            },
        )
        try {
            val done = transfer.run(running)
            _record.value = done
            displayProgress.reset(done.downloadedBytes)
            save(done)
            notify(done)
            false
        } catch (cancelled: CancellationException) {
            val current = _record.value ?: running
            if (current.status != UpdateDownloadStatus.PAUSED && current.status != UpdateDownloadStatus.FAILED) {
                val stopped = current.copy(status = UpdateDownloadStatus.WAITING)
                _record.value = stopped
                displayProgress.reset(stopped.downloadedBytes)
                save(stopped)
            }
            throw cancelled
        } catch (failure: UpdateDownloadFailure) {
            val current = _record.value ?: running
            if (current.status == UpdateDownloadStatus.PAUSED) return@withLock false
            val status = if (failure.retryable) UpdateDownloadStatus.WAITING else UpdateDownloadStatus.FAILED
            val failed = current.copy(status = status, errorCode = failure.code.value)
            _record.value = failed
            displayProgress.reset(failed.downloadedBytes)
            save(failed)
            notify(failed)
            failure.retryable
        } finally {
            job = null
            calls.clear()
        }
    }

    @Synchronized fun pause() {
        val current = _record.value ?: return
        if (current.status == UpdateDownloadStatus.COMPLETE || current.status == UpdateDownloadStatus.PAUSED) return
        val paused = current.copy(status = UpdateDownloadStatus.PAUSED)
        _record.value = paused
        displayProgress.reset(paused.downloadedBytes)
        save(paused)
        cancelActiveCalls()
        job?.cancel()
    }

    /** Forget the task and delete only the files it owns. */
    suspend fun cancel() = withContext(Dispatchers.IO) {
        val current = _record.value ?: restore()
        cancelActiveCalls()
        job?.cancel()
        job?.join()
        if (current != null) {
            updateDownloadPartial(directory, current.version).delete()
            updateDownloadTarget(directory, current.version).delete()
        }
        prefs.edit().remove(KEY_RECORD).commit()
        _record.value = null
        displayProgress.reset(0)
    }

    /**
     * Read the atomic record and the telemetry's short memory-only lock. The page polls on Main,
     * so it must never acquire the engine monitor that holds synchronous journal writes.
     */
    fun snapshot(): UpdateDownloadSnapshot? = _record.value?.let(displayProgress::snapshot)

    /** Serialize only durable offsets, while keeping other workers' live bytes on screen. */
    private fun checkpoint(record: UpdateDownloadRecord) = synchronized(this) {
        val next = if (_record.value?.status == UpdateDownloadStatus.PAUSED) {
            record.copy(status = UpdateDownloadStatus.PAUSED)
        } else record
        save(next)
        _record.value = next
        if (next.status == UpdateDownloadStatus.RUNNING) displayProgress.advance(next.downloadedBytes)
        else displayProgress.reset(next.downloadedBytes)
    }

    /** Mark a retryable failure as ready to run again. */
    @Synchronized fun retry(): UpdateDownloadRecord? = _record.value?.let { current ->
        if (current.status == UpdateDownloadStatus.FAILED || current.status == UpdateDownloadStatus.PAUSED) {
            current.copy(status = UpdateDownloadStatus.WAITING, errorCode = null).also {
                _record.value = it
                displayProgress.reset(it.downloadedBytes)
                save(it)
            }
        } else current
    }

    private fun call(request: Request): Call = downloadClient.newCall(request).also { calls.add(it) }

    private fun cancelActiveCalls() {
        calls.toList().forEach { it.cancel() }
        calls.clear()
    }

    private fun save(record: UpdateDownloadRecord) {
        prefs.edit().putString(KEY_RECORD, json.encodeToString(UpdateDownloadRecord.serializer(), record)).commit()
    }

    /**
     * Versions before this one handed the APK to DownloadManager. Its id is meaningless to the
     * new downloader; leaving the job running would download the same 32 MB twice.
     */
    private fun discardLegacyDownloadManagerJob() {
        val legacyId = prefs.getLong("download_id", -1)
        if (legacyId < 0) return
        runCatching { context.getSystemService(DownloadManager::class.java).remove(legacyId) }
        prefs.edit().remove("download_id").remove("version").commit()
    }

    private companion object {
        const val KEY_RECORD = "download_record"
        const val RESERVE_BYTES = 16L * 1024 * 1024
    }
}

/** Snapshot the page polls; `null` status means no task exists. */
data class UpdateDownloadSnapshot(
    val status: UpdateDownloadStatus,
    val downloaded: Long,
    val total: Long,
    val errorCode: String?,
    val bytesPerSecond: Long? = null,
)
