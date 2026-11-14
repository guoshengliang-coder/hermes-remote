package com.hermes.client.data.feedback

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesFileProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The merged host manifest must preserve BOTH existing shares and the SDK's narrow camera grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeedbackFileProviderCompatibilityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val hostAuthority = "${app.packageName}.fileprovider"
    private val sdkAuthority = "${app.packageName}.missiongofeedback.fileprovider"

    @Test fun hostAndSdkKeepSeparateUnexportedProviders() {
        val host = app.packageManager.resolveContentProvider(hostAuthority, PackageManager.GET_META_DATA)
        val sdk = app.packageManager.resolveContentProvider(sdkAuthority, PackageManager.GET_META_DATA)
        assertNotNull(host)
        assertNotNull(sdk)
        assertEquals(HermesFileProvider::class.java.name, host!!.name)
        assertEquals(FileProvider::class.java.name, sdk!!.name)
        listOf(host, sdk).forEach {
            assertFalse(it.exported)
            assertTrue(it.grantUriPermissions)
        }
    }

    @Test fun existingHostShareUriStillReadsTheOriginalFile() {
        val file = File(app.cacheDir, "chat-files/provider-test.txt").apply {
            parentFile?.mkdirs()
            writeText("existing host share")
        }
        val uri = FileProvider.getUriForFile(app, hostAuthority, file)
        assertEquals(hostAuthority, uri.authority)
        assertEquals("existing host share", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
    }

    @Test fun sdkCameraUriWorksWithoutExposingOtherHostCacheFiles() {
        val capture = File(app.cacheDir, "missiongo-captures/provider-test.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val uri = FileProvider.getUriForFile(app, sdkAuthority, capture)
        assertEquals(sdkAuthority, uri.authority)
        assertEquals(3, app.contentResolver.openInputStream(uri)!!.use { it.readBytes().size })
        val unrelated = File(app.cacheDir, "host-only.txt").apply { writeText("private host cache") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(app, sdkAuthority, unrelated)
        }
    }
}
