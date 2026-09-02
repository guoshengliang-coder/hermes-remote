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

    private fun snapshot(status: Int, downloaded: Long = 1, total: Long = 10, uri: String? = null, reason: Int = 0) =
        DownloadSnapshot(status, downloaded, total, uri, reason)

    private val done = snapshot(android.app.DownloadManager.STATUS_SUCCESSFUL, 3, 3, "file:///safe.apk")

    // ---- page entry -------------------------------------------------------------------------

    // The two jobs are independent on purpose: a restored download is local state and must render
    // even when the network check fails, and an empty index must not erase it either.
    @Test fun `entry resumes a saved download even when the index check fails`() = runTest {
        val repo = FakeRepository(fetchError = IllegalStateException("offline"), saved = 7L to version, snapshots = mutableListOf(done))
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
        val repo = FakeRepository(index = empty, saved = 7L to version, snapshots = mutableListOf(done))
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
    // DownloadManager ends up with two jobs for the same APK.
    @Test fun `a second tap during enqueue cannot start a second job`() = runTest {
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

    @Test fun `paused downloads report why and keep polling until they resume`() = runTest {
        val repo = FakeRepository(
            index = index,
            snapshots = mutableListOf(
                snapshot(android.app.DownloadManager.STATUS_PAUSED, reason = android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK),
                snapshot(android.app.DownloadManager.STATUS_RUNNING, downloaded = 5, total = 10),
                done,
            ),
        )
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); runCurrent()
        assertEquals(DownloadPhase.PAUSED, vm.state.value.task?.phase)
        assertEquals(android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK, vm.state.value.task?.reason)
        assertNull("a pause is not a failure", vm.state.value.taskError)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(DownloadPhase.DOWNLOADING, vm.state.value.task?.phase)
        assertEquals(50, vm.state.value.task?.percent)
        advanceUntilIdle()
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
    }

    @Test fun `download failure reports the reason and retry restarts the job`() = runTest {
        val repo = FakeRepository(
            index = index,
            snapshots = mutableListOf(snapshot(android.app.DownloadManager.STATUS_FAILED, reason = android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE)),
        )
        val vm = UpdateViewModel(repo) { 1L }
        vm.onOpen(); advanceUntilIdle()
        vm.download(version); advanceUntilIdle()
        assertEquals(DownloadPhase.FAILED, vm.state.value.task?.phase)
        assertEquals(AppErrorCode.UPDATE_DOWNLOAD_FAILED, vm.state.value.taskError?.code)
        assertTrue(vm.state.value.taskError!!.sanitizedDiagnostic().contains("storage"))
        assertEquals(android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE, vm.state.value.task?.reason)

        repo.snapshots.add(done)
        vm.retry(); advanceUntilIdle()
        assertEquals(2, repo.enqueued.size)
        assertEquals(DownloadPhase.INSTALLABLE, vm.state.value.task?.phase)
        assertNull(vm.state.value.taskError)
    }

    @Test fun `offline restored failure can retry without a loaded index`() = runTest {
        val repo = FakeRepository(
            fetchError = IllegalStateException("offline"),
            saved = 7L to version,
            snapshots = mutableListOf(
                snapshot(android.app.DownloadManager.STATUS_FAILED, reason = android.app.DownloadManager.ERROR_CANNOT_RESUME),
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

    @Test fun `cancel removes the download manager job and the task`() = runTest {
        val repo = FakeRepository(index = index, snapshots = mutableListOf(snapshot(android.app.DownloadManager.STATUS_RUNNING)))
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
            snapshots = mutableListOf(snapshot(android.app.DownloadManager.STATUS_RUNNING)),
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
            snapshots = mutableListOf(snapshot(android.app.DownloadManager.STATUS_RUNNING)),
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
        val repo = FakeRepository(index = index, saved = 7L to older, snapshots = mutableListOf(done))
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
        var saved: Pair<Long, UpdateVersion>? = null,
        val snapshots: MutableList<DownloadSnapshot> = mutableListOf(),
        val enqueueGate: CompletableDeferred<Unit>? = null,
        val cancelGate: CompletableDeferred<Unit>? = null,
        var cancelError: Throwable? = null,
        var queryError: Throwable? = null,
        var verified: File = File("verified.apk"),
        var installResult: InstallResult = InstallResult.InstallerOpened,
    ) : UpdateRepositoryContract {
        val enqueued = mutableListOf<UpdateVersion>()
        var cancelled = 0
        var installs = 0
        private var nextId = 1L

        override suspend fun fetch() = fetchError?.let { throw it } ?: requireNotNull(index)
        override suspend fun enqueue(version: UpdateVersion): Long {
            enqueueError?.let { throw it }
            enqueued += version
            enqueueGate?.await()
            return nextId++
        }
        override suspend fun saved() = saved
        override suspend fun query(id: Long) = queryError?.let { throw it } ?: if (snapshots.isEmpty()) null else snapshots.removeAt(0)
        override suspend fun verify(version: UpdateVersion, localUri: String) = verifyError?.let { throw it } ?: verified
        override suspend fun cancel() {
            cancelGate?.await()
            cancelError?.let { throw it }
            cancelled++
        }
        override fun install(file: File): InstallResult { installs++; return installResult }
    }
}
