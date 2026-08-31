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
    val language: StateFlow<AppLanguage> = settings.appLanguage.stateIn(
        scope,
        SharingStarted.Eagerly,
        AppLanguage.ZH,
    )

    val current: AppLanguage get() = language.value
}
