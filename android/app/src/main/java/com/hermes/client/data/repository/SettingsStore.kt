package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LanguagePreference
import com.hermes.client.ui.localization.resolve

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** Device-local app preferences: theme mode and tool-call display verbosity. */
class SettingsStore(
    private val context: Context,
    /**
     * Default for the diagnostic-logging toggle when the user has never touched it.
     *
     * True in debug builds, which is what every APK handed to a tester currently is
     * (`scripts/package-debug-apk.sh` → `Hermes-Remote-<version>-debug.apk`). A stall like HG-27
     * is only diagnosable if capture was already running when it happened, and asking the user to
     * have switched it on beforehand means the first occurrence is always lost. Release builds
     * keep the off-by-default privacy decision (DESIGN.md §5.15).
     *
     * Injectable so both defaults can be tested from one variant.
     */
    private val debugLoggingDefault: Boolean = BuildConfig.DEBUG,
) {
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

    /**
     * What the user picked. An install that has never opened the language screen has no key, and
     * follows the phone from here on — there is no migration, so a Chinese phone keeps the Chinese
     * it already had and an English phone stops being shown Chinese it never asked for (HG-17).
     * An install that DID pick a language keeps that choice: "ZH" and "EN" are still valid names.
     */
    val languagePreference: Flow<LanguagePreference> = context.settingsDataStore.data.map { prefs ->
        runCatching { LanguagePreference.valueOf(prefs[languageKey] ?: "SYSTEM") }
            .getOrDefault(LanguagePreference.SYSTEM)
    }

    /** The preference resolved against the phone's locale — the value the UI draws with. */
    val appLanguage: Flow<AppLanguage> = languagePreference.map { it.resolve() }

    suspend fun setAppLanguage(preference: LanguagePreference) {
        context.settingsDataStore.edit { it[languageKey] = preference.name }
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

    /**
     * Diagnostic logging toggle (Settings → Diagnostics). Defaults to [debugLoggingDefault]; an
     * explicit choice by the user always wins over it, in both directions.
     */
    val debugLogging: Flow<Boolean> =
        context.settingsDataStore.data.map { resolveDebugLogging(it[debugLoggingKey], debugLoggingDefault) }

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

/**
 * Whether diagnostic capture runs, given what the user stored and what this build defaults to.
 *
 * Separated out because the mistake it guards against is easy to make and invisible once made:
 * an explicit choice must win over the default in BOTH directions, so capture switched off in a
 * debug build has to stay off across restarts rather than being turned back on by the default.
 */
internal fun resolveDebugLogging(stored: Boolean?, default: Boolean): Boolean = stored ?: default
