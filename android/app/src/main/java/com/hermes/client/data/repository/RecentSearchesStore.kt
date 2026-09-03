package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** Upper bound of the recent-searches list per profile (docs/DESIGN.md §5.2 搜索页). */
const val RECENT_SEARCHES_MAX = 8

/**
 * Pure list rule: a pushed query goes to the front, an equal earlier entry (case-insensitive,
 * trimmed) is removed, and the list is capped at [max]. Blank queries are ignored.
 */
fun pushRecentSearch(current: List<String>, query: String, max: Int = RECENT_SEARCHES_MAX): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return current
    return (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(max)
}

fun removeRecentSearch(current: List<String>, query: String): List<String> =
    current.filterNot { it.equals(query.trim(), ignoreCase = true) }

private val Context.recentSearchesDataStore by preferencesDataStore(name = "recent_searches")

/**
 * Device-local recent search queries, kept per profile (the search scope is the active profile,
 * so the history follows it). Stored as a JSON array under a per-profile key.
 */
class RecentSearchesStore(private val context: Context) {
    private val json = Json
    private val serializer = ListSerializer(String.serializer())

    private fun key(profile: String?) = stringPreferencesKey("recent:" + profile.orEmpty().ifBlank { "default" })

    private val prefs = context.recentSearchesDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    fun recent(profile: String?): Flow<List<String>> = prefs.map { p ->
        p[key(profile)]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    suspend fun push(profile: String?, query: String) = update(profile) { pushRecentSearch(it, query) }

    suspend fun remove(profile: String?, query: String) = update(profile) { removeRecentSearch(it, query) }

    suspend fun clear(profile: String?) {
        context.recentSearchesDataStore.edit { it.remove(key(profile)) }
    }

    private suspend fun update(profile: String?, transform: (List<String>) -> List<String>) {
        context.recentSearchesDataStore.edit { p ->
            val k = key(profile)
            val current = p[k]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
            val next = transform(current)
            if (next.isEmpty()) p.remove(k) else p[k] = json.encodeToString(serializer, next)
        }
    }
}
