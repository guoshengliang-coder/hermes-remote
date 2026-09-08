package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LanguagePreference
import com.hermes.client.ui.localization.resolve
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val rest: HermesRestApi,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
    val toolCallTechnical: StateFlow<Boolean> =
        settings.toolCallTechnical.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val appLanguage: StateFlow<AppLanguage> =
        settings.appLanguage.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LanguagePreference.SYSTEM.resolve(),
        )

    /** The radio selection on the language screen, which shows the choice rather than its result. */
    val languagePreference: StateFlow<LanguagePreference> =
        settings.languagePreference.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LanguagePreference.SYSTEM,
        )

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setToolCallTechnical(technical: Boolean) =
        viewModelScope.launch { settings.setToolCallTechnical(technical) }
    fun setAppLanguage(preference: LanguagePreference) =
        viewModelScope.launch { settings.setAppLanguage(preference) }

    suspend fun gatewayVersion(): String? = runCatching { rest.gatewayStatus().version }.getOrNull()
}
