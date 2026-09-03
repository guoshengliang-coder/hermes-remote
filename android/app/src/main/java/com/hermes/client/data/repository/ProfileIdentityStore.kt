package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/** The pre-identity store: profile name → ARGB only. Kept so its contents migrate exactly once. */
private val Context.legacyAvatarColorDataStore by preferencesDataStore(name = "avatar_colors")

private val Context.profileIdentityDataStore by preferencesDataStore(
    name = "profile_identity",
    produceMigrations = { ctx -> listOf(LegacyAvatarColorMigration(ctx.legacyAvatarColorDataStore)) },
)

/**
 * Per-profile [ProfileIdentity] records, device-local (DataStore). One flat Preferences file:
 * `<field>:<profile>` keys, so a profile's record is the set of keys sharing its suffix. Photo
 * bytes live as files under [avatarDir]; the store only names them.
 */
class ProfileIdentityStore(
    private val store: DataStore<Preferences>,
    val avatarDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val identities: Flow<Map<String, ProfileIdentity>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decodeIdentities(it) }

    suspend fun get(profile: String): ProfileIdentity = identities.first()[profile] ?: ProfileIdentity.DEFAULT

    /** Writes the whole record (stamping [ProfileIdentity.updatedAt]); a default record clears it. */
    suspend fun save(profile: String, identity: ProfileIdentity) {
        store.edit { prefs ->
            clearRecord(prefs, profile)
            if (!identity.isDefault) writeRecord(prefs, profile, identity.copy(updatedAt = clock()))
        }
    }

    suspend fun reset(profile: String) {
        store.edit { clearRecord(it, profile) }
    }

    companion object {
        fun create(context: Context): ProfileIdentityStore =
            ProfileIdentityStore(context.profileIdentityDataStore, File(context.filesDir, "avatars"))

        private const val FIELD_NAME = "name"
        private const val FIELD_AVATAR = "avatar"
        private const val FIELD_COLOR = "color"
        private const val FIELD_STYLE = "style"
        private const val FIELD_UPDATED = "updated"

        internal fun colorKey(profile: String) = intPreferencesKey("$FIELD_COLOR:$profile")

        private fun writeRecord(prefs: MutablePreferences, profile: String, identity: ProfileIdentity) {
            identity.displayName?.let { prefs[stringPreferencesKey("$FIELD_NAME:$profile")] = it }
            identity.avatarFile?.let { prefs[stringPreferencesKey("$FIELD_AVATAR:$profile")] = it }
            identity.colorArgb?.let { prefs[colorKey(profile)] = it }
            if (identity.style != AvatarStyle.SOLID) prefs[stringPreferencesKey("$FIELD_STYLE:$profile")] = identity.style.name
            prefs[longPreferencesKey("$FIELD_UPDATED:$profile")] = identity.updatedAt
        }

        private fun clearRecord(prefs: MutablePreferences, profile: String) {
            prefs.remove(stringPreferencesKey("$FIELD_NAME:$profile"))
            prefs.remove(stringPreferencesKey("$FIELD_AVATAR:$profile"))
            prefs.remove(colorKey(profile))
            prefs.remove(stringPreferencesKey("$FIELD_STYLE:$profile"))
            prefs.remove(longPreferencesKey("$FIELD_UPDATED:$profile"))
        }

        internal fun decodeIdentities(prefs: Preferences): Map<String, ProfileIdentity> {
            val out = HashMap<String, ProfileIdentity>()
            for ((key, value) in prefs.asMap()) {
                val sep = key.name.indexOf(':')
                if (sep <= 0) continue
                val field = key.name.substring(0, sep)
                val profile = key.name.substring(sep + 1)
                if (profile.isEmpty()) continue
                val current = out[profile] ?: ProfileIdentity.DEFAULT
                out[profile] = when (field) {
                    FIELD_NAME -> current.copy(displayName = value as? String)
                    FIELD_AVATAR -> current.copy(avatarFile = value as? String)
                    FIELD_COLOR -> current.copy(colorArgb = value as? Int)
                    FIELD_STYLE -> current.copy(style = (value as? String)?.let { runCatching { AvatarStyle.valueOf(it) }.getOrNull() } ?: AvatarStyle.SOLID)
                    FIELD_UPDATED -> current.copy(updatedAt = value as? Long ?: 0L)
                    else -> continue
                }
            }
            return out
        }
    }
}

/**
 * Carries the old per-profile avatar colours into the identity store once, then empties the old
 * file. Runs inside DataStore's migration step, so it is applied before the first read.
 */
class LegacyAvatarColorMigration(private val legacy: DataStore<Preferences>) : DataMigration<Preferences> {
    private val done = booleanPreferencesKey("migrated:avatar_colors")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[done] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = runCatching { legacy.data.first() }.getOrDefault(emptyPreferences())
        val next = currentData.toMutablePreferences()
        for ((key, value) in old.asMap()) {
            val argb = value as? Int ?: continue
            val target = ProfileIdentityStore.colorKey(key.name)
            if (next[target] == null) next[target] = argb
        }
        next[done] = true
        return next.toPreferences()
    }

    override suspend fun cleanUp() {
        runCatching { legacy.edit { it.clear() } }
    }
}
