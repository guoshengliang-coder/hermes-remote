package com.hermes.client.ui.profiles

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hermes.client.data.repository.AvatarColorStore
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.AVATAR_SWATCHES
import com.hermes.client.ui.theme.LocalAvatarColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The profile picker — a dedicated screen (opened from the card page's identity card). Rows
 * switch the app-wide profile; the trailing palette button customises that profile's avatar
 * colour (device-local, avatar-only — chrome stays on the brand palette).
 */
@HiltViewModel
class ProfilePickerViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val avatarColors: AvatarColorStore,
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

    fun setAvatarColor(profile: String, argb: Int) = viewModelScope.launch { avatarColors.setColor(profile, argb) }
    fun clearAvatarColor(profile: String) = viewModelScope.launch { avatarColors.clear(profile) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePickerScreen(
    onBack: () -> Unit,
    vm: ProfilePickerViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val switchFailed by vm.switchFailed.collectAsStateWithLifecycle()
    var colorTarget by remember { mutableStateOf<String?>(null) }

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
                val a = activity[p.name]
                val sub = when {
                    isActive -> localized(language, "当前身份", "Active profile")
                    a != null && a.waiting > 0 -> localized(language, "${a.waiting} 待处理", "${a.waiting} waiting")
                    a != null && a.running > 0 -> localized(language, "${a.running} 个进行中", "${a.running} running")
                    else -> null
                }
                ListItem(
                    leadingContent = { ProfileAvatar(p.name, size = 44.dp) },
                    headlineContent = { Text(p.name) },
                    supportingContent = sub?.let { { Text(it) } },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when {
                                switching == p.name -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                isActive -> Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = localized(language, "当前身份", "Active"),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { colorTarget = p.name }) {
                                Icon(
                                    Icons.Rounded.Palette,
                                    contentDescription = localized(language, "头像颜色", "Avatar colour"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

    colorTarget?.let { target ->
        val selected = LocalAvatarColors.current[target]
        ModalBottomSheet(onDismissRequest = { colorTarget = null }) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                Text(
                    localized(language, "头像颜色 · $target", "Avatar colour · $target"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                AVATAR_SWATCHES.chunked(6).forEach { rowColors ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        rowColors.forEach { argb ->
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(Color(argb))
                                    .clickable { vm.setAvatarColor(target, argb); colorTarget = null },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (argb == selected) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                    headlineContent = { Text(localized(language, "自动（按名称生成）", "Automatic (from the name)")) },
                    modifier = Modifier.clickable { vm.clearAvatarColor(target); colorTarget = null },
                )
            }
        }
    }
}
