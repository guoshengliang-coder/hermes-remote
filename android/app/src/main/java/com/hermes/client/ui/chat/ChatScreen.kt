package com.hermes.client.ui.chat
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.ui.components.bannerLabel
import com.hermes.client.ui.components.connectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    vm: ChatViewModel = hiltViewModel(),
    onMenu: () -> Unit = {},
    onNewChat: (String) -> Unit = {},
    onUnauthorized: () -> Unit = {},
) {
    LaunchedEffect(sessionId) { vm.open(sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val connState by vm.connectionState.collectAsStateWithLifecycle()
    val unauthorized by vm.unauthorized.collectAsStateWithLifecycle()
    val sessionTitle by vm.sessionTitle.collectAsStateWithLifecycle()
    val currentModel by vm.currentModel.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val currentProvider by vm.currentProvider.collectAsStateWithLifecycle()
    val modelSheet by vm.modelSheet.collectAsStateWithLifecycle()
    var modelSheetOpen by rememberSaveable { mutableStateOf(false) }
    val commands by vm.commands.collectAsStateWithLifecycle()
    val pathItems by vm.pathItems.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val savedPrompts by vm.savedPrompts.collectAsStateWithLifecycle()
    var showPromptSheet by remember { mutableStateOf(false) }
    val personaUi by vm.personaUi.collectAsStateWithLifecycle()
    var showPersonaSheet by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.stopReading() } }
    var draft by remember { mutableStateOf("") }
    var composerFocused by rememberSaveable { mutableStateOf(false) }
    var creatingSession by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var currentMatch by rememberSaveable { mutableStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Search the same merged turns rendered by ChatMessageList so highlight indices stay aligned.
    val conversationTurns = remember(state.messages) { state.messages.organizedConversationTurns() }
    val matches = remember(query, conversationTurns) { matchIndices(conversationTurns, query) }
    // Reset the cursor when the QUERY changes — not when `matches` changes: `matches` is a fresh
    // list instance on every streamed token, which would otherwise yank the cursor to 0 mid-search.
    LaunchedEffect(query, searchOpen) { currentMatch = 0 }
    // Coerce currentMatch into range so the highlight stays in sync with the (coerced) counter during
    // the transient window after `matches` shrinks but before the reset effect runs.
    val highlightIndex = if (searchOpen && matches.isNotEmpty()) matches[currentMatch.coerceAtMost(matches.lastIndex)] else null
    // Key the scroll on the resolved match index, so it only animates when the active match actually
    // moves — not on every streamed token (which changes `matches`'s identity but not the target).
    LaunchedEffect(highlightIndex) { highlightIndex?.let { listState.animateScrollToItem(it) } }
    // System back closes the search bar first (rather than leaving the chat) when it's open.
    androidx.activity.compose.BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }
    val focusManager = LocalFocusManager.current
    androidx.activity.compose.BackHandler(enabled = !searchOpen && composerFocused) {
        composerFocused = false
        focusManager.clearFocus()
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(composerFocused) {
        // The compact and expanded layouts use different field placements. Re-request focus after
        // expansion so the keyboard remains open instead of flashing and immediately collapsing.
        if (composerFocused) focusRequester.requestFocus()
    }
    val initialDraft by vm.initialDraft.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(initialDraft) {
        initialDraft?.takeIf { it.isNotEmpty() }?.let { draft = it; vm.clearInitialDraft() }
    }
    // Slash-command palette: when the draft is a "/query", show matching commands.
    val slashMatches = if (draft.startsWith("/") && !draft.contains(' ')) {
        val q = draft.drop(1).lowercase()
        commands.filter { it.first.removePrefix("/").lowercase().startsWith(q) }
    } else emptyList()
    // "@" mention picker: the last whitespace-separated token starting with "@".
    val atWord = draft.substringAfterLast(' ').takeIf { it.startsWith("@") }
    LaunchedEffect(atWord) { if (atWord != null) vm.completePath(atWord) else vm.clearPathItems() }
    val showPath = slashMatches.isEmpty() && atWord != null && pathItems.isNotEmpty()

    fun insertAt(text: String) {
        val base = draft.dropLast(atWord?.length ?: 0)
        draft = base + text + (if (text.endsWith(":")) "" else " ")
    }
    val connected = connState is ConnectionState.Connected
    val canSend = canSend(connected, draft.isNotBlank(), state.pendingAttachments.isNotEmpty(), state.isGenerating)
    val haptic = LocalHapticFeedback.current

    fun submit() {
        if (!canSend) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.send(draft)
        draft = ""
    }

    // Image attach: read picked/captured bytes and stage them onto the session.
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    var transcriptMenu by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    val attachScope = androidx.compose.runtime.rememberCoroutineScope()

    fun readBytes(uri: Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    // Photo library: multi-select via the system photo picker (no permission).
    // Read bytes off the main thread (large images would otherwise jank/ANR the UI).
    val pickPhotos = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ATTACH_CAP),
    ) { uris ->
        attachScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            uris.forEach { uri ->
                readBytes(uri)?.let { vm.stageAttachment(it, context.contentResolver.getType(uri) ?: "image/*") }
            }
        }
    }

    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        attachScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            uris.take(ATTACH_CAP).forEach { uri ->
                readBytes(uri)?.let { vm.stageAttachment(it, context.contentResolver.getType(uri) ?: "image/*") }
            }
        }
    }

    // Camera: capture into a FileProvider cache uri, then read it back. No CAMERA permission (delegates).
    // rememberSaveable (Uri is Parcelable): survive process death while the camera app is foregrounded,
    // so the captured photo isn't dropped when we return.
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) captureUri?.let { uri ->
            attachScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                readBytes(uri)?.let { vm.stageAttachment(it, "image/jpeg") }
            }
        }
    }
    fun launchCamera() {
        attachScope.launch {
            // Do the cache sweep + file creation off the main thread (disk I/O can jank/ANR),
            // then return to the main thread to set the uri and launch the camera.
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Prior captures are already staged in-memory, so their temp files are disposable.
                context.cacheDir.listFiles { f -> f.name.startsWith("capture_") }?.forEach { it.delete() }
                val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            captureUri = uri
            runCatching { takePhoto.launch(uri) }
        }
    }

    // Voice dictation: the system speech recognizer returns a transcript we append to the draft.
    // RecognizerIntent needs no RECORD_AUDIO (the system speech app owns the mic + permission).
    val speechAvailable = remember(context) { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull().orEmpty()
            draft = appendDictation(draft, spoken)
        }
    }
    fun startDictation() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your message")
        }
        runCatching { speech.launch(intent) }
    }

    // I1: route back to Setup when the server returns 401
    LaunchedEffect(unauthorized) {
        if (unauthorized) onUnauthorized()
    }

    Scaffold(
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                ) {
                    IconButton(onClick = onMenu) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        sessionTitle,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = adaptiveSessionTitleSize(sessionTitle).sp,
                            lineHeight = (adaptiveSessionTitleSize(sessionTitle) + 4).sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = !creatingSession,
                        onClick = {
                            if (creatingSession) return@IconButton
                            creatingSession = true
                            attachScope.launch {
                                val id = vm.createNewSession()
                                creatingSession = false
                                if (id != null) onNewChat(id)
                                else android.widget.Toast.makeText(
                                    context, "暂时无法新建会话", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        if (creatingSession) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Add, contentDescription = "新建会话")
                        }
                    }
                    Box {
                        IconButton(onClick = { transcriptMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "更多",
                            )
                        }
                        DropdownMenu(expanded = transcriptMenu, onDismissRequest = { transcriptMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("搜索当前对话") },
                                onClick = {
                                    transcriptMenu = false
                                    searchOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制全部对话") },
                                onClick = {
                                    val t = transcriptText(state.messages)
                                    if (t.isBlank()) {
                                        android.widget.Toast.makeText(context, "Nothing to export yet", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        runCatching {
                                            clipboard.setText(AnnotatedString(t))
                                            android.widget.Toast.makeText(context, "Transcript copied", android.widget.Toast.LENGTH_SHORT).show()
                                        }.onFailure {
                                            android.widget.Toast.makeText(context, "Couldn't copy transcript", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    transcriptMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("分享对话") },
                                onClick = {
                                    val t = transcriptText(state.messages)
                                    if (t.isBlank()) {
                                        android.widget.Toast.makeText(context, "Nothing to export yet", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Hermes chat transcript")
                                            putExtra(android.content.Intent.EXTRA_TEXT, t)
                                        }
                                        runCatching {
                                            context.startActivity(android.content.Intent.createChooser(send, "Share transcript"))
                                        }.onFailure {
                                            android.widget.Toast.makeText(context, "Couldn't share transcript", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    transcriptMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("切换身份") },
                                onClick = {
                                    transcriptMenu = false
                                    vm.loadPersonas()
                                    showPersonaSheet = true
                                },
                            )
                        }
                    }
                    }
                }
            }
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.pendingAttachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.pendingAttachments, key = { it.id }) { a ->
                            val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.id) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    decodeThumbnail(a.bytes, reqPx = 200)?.asImageBitmap()
                                }
                            }
                            Box(Modifier.size(58.dp)) {
                                val bmp = thumb
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "待发送图片",
                                        modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                                }
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                        .clickable { vm.removeAttachment(a.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, "移除图片", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(if (composerFocused) 28.dp else 30.dp),
                    tonalElevation = 1.dp,
                    shadowElevation = 7.dp,
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (composerFocused) 126.dp else 60.dp),
                ) {
                    if (composerFocused) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { if (it.isFocused) composerFocused = true },
                                placeholder = {
                                    Text(
                                        "输入消息…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                },
                                minLines = 2,
                                maxLines = 6,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                ),
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (speechAvailable) {
                                    IconButton(onClick = { startDictation() }) {
                                        Icon(Icons.Rounded.Mic, contentDescription = "语音输入", modifier = Modifier.size(24.dp))
                                    }
                                }
                                Surface(
                                    onClick = { modelSheetOpen = true },
                                    color = androidx.compose.ui.graphics.Color.Transparent,
                                    shape = RoundedCornerShape(18.dp),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            compactModelLabel(currentModel),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                        )
                                        Icon(
                                            Icons.Rounded.ArrowDropDown,
                                            contentDescription = "切换模型",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { showAttachSheet = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = "添加内容", modifier = Modifier.size(28.dp))
                                }
                                when {
                                    state.isGenerating -> IconButton(onClick = { vm.stop() }) {
                                        Icon(Icons.Rounded.Stop, contentDescription = "停止", tint = LocalProfileAccent.current.accent)
                                    }
                                    else -> Surface(
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = if (canSend) LocalProfileAccent.current.accent
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        IconButton(onClick = { submit() }, enabled = canSend) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.Send,
                                                contentDescription = "发送",
                                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (speechAvailable) {
                                IconButton(onClick = { startDictation() }) {
                                    Icon(Icons.Rounded.Mic, contentDescription = "语音输入", modifier = Modifier.size(24.dp))
                                }
                            }
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { if (it.isFocused) composerFocused = true },
                                placeholder = {
                                    Text(
                                        "发消息或按住说话",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                },
                                minLines = 1,
                                maxLines = 3,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { submit() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                ),
                            )
                            when {
                                state.isGenerating -> IconButton(onClick = { vm.stop() }) {
                                    Icon(Icons.Rounded.Stop, contentDescription = "停止", tint = LocalProfileAccent.current.accent)
                                }
                                canSend -> Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = LocalProfileAccent.current.accent,
                                ) {
                                    IconButton(onClick = { submit() }) {
                                        Icon(Icons.AutoMirrored.Rounded.Send, "发送", tint = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                                else -> IconButton(onClick = { showAttachSheet = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = "添加内容", modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
                Text(
                    "内容由 AI 生成",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!connected) {
                ConnectionBanner(connState, onRetry = { vm.reconnect() })
            }
            if (slashMatches.isNotEmpty()) {
                // Typing "/" turns the message area into a full, scrollable command picker.
                Text(
                    "COMMANDS",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalProfileAccent.current.accent,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(slashMatches) { (name, desc) ->
                        val cmd = if (name.startsWith("/")) name else "/$name"
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(cmd) },
                            supportingContent = { if (desc.isNotBlank()) Text(desc) },
                            modifier = Modifier.clickable { draft = "$cmd " },
                        )
                    }
                }
            } else if (showPath) {
                Text(
                    "ATTACH / MENTION",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalProfileAccent.current.accent,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(pathItems) { item ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(item.display) },
                            supportingContent = { if (item.meta.isNotBlank()) Text(item.meta) },
                            modifier = Modifier.clickable { insertAt(item.text) },
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (searchOpen) {
                        val accent = LocalProfileAccent.current.accent
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search in chat…") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            // Coerce into range: `currentMatch` can transiently exceed a shrunk match
                            // set before the reset effect runs — avoids a glitchy counter like "5/2".
                            val displayIndex = if (matches.isEmpty()) 0 else currentMatch.coerceAtMost(matches.lastIndex) + 1
                            Text(
                                "$displayIndex/${matches.size}",
                                color = accent,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            IconButton(
                                onClick = { if (matches.isNotEmpty()) currentMatch = (currentMatch - 1 + matches.size) % matches.size },
                                enabled = matches.isNotEmpty(),
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = "Previous match",
                                    tint = accent,
                                )
                            }
                            IconButton(
                                onClick = { if (matches.isNotEmpty()) currentMatch = (currentMatch + 1) % matches.size },
                                enabled = matches.isNotEmpty(),
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Next match",
                                    tint = accent,
                                )
                            }
                            IconButton(onClick = { searchOpen = false; query = "" }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Close search")
                            }
                        }
                    }
                    ChatMessageList(
                        state = state,
                        sessionId = sessionId,
                        listState = listState,
                        highlightIndex = highlightIndex,
                        isGenerating = state.isGenerating,
                        onEditResend = { text -> draft = text; focusRequester.requestFocus() },
                        onRegenerate = { vm.regenerate() },
                        isSpeaking = speaking,
                        onReadAloud = { vm.readAloud(it) },
                        onStopReading = { vm.stopReading() },
                        modifier = Modifier.weight(1f),
                        onBlankAreaTap = {
                            if (composerFocused) {
                                composerFocused = false
                                focusManager.clearFocus()
                            }
                        },
                    )
                }
            }
        }
    }

    state.pendingApproval?.let { req ->
        ApprovalSheet(
            req = req,
            onRespond = { vm.respondApproval(it) },
            onDismiss = { /* keep pending: do nothing until the user chooses */ },
        )
    }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Text(
                "添加内容",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachmentActionCard(
                    icon = Icons.Rounded.PhotoCamera,
                    label = "拍照",
                    modifier = Modifier.weight(1f),
                    onClick = { showAttachSheet = false; launchCamera() },
                )
                AttachmentActionCard(
                    icon = Icons.Rounded.PhotoLibrary,
                    label = "照片",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showAttachSheet = false
                        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                AttachmentActionCard(
                    icon = Icons.Rounded.InsertDriveFile,
                    label = "手机图片",
                    modifier = Modifier.weight(1f),
                    onClick = { showAttachSheet = false; pickFiles.launch(arrayOf("image/*")) },
                )
            }
            ListItem(
                headlineContent = { Text("常用提示", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text("插入已经保存的提示词") },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null) },
                modifier = Modifier.clickable {
                    showAttachSheet = false
                    showPromptSheet = true
                }.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    state.pendingClarify?.let { req ->
        var answer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.clarify("") },
            title = { Text("Clarification") },
            text = {
                Column {
                    Text(req.question)
                    OutlinedTextField(value = answer, onValueChange = { answer = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clarify(answer) }) { Text("Send") }
            },
        )
    }

    if (modelSheetOpen) {
        val items = com.hermes.client.ui.models.modelSelectorRows(
            providers = providers, favorites = favorites, query = modelSheet.query,
            currentProvider = currentProvider, currentModel = currentModel,
        )
        com.hermes.client.ui.models.ModelSelectorSheet(
            items = items,
            query = modelSheet.query, onQueryChange = vm::onSheetQuery,
            scope = modelSheet.scope, onScopeChange = vm::onSheetScope,
            onToggleFavorite = vm::toggleFavorite,
            onSelect = { p, m -> vm.onSelectFromSheet(p, m) { modelSheetOpen = false } },
            pending = modelSheet.pending, error = modelSheet.error,
            onDismiss = { modelSheetOpen = false },
        )
    }

    if (showPromptSheet) {
        val promptSheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showPromptSheet = false }, sheetState = promptSheetState) {
            if (savedPrompts.isEmpty()) {
                Text(
                    "No saved prompts yet — add them in Settings › Saved prompts.",
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(savedPrompts, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title) },
                            supportingContent = { Text(p.body.lineSequence().firstOrNull().orEmpty()) },
                            modifier = Modifier.clickable {
                                draft = if (draft.isBlank()) p.body else draft.trimEnd() + "\n" + p.body
                                showPromptSheet = false
                                focusRequester.requestFocus()
                            },
                        )
                    }
                }
            }
        }
    }

    if (showPersonaSheet) {
        PersonaSheet(
            ui = personaUi,
            onPick = { vm.setPersona(it) },
            onRetry = { vm.loadPersonas() },
            onDismiss = { showPersonaSheet = false },
        )
    }
}

internal fun compactModelLabel(model: String?): String {
    val value = model?.trim()?.substringAfterLast('/')?.ifBlank { null } ?: return "Auto"
    return if (value.length <= 24) value else value.take(23) + "…"
}

internal fun adaptiveSessionTitleSize(title: String): Int = when {
    title.length <= 8 -> 24
    title.length <= 14 -> 20
    title.length <= 22 -> 18
    else -> 16
}

@Composable
private fun AttachmentActionCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(116.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun ConnectionBanner(state: ConnectionState, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = bannerLabel(state),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        // While Connecting the client is already trying — no point offering a manual retry.
        if (state !is ConnectionState.Connecting) {
            TextButton(
                onClick = onRetry,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Retry") }
        }
    }
}


/**
 * Decode [bytes] to a Bitmap downsampled so its largest side is roughly [reqPx] px — a chip thumbnail
 * never needs full resolution, and decoding a 12MP photo at full size (×ATTACH_CAP) risks OOM/jank.
 */
private fun decodeThumbnail(bytes: ByteArray, reqPx: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim > 0 && maxDim / sample > reqPx * 2) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
