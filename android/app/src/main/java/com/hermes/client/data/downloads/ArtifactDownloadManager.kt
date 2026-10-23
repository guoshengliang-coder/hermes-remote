package com.hermes.client.data.downloads

import android.content.Context
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.error.artifactDownloadError
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ArtifactDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rest: HermesRestApi,
    val videoRanges: com.hermes.client.data.video.ArtifactRangeRepository = com.hermes.client.data.video.ArtifactRangeRepository(context, rest),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ArtifactDownloadStore(context)
    private val directory = File(context.filesDir, "attachment-downloads").apply { mkdirs() }
    private val ready = CompletableDeferred<Unit>()
    private val lock = Any()
    private val runner = Mutex()
    private val batches = ArtifactDownloadBatch(rest, videoRanges)
    private val jobs = mutableMapOf<String, Job>()
    private val _tasks = MutableStateFlow<List<ArtifactDownloadTask>>(emptyList())
    val tasks: StateFlow<List<ArtifactDownloadTask>> = _tasks.asStateFlow()

    init {
        scope.launch {
            try {
                synchronized(lock) {
                    _tasks.value = store.all().map { task ->
                        if (task.status == ArtifactDownloadStatus.RUNNING) task.copy(status = ArtifactDownloadStatus.WAITING) else task
                    }
                }
                ready.complete(Unit)
            } catch (error: Exception) { ready.completeExceptionally(error) }
        }
    }

    suspend fun owner(deviceId: String?): String? = rest.artifactOwnerKey(deviceId)
    suspend fun awaitTask(id: String?): ArtifactDownloadTask? { ready.await(); return _tasks.value.find { it.id == id } }

    suspend fun enqueue(file: ChatFile, deviceId: String?): ArtifactDownloadTask = withContext(Dispatchers.IO) {
        ready.await()
        val owner = owner(deviceId) ?: throw HermesApiException(401, "download owner unavailable")
        val remote = file.remotePath ?: throw HermesApiException(404, "no remote reference")
        val id = taskId(owner, remote)
        synchronized(lock) {
            val old = _tasks.value.find { it.id == id }
            if (old?.status == ArtifactDownloadStatus.COMPLETE && completedFile(old) != null) return@synchronized old
            if (old?.status in listOf(ArtifactDownloadStatus.QUEUED, ArtifactDownloadStatus.RUNNING)) return@synchronized old!!
            val reset = old?.errorCode == AppErrorCode.ARTIFACT_CHANGED.value || old?.status == ArtifactDownloadStatus.CANCELLED
            if (reset && old != null) partial(old).delete()
            val task = (old ?: ArtifactDownloadTask(id, owner, deviceId, remote, file.name, file.mimeType, sizeBytes = file.sizeBytes))
                .copy(status = ArtifactDownloadStatus.QUEUED, errorCode = null, progressBytes = null, waitingForPlayback = false,
                    etag = if (reset) null else old?.etag,
                    downloaded = if (reset) 0 else old?.downloaded ?: 0)
            put(task)
            task
        }
    }

    fun schedule(task: ArtifactDownloadTask) {
        if (task.status == ArtifactDownloadStatus.COMPLETE || task.status == ArtifactDownloadStatus.RUNNING) return
        if (synchronized(lock) { jobs[task.id]?.isActive == true }) return
        try { ArtifactTransferScheduler.schedule(context, task) }
        catch (_: Exception) {
            scope.launch { edit(task.id) { it.copy(status = ArtifactDownloadStatus.FAILED, errorCode = AppErrorCode.ARTIFACT_DOWNLOAD_FAILED.value) } }
        }
    }

    fun pause(id: String) = stop(id, cancel = false)
    fun cancel(id: String) = stop(id, cancel = true)
    private fun stop(id: String, cancel: Boolean): Job =
        scope.launch {
            ready.await()
            val job = synchronized(lock) {
                edit(id) { it.copy(status = if (cancel) ArtifactDownloadStatus.CANCELLED else ArtifactDownloadStatus.PAUSED, progressBytes = null, waitingForPlayback = false) }
                jobs[id]?.also { it.cancel() }
            }
            ArtifactTransferScheduler.cancel(context, id)
            job?.join()
            if (cancel) _tasks.value.find { it.id == id }?.let { partial(it).delete() }
        }

    /** A durable file is valid independently of the current Mac being online. */
    fun completedFile(task: ArtifactDownloadTask): File? = target(task).takeIf {
        task.status == ArtifactDownloadStatus.COMPLETE && it.isFile && it.length() == task.sizeBytes
    }

    suspend fun runTask(id: String, notify: (ArtifactDownloadTask) -> Unit): Boolean = runner.withLock {
        ready.await()
        val initial = _tasks.value.find { it.id == id } ?: return@withLock false
        if (initial.status !in listOf(ArtifactDownloadStatus.QUEUED, ArtifactDownloadStatus.WAITING, ArtifactDownloadStatus.RUNNING)) return@withLock false
        coroutineScope {
            val work = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                var task = initial
                try {
                    if (owner(task.deviceId) != task.owner) throw HermesApiException(401, "download owner changed")
                    task = edit(id) { it.copy(status = ArtifactDownloadStatus.RUNNING, errorCode = null) } ?: return@async false
                    notify(task)
                    var descriptor = rest.prepareArtifactDownload(task.remotePath, task.deviceId, task.owner)
                    if (task.etag != null && (task.etag != descriptor.etag || task.sizeBytes != descriptor.sizeBytes)) {
                        throw HermesApiException(412, "source changed", errorCode = AppErrorCode.ARTIFACT_CHANGED.value)
                    }
                    task = edit(id) { it.copy(etag = descriptor.etag, sizeBytes = descriptor.sizeBytes,
                        mimeType = it.mimeType ?: descriptor.contentType) } ?: return@async false
                    val partial = partial(task)
                    val target = target(task)
                    // Rename may have completed just before a process died, before the DB commit.
                    if (target.isFile && target.length() == descriptor.sizeBytes && task.downloaded == descriptor.sizeBytes) {
                        task = edit(id) { it.copy(status = ArtifactDownloadStatus.COMPLETE) }!!
                        notify(task); return@async false
                    }
                    target.delete()
                    partial.createNewFile()
                    // Only complete, fsynced, validated ranges are committed. Drop an interrupted
                    // final range rather than treating extra crash-left bytes as verified progress.
                    val checkpoint = minOf(task.downloaded, partial.length(), descriptor.sizeBytes)
                    RandomAccessFile(partial, "rw").use { it.setLength(checkpoint) }
                    task = edit(id) { it.copy(downloaded = checkpoint) }!!
                    if (directory.usableSpace < descriptor.sizeBytes - checkpoint + 16L * 1024 * 1024) {
                        throw HermesApiException(507, "insufficient space", errorCode = AppErrorCode.ARTIFACT_STORAGE_FULL.value)
                    }
                    var rangeBytes = 256 * 1024
                    while (partial.length() < descriptor.sizeBytes) {
                        currentCoroutineContext().ensureActive()
                        if (owner(task.deviceId) != task.owner) throw HermesApiException(401, "download owner changed")
                        val committed = task.downloaded
                        try {
                            videoRanges.arbiter.download(task.owner,
                                onYield = {
                                    RandomAccessFile(partial, "rw").use { it.setLength(committed) }
                                    publishProgress(id, committed)
                                },
                                waiting = { waiting ->
                                    synchronized(lock) {
                                        _tasks.value = _tasks.value.map { if (it.id == id && it.status == ArtifactDownloadStatus.RUNNING) it.copy(waitingForPlayback = waiting) else it }
                                    }
                                }) {
                                videoRanges.checkOwner(task.owner, task.deviceId)
                                val cached = videoRanges.cached(task.owner, task.remotePath, descriptor, partial.length())
                                if (descriptor.maxConcurrentRanges > 1) {
                                    val started = android.os.SystemClock.elapsedRealtime()
                                    var lastProgress = 0L
                                    batches.append(descriptor, partial, task.deviceId, task.owner, task.remotePath, rangeBytes) { received ->
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastProgress >= 500) {
                                            lastProgress = now
                                            notify(task.copy(downloaded = received)); publishProgress(id, received)
                                        }
                                    }
                                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                                    rangeBytes = when {
                                        elapsed < 1_500 -> (rangeBytes * 2).coerceAtMost(descriptor.maxRangeBytes.coerceAtMost(1024 * 1024))
                                        elapsed > 3_000 -> (rangeBytes / 2).coerceAtLeast(256 * 1024)
                                        else -> rangeBytes
                                    }
                                } else if (cached != null) {
                                    java.io.FileOutputStream(partial, true).use { it.write(cached); it.fd.sync() }
                                } else {
                                    var lastProgress = 0L
                                    rest.downloadArtifactRange(descriptor, partial, task.deviceId, task.owner) { received ->
                                        if (System.currentTimeMillis() - lastProgress > 500) {
                                            lastProgress = System.currentTimeMillis()
                                            notify(task.copy(downloaded = received))
                                            publishProgress(id, received)
                                        }
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            RandomAccessFile(partial, "rw").use { it.setLength(committed) }
                            if (error is HermesApiException && error.errorCode == AppErrorCode.ARTIFACT_REFERENCE_EXPIRED.value) {
                                descriptor = rest.prepareArtifactDownload(task.remotePath, task.deviceId, task.owner)
                                if (descriptor.etag != task.etag || descriptor.sizeBytes != task.sizeBytes) {
                                    throw HermesApiException(412, "source changed", errorCode = AppErrorCode.ARTIFACT_CHANGED.value)
                                }
                                continue
                            }
                            throw error
                        }
                        task = edit(id) { it.copy(downloaded = partial.length(), progressBytes = null) }!!
                        if (descriptor.contentType.startsWith("video/")) {
                            videoRanges.publish(task.owner, task.remotePath, descriptor, partial, committed)
                        }
                        notify(task)
                    }
                    currentCoroutineContext().ensureActive()
                    if (owner(task.deviceId) != task.owner) throw HermesApiException(401, "download owner changed")
                    check(partial.renameTo(target)) { "download finalization failed" }
                    task = edit(id) { it.copy(status = ArtifactDownloadStatus.COMPLETE, downloaded = descriptor.sizeBytes) }!!
                    notify(task)
                    false
                } catch (cancelled: CancellationException) {
                    edit(id) { if (it.status == ArtifactDownloadStatus.RUNNING) it.copy(status = ArtifactDownloadStatus.WAITING, downloaded = task.downloaded, waitingForPlayback = false) else it }
                    throw cancelled
                } catch (error: Exception) {
                    // Closing an OkHttp call during cancellation can throw IOException rather
                    // than CancellationException. Never turn an explicit Pause/Cancel into retry.
                    if (!currentCoroutineContext().isActive) {
                        edit(id) { if (it.status == ArtifactDownloadStatus.RUNNING) it.copy(status = ArtifactDownloadStatus.WAITING, downloaded = task.downloaded, waitingForPlayback = false) else it }
                        currentCoroutineContext().ensureActive()
                    }
                    val full = error is java.io.IOException && directory.usableSpace < 1024 * 1024
                    val failure = artifactDownloadError(if (full) HermesApiException(507, "storage full", errorCode = "HR-FILE-014") else error)
                    task = edit(id) {
                        if (it.status in setOf(ArtifactDownloadStatus.PAUSED, ArtifactDownloadStatus.CANCELLED)) it
                        else it.copy(status = if (failure.retryable) ArtifactDownloadStatus.WAITING else ArtifactDownloadStatus.FAILED,
                            errorCode = failure.code.value, downloaded = task.downloaded, waitingForPlayback = false)
                    } ?: task
                    notify(task)
                    failure.retryable && task.status == ArtifactDownloadStatus.WAITING
                }
            }
            synchronized(lock) {
                if (_tasks.value.find { it.id == id }?.status !in listOf(ArtifactDownloadStatus.QUEUED, ArtifactDownloadStatus.WAITING, ArtifactDownloadStatus.RUNNING)) {
                    work.cancel()
                } else { jobs[id] = work; work.start() }
            }
            try { work.await() }
            finally { synchronized(lock) { jobs.remove(id) } }
        }
    }

    private fun target(task: ArtifactDownloadTask): File {
        val name = task.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(100).ifBlank { "attachment" }
        return File(directory, "${task.id}-$name")
    }
    private fun partial(task: ArtifactDownloadTask) = File(directory, "${target(task).name}.part")
    private fun put(task: ArtifactDownloadTask) {
        store.put(task)
        _tasks.value = _tasks.value.filterNot { it.id == task.id } + task
    }
    private fun edit(id: String, change: (ArtifactDownloadTask) -> ArtifactDownloadTask): ArtifactDownloadTask? = synchronized(lock) {
        val current = _tasks.value.find { it.id == id } ?: return@synchronized null
        change(current).also(::put)
    }
    private fun publishProgress(id: String, bytes: Long) = synchronized(lock) {
        _tasks.value = _tasks.value.map { if (it.id == id && it.status == ArtifactDownloadStatus.RUNNING) it.copy(progressBytes = bytes) else it }
    }
    companion object {
        fun taskId(owner: String, remotePath: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$owner\u0000$remotePath".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
