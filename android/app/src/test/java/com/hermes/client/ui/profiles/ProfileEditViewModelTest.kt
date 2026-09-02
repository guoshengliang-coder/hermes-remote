package com.hermes.client.ui.profiles

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.media.AvatarPhotoImporter
import com.hermes.client.data.repository.AvatarStyle
import com.hermes.client.data.repository.ProfileIdentity
import com.hermes.client.data.repository.ProfileIdentityStore
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher + Job())
    private lateinit var avatarDir: File
    private lateinit var store: ProfileIdentityStore

    /** Writes a fake avatar file instead of decoding anything; `fail` makes it throw. */
    private inner class FakeImporter(private var fail: Boolean = false) : AvatarPhotoImporter(mockk<Context>(relaxed = true), File("/dev/null")) {
        var counter = 0
        fun failNext() { fail = true }
        override suspend fun import(uri: Uri, profile: String): Result<String> {
            if (fail) return Result.failure(IllegalStateException("decode failed"))
            avatarDir.mkdirs()
            val name = "avatar-$profile-${counter++}.webp"
            File(avatarDir, name).writeBytes(byteArrayOf(1, 2, 3))
            return Result.success(name)
        }
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        avatarDir = File(tmp.root, "avatars")
        store = ProfileIdentityStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "identity.preferences_pb") }),
            avatarDir,
        )
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(importer: AvatarPhotoImporter = FakeImporter()) =
        ProfileEditViewModel(SavedStateHandle(mapOf("profile" to "work")), store, importer, dispatcher)

    @Test fun loads_the_saved_record_and_starts_clean() = scope.runTest {
        store.save("work", ProfileIdentity(displayName = "工作台", style = AvatarStyle.OUTLINE))
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertEquals("工作台", vm.state.value.draft.displayName)
        assertEquals(AvatarStyle.OUTLINE, vm.state.value.draft.style)
        assertFalse(vm.state.value.dirty)
    }

    // Edits are a draft: nothing reaches the store until save, which then clears dirty.
    @Test fun edits_stay_draft_until_saved() = scope.runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.setDisplayName("  Rex ")
        vm.setHue(214f)
        assertTrue(vm.state.value.dirty)
        assertTrue(store.get("work").isDefault)

        var done: Boolean? = null
        vm.save { done = it }
        advanceUntilIdle()
        assertEquals(true, done)
        assertFalse(vm.state.value.dirty)
        val saved = store.get("work")
        assertEquals("Rex", saved.displayName)
        assertEquals(vm.state.value.draft.colorArgb, saved.colorArgb)
    }

    // A blank name is "no custom name": the record drops the field rather than storing "".
    @Test fun clearing_the_name_removes_it_and_reset_to_default_clears_the_record() = scope.runTest {
        store.save("work", ProfileIdentity(displayName = "Rex", colorArgb = 7))
        val vm = vm()
        advanceUntilIdle()
        vm.setDisplayName("")
        vm.save {}
        advanceUntilIdle()
        assertNull(store.get("work").displayName)
        assertEquals(7, store.get("work").colorArgb)

        vm.resetToDefault()
        assertTrue(vm.state.value.dirty)
        vm.save {}
        advanceUntilIdle()
        assertTrue(store.get("work").isDefault)
    }

    @Test fun picked_photo_previews_immediately_and_is_deleted_on_discard() = scope.runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.importPhoto(mockk(relaxed = true))
        advanceUntilIdle()
        val name = vm.state.value.draft.avatarFile!!
        assertTrue(File(avatarDir, name).exists())
        assertTrue(vm.state.value.dirty)

        vm.discard()
        advanceUntilIdle()
        assertFalse(File(avatarDir, name).exists())
    }

    @Test fun saving_a_new_photo_removes_the_previous_one() = scope.runTest {
        avatarDir.mkdirs()
        File(avatarDir, "old.webp").writeBytes(byteArrayOf(9))
        store.save("work", ProfileIdentity(avatarFile = "old.webp"))
        val vm = vm()
        advanceUntilIdle()
        vm.importPhoto(mockk(relaxed = true))
        advanceUntilIdle()
        val fresh = vm.state.value.draft.avatarFile!!
        vm.save {}
        advanceUntilIdle()
        assertEquals(fresh, store.get("work").avatarFile)
        assertTrue(File(avatarDir, fresh).exists())
        assertFalse(File(avatarDir, "old.webp").exists())
    }

    @Test fun a_failed_import_surfaces_the_media_error_code() = scope.runTest {
        val importer = FakeImporter()
        val vm = vm(importer)
        advanceUntilIdle()
        importer.failNext()
        vm.importPhoto(mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals(AppErrorCode.AVATAR_PHOTO_FAILED, vm.state.value.error?.code)
        assertEquals("HR-MEDIA-002", vm.state.value.error?.code?.value)
        assertTrue(vm.state.value.error!!.retryable)
        assertNull(vm.state.value.draft.avatarFile)
        assertFalse(vm.state.value.importing)
        vm.clearError()
        assertNull(vm.state.value.error)
    }
}
