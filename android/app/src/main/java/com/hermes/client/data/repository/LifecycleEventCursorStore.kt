package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lifecycleEventDataStore by preferencesDataStore(name = "lifecycle_events")

interface LifecycleEventCursor {
    suspend fun read(scope: String): Long
    suspend fun write(scope: String, value: Long)
}

internal fun lifecycleCursorPreferenceName(scope: String): String {
    require(scope.isNotBlank()) { "cursor scope must not be blank" }
    if (scope == com.hermes.client.data.network.LifecycleEventsSource.LEGACY_CURSOR_SCOPE) {
        return "relay_cursor"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "account_cursor_$digest"
}

/** Durable cursor: a process death may replay an event, but can never silently skip one. */
@Singleton
class LifecycleEventCursorStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LifecycleEventCursor {
    override suspend fun read(scope: String): Long {
        val cursorKey = longPreferencesKey(lifecycleCursorPreferenceName(scope))
        return context.lifecycleEventDataStore.data.map { it[cursorKey] ?: 0L }.first()
    }

    override suspend fun write(scope: String, value: Long) {
        require(value >= 0) { "cursor must be non-negative" }
        val cursorKey = longPreferencesKey(lifecycleCursorPreferenceName(scope))
        context.lifecycleEventDataStore.edit { preferences ->
            val current = preferences[cursorKey] ?: 0L
            if (value > current) preferences[cursorKey] = value
        }
    }
}
