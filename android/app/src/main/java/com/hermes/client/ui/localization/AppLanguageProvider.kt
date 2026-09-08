package com.hermes.client.ui.localization

import com.hermes.client.data.repository.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Process-wide language source for non-Compose surfaces such as notifications and services.
 * Compose uses [LocalAppLanguage], but background work can run without an Activity or composition.
 */
@Singleton
class AppLanguageProvider @Inject constructor(
    settings: SettingsStore,
    scope: CoroutineScope,
) {
    private val preference: StateFlow<LanguagePreference> = settings.languagePreference.stateIn(
        scope,
        SharingStarted.Eagerly,
        LanguagePreference.SYSTEM,
    )

    val language: StateFlow<AppLanguage> = settings.appLanguage.stateIn(
        scope,
        SharingStarted.Eagerly,
        LanguagePreference.SYSTEM.resolve(),
    )

    /**
     * Resolved at the moment it is read, not when the flow last emitted. A language change in the
     * phone's own settings does not write to our DataStore, so nothing would re-emit here; a
     * notification posted after such a change would otherwise keep the old language until the
     * process died.
     */
    val current: AppLanguage get() = preference.value.resolve()
}
