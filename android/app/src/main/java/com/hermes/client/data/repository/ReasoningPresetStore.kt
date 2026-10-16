package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import com.hermes.client.data.sync.AccountModelPreferences

private val Context.reasoningPresetDataStore by preferencesDataStore(name = "reasoning_presets")

/**
 * Account-owned presets when supported, otherwise device-local, keyed by [favKey]. Mirrors Hermes
 * desktop client's model-presets store: the effort chosen for a model is remembered globally
 * and re-applied to the session whenever that model is selected — which is what makes effort
 * FEEL global while every write stays session-scoped.
 */
class ReasoningPresetStore(private val context: Context, val sync: AccountModelPreferences? = null) {
    /** favKey(provider, model) → wire effort value (e.g. "high", "none"). */
    val legacyPresets: Flow<Map<String, String>> =
        context.reasoningPresetDataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                (value as? String)?.let { key.name to it }
            }.toMap()
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val presets: Flow<Map<String, String>> = flow {
        val cloud = sync
        if (cloud == null) emitAll(legacyPresets) else {
            cloud.ensureBound()
            emitAll(cloud.state.flatMapLatest { state ->
                if (state.useCloud) flowOf(state.projected().mapNotNull { row -> row.reasoningPreset?.let { favKey(row.provider, row.model) to it } }.toMap())
                else legacyPresets
            })
        }
    }

    suspend fun set(provider: String, model: String, effort: String, confirmedOwner: String? = null) {
        sync?.let { cloud ->
            val expected = confirmedOwner ?: cloud.currentOwnerKey()
            cloud.ensureBound()
            if (cloud.currentOwnerKey() != expected) return
            if (cloud.state.value.useCloud) { cloud.setReasoning(provider, model, effort, expected); return }
        }
        context.reasoningPresetDataStore.edit { prefs ->
            prefs[stringPreferencesKey(favKey(provider, model))] = effort
        }
    }
}
