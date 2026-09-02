package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.projectPrefsDataStore by preferencesDataStore(name = "project_prefs")

/**
 * Device-local project preferences:
 * - [defaultProjectPath]: the gateway's launch directory, learned from the `info.cwd` of a
 *   `session.create` issued WITHOUT a cwd. Lets the derived project list fold sessions that live
 *   in that folder into the default project, and lets the picker move a session back to it.
 * - [introSeen]: ids of projects whose "sessions created here join <project>" snackbar was shown.
 * - [projectScope]: the project the Projects segment was left drilled into, restored on launch.
 */
class ProjectPrefsStore(private val context: Context) {
    private val defaultPathKey = stringPreferencesKey("default_project_path")
    private val introSeenKey = stringSetPreferencesKey("intro_seen")
    private val scopeKey = stringPreferencesKey("project_scope")

    private val prefs = context.projectPrefsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val defaultProjectPath: Flow<String?> = prefs.map { it[defaultPathKey]?.ifBlank { null } }
    val introSeen: Flow<Set<String>> = prefs.map { it[introSeenKey].orEmpty() }
    val projectScope: Flow<String?> = prefs.map { it[scopeKey]?.ifBlank { null } }

    suspend fun setDefaultProjectPath(path: String?) {
        context.projectPrefsDataStore.edit { p ->
            val clean = path?.trimEnd('/', '\\')?.ifBlank { null }
            if (clean == null) p.remove(defaultPathKey) else p[defaultPathKey] = clean
        }
    }

    suspend fun markIntroSeen(projectId: String) {
        context.projectPrefsDataStore.edit { p -> p[introSeenKey] = p[introSeenKey].orEmpty() + projectId }
    }

    suspend fun setProjectScope(projectId: String?) {
        context.projectPrefsDataStore.edit { p ->
            if (projectId.isNullOrBlank()) p.remove(scopeKey) else p[scopeKey] = projectId
        }
    }
}
