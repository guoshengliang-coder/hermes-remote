package com.hermes.client.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectPrefsStoreTest {
    @Test fun root_default_survives_preference_storage_and_a_new_reader() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ProjectPrefsStore(context)
        store.markIntroSeen("existing-project")
        store.setDefaultProjectPath("/")
        assertEquals("/", ProjectPrefsStore(context).defaultProjectPath.first())
        store.setDefaultProjectPath("/Users/me/project///")
        assertEquals("/Users/me/project", store.defaultProjectPath.first())
        store.setDefaultProjectPath("   ")
        assertNull(store.defaultProjectPath.first())
        assertEquals(setOf("existing-project"), store.introSeen.first())
    }
}
