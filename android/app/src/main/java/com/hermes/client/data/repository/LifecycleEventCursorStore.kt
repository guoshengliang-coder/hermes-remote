package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lifecycleEventDataStore by preferencesDataStore(name = "lifecycle_events")

interface LifecycleEventCursor {
    suspend fun read(): Long
    suspend fun write(value: Long)
}

/** Durable cursor: a process death may replay an event, but can never silently skip one. */
@Singleton
class LifecycleEventCursorStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LifecycleEventCursor {
    private val cursorKey = longPreferencesKey("relay_cursor")

    override suspend fun read(): Long =
        context.lifecycleEventDataStore.data.map { it[cursorKey] ?: 0L }.first()

    override suspend fun write(value: Long) {
        require(value >= 0) { "cursor must be non-negative" }
        context.lifecycleEventDataStore.edit { preferences ->
            val current = preferences[cursorKey] ?: 0L
            if (value > current) preferences[cursorKey] = value
        }
    }
}
