package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.hermes.client.ui.localization.AppLanguage

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** Device-local app preferences: theme mode and tool-call display verbosity. */
class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val toolDisplayKey = stringPreferencesKey("tool_call_display") // "product" | "technical"
    private val debugLoggingKey = booleanPreferencesKey("debug_logging")
    private val languageKey = stringPreferencesKey("app_language")
    // Usage page window. A viewing preference, so it stays on the device and is never synced
    // or scoped per profile (DESIGN.md §5.14).
    private val usageRangeKey = stringPreferencesKey("usage_range_days")

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }

    val appLanguage: Flow<AppLanguage> = context.settingsDataStore.data.map { prefs ->
        runCatching { AppLanguage.valueOf(prefs[languageKey] ?: "ZH") }.getOrDefault(AppLanguage.ZH)
    }

    suspend fun setAppLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { it[languageKey] = language.name }
    }

    /** True = show full tool input/output (Technical); false = hide payloads (Product). */
    val toolCallTechnical: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        // Consumer chat apps keep implementation payloads out of the conversation by default.
        // Users who are debugging can still opt into Technical mode from Appearance.
        (prefs[toolDisplayKey] ?: "product") == "technical"
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setToolCallTechnical(technical: Boolean) {
        context.settingsDataStore.edit { it[toolDisplayKey] = if (technical) "technical" else "product" }
    }

    /** Diagnostic logging toggle (Settings → Diagnostics). Off by default. */
    val debugLogging: Flow<Boolean> = context.settingsDataStore.data.map { it[debugLoggingKey] ?: false }

    val usageRangeDays: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[usageRangeKey]?.toIntOrNull()?.takeIf { it in USAGE_RANGE_CHOICES } ?: 30
    }

    suspend fun setUsageRangeDays(days: Int) {
        if (days !in USAGE_RANGE_CHOICES) return
        context.settingsDataStore.edit { it[usageRangeKey] = days.toString() }
    }

    suspend fun setDebugLogging(enabled: Boolean) {
        context.settingsDataStore.edit { it[debugLoggingKey] = enabled }
    }
}

/** The only windows the usage page offers. Upstream clamps `days` to 1-365 regardless. */
val USAGE_RANGE_CHOICES = listOf(7, 30, 90)
