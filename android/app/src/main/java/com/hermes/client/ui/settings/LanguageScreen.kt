package com.hermes.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.localization.LanguagePreference
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@Composable
fun LanguageScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val selected by vm.languagePreference.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "语言", "Language"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = localized(language, "返回", "Back"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Follow-system leads and is the default, the same shape the theme screen already has.
            // The other two are written in their own language: a reader looking for English does
            // not read 「英语」 to find it.
            LanguageRow(
                localized(language, "跟随系统", "Follow system"),
                LanguagePreference.SYSTEM,
                selected,
                vm::setAppLanguage,
            )
            LanguageRow("简体中文", LanguagePreference.ZH, selected, vm::setAppLanguage)
            LanguageRow("English", LanguagePreference.EN, selected, vm::setAppLanguage)
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    value: LanguagePreference,
    selected: LanguagePreference,
    onSelect: (LanguagePreference) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { RadioButton(selected = selected == value, onClick = null) },
        modifier = Modifier.clickable { onSelect(value) },
    )
}
