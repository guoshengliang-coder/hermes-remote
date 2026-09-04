package com.hermes.client.ui.profiles

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.progress.isActive
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.displayNameFor
import com.hermes.client.data.repository.hasCustomName
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.LocalProfileIdentities
import com.hermes.client.ui.components.PencilStrokeIcon
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The profile picker — a dedicated screen (opened from the card page's identity card). Rows
 * switch the app-wide profile; the trailing pencil opens that profile's identity settings
 * (display name, photo, avatar colour and style — device-local).
 */
@HiltViewModel
class ProfilePickerViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    runtimeStore: SessionRuntimeStore,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileDto>> = profileManager.list
    val active: StateFlow<String?> = profileManager.active

    data class Activity(val running: Int = 0, val waiting: Int = 0)
    private val _activity = MutableStateFlow<Map<String, Activity>>(emptyMap())
    val activity: StateFlow<Map<String, Activity>> = _activity.asStateFlow()

    private val _switching = MutableStateFlow<String?>(null)
    val switching: StateFlow<String?> = _switching.asStateFlow()

    private val _switchFailed = MutableStateFlow<String?>(null)
    val switchFailed: StateFlow<String?> = _switchFailed.asStateFlow()
    fun clearSwitchFailed() { _switchFailed.value = null }

    init {
        viewModelScope.launch { profileManager.refresh() }
        viewModelScope.launch {
            runtimeStore.runtimes.collect { runtimes ->
                _activity.value = runtimes.entries
                    .groupBy { it.key.profile ?: "default" }
                    .mapValues { (_, entries) ->
                        Activity(
                            running = entries.count { it.value.phase.isActive },
                            waiting = entries.count {
                                it.value.phase in setOf(
                                    SessionRunPhase.WAITING_APPROVAL,
                                    SessionRunPhase.WAITING_CLARIFICATION,
                                    SessionRunPhase.WAITING_ATTENTION,
                                )
                            },
                        )
                    }
            }
        }
    }

    /** Switch and report success so the screen can pop back only when it actually happened. */
    fun switchProfile(name: String, onDone: (Boolean) -> Unit) {
        if (_switching.value != null) return
        _switching.value = name
        viewModelScope.launch {
            val ok = try { profileManager.switchTo(name) } finally { _switching.value = null }
            if (!ok) _switchFailed.value = name
            onDone(ok)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePickerScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    vm: ProfilePickerViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val switchFailed by vm.switchFailed.collectAsStateWithLifecycle()
    val identities = LocalProfileIdentities.current

    LaunchedEffect(switchFailed) {
        switchFailed?.let {
            Toast.makeText(context, localized(language, "切换身份失败，仍在当前身份", "Couldn't switch profile — staying on the current one"), Toast.LENGTH_SHORT).show()
            vm.clearSwitchFailed()
        }
    }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = localized(language, "身份", "Profiles"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back"))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(profiles, key = { it.name }) { p ->
                val isActive = p.name == active
                val identity = identities[p.name]
                val a = activity[p.name]
                val status = when {
                    isActive -> localized(language, "当前身份", "Active profile")
                    a != null && a.waiting > 0 -> localized(language, "${a.waiting} 待处理", "${a.waiting} waiting")
                    a != null && a.running > 0 -> localized(language, "${a.running} 个进行中", "${a.running} running")
                    else -> null
                }
                // Subline: the profile name only when a custom display name has taken the
                // headline, then the status — joined with a middle dot.
                val sub = listOfNotNull(p.name.takeIf { identity.hasCustomName() }, status)
                    .takeIf { it.isNotEmpty() }?.joinToString(" · ")
                ListItem(
                    leadingContent = { ProfileAvatar(p.name, size = 44.dp, identity = identity) },
                    headlineContent = { Text(displayNameFor(p.name, identity)) },
                    supportingContent = sub?.let { { Text(it) } },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when {
                                switching == p.name -> com.hermes.client.ui.components.HermesMark(size = 20.dp)
                                isActive -> Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = localized(language, "当前身份", "Active"),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { onEdit(p.name) }) {
                                Icon(
                                    PencilStrokeIcon,
                                    contentDescription = localized(language, "身份设置", "Profile settings"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable(enabled = switching == null) {
                        if (isActive) onBack()
                        else vm.switchProfile(p.name) { ok -> if (ok) onBack() }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
