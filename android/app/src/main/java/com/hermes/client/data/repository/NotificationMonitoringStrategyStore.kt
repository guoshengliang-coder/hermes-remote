package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationStrategyDataStore by preferencesDataStore(name = "notification_strategy")

enum class NotificationMonitoringStrategy {
    ADAPTIVE,
    REALTIME,
    POWER_SAVING,
}

@Singleton
class NotificationMonitoringStrategyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("strategy")

    val strategy: Flow<NotificationMonitoringStrategy> = context.notificationStrategyDataStore.data.map { prefs ->
        prefs[key]?.let { saved ->
            NotificationMonitoringStrategy.entries.firstOrNull { it.name == saved }
        } ?: NotificationMonitoringStrategy.ADAPTIVE
    }

    suspend fun set(value: NotificationMonitoringStrategy) {
        context.notificationStrategyDataStore.edit { it[key] = value.name }
    }
}
