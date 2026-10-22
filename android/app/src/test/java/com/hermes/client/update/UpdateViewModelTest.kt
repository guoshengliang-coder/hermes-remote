package com.hermes.client.update

import com.hermes.client.MainDispatcherRule
import com.hermes.client.data.error.AppErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val cert = "06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5"
    private val version = version(900, "9.0.0")
    private val older = version(800, "8.0.0")
    private val index = UpdateIndex(1, "internal", 900, "2026-01-01T00:00:00Z", listOf(version, older))
    private val empty = UpdateIndex(1, "internal", 0, "2026-01-01T00:00:00Z", emptyList())

    private fun version(code: Int, name: String) = UpdateVersion(
        name, code, "com.hermes.remote", "internal", "2026-01-01T00:00:00Z",
        "Hermes-Remote-$name-debug.apk", "https://mrlgs.net/releases/Hermes-Remote-$name-debug.apk",
        3, "a".repeat(64), cert, 26, listOf("note"), "abcdef1",
    )

    private fun snapshot(status: UpdateDownloadStatus, downloaded: Long = 1, total: Long = 10, errorCode: String? = null) =
        UpdateDownloadSnapshot(status, downloaded, total, errorCode)

    private val done = snapshot(UpdateDownloadStatus.COMPLETE, 3, 3)

    // ---- page entry -------------------------------------------------------------------------

    // The two jobs are independent on purpose: a restored download is local state and must render
    // even when the network check fails, and an empty index must not erase it either.
    @Test fun `entry resumes a saved download even when the index check fails`() = runTest {
        val repo = FakeRepository(fetchError = IllegalStateException("offline"), saved = version, snapshots = mutableListOf(done))
        val vm = UpdateViewModel(repo) { 1_000L }
        vm.onOpen(); advanceUntilIdle()
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertEquals(version, vm.state.value.task?.version)
        assertEquals(File("verified.apk"), vm.state.value.task?.verifiedFile)
        assertEquals(AppErrorCode.UPDATE_CHECK_FAILED, vm.state.value.checkError?.code)
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.taskError)
    }

    @Test fun `entry resumes a saved download when the index has no versions`() = runTest {
        val repo = FakeRepository(index = empty, saved = version, snapshots = mutableListOf(done))
        val vm = UpdateViewModel(repo) { 1_000L }
        vm.onOpen(); advanceUntilIdle()
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertNull(vm.state.value.latest)
        assertTrue(vm.state.value.checkedOnce)
        assertEquals(1_000L, vm.state.value.lastCheckedAtMs)
    }

    @Test fun `a healed saved record leaves no task and no error`() = runTest {
        val repo = FakeRepository(index = index, saved = null)
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        assertNull(vm.state.value.task)
        assertNull(vm.state.value.taskError)
    }

    @Test fun `check splits the manifest latest release from read-only history`() = runTest {
        val vm = UpdateViewModel(FakeRepository(index = index)) { 5L }
        vm.onOpen(); advanceUntilIdle()
        assertEquals(900, vm.state.value.latest?.version?.versionCode)
        assertEquals(listOf(800), vm.state.value.history.map { it.version.versionCode })
        assertEquals(2, vm.state.value.rows.size)
        assertFalse(vm.state.value.checking)
        assertEquals(5L, vm.state.value.lastCheckedAtMs)
    }

    @Test fun `a later check failure keeps the previously loaded rows`() = runTest {
        val repo = FakeRepository(index = index)
        val vm = UpdateViewModel(repo) { 5L }
        vm.onOpen(); advanceUntilIdle()
        repo.fetchError = IllegalStateException("offline")
        vm.check(); advanceUntilIdle()
        assertEquals(900, vm.state.value.latest?.version?.versionCode)
        assertEquals(AppErrorCode.UPDATE_CHECK_FAILED, vm.state.value.checkError?.code)
    }

    // ---- starting a download ----------------------------------------------------------------

    @Test fun `only the manifest latest version is installable from the page`() = runTest {
        val repo = FakeRepository(index = index, snapshots = mutableListOf(done))
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(older); advanceUntilIdle()
        assertEquals(0, repo.enqueued.size)
        assertNull(vm.state.value.task)
        vm.download(version); advanceUntilIdle()
        assertEquals(listOf(900), repo.enqueued.map { it.versionCode })
    }

    @Test fun `an incompatible manifest latest cannot be downloaded`() = runTest {
        val repo = FakeRepository(index = index, snapshots = mutableListOf(done))
        val vm = UpdateViewModel(repo, { 1L }, deviceSdk = 25)
        vm.onOpen(); advanceUntilIdle()
        assertEquals(VersionEligibility.INCOMPATIBLE, vm.state.value.latest?.eligibility)

        vm.download(version); advanceUntilIdle()

        assertTrue(repo.enqueued.isEmpty())
        assertNull(vm.state.value.task)
    }

    // Without a synchronous busy state the second tap lands while the state still reads IDLE and
    // the engine ends up with two transfers for the same APK.
    @Test fun `a second tap during start cannot begin a second transfer`() = runTest {
        val enqueueGate = CompletableDeferred<Unit>()
        val repo = FakeRepository(index = index, snapshots = mutableListOf(done), enqueueGate = enqueueGate)
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version)
        assertEquals(DownloadPhase.ENQUEUING, vm.state.value.task?.phase)
        vm.download(version)
        vm.download(older)
        assertEquals(1, repo.enqueued.size)
        enqueueGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.enqueued.size)
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
    }

    @Test fun `enqueue failure is a retryable start failure`() = runTest {
        val repo = FakeRepository(index = index, enqueueError = IllegalStateException("queue unavailable"))
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(DownloadPhase.FAILED, vm.state.value.task?.phase)
        assertEquals(AppErrorCode.UPDATE_ENQUEUE_FAILED, vm.state.value.taskError?.code)
        assertTrue(vm.state.value.taskError!!.retryable)
    }

    // ---- running a download -----------------------------------------------------------------

    // A pause is the user's decision or an interrupted transfer, not a failure: the task and its
    // downloaded bytes stay, and resuming starts the transfer again from the checkpoint.
    @Test fun `a paused download keeps the task and resume continues from its bytes`() = runTest {
        val repo = FakeRepository(
            index = index,
            snapshots = mutableListOf(
                snapshot(UpdateDownloadStatus.PAUSED, downloaded = 4, total = 10),
                done,
            ),
        )
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(DownloadPhase.PAUSED, vm.state.value.task?.phase)
        assertEquals(4L, vm.state.value.task?.downloadedBytes)
        assertNull("a pause is not a failure", vm.state.value.taskError)

        vm.retry(); advanceUntilIdle()

        assertEquals(2, repo.enqueued.size)
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
    }

    @Test fun `download failure carries the shared error code and retry resumes`() = runTest {
        val repo = FakeRepository(
            index = index,
            snapshots = mutableListOf(snapshot(UpdateDownloadStatus.FAILED, errorCode = "HR-UPDATE-010")),
        )
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(DownloadPhase.FAILED, vm.state.value.task?.phase)
        assertEquals(AppErrorCode.UPDATE_STORAGE_FULL, vm.state.value.taskError?.code)
        assertTrue(vm.state.value.taskError!!.sanitizedDiagnostic().contains("HR-UPDATE-010"))

        repo.snapshots.add(done)
        vm.retry(); advanceUntilIdle()
        assertEquals(2, repo.enqueued.size)
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertNull(vm.state.value.taskError)
    }

    @Test fun `offline restored failure can retry without a loaded index`() = runTest {
        val repo = FakeRepository(
            fetchError = IllegalStateException("offline"),
            saved = version,
            snapshots = mutableListOf(
                snapshot(UpdateDownloadStatus.FAILED, errorCode = "HR-UPDATE-004"),
                done,
            ),
        )
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        assertNull(vm.state.value.latest)
        assertEquals(DownloadPhase.FAILED, vm.state.value.task?.phase)

        vm.retry(); advanceUntilIdle()

        assertEquals(listOf(900), repo.enqueued.map { it.versionCode })
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertNull(vm.state.value.taskError)
    }

    @Test fun `a lost download record is reported as a missing file`() = runTest {
        val vm = UpdateViewModel(FakeRepository(index = index)) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(AppErrorCode.UPDATE_FILE_MISSING, vm.state.value.taskError?.code)
    }

    @Test fun `cancel removes the task and its partial file`() = runTest {
        val repo = FakeRepository(index = index, snapshots = mutableListOf(snapshot(UpdateDownloadStatus.RUNNING)))
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); runCurrent()
        assertEquals(DownloadPhase.DOWNLOADING, vm.state.value.task?.phase)
        vm.cancel(); advanceUntilIdle()
        assertEquals(1, repo.cancelled)
        assertNull(vm.state.value.task)
        assertNull(vm.state.value.taskError)
    }

    @Test fun `cancel owns the slot until cleanup finishes and blocks a replacement download`() = runTest {
        val cancelGate = CompletableDeferred<Unit>()
        val repo = FakeRepository(
            index = index,
            snapshots = mutableListOf(snapshot(UpdateDownloadStatus.RUNNING)),
            cancelGate = cancelGate,
        )
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); runCurrent()

        vm.cancel(); runCurrent()
        assertEquals(DownloadPhase.CANCELLING, vm.state.value.task?.phase)
        vm.download(version)
        assertEquals(1, repo.enqueued.size)

        cancelGate.complete(Unit); advanceUntilIdle()
        assertEquals(1, repo.cancelled)
        assertNull(vm.state.value.task)
    }

    @Test fun `cancel failure restores the task and reports a cleanup error`() = runTest {
        val repo = FakeRepository(
            index = index,
            snapshots = mutableListOf(snapshot(UpdateDownloadStatus.RUNNING)),
            cancelError = IllegalStateException("preferences not cleared"),
        )
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); runCurrent()

        vm.cancel(); advanceUntilIdle()

        assertEquals(DownloadPhase.DOWNLOADING, vm.state.value.task?.phase)
        assertEquals(AppErrorCode.UPDATE_CLEANUP_FAILED, vm.state.value.taskError?.code)
    }

    @Test fun `download query exception becomes a retryable task failure`() = runTest {
        val repo = FakeRepository(index = index, queryError = IllegalStateException("download service unavailable"))
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()

        vm.download(version); advanceUntilIdle()

        assertEquals(DownloadPhase.FAILED, vm.state.value.task?.phase)
        assertEquals(AppErrorCode.UPDATE_DOWNLOAD_FAILED, vm.state.value.taskError?.code)
        assertEquals("download_query", vm.state.value.taskError?.stage)
    }

    @Test fun `verification failure blocks installation with its own code`() = runTest {
        val repo = FakeRepository(index = index, snapshots = mutableListOf(done), verifyError = IllegalStateException("sha mismatch"))
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(DownloadPhase.FAILED, vm.state.value.task?.phase)
        assertEquals(AppErrorCode.UPDATE_VERIFICATION_FAILED, vm.state.value.taskError?.code)
        assertNull(vm.state.value.task?.verifiedFile)
        vm.install()
        assertEquals(0, repo.installs)
    }

    @Test fun `a restored intermediate update cannot install after a newer latest is known`() = runTest {
        val repo = FakeRepository(index = index, saved = older, snapshots = mutableListOf(done))
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertTrue(vm.state.value.toString(), vm.state.value.taskIsSuperseded)

        vm.install()

        assertEquals(0, repo.installs)
    }

    @Test fun `install requires a verified file and maps installer outcomes`() = runTest {
        val repo = FakeRepository(index = index, snapshots = mutableListOf(done), installResult = InstallResult.PermissionRequired)
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        vm.install()
        assertEquals(AppErrorCode.INSTALL_PERMISSION_REQUIRED, vm.state.value.taskError?.code)
        repo.installResult = InstallResult.Failure("installer unavailable")
        vm.install()
        assertEquals(AppErrorCode.UPDATE_INSTALLER_FAILED, vm.state.value.taskError?.code)
        repo.installResult = InstallResult.InstallerOpened
        vm.install()
        assertNull(vm.state.value.taskError)
        assertEquals(3, repo.installs)
    }

    private class FakeRepository(
        var index: UpdateIndex? = null,
        var fetchError: Throwable? = null,
        var enqueueError: Throwable? = null,
        var verifyError: Throwable? = null,
        var saved: UpdateVersion? = null,
        val snapshots: MutableList<UpdateDownloadSnapshot> = mutableListOf(),
        val enqueueGate: CompletableDeferred<Unit>? = null,
        val cancelGate: CompletableDeferred<Unit>? = null,
        var cancelError: Throwable? = null,
        var queryError: Throwable? = null,
        var verified: File = File("verified.apk"),
        var installResult: InstallResult = InstallResult.InstallerOpened,
        var onDisk: Set<Int> = emptySet(),
        var exportResult: ExportResult = ExportResult.SavedToDownloads,
    ) : UpdateRepositoryContract {
        val enqueued = mutableListOf<UpdateVersion>()
        var cancelled = 0
        var installs = 0
        val pruneKeeps = mutableListOf<Set<String>>()
        val exports = mutableListOf<UpdateVersion>()
        private var nextId = 1L

        override suspend fun localApkVersions(candidates: List<UpdateVersion>) =
            candidates.map { it.versionCode }.filter { it in onDisk }.toSet()
        override suspend fun pruneDownloads(keepFileNames: Set<String>) { pruneKeeps += keepFileNames }
        override suspend fun exportApk(version: UpdateVersion): ExportResult { exports += version; return exportResult }

        override suspend fun fetch() = fetchError?.let { throw it } ?: requireNotNull(index)
        override suspend fun start(version: UpdateVersion) {
            enqueueError?.let { throw it }
            enqueued += version
            enqueueGate?.await()
        }
        override suspend fun saved() = saved
        override suspend fun query() = queryError?.let { throw it } ?: if (snapshots.isEmpty()) null else snapshots.removeAt(0)
        override suspend fun verify(version: UpdateVersion) = verifyError?.let { throw it } ?: verified
        override suspend fun cancel() {
            cancelGate?.await()
            cancelError?.let { throw it }
            cancelled++
        }
        override fun install(file: File): InstallResult { installs++; return installResult }
    }

    // ---- this batch: retention, rollback material, auto-install hand-off ---------------------

    @Test fun `check prunes to the newest five releases plus the active task file`() = runTest {
        val many = UpdateIndex(1, "internal", 907, "2026-01-01T00:00:00Z", (907 downTo 900).map { version(it, "9.0.$it") })
        val repo = FakeRepository(index = many, onDisk = setOf(905, 901))
        val vm = UpdateViewModel(repo) { 1L }
        vm.check(); advanceUntilIdle()
        assertEquals(
            (907 downTo 903).map { "Hermes-Remote-9.0.$it-debug.apk" }.toSet(),
            repo.pruneKeeps.single(),
        )
        assertEquals(setOf(905, 901), vm.state.value.apkOnDisk)
    }

    @Test fun `foreground completion auto-launches the installer exactly once`() = runTest {
        val repo = FakeRepository(index = index, snapshots = mutableListOf(done))
        val vm = UpdateViewModel(repo, { 1L }, 26)
        vm.setPageVisible(true)
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertEquals(true, vm.state.value.task?.autoInstallAttempted)
        assertEquals(1, repo.installs)
    }

    @Test fun `background completion notifies instead of launching the installer`() = runTest {
        val notified = mutableListOf<UpdateVersion>()
        val repo = FakeRepository(index = index, snapshots = mutableListOf(done))
        val vm = UpdateViewModel(repo, { 1L }, 26, notified::add)
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertEquals(0, repo.installs)
        assertEquals(listOf(version), notified)
    }

    @Test fun `export surfaces per-version feedback and can be dismissed`() = runTest {
        val repo = FakeRepository(index = index, exportResult = ExportResult.SavedToDownloads)
        val vm = UpdateViewModel(repo) { 1L }
        vm.export(older); advanceUntilIdle()
        assertEquals(older.versionCode to ExportResult.SavedToDownloads, vm.state.value.exportNotice)
        assertEquals(listOf(older), repo.exports)
        vm.dismissExportNotice()
        assertNull(vm.state.value.exportNotice)
    }
}
