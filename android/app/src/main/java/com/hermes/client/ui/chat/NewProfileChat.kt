package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.ui.components.*
import com.hermes.client.ui.localization.l10n

/** The first turn remains local until the selected destination is created. */
data class FirstTurn(val text: String, val files: List<PendingAttachment>, val cwd: String? = null,
    val model: String? = null, val provider: String? = null, val reasoning: String? = null,
    val onDelivered: (() -> Unit)? = null,
    val startInCreatedSession: ((FirstTurn, String, String, String?) -> Boolean)? = null,
    val prepareCreation: (suspend (FirstTurn) -> FirstTurnCreation)? = null,
    val recoveryMessageId: String? = null,
)

data class FirstTurnCreation(
    val messageId: String,
    val bind: suspend (String) -> Unit,
    val failBeforeCreate: suspend () -> Unit,
    val finishCreationFailure: () -> Unit = {},
)

@Composable
internal fun NewProfileChat(
    profiles: List<ProfileDto>, connection: ConnectionState, busy: Boolean,
    onClose: () -> Unit, shareDestination: Boolean = false,
    initialCwd: String? = null, initialProjectProfile: String? = null,
    onCreate: (String, FirstTurn?) -> Unit,
) {
    val hidden = LocalHiddenProfiles.current
    val names = profiles.map { if (it.isDefault) "default" else it.name }.distinct().filterNot(hidden::contains)
    var selection by rememberSaveable { mutableStateOf(com.hermes.client.data.repository.DEFAULT_NEW_CHAT_PROFILE) }
    val owner = selection.takeIf(names::contains)
    var picking by remember { mutableStateOf(shareDestination) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (!busy) submitted = false }
    val draftId = rememberSaveable { "draft-${java.util.UUID.randomUUID()}" }
    // A dialog gets its own VM, disposed on close; never reuse the underlying conversation's VM.
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val parent = checkNotNull(LocalViewModelStoreOwner.current) as HasDefaultViewModelProviderFactory
    val storeOwner = remember(store, parent) { object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = store
        override val defaultViewModelProviderFactory = parent.defaultViewModelProviderFactory
        override val defaultViewModelCreationExtras = parent.defaultViewModelCreationExtras
    } }
    if (shareDestination) {
        if (picking) RolePickerSheet(names, l10n("切换角色", "Switch role"), setOfNotNull(owner),
            onPick = { if (!busy) onCreate(it, null) }, onDismiss = onClose)
        return
    }
    NewChatWindow(onDismiss = onClose) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            val vm: ChatViewModel = hiltViewModel()
            if (owner != null) ChatScreen(sessionId = draftId, sessionProfile = owner, isNewSession = true, vm = vm,
                localDraft = true, draftCwd = initialCwd.takeIf { owner == initialProjectProfile }, draftBusy = busy,
                onMenu = onClose,
                onDraftSend = { turn -> if (!busy && !submitted) { submitted = true; onCreate(owner, turn) } },
                draftRoleSwitch = {
                    TextButton(onClick = { picking = true }, enabled = !busy && !submitted) { Text(l10n("切换角色", "Switch role")) }
                })
            else Column {
                TextButton(onClick = { picking = true }, enabled = names.isNotEmpty()) { Text(l10n("请选择角色", "Choose a role")) }
                TextButton(onClick = onClose) { Text(l10n("返回", "Back")) }
            }
        }
        if (picking) RolePickerSheet(names, l10n("切换角色", "Switch role"), setOfNotNull(owner),
            onPick = { if (!busy) { selection = it; picking = false } }, onDismiss = { picking = false })
    }
}

/** The shared chat composer owns IME/system-bar insets, including in this separate window. */
@Composable
internal fun NewChatWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}
