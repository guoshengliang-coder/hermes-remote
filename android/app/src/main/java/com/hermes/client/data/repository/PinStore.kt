package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pinDataStore by preferencesDataStore(name = "pinned_sessions")

/**
 * Device-local pinned-session store. The gateway has no pin API, so pins live on the phone
 * only (they do not sync to the desktop app). Account-mode pins include the owning Mac so equal
 * profile/session IDs on different Macs cannot collide.
 */
class PinStore(private val context: Context) {
    private val key = stringSetPreferencesKey("pinned")

    val pinned: Flow<Set<String>> = context.pinDataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(token: String) {
        context.pinDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = if (token in cur) cur - token else cur + token
        }
    }

    companion object {
        fun token(profile: String?, sessionId: String, deviceId: String? = null): String {
            val legacy = "${profile ?: "default"}/$sessionId"
            return deviceId?.takeIf { it.isNotBlank() }?.let { "device:${it.length}:$it/$legacy" } ?: legacy
        }
    }
}
