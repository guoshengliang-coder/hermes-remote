package com.hermes.client.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileIdentityStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = TestScope(UnconfinedTestDispatcher() + Job())

    private fun prefs(name: String, migrations: List<androidx.datastore.core.DataMigration<Preferences>> = emptyList()): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            migrations = migrations,
            scope = scope,
            produceFile = { File(tmp.root, "$name.preferences_pb") },
        )

    @Test fun save_and_read_round_trip_stamps_updatedAt() = scope.runTest {
        var now = 1_000L
        val store = ProfileIdentityStore(prefs("a"), File(tmp.root, "avatars"), clock = { now })
        store.save("work", ProfileIdentity(displayName = "工作台", avatarFile = "a.webp", colorArgb = 0xFF1F4B84.toInt(), style = AvatarStyle.OUTLINE))
        val got = store.get("work")
        assertEquals("工作台", got.displayName)
        assertEquals("a.webp", got.avatarFile)
        assertEquals(0xFF1F4B84.toInt(), got.colorArgb)
        assertEquals(AvatarStyle.OUTLINE, got.style)
        assertEquals(1_000L, got.updatedAt)
        // Other profiles are untouched and read as default.
        assertTrue(store.get("personal").isDefault)
    }

    @Test fun saving_a_default_record_clears_it_and_reset_removes_it() = scope.runTest {
        val store = ProfileIdentityStore(prefs("b"), File(tmp.root, "avatars"))
        store.save("work", ProfileIdentity(displayName = "x", colorArgb = 1))
        store.save("work", ProfileIdentity.DEFAULT)
        assertNull(store.identities.first()["work"])

        store.save("odos", ProfileIdentity(style = AvatarStyle.OUTLINE))
        store.reset("odos")
        assertNull(store.identities.first()["odos"])
    }

    // Colours chosen with the old avatar_colors store must survive the upgrade, exactly once.
    @Test fun legacy_avatar_colours_migrate_then_the_old_file_is_emptied() = scope.runTest {
        val legacy = prefs("avatar_colors")
        legacy.edit { it[intPreferencesKey("work")] = 0xFF1F8484.toInt(); it[intPreferencesKey("odos")] = 0xFF3D1F84.toInt() }

        val store = ProfileIdentityStore(prefs("profile_identity", listOf(LegacyAvatarColorMigration(legacy))), File(tmp.root, "avatars"))
        val all = store.identities.first()
        assertEquals(0xFF1F8484.toInt(), all["work"]?.colorArgb)
        assertEquals(0xFF3D1F84.toInt(), all["odos"]?.colorArgb)
        assertNull(all["work"]?.displayName)
        assertTrue(legacy.data.first().asMap().isEmpty())
    }

    @Test fun decode_ignores_foreign_and_malformed_keys() {
        val decoded = ProfileIdentityStore.decodeIdentities(
            androidx.datastore.preferences.core.preferencesOf(
                androidx.datastore.preferences.core.booleanPreferencesKey("migrated:avatar_colors") to true,
                androidx.datastore.preferences.core.stringPreferencesKey("name:work") to "工作台",
                androidx.datastore.preferences.core.stringPreferencesKey("style:work") to "BOGUS",
                androidx.datastore.preferences.core.stringPreferencesKey("nocolon") to "x",
            ),
        )
        assertEquals(setOf("work"), decoded.keys)
        assertEquals("工作台", decoded["work"]?.displayName)
        assertEquals(AvatarStyle.SOLID, decoded["work"]?.style)
    }
}
