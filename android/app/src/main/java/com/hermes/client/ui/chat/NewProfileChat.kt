package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.ui.components.LocalHiddenProfiles
import com.hermes.client.ui.components.LocalProfileIdentities
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/** The draft has no remote session until the user confirms its owner. */
@Composable
internal fun NewProfileChat(profiles: List<ProfileDto>, onClose: () -> Unit, onCreate: (String) -> Unit) {
    val language = LocalAppLanguage.current
    val identities = LocalProfileIdentities.current
    val hidden = LocalHiddenProfiles.current
    val names = profiles.map { if (it.isDefault) "default" else it.name }.distinct().filterNot(hidden::contains)
    var owner by remember(names) { mutableStateOf("default".takeIf(names::contains)) }
    var picking by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.Start)) {
                    Text(localized(language, "返回", "Back"))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { picking = true }, enabled = names.isNotEmpty(), modifier = Modifier.size(64.dp)) {
                        ProfileAvatar(owner, size = 64.dp)
                    }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        names.forEach { name ->
                            DropdownMenuItem(text = { Text(identities[name]?.displayName?.let { "$it · $name" } ?: name) },
                                onClick = { owner = name; picking = false })
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(owner?.let { greetingForHour(java.time.LocalTime.now().hour, identities[it]?.displayName ?: it, language) }
                    ?: localized(language, "选择会话身份", "Choose conversation profile"), style = MaterialTheme.typography.titleLarge)
                Text(if (names.isEmpty()) localized(language, "请在设置中检查可见身份", "Check visible profiles in settings")
                    else localized(language, "点头像选择身份", "Tap the avatar to select a profile"))
                Spacer(Modifier.weight(1f))
                Button(onClick = { owner?.let(onCreate) }, enabled = owner in names) {
                    Text(localized(language, "开始会话", "Start chat"))
                }
            }
        }
    }
}
