package com.hermes.client.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationOnboardingViewModel @Inject constructor(
    private val settings: NotificationSettings,
) : ViewModel() {
    val prefs: StateFlow<NotificationPrefs?> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun markSeen() = viewModelScope.launch { settings.setOnboardingSeen() }
    fun enable() = viewModelScope.launch { settings.setEnabled(true) }
}

/**
 * One-time post-pairing prompt. A remote agent's most valuable pushes are "needs your approval"
 * and "needs your answer" — without notification permission the whole waiting-on-you loop goes
 * dark, so we ask right after the first successful connection, with the reason attached.
 *
 * Renders nothing when the prompt was already shown or notifications are already enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationOnboardingSheet(
    onDone: () -> Unit,
    vm: NotificationOnboardingViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val current = prefs ?: return // prefs still loading; render nothing this frame
    if (current.onboardingSeen || current.enabled) {
        onDone()
        return
    }

    fun finish(enabled: Boolean) {
        if (enabled) {
            vm.enable()
            GatewayConnectionService.start(context)
        }
        vm.markSeen()
        onDone()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        finish(enabled = granted)
    }

    ModalBottomSheet(onDismissRequest = { vm.markSeen(); onDone() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                localized(language, "别错过需要你的时刻", "Don't miss when Hermes needs you"),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                localized(
                    language,
                    "Hermes 在后台执行任务时，遇到需要你批准或回答的问题会停下来等你。开启通知，第一时间收到提醒。",
                    "While Hermes works in the background it will pause and wait whenever it needs your approval or an answer. Turn on notifications to hear about it immediately.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        finish(enabled = true)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(localized(language, "开启通知", "Turn on notifications"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                TextButton(onClick = { finish(enabled = false) }) {
                    Text(localized(language, "暂不需要", "Not now"))
                }
            }
        }
    }
}
