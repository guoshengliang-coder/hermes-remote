package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val technical by vm.toolCallTechnical.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "外观", "Appearance"),
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back")) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Label(localized(language, "颜色模式", "Color mode"))
            val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { vm.setThemeMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(when (m) {
                        ThemeMode.SYSTEM -> localized(language, "跟随系统", "System")
                        ThemeMode.LIGHT -> localized(language, "浅色", "Light")
                        ThemeMode.DARK -> localized(language, "深色", "Dark")
                    }) }
                }
            }

            Label(localized(language, "工具调用显示", "Tool-call display"), top = 24.dp)
            Text(
                localized(language, "技术模式显示完整工具输入和输出；产品模式隐藏原始数据。", "Technical shows full tool input/output; Product hides raw payloads."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
            )
            val options = listOf(false to localized(language, "产品", "Product"), true to localized(language, "技术", "Technical"))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = technical == value,
                        onClick = { vm.setToolCallTechnical(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = top),
    )
}
