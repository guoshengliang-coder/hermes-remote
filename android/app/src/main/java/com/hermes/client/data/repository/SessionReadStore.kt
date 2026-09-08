package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionReadDataStore by preferencesDataStore(name = "session_read_state")

/** Device-local unread markers, keyed by Mac/profile/session so they survive process restarts. */
class SessionReadStore(private val context: Context) {
    private val unreadKey = stringSetPreferencesKey("unread_sessions")

    val unread: Flow<Set<String>> = context.sessionReadDataStore.data.map { prefs ->
        prefs[unreadKey].orEmpty()
    }

    suspend fun markUnread(token: String) {
        context.sessionReadDataStore.edit { prefs ->
            prefs[unreadKey] = prefs[unreadKey].orEmpty() + token
        }
    }

    suspend fun markRead(token: String) {
        context.sessionReadDataStore.edit { prefs ->
            prefs[unreadKey] = prefs[unreadKey].orEmpty() - token
        }
    }

    companion object {
        fun token(profile: String?, sessionId: String, deviceId: String? = null): String {
            val legacy = "${profile?.ifBlank { "default" } ?: "default"}/$sessionId"
            return deviceId?.takeIf { it.isNotBlank() }?.let { "device:${it.length}:$it/$legacy" } ?: legacy
        }
    }
}
