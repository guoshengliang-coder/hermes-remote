package com.hermes.client.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.HubRow
import com.hermes.client.ui.theme.LocalProfileAccent
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val switchFailed by vm.switchFailed.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(switchFailed) {
        switchFailed?.let {
            android.widget.Toast.makeText(context, localized(language, "切换身份失败，仍在当前身份", "Couldn't switch profile — staying on the current one"), android.widget.Toast.LENGTH_SHORT).show()
            vm.clearSwitchFailed()
        }
    }

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
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            HubRow(Icons.Rounded.People, localized(language, "身份", "Profiles"), localized(language, "管理不同身份配置", "Manage tenant profiles")) { onNavigate("profiles") }
            HorizontalDivider()
            HubRow(Icons.Rounded.AutoAwesome, localized(language, "模型", "Models"), localized(language, "浏览和选择模型", "Browse & pick models")) { onNavigate("models") }
            HorizontalDivider()
            HubRow(Icons.Rounded.AdminPanelSettings, localized(language, "管理", "Management"), localized(language, "管理工具和会话工具", "Admin & session tools")) { onNavigate("management") }
            HorizontalDivider()
            HubRow(Icons.Rounded.Settings, localized(language, "设置", "Settings"), localized(language, "应用与连接设置", "App & connection settings")) { onNavigate("settings") }
            HorizontalDivider()
            HubRow(Icons.Rounded.SystemUpdate, localized(language, "检查更新", "Check for updates"), localized(language, "查看、下载并安装可用版本", "View, download, and install available versions")) { onNavigate("app_update") }
        }
    }

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
