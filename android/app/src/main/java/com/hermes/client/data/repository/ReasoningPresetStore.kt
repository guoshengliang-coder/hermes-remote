package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.reasoningPresetDataStore by preferencesDataStore(name = "reasoning_presets")

/**
 * Device-local per-model reasoning-effort presets, keyed by [favKey]. Mirrors the Hermes
 * desktop client's model-presets store: the effort chosen for a model is remembered globally
 * and re-applied to the session whenever that model is selected — which is what makes effort
 * FEEL global while every write stays session-scoped.
 */
class ReasoningPresetStore(private val context: Context) {
    /** favKey(provider, model) → wire effort value (e.g. "high", "none"). */
    val presets: Flow<Map<String, String>> =
        context.reasoningPresetDataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                (value as? String)?.let { key.name to it }
            }.toMap()
        }

    suspend fun set(provider: String, model: String, effort: String) {
        context.reasoningPresetDataStore.edit { prefs ->
            prefs[stringPreferencesKey(favKey(provider, model))] = effort
        }
    }
}
