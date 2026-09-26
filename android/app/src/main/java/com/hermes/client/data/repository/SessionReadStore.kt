package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionReadDataStore by preferencesDataStore(name = "session_read_state")

/**
 * What [com.hermes.client.data.progress.SessionRuntimeStore] needs from the read markers.
 *
 * Narrow on purpose: the unread set is the authoritative "not yet seen" flag a restored terminal
 * verdict is checked against, and an interface lets a test supply one without an Android `Context`
 * and a DataStore (HG-138).
 */
interface SessionReadMarkers {
    val unread: Flow<Set<String>>
    suspend fun markUnread(token: String)
    suspend fun markRead(token: String)
}

/** Device-local unread markers, keyed by Mac/profile/session so they survive process restarts. */
class SessionReadStore(private val context: Context) : SessionReadMarkers {
    private val unreadKey = stringSetPreferencesKey("unread_sessions")
    private val knownKey = stringSetPreferencesKey("known_sessions")

    override val unread: Flow<Set<String>> = context.sessionReadDataStore.data.map { prefs ->
        prefs[unreadKey].orEmpty()
    }

    /**
     * Read tokens of every session the last successful list load returned, or null before any
     * list has ever loaded on this install. A push-woken process never loads the list, so without
     * this the launcher badge could not be counted there at all (HG-103); see [badgeCount].
     */
    val knownSessions: Flow<Set<String>?> = context.sessionReadDataStore.data.map { prefs ->
        prefs[knownKey]
    }

    suspend fun saveKnownSessions(tokens: Set<String>) {
        context.sessionReadDataStore.edit { prefs ->
            if (prefs[knownKey] != tokens) prefs[knownKey] = tokens
        }
    }

    override suspend fun markUnread(token: String) {
        context.sessionReadDataStore.edit { prefs ->
            prefs[unreadKey] = prefs[unreadKey].orEmpty() + token
        }
    }

    override suspend fun markRead(token: String) {
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
