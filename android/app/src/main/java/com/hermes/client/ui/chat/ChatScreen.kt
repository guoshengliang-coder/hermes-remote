package com.hermes.client.ui.chat
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionProfile: String? = null,
    initialTitle: String? = null,
    isNewSession: Boolean = false,
    vm: ChatViewModel = hiltViewModel(),
    onMenu: () -> Unit = {},
    onNewChat: (String) -> Unit = {},
    onUnauthorized: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    LaunchedEffect(sessionId, sessionProfile, initialTitle, isNewSession) {
        vm.open(sessionId, sessionProfile, initialTitle, isNewSession)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val connState by vm.connectionState.collectAsStateWithLifecycle()
    val unauthorized by vm.unauthorized.collectAsStateWithLifecycle()
    val sessionTitle by vm.sessionTitle.collectAsStateWithLifecycle()
    val currentModel by vm.currentModel.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val currentProvider by vm.currentProvider.collectAsStateWithLifecycle()
    val modelSheet by vm.modelSheet.collectAsStateWithLifecycle()
    var modelSheetOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    val commands by vm.commands.collectAsStateWithLifecycle()
    val pathItems by vm.pathItems.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val savedPrompts by vm.savedPrompts.collectAsStateWithLifecycle()
    var showPromptSheet by remember { mutableStateOf(false) }
    val personaUi by vm.personaUi.collectAsStateWithLifecycle()
    var showPersonaSheet by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.stopReading() } }
    var draft by rememberSaveable(sessionId) { mutableStateOf("") }
    var composerFocused by rememberSaveable(sessionId) { mutableStateOf(false) }
    var creatingSession by rememberSaveable(sessionId) { mutableStateOf(false) }
    var searchOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    var query by rememberSaveable(sessionId) { mutableStateOf("") }
    var currentMatch by rememberSaveable(sessionId) { mutableStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Search the same merged turns rendered by ChatMessageList so highlight indices stay aligned.
    val conversationTurns = remember(state.messages, searchOpen) {
        if (searchOpen) state.messages.organizedConversationTurns() else emptyList()
    }
    val matches = remember(query, conversationTurns) { matchIndices(conversationTurns, query) }
    // Reset the cursor when the QUERY changes — not when `matches` changes: `matches` is a fresh
    // list instance on every streamed token, which would otherwise yank the cursor to 0 mid-search.
    LaunchedEffect(query, searchOpen) { currentMatch = 0 }
    // Coerce currentMatch into range so the highlight stays in sync with the (coerced) counter during
    // the transient window after `matches` shrinks but before the reset effect runs.
    val highlightIndex = if (searchOpen && matches.isNotEmpty()) matches[currentMatch.coerceAtMost(matches.lastIndex)] else null
    // Highlight scrolling lives inside ChatMessageList: with reverseLayout the turn index must be
    // mapped to the reversed list index, and the list owns that mapping.
    // System back closes the search bar first (rather than leaving the chat) when it's open.
    androidx.activity.compose.BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    androidx.activity.compose.BackHandler(enabled = !searchOpen && composerFocused) {
        composerFocused = false
        focusManager.clearFocus()
    }
    val focusRequester = remember(sessionId) { FocusRequester() }

    fun collapseComposer(clearDraft: Boolean = false) {
        if (clearDraft) draft = ""
        composerFocused = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(sessionId) {
        // A fresh navigation entry must never inherit the outgoing chat's IME/focus state.
        collapseComposer()
    }
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
    var savingImageId by remember { mutableStateOf<String?>(null) }
    var pendingSaveAsImage by remember { mutableStateOf<com.hermes.client.domain.ChatImage?>(null) }
    var showCameraPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var cameraLaunchRequest by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val attachScope = androidx.compose.runtime.rememberCoroutineScope()

    fun showAttachmentError(message: String?) {
        android.widget.Toast.makeText(
            context,
            message ?: localized(language, "无法读取附件", "Unable to read attachment"),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    fun showImageMessage(message: String, long: Boolean = false) {
        android.widget.Toast.makeText(
            context,
            message,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    val saveImageDocument = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val image = pendingSaveAsImage
        pendingSaveAsImage = null
        val destination = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || image == null || destination == null) return@rememberLauncherForActivityResult
        savingImageId = image.id
        vm.saveImageToUri(image, destination) { saved ->
            savingImageId = null
            saved.onSuccess {
                showImageMessage(localized(language, "图片已保存", "Image saved"))
            }.onFailure {
                showImageMessage(it.message ?: localized(language, "保存图片失败", "Unable to save image"), long = true)
            }
        }
    }

    fun saveImageAs(image: com.hermes.client.domain.ChatImage) {
        if (savingImageId != null) return
        pendingSaveAsImage = image
        saveImageDocument.launch(
            android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = vm.imageExportMimeType(image)
                putExtra(android.content.Intent.EXTRA_TITLE, vm.imageExportName(image))
            },
        )
    }

    fun saveImage(image: com.hermes.client.domain.ChatImage) {
        if (savingImageId != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savingImageId = image.id
            vm.saveImageToGallery(image) { saved ->
                savingImageId = null
                saved.onSuccess { result ->
                    showImageMessage(
                        localized(language, "已保存到相册：${result.displayName}", "Saved to Photos: ${result.displayName}"),
                    )
                }.onFailure {
                    showImageMessage(it.message ?: localized(language, "保存图片失败", "Unable to save image"), long = true)
                }
            }
        } else saveImageAs(image)
    }

    fun shareImage(image: com.hermes.client.domain.ChatImage) {
        vm.prepareImageForShare(image) { prepared ->
            prepared.onSuccess { local ->
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    local,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = vm.imageExportMimeType(image)
                    putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
                    clipData = android.content.ClipData.newRawUri(vm.imageExportName(image), contentUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            intent,
                            localized(language, "分享图片", "Share image"),
                        ),
                    )
                }.onFailure { showImageMessage(it.message ?: localized(language, "无法分享图片", "Unable to share image"), long = true) }
            }.onFailure {
                showImageMessage(it.message ?: localized(language, "图片尚未加载完成", "Image is not ready"), long = true)
            }
        }
    }

    fun stageUri(uri: Uri, fallbackName: String = "attachment") {
        attachScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    prepareAttachment(context, uri, fallbackName)
                }
            }.onSuccess { attachment ->
                vm.stageAttachment(attachment.bytes, attachment.mimeType, attachment.name)
            }.onFailure { showAttachmentError(it.message) }
        }
    }

    fun handleFile(file: com.hermes.client.domain.ChatFile, share: Boolean) {
        android.widget.Toast.makeText(
            context,
            localized(language, "正在准备文件…", "Preparing file…"),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        vm.fetchFile(file) { result ->
            result.onSuccess { local ->
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    local,
                )
                val intent = if (share) {
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = file.mimeType ?: "application/octet-stream"
                        putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
                    }
                } else {
                    android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, file.mimeType ?: "application/octet-stream")
                    }
                }.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching {
                    context.startActivity(
                        if (share) android.content.Intent.createChooser(intent, file.name) else intent,
                    )
                }.onFailure { showAttachmentError(it.message) }
            }.onFailure { showAttachmentError(it.message) }
        }
    }

    // Photo library: multi-select via the system photo picker (no permission).
    // Read bytes off the main thread (large images would otherwise jank/ANR the UI).
    val pickPhotos = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ATTACH_CAP),
    ) { uris ->
        uris.take(ATTACH_CAP).forEach { stageUri(it, "photo.jpg") }
    }

    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.take(ATTACH_CAP).forEach { stageUri(it) }
    }

    // Camera: zxing contributes CAMERA to the merged manifest, so Android requires the runtime grant
    // even though capture is delegated to the system camera. Honor/MagicOS enforces this strictly.
    // rememberSaveable (Uri is Parcelable): survive process death while the camera app is foregrounded,
    // so the captured photo isn't dropped when we return.
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var captureFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        CompatibleTakePictureContract(),
    ) { ok ->
        val uri = captureUri
        val file = captureFilePath?.let(::File)
        // A few OEM cameras write the full file but return RESULT_CANCELED. Trust a non-empty
        // output file in that case; never stage an empty placeholder even when RESULT_OK was sent.
        if (uri != null && file?.isFile == true && file.length() > 0L) {
            stageUri(uri, "camera-${System.currentTimeMillis()}.jpg")
        } else if (ok) {
            showAttachmentError(localized(language, "相机没有返回可用的照片", "The camera didn't return a usable photo"))
        }
        if (file?.length() == 0L) file.delete()
        uri?.let { revokeCameraUriPermission(context, it) }
        captureUri = null
        captureFilePath = null
    }

    val requestCameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) cameraLaunchRequest += 1 else showCameraPermissionDialog = true
    }

    fun launchCamera() {
        val cameraIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(context.packageManager) == null) {
            showAttachmentError(localized(language, "没有可用的相机应用", "No camera app is available"))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraLaunchRequest += 1
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(cameraLaunchRequest) {
        if (cameraLaunchRequest == 0) return@LaunchedEffect
        runCatching {
            // Do the cache sweep + file creation off the main thread (disk I/O can jank/ANR),
            // then return to the main thread to set the uri and launch the camera.
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Prior captures are already staged in-memory, so their temp files are disposable.
                context.cacheDir.listFiles { f -> f.name.startsWith("capture_") }?.forEach { it.delete() }
                File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            captureUri = uri
            captureFilePath = file.absolutePath
            grantCameraUriPermissions(context, uri)
            takePhoto.launch(uri)
        }.onFailure {
            captureUri?.let { uri -> revokeCameraUriPermission(context, uri) }
            captureUri = null
            captureFilePath?.let(::File)?.delete()
            captureFilePath = null
            showAttachmentError(it.message)
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
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, localized(language, "请说出消息内容", "Speak your message"))
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back"))
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
                                if (id != null) {
                                    collapseComposer(clearDraft = true)
                                    onNewChat(id)
                                }
                                else android.widget.Toast.makeText(
                                    context, localized(language, "暂时无法新建会话", "Couldn't create a new session"), android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        if (creatingSession) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Add, contentDescription = localized(language, "新建会话", "New session"))
                        }
                    }
                    Box {
                        IconButton(onClick = { transcriptMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = localized(language, "更多", "More"),
                            )
                        }
                        DropdownMenu(expanded = transcriptMenu, onDismissRequest = { transcriptMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(localized(language, "搜索当前对话", "Search this chat")) },
                                onClick = {
                                    transcriptMenu = false
                                    searchOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(localized(language, "复制全部对话", "Copy transcript")) },
                                onClick = {
                                    val t = transcriptText(state.messages)
                                    if (t.isBlank()) {
                                        android.widget.Toast.makeText(context, localized(language, "暂无可导出的内容", "Nothing to export yet"), android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        runCatching {
                                            clipboard.setText(AnnotatedString(t))
                                            android.widget.Toast.makeText(context, localized(language, "对话已复制", "Transcript copied"), android.widget.Toast.LENGTH_SHORT).show()
                                        }.onFailure {
                                            android.widget.Toast.makeText(context, localized(language, "无法复制对话", "Couldn't copy transcript"), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    transcriptMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(localized(language, "分享对话", "Share transcript")) },
                                onClick = {
                                    val t = transcriptText(state.messages)
                                    if (t.isBlank()) {
                                        android.widget.Toast.makeText(context, localized(language, "暂无可导出的内容", "Nothing to export yet"), android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, localized(language, "Hermes 对话记录", "Hermes chat transcript"))
                                            putExtra(android.content.Intent.EXTRA_TEXT, t)
                                        }
                                        runCatching {
                                            context.startActivity(android.content.Intent.createChooser(send, localized(language, "分享对话", "Share transcript")))
                                        }.onFailure {
                                            android.widget.Toast.makeText(context, localized(language, "无法分享对话", "Couldn't share transcript"), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    transcriptMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(localized(language, "切换身份", "Switch profile")) },
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
                            if (a.kind == AttachmentKind.IMAGE) {
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
                                            contentDescription = localized(language, "待发送图片", "Image ready to send"),
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
                                        Icon(Icons.Rounded.Close, localized(language, "移除图片", "Remove image"), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(15.dp))
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.width(190.dp).height(58.dp),
                                ) {
                                    Row(
                                        Modifier.padding(start = 12.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.InsertDriveFile, contentDescription = null)
                                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                            Text(attachmentSizeLabel(a.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { vm.removeAttachment(a.id) }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Rounded.Close, localized(language, "移除文件", "Remove file"), modifier = Modifier.size(17.dp))
                                        }
                                    }
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
                                        localized(language, "输入消息…", "Type a message…"),
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
                                        Icon(Icons.Rounded.Mic, contentDescription = localized(language, "语音输入", "Voice input"), modifier = Modifier.size(24.dp))
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
                                            if (currentModel.isNullOrBlank()) localized(language, "自动", "Auto") else compactModelLabel(currentModel),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                        )
                                        Icon(
                                            Icons.Rounded.ArrowDropDown,
                                            contentDescription = localized(language, "切换模型", "Switch model"),
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { showAttachSheet = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = localized(language, "添加内容", "Add content"), modifier = Modifier.size(28.dp))
                                }
                                when {
                                    state.isGenerating -> IconButton(onClick = { vm.stop() }) {
                                        Icon(Icons.Rounded.Stop, contentDescription = localized(language, "停止", "Stop"), tint = LocalProfileAccent.current.accent)
                                    }
                                    else -> Surface(
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = if (canSend) LocalProfileAccent.current.accent
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        IconButton(onClick = { submit() }, enabled = canSend) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.Send,
                                                contentDescription = localized(language, "发送", "Send"),
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
                                    Icon(Icons.Rounded.Mic, contentDescription = localized(language, "语音输入", "Voice input"), modifier = Modifier.size(24.dp))
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
                                        localized(language, "发消息或按住说话", "Message or hold to talk"),
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
                                    Icon(Icons.Rounded.Stop, contentDescription = localized(language, "停止", "Stop"), tint = LocalProfileAccent.current.accent)
                                }
                                canSend -> Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = LocalProfileAccent.current.accent,
                                ) {
                                    IconButton(onClick = { submit() }) {
                                        Icon(Icons.AutoMirrored.Rounded.Send, localized(language, "发送", "Send"), tint = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                                else -> IconButton(onClick = { showAttachSheet = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = localized(language, "添加内容", "Add content"), modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
                Text(
                    localized(language, "内容由 AI 生成", "AI-generated content"),
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
                    localized(language, "命令", "COMMANDS"),
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
                    localized(language, "附件 / 提及", "ATTACH / MENTION"),
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
                                placeholder = { Text(localized(language, "在对话中搜索…", "Search in chat…")) },
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
                                    contentDescription = localized(language, "上一个匹配项", "Previous match"),
                                    tint = accent,
                                )
                            }
                            IconButton(
                                onClick = { if (matches.isNotEmpty()) currentMatch = (currentMatch + 1) % matches.size },
                                enabled = matches.isNotEmpty(),
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = localized(language, "下一个匹配项", "Next match"),
                                    tint = accent,
                                )
                            }
                            IconButton(onClick = { searchOpen = false; query = "" }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = localized(language, "关闭搜索", "Close search"))
                            }
                        }
                    }
                    ChatMessageList(
                        state = state,
                        sessionId = sessionId,
                        listState = listState,
                        highlightIndex = highlightIndex,
                        externalScrollActive = searchOpen,
                        isGenerating = state.isGenerating,
                        onEditResend = { text -> draft = text; focusRequester.requestFocus() },
                        onRegenerate = { vm.regenerate() },
                        isSpeaking = speaking,
                        onReadAloud = { vm.readAloud(it) },
                        onStopReading = { vm.stopReading() },
                        onImageSave = ::saveImage,
                        onImageSaveAs = ::saveImageAs,
                        onImageShare = ::shareImage,
                        savingImageId = savingImageId,
                        onFileOpen = { handleFile(it, share = false) },
                        onFileShare = { handleFile(it, share = true) },
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
                localized(language, "添加内容", "Add content"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachmentActionCard(
                    icon = Icons.Rounded.PhotoCamera,
                    label = localized(language, "拍照", "Camera"),
                    modifier = Modifier.weight(1f),
                    onClick = { showAttachSheet = false; launchCamera() },
                )
                AttachmentActionCard(
                    icon = Icons.Rounded.PhotoLibrary,
                    label = localized(language, "照片", "Photos"),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showAttachSheet = false
                        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                AttachmentActionCard(
                    icon = Icons.Rounded.InsertDriveFile,
                    label = localized(language, "手机文件", "Files"),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showAttachSheet = false
                        pickFiles.launch(arrayOf(
                            "image/*",
                            "application/pdf",
                            "text/*",
                            "application/json",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-powerpoint",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "audio/*",
                            "video/*",
                            "application/zip",
                            "application/octet-stream",
                        ))
                    },
                )
            }
            ListItem(
                headlineContent = { Text(localized(language, "常用提示", "Saved prompts"), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(localized(language, "插入已经保存的提示词", "Insert a saved prompt")) },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null) },
                modifier = Modifier.clickable {
                    showAttachSheet = false
                    showPromptSheet = true
                }.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCameraPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionDialog = false },
            title = { Text(localized(language, "需要相机权限", "Camera permission required")) },
            text = {
                Text(
                    localized(
                        language,
                        "拍照需要使用相机权限。请在系统设置中允许相机权限，然后重新点击拍照。",
                        "Taking a photo requires camera access. Allow it in system settings, then try again.",
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { showCameraPermissionDialog = false }) {
                    Text(localized(language, "取消", "Cancel"))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCameraPermissionDialog = false
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure { showAttachmentError(it.message) }
                    },
                ) { Text(localized(language, "打开设置", "Open settings")) }
            },
        )
    }

    state.pendingClarify?.let { req ->
        var answer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.clarify("") },
            title = { Text(localized(language, "需要补充信息", "Clarification")) },
            text = {
                Column {
                    Text(req.question)
                    OutlinedTextField(value = answer, onValueChange = { answer = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clarify(answer) }) { Text(localized(language, "发送", "Send")) }
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
                    localized(language, "暂无常用提示，可前往“设置 › 常用提示”添加。", "No saved prompts yet — add them in Settings › Saved prompts."),
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

/**
 * The stock TakePicture contract grants URI flags, but several OEM camera apps also require the
 * output Uri in ClipData. Keeping both makes the FileProvider hand-off portable without exposing it.
 */
internal class CompatibleTakePictureContract : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            clipData = ClipData.newRawUri("Hermes Remote photo", input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}

private fun grantCameraUriPermissions(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    context.packageManager.queryIntentActivities(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY).forEach { info ->
        info.activityInfo?.packageName?.let { packageName ->
            runCatching { context.grantUriPermission(packageName, uri, flags) }
        }
    }
}

private fun revokeCameraUriPermission(context: Context, uri: Uri) {
    runCatching {
        context.revokeUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
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
    val language = LocalAppLanguage.current
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
            ) { Text(localized(language, "重试", "Retry")) }
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
