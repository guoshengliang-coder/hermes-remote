package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import com.hermes.client.data.sync.AccountModelPreferences

private val Context.modelFavoritesDataStore by preferencesDataStore(name = "model_favorites")

/**
 * Stable key for a (provider, model) pair. NUL (`\u0000`) separator: provider slugs and model
 * names can contain ':', '/', '(', ')', '.', '-', and spaces, but never NUL — so the key never
 * collides across different (provider, model) splits.
 */
fun favKey(provider: String, model: String): String = "$provider\u0000$model"

/** Account-owned when supported; the original device-global set remains the Legacy source. */
class ModelFavoritesStore(private val context: Context, val sync: AccountModelPreferences? = null, val instance: InstanceModelPreferences? = null) {
    private val key = stringSetPreferencesKey("favorites")

    val legacyFavorites: Flow<Set<String>> =
        context.modelFavoritesDataStore.data.map { it[key] ?: emptySet() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val originalFavorites: Flow<Set<String>> = flow {
        val cloud = sync
        if (cloud == null) emitAll(legacyFavorites) else {
            cloud.ensureBound()
            emitAll(cloud.state.flatMapLatest { state ->
                if (state.useCloud) flowOf(state.projected().filter { it.favorite }.map { favKey(it.provider, it.model) }.toSet())
                else legacyFavorites
            })
        }
    }

    val favorites: Flow<Set<String>> = instance?.let { remote ->
        combine(originalFavorites, remote.state) { original, state -> if (state.active) state.favorites else original }
    } ?: originalFavorites

    suspend fun toggle(provider: String, model: String, confirmedOwner: String? = null, instanceOwner: String? = null) {
        instance?.let { remote ->
            if (remote.state.value.active) {
                remote.set(provider, model, "favorite", favKey(provider, model) !in remote.state.value.favorites, expectedOwner = instanceOwner ?: remote.state.value.owner)
                return
            }
        }
        sync?.let { cloud ->
            val expected = confirmedOwner ?: cloud.currentOwnerKey()
            cloud.ensureBound()
            if (cloud.currentOwnerKey() != expected) return
            if (cloud.state.value.useCloud) {
                val before = cloud.state.value.projected().find { it.provider == provider && it.model == model }?.favorite == true
                cloud.setFavorite(provider, model, !before, expected)
                return
            }
        }
        val k = favKey(provider, model)
        context.modelFavoritesDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = if (k in cur) cur - k else cur + k
        }
    }
}
