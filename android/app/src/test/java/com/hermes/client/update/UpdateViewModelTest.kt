package com.hermes.client.update

import com.hermes.client.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule val main=MainDispatcherRule()
    private val cert="06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5"
    private val version=UpdateVersion("9.0.0",900,"com.hermes.remote","internal","2026-01-01T00:00:00Z","Hermes-Remote-9.0.0-debug.apk","https://mrlgs.net/releases/Hermes-Remote-9.0.0-debug.apk",3,"a".repeat(64),cert,26,listOf("note"),"abcdef1")
    private val index=UpdateIndex(1,"internal",900,"2026-01-01T00:00:00Z",listOf(version))

    @Test fun `refresh success and failure update state`()=runTest {
        val success=FakeRepository(index=index);val vm=UpdateViewModel(success);vm.refresh();advanceUntilIdle();assertEquals(900,vm.state.value.latestVersionCode);assertEquals(classifyVersion(version,com.hermes.client.BuildConfig.VERSION_CODE,com.hermes.client.BuildConfig.APPLICATION_ID,UPDATE_CHANNEL,cert,android.os.Build.VERSION.SDK_INT),vm.state.value.rows.single().eligibility)
        val failure=UpdateViewModel(FakeRepository(fetchError=IllegalStateException("offline")));failure.refresh();advanceUntilIdle();assertEquals("offline",failure.state.value.error)
    }
    @Test fun `enqueue failure becomes retryable failed state`()=runTest { val vm=UpdateViewModel(FakeRepository(enqueueError=IllegalStateException("queue unavailable")));vm.download(version);advanceUntilIdle();assertEquals(DownloadPhase.FAILED,vm.state.value.phase);assertEquals("queue unavailable",vm.state.value.error) }
    @Test fun `completed and recovered task verifies to installable`()=runTest { val file=File("verified.apk");val repo=FakeRepository(saved=7L to version,snapshots=mutableListOf(DownloadSnapshot(android.app.DownloadManager.STATUS_SUCCESSFUL,3,3,"file:///safe.apk",0)),verified=file);val vm=UpdateViewModel(repo);advanceUntilIdle();assertEquals(DownloadPhase.INSTALLABLE,vm.state.value.phase);assertEquals(file,vm.state.value.verifiedFile) }
    @Test fun `failed download exposes friendly reason and can retry`()=runTest { val repo=FakeRepository(snapshots=mutableListOf(DownloadSnapshot(android.app.DownloadManager.STATUS_FAILED,0,3,null,android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE)));val vm=UpdateViewModel(repo);vm.download(version);advanceUntilIdle();assertEquals(DownloadPhase.FAILED,vm.state.value.phase);assertTrue(vm.state.value.error!!.contains("storage"));repo.snapshots.add(DownloadSnapshot(android.app.DownloadManager.STATUS_SUCCESSFUL,3,3,"file:///safe.apk",0));vm.download(version);advanceUntilIdle();assertEquals(DownloadPhase.INSTALLABLE,vm.state.value.phase) }

    @Test fun `switching A to B cancels old monitor and only B updates state`()=runTest {
        val a=version.copy(versionName="8.0.0",versionCode=800,fileName="Hermes-Remote-8.0.0-debug.apk",downloadUrl="https://mrlgs.net/releases/Hermes-Remote-8.0.0-debug.apk")
        val repo=FakeRepository(enqueueIds=mutableListOf(1,2),snapshotsById=mutableMapOf(
            1L to mutableListOf(DownloadSnapshot(android.app.DownloadManager.STATUS_RUNNING,1,10,null,0)),
            2L to mutableListOf(DownloadSnapshot(android.app.DownloadManager.STATUS_SUCCESSFUL,3,3,"file:///safe.apk",0)),
        ))
        val vm=UpdateViewModel(repo);vm.download(a);runCurrent();vm.download(version);advanceUntilIdle()
        assertEquals(900,vm.state.value.activeVersionCode);assertEquals(DownloadPhase.INSTALLABLE,vm.state.value.phase)
    }

    @Test fun `new download cancels recovered monitor`()=runTest {
        val recovered=version.copy(versionName="8.0.0",versionCode=800,fileName="Hermes-Remote-8.0.0-debug.apk",downloadUrl="https://mrlgs.net/releases/Hermes-Remote-8.0.0-debug.apk")
        val repo=FakeRepository(saved=1L to recovered,enqueueIds=mutableListOf(2),snapshotsById=mutableMapOf(
            1L to mutableListOf(DownloadSnapshot(android.app.DownloadManager.STATUS_RUNNING,1,10,null,0)),
            2L to mutableListOf(DownloadSnapshot(android.app.DownloadManager.STATUS_SUCCESSFUL,3,3,"file:///safe.apk",0)),
        ))
        val vm=UpdateViewModel(repo);runCurrent();vm.download(version);advanceUntilIdle()
        assertEquals(900,vm.state.value.activeVersionCode);assertEquals(DownloadPhase.INSTALLABLE,vm.state.value.phase)
    }

    @Test fun `structured install outcomes become retryable messages`()=runTest {
        val repo=FakeRepository(snapshots=mutableListOf(DownloadSnapshot(android.app.DownloadManager.STATUS_SUCCESSFUL,3,3,"file:///safe.apk",0)),installResult=InstallResult.PermissionRequired)
        val vm=UpdateViewModel(repo);vm.download(version);advanceUntilIdle();vm.install();assertTrue(vm.state.value.error!!.contains("permission"))
        repo.installResult=InstallResult.Failure("installer unavailable");vm.install();assertEquals("installer unavailable",vm.state.value.error)
    }

    private class FakeRepository(var index:UpdateIndex?=null,var fetchError:Throwable?=null,var enqueueError:Throwable?=null,var saved:Pair<Long,UpdateVersion>?=null,val snapshots:MutableList<DownloadSnapshot> = mutableListOf(),var verified:File=File("verified.apk"),val enqueueIds:MutableList<Long> = mutableListOf(),val snapshotsById:MutableMap<Long,MutableList<DownloadSnapshot>> = mutableMapOf(),var installResult:InstallResult=InstallResult.InstallerOpened):UpdateRepositoryContract {
        override suspend fun fetch()=fetchError?.let{throw it}?:requireNotNull(index)
        override suspend fun enqueue(version:UpdateVersion)=enqueueError?.let{throw it}?:if(enqueueIds.isEmpty())7L else enqueueIds.removeAt(0)
        override suspend fun saved()=saved
        override suspend fun query(id:Long)=snapshotsById[id]?.let { if(it.isEmpty()) null else it.removeAt(0) } ?: if(snapshots.isEmpty()) null else snapshots.removeAt(0)
        override suspend fun verify(version:UpdateVersion,localUri:String)=verified
        override fun install(file:File)=installResult
    }
}
