package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.modelRecentsDataStore by preferencesDataStore(name = "model_recents")

/** How many chips the 快捷切换 row remembers. Beyond this the oldest entry falls off. */
const val MODEL_RECENTS_LIMIT = 5

/**
 * Move [key] to the front of [current], de-duplicating and dropping anything past
 * [MODEL_RECENTS_LIMIT]. Pure, so the ordering rule is testable without a DataStore — the same
 * split `RecentSearchesStore` makes for the same reason.
 */
fun pushRecentModel(current: List<String>, key: String): List<String> =
    (listOf(key) + current.filterNot { it == key }).take(MODEL_RECENTS_LIMIT)

/**
 * Device-local, global (not per-profile) list of the models most recently switched to, newest
 * first — the source for the 快捷切换 chip row (docs/DESIGN.md §5.17).
 *
 * Sibling of [ModelFavoritesStore] and keyed the same way, but a LIST rather than a set: the whole
 * point of this store is the order, so it cannot reuse `stringSetPreferencesKey` the way favourites
 * do. The list persists as one string joined on `U+0001`, one code point over from the `U+0000`
 * inside [favKey] — same reasoning, one level up, so the two separators can never collide.
 *
 * Purely local: nothing here is sent upstream, so recording a switch cannot fail and needs no
 * error code.
 */
class ModelRecentsStore(private val context: Context) {
    private val key = stringPreferencesKey("recents")

    val recents: Flow<List<String>> = context.modelRecentsDataStore.data.map { prefs ->
        decode(prefs[key])
    }

    /** Move (provider, model) to the front, de-duplicating, and drop anything past the limit. */
    suspend fun record(provider: String, model: String) {
        if (provider.isBlank() || model.isBlank()) return
        val k = favKey(provider, model)
        context.modelRecentsDataStore.edit { prefs ->
            prefs[key] = pushRecentModel(decode(prefs[key]), k).joinToString(SEPARATOR)
        }
    }

    private fun decode(raw: String?): List<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    private companion object {
        const val SEPARATOR = "\u0001"
    }
}
