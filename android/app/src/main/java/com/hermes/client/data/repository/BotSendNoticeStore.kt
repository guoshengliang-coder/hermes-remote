package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.botSendNoticeDataStore by preferencesDataStore(name = "bot_send_notice")

/**
 * Which messaging channels this device has already been told about before sending into one of
 * their conversations.
 *
 * Per channel rather than once globally: the notice names the channel it is about, and a person
 * who acknowledged it for DingTalk has not been told anything about Slack. Someone with a single
 * channel therefore sees it exactly once, ever.
 */
class BotSendNoticeStore(private val context: Context) {
    private val key = stringSetPreferencesKey("acknowledged_sources")

    val acknowledged: Flow<Set<String>> = context.botSendNoticeDataStore.data
        // A corrupt or unreadable DataStore must not block sending; it only costs one extra
        // showing of a dialog the person can dismiss.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[key] ?: emptySet() }

    suspend fun acknowledge(source: String) {
        if (source.isBlank()) return
        context.botSendNoticeDataStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet()) + source
        }
    }
}
