package com.hermes.client.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.HubRow
import com.hermes.client.ui.theme.ACCENT_SWATCHES
import com.hermes.client.ui.theme.LocalProfileAccent
import com.hermes.client.ui.theme.accentFromHsl
import com.hermes.client.ui.theme.colorArgbToHsl
import com.hermes.client.ui.theme.hslToColorArgb
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * "You" tab — profile identity + everything about the account/app: the profile quick-switch
 * (relocated here from the retired drawer), plus Profiles, Models, Management, and Settings.
 */
@Composable
fun YouHubScreen(
    onNavigate: (String) -> Unit,
    vm: ShellViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    var showColorPicker by remember { mutableStateOf(false) }
    val currentOverride = active?.let { com.hermes.client.ui.theme.LocalProfileAccentOverrides.current[it] }

    Scaffold(topBar = { HermesTopBar(title = localized(language, "我的", "You"), subtitle = active?.let { localized(language, "当前身份：$it", "Active profile: $it") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                localized(language, "身份", "PROFILES"),
                style = MaterialTheme.typography.titleSmall,
                color = LocalProfileAccent.current.accent,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            ProfileAvatarRow(
                profiles = profiles.map { it.name },
                active = active,
                onSwitch = { vm.switchProfile(it) },
            )
            active?.let { a ->
                HubRow(
                    Icons.Rounded.Palette,
                    localized(language, "强调色", "Accent colour"),
                    if (currentOverride != null) localized(language, "“$a”的自定义颜色", "Custom colour for \"$a\"") else localized(language, "“$a”的自动颜色", "Auto colour for \"$a\""),
                ) { showColorPicker = true }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            HubRow(Icons.Rounded.People, localized(language, "身份", "Profiles"), localized(language, "管理不同身份配置", "Manage tenant profiles")) { onNavigate("profiles") }
            HorizontalDivider()
            HubRow(Icons.Rounded.AutoAwesome, localized(language, "模型", "Models"), localized(language, "浏览和选择模型", "Browse & pick models")) { onNavigate("models") }
            HorizontalDivider()
            HubRow(Icons.Rounded.AdminPanelSettings, localized(language, "管理", "Management"), localized(language, "管理工具和会话工具", "Admin & session tools")) { onNavigate("management") }
            HorizontalDivider()
            HubRow(Icons.Rounded.Settings, localized(language, "设置", "Settings"), localized(language, "应用与连接设置", "App & connection settings")) { onNavigate("settings") }
        }
    }

    if (showColorPicker) {
        active?.let { a ->
            AccentColorDialog(
                profile = a,
                selected = currentOverride,
                onPick = { argb -> vm.setAccent(a, argb); showColorPicker = false },
                onAuto = { vm.clearAccent(a); showColorPicker = false },
                onDismiss = { showColorPicker = false },
            )
        }
    }
}

/** Curated-swatch colour picker for a profile's accent, with an Auto (clear) option. Swatches are
 *  contrast-safe by construction, so any pick keeps chrome text legible. */
@Composable
private fun AccentColorDialog(
    profile: String,
    selected: Int?,
    onPick: (Int) -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "强调色 · $profile", "Accent colour · $profile")) },
        text = {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val initHsl = selected?.let { colorArgbToHsl(it) }
            var hue by remember { mutableStateOf(initHsl?.first ?: 210f) }
            var sat by remember { mutableStateOf(initHsl?.second ?: 0.62f) }
            var light by remember { mutableStateOf(initHsl?.third ?: 0.44f) }
            val preview = accentFromHsl(hue, sat, light, dark)

            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    localized(language, "选择色块、调节自定义颜色，或使用自动配色。", "Pick a swatch, dial in a custom colour, or Auto for the automatic hue."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                ACCENT_SWATCHES.chunked(6).forEach { rowColors ->
                    Row {
                        rowColors.forEach { argb ->
                            Box(
                                Modifier
                                    .padding(6.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .clickable { onPick(argb) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (argb == selected) {
                                    Icon(Icons.Rounded.Check, contentDescription = localized(language, "已选择", "Selected"), tint = Color.White)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Text(
                    localized(language, "自定义", "CUSTOM"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                // Live preview of the dialled-in colour (accent + its adaptive, always-legible on-colour).
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(preview.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(localized(language, "预览", "Preview"), color = preview.onAccent, style = MaterialTheme.typography.labelLarge)
                }
                SliderRow(localized(language, "色相", "Hue"), hue, 0f..360f) { hue = it }
                SliderRow(localized(language, "饱和度", "Saturation"), sat, 0f..1f) { sat = it }
                SliderRow(localized(language, "明度", "Lightness"), light, 0f..1f) { light = it }
                Button(
                    onClick = { onPick(hslToColorArgb(hue, sat, light)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text(localized(language, "使用自定义颜色", "Use custom colour")) }
            }
        },
        confirmButton = { TextButton(onClick = onAuto) { Text(localized(language, "自动", "Auto")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "关闭", "Close")) } },
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

/** Quick-switch avatar row: each profile's initial in a chip tinted to that profile's own accent. */
@Composable
private fun ProfileAvatarRow(profiles: List<String>, active: String?, onSwitch: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        profiles.forEach { name ->
            val selected = name == active
            // Each avatar previews that profile's own accent hue (via ProfileAvatar), so the
            // switcher itself teaches the color mapping.
            Column(
                Modifier.padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                com.hermes.client.ui.components.ProfileAvatar(
                    name,
                    modifier = Modifier.clickable { onSwitch(name) },
                    size = 48.dp,
                )
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) LocalProfileAccent.current.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
