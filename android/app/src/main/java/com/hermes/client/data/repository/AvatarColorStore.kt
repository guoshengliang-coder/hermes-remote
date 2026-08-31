package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.avatarColorDataStore by preferencesDataStore(name = "avatar_colors")

/**
 * User-chosen avatar colours per profile (profile name → ARGB), device-local. Scope is the
 * AVATAR ONLY — chrome stays on the brand palette. Absent entries fall back to the hashed
 * auto colour. Every offered swatch shares the avatar lightness (0.32), so white initials
 * stay legible on any pick without adaptive on-colour machinery.
 */
class AvatarColorStore(private val context: Context) {
    val overrides: Flow<Map<String, Int>> = context.avatarColorDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs.asMap().mapNotNull { (k, v) -> (v as? Int)?.let { k.name to it } }.toMap() }

    suspend fun setColor(profile: String, argb: Int) {
        context.avatarColorDataStore.edit { it[intPreferencesKey(profile)] = argb }
    }

    /** Revert [profile] to the automatic (name-hashed) colour. */
    suspend fun clear(profile: String) {
        context.avatarColorDataStore.edit { it.remove(intPreferencesKey(profile)) }
    }
}
