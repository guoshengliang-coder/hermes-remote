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
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@Composable
fun LanguageScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val selected by vm.appLanguage.collectAsStateWithLifecycle()
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
            LanguageRow("简体中文", AppLanguage.ZH, selected, vm::setAppLanguage)
            LanguageRow("English", AppLanguage.EN, selected, vm::setAppLanguage)
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    value: AppLanguage,
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { RadioButton(selected = selected == value, onClick = null) },
        modifier = Modifier.clickable { onSelect(value) },
    )
}
