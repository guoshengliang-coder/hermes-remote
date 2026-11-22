package com.hermes.client.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.ui.components.*
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FirstTurn(val text: String, val files: List<PendingAttachment>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewProfileChat(
    profiles: List<ProfileDto>, connection: ConnectionState, busy: Boolean,
    onClose: () -> Unit, shareDestination: Boolean = false,
    onCreate: (String, FirstTurn?) -> Unit,
) {
    val language = LocalAppLanguage.current
    val hidden = LocalHiddenProfiles.current
    val names = profiles.map { if (it.isDefault) "default" else it.name }.distinct().filterNot(hidden::contains)
    var selection by rememberSaveable { mutableStateOf("default") }
    val owner = selection.takeIf(names::contains)
    var picking by rememberSaveable { mutableStateOf(shareDestination) }
    var text by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val files = remember { mutableStateListOf<PendingAttachment>() }
    var reading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            try {
                val item = withContext(Dispatchers.IO) { prepareAttachment(context, uri) }
                files.add(PendingAttachment(java.util.UUID.randomUUID().toString(), item.bytes, item.mimeType, item.name, stagedFile = item.stagedFile))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, localized(language, "无法读取附件（HR-FILE-001）", "Unable to read attachment (HR-FILE-001)"), android.widget.Toast.LENGTH_LONG).show()
            } finally { reading = false }
        }
    }
    val close = { if (!busy) onClose() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                TopAppBar(title = { Text(localized(language, "新会话", "New chat")) }, navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Rounded.ArrowBack, localized(language, "返回", "Back")) }
                })
                NewChatGreeting(owner, owner?.let { roleLabel(it) }, localized(language, "默认模型", "Default model"),
                    connection, WindowInsets.ime.getBottom(LocalDensity.current) > 0, Modifier.weight(1f), roleSwitch = {
                        TextButton(onClick = { picking = true }, enabled = !submitted && !busy && names.isNotEmpty()) {
                            Text(localized(language, "切换角色", "Switch role"))
                        }
                        if (names.isEmpty()) Text(localized(language, "请在设置中检查可见角色", "Check visible roles in settings"))
                    })
                if (!shareDestination) {
                    files.toList().forEach { item ->
                        InputChip(selected = false, enabled = !busy, onClick = { files.remove(item); item.stagedFile?.delete() },
                            label = { Text(item.name, maxLines = 1) })
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = !busy && !reading && files.size < ATTACH_CAP, onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Rounded.Add, localized(language, "添加内容", "Add content"))
                        }
                        val canSend = !busy && !reading && owner != null && (text.isNotBlank() || files.isNotEmpty())
                        val submit = { if (canSend && owner != null) { submitted = true; onCreate(owner, FirstTurn(text, files.toList())) } }
                        ChatDraftField(text, { text = it }, busy, Modifier.weight(1f), submit)
                        ComposerActionButton(false, canSend, language, onClick = submit)
                    }
                }
            }
        }
        if (picking) ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text(localized(language, "切换角色", "Switch role"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            names.forEach { id ->
                Surface(onClick = { selection = id; picking = false; if (shareDestination) onCreate(id, null) }, enabled = !busy) {
                    ListItem(headlineContent = { Text(roleLabel(id)) }, leadingContent = { ProfileAvatar(id, size = 36.dp) },
                        trailingContent = { if (id == owner) Icon(Icons.Rounded.Check, localized(language, "已选择", "Selected")) })
                }
            }
        }
    }
}
