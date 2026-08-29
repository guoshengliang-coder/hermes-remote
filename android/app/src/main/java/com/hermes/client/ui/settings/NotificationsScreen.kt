package com.hermes.client.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.NotificationSettings
import com.hermes.client.notifications.GatewayConnectionService
import com.hermes.client.notifications.NotificationPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val settings: NotificationSettings,
) : ViewModel() {
    val prefs: StateFlow<NotificationPrefs> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrefs())

    fun setEnabled(v: Boolean) = viewModelScope.launch { settings.setEnabled(v) }
    fun setApprovals(v: Boolean) = viewModelScope.launch { settings.setApprovals(v) }
    fun setRunFinished(v: Boolean) = viewModelScope.launch { settings.setRunFinished(v) }
    fun setRunProgress(v: Boolean) = viewModelScope.launch { settings.setRunProgress(v) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val language = LocalAppLanguage.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.setEnabled(true)
            GatewayConnectionService.start(context)
        }
    }

    fun enable() {
        if (Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setEnabled(true); GatewayConnectionService.start(context)
        }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "通知", "Notifications"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = localized(language, "返回", "Back"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ToggleRow(
                localized(language, "启用通知", "Enable notifications"),
                localized(language, "开启时在后台保持网关连接；关闭可节省电量。", "Keeps a background connection to your gateway while on. Off saves battery."),
                prefs.enabled,
            ) { on -> if (on) enable() else { vm.setEnabled(false); GatewayConnectionService.stop(context) } }
            HorizontalDivider()
            ToggleRow(localized(language, "审批请求", "Approval requests"), localized(language, "智能体需要你批准操作时提醒", "When the agent needs you to approve an action"), prefs.approvals, enabled = prefs.enabled) { vm.setApprovals(it) }
            HorizontalDivider()
            ToggleRow(
                localized(language, "运行完成", "Run finished"),
                localized(language, "应用在后台时，智能体运行完成后提醒", "Notify when an agent run completes (while the app is in the background)"),
                prefs.runFinished,
                enabled = prefs.enabled,
            ) { vm.setRunFinished(it) }
            HorizontalDivider()
            ToggleRow(
                localized(language, "实时运行进度", "Live run progress"),
                localized(language, "智能体运行时显示持续更新的进度通知", "Show an ongoing notification with live progress while an agent run is in flight"),
                prefs.runProgress,
                enabled = prefs.enabled,
            ) { vm.setRunProgress(it) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
