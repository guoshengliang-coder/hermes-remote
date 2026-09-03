package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.notifications.NotificationPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.notificationDataStore by preferencesDataStore(name = "notifications")

/**
 * Device-local notification preferences (master toggle + needs-you + run-finished + run-failed +
 * run-progress). Off by default; each category maps to one notification channel.
 */
class NotificationSettings(private val context: Context) {
    private val kEnabled = booleanPreferencesKey("enabled")
    private val kApprovals = booleanPreferencesKey("approvals")
    private val kRunFinished = booleanPreferencesKey("runFinished")
    private val kRunFailed = booleanPreferencesKey("runFailed")
    private val kRunProgress = booleanPreferencesKey("runProgress")
    private val kOnboardingSeen = booleanPreferencesKey("onboardingSeen")

    val prefs: Flow<NotificationPrefs> = context.notificationDataStore.data.map { p ->
        NotificationPrefs(
            enabled = p[kEnabled] ?: false,
            approvals = p[kApprovals] ?: true,
            runFinished = p[kRunFinished] ?: true,
            runFailed = p[kRunFailed] ?: true,
            runProgress = p[kRunProgress] ?: true,
            onboardingSeen = p[kOnboardingSeen] ?: false,
        )
    }

    suspend fun setEnabled(v: Boolean) = context.notificationDataStore.edit { it[kEnabled] = v }
    suspend fun setApprovals(v: Boolean) = context.notificationDataStore.edit { it[kApprovals] = v }
    suspend fun setRunFinished(v: Boolean) = context.notificationDataStore.edit { it[kRunFinished] = v }
    suspend fun setRunFailed(v: Boolean) = context.notificationDataStore.edit { it[kRunFailed] = v }
    suspend fun setRunProgress(v: Boolean) = context.notificationDataStore.edit { it[kRunProgress] = v }
    suspend fun setOnboardingSeen() = context.notificationDataStore.edit { it[kOnboardingSeen] = true }
}
