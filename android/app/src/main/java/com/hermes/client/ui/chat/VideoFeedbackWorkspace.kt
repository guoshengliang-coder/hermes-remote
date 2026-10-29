package com.hermes.client.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.data.error.*
import com.hermes.client.data.video.*
import com.hermes.client.ui.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class VideoFeedbackContext(
    val scope: String, val reference: String, val conversation: String,
    val downloadedGeneration: String? = null,
    /** Return a structured refusal; a successful call stages only, never sends a prompt. */
    val attach: (File) -> AppError?,
)

@Composable
internal fun VideoFeedbackWorkspace(
    name: String, context: VideoFeedbackContext?, generation: String?, duration: Long,
    position: () -> Long?, seek: (Long) -> Unit, onAttached: () -> Unit,
    mode: MutableState<Boolean>? = null,
    playerPosition: Long = 0, canMark: Boolean = true, pause: () -> Unit = {}, resume: () -> Unit = {},
    playbackEpoch: () -> Long = { 0L },
    feedbackModel: VideoFeedbackViewModel? = null,
    video: @Composable (annotate: (() -> Unit)?, editing: Boolean, selectionStart: Long?, playbackLocked: Boolean) -> Unit,
) {
    val androidContext = LocalContext.current
    val language = LocalAppLanguage.current
    val coroutine = rememberCoroutineScope()
    val localMode = rememberSaveable { mutableStateOf(false) }
    var editing by (mode ?: localMode)
    var busy by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<AppError?>(null) }
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    val key = context?.let { info -> generation?.let { videoFeedbackKey(info.scope, info.reference, info.downloadedGeneration ?: it) } }
    val vm: VideoFeedbackViewModel? = feedbackModel ?: key?.let { storageKey ->
        viewModel(key = "video-feedback:$storageKey", factory = remember(storageKey) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    VideoFeedbackViewModel(VideoFeedbackStore(File(androidContext.filesDir, "video-feedback")), storageKey) as T
            }
        })
    }
    val currentContext by rememberUpdatedState(context)
    val currentKey by rememberUpdatedState(key)
    val currentEpoch by rememberUpdatedState(playbackEpoch)
    val currentMode by rememberUpdatedState(editing)
    val currentResume by rememberUpdatedState(resume)
    var mounted by remember(key) { mutableStateOf(true) }
    DisposableEffect(key) { onDispose { mounted = false } }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(vm?.state?.editing) {
        if (vm?.state?.editing != true) { focus.clearFocus(); keyboard?.hide() }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val source = pendingSave?.let(::File)
        pendingSave = null
        if (uri == null) { source?.delete(); return@rememberLauncherForActivityResult }
        coroutine.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(source)
                    androidContext.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: error("document output unavailable")
                }
                android.widget.Toast.makeText(androidContext, localized(language, "批注文件已保存", "Notes file saved"), android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { exportError = videoFeedbackError(e, export = true) }
            finally { source?.delete(); busy = false }
        }
    }
    fun export(attach: Boolean) {
        if (busy || pendingSave != null) return
        val info = context ?: return
        val document = vm?.state?.document ?: return
        val snapshotDuration = duration
        busy = true
        coroutine.launch {
            busy = true; exportError = null
            var output: File? = null
            try {
                val exported = withContext(Dispatchers.IO) {
                    val markdown = videoFeedbackMarkdown(name, info.reference, info.conversation, snapshotDuration, document, info.downloadedGeneration ?: generation)
                    val directory = File(androidContext.cacheDir, "video-feedback-export").apply { check(isDirectory || mkdirs()) }
                    val temporary = File.createTempFile("Video-feedback-", ".md", directory)
                    try { temporary.writeText(markdown); temporary }
                    catch (e: Exception) { temporary.delete(); throw e }
                }
                output = exported
                if (attach) {
                    // The parent rechecks the live conversation/account and attachment capacity.
                    val error = if (currentContext?.scope == info.scope) currentContext?.attach?.invoke(exported)
                        else AppError(AppErrorCode.VIDEO_NOTES_EXPORT, true, stage = "video_notes_attach_scope")
                    if (error != null) exportError = error else { output = null; onAttached() }
                } else {
                    pendingSave = exported.path
                    save.launch(exported.name)
                    output = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { exportError = videoFeedbackError(e, export = true) }
            finally { output?.delete(); busy = false }
        }
    }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun finish() {
        if (vm?.state?.committing == true) return
        when {
            keyboardVisible -> { focus.clearFocus(); keyboard?.hide() }
            vm?.state?.screen != VideoFeedbackScreen.TOOLS -> vm?.later()
            else -> editing = false
        }
    }
    BackHandler(enabled = editing) { finish() }
    val content: @Composable () -> Unit = {
        video(if (vm != null) { { if (editing) finish() else editing = true } } else null,
            editing, if (editing) vm?.state?.selectionStart else null,
            editing && vm?.state?.let { it.editing || it.committing || it.screen == VideoFeedbackScreen.LIST } == true)
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val toolHeight = maxHeight * .58f
        if (editing && vm != null) {
            val actions = VideoFeedbackActions(
                point = { if (canMark) position()?.let { vm.point(it, duration); if (vm.state.editing) pause() } },
                start = { if (canMark) position()?.let { vm.markStart(it, duration) } },
                end = { if (canMark) position()?.let { vm.markEnd(it, duration); if (vm.state.editing) pause() } },
                cancelSelection = vm::cancelSelection, list = { pause(); vm.showList() },
                resumeDraft = { pause(); vm.resumeDraft(it) }, text = vm::text,
                commit = {
                    val newNote = vm.state.document.draft?.id == null
                    val epoch = playbackEpoch()
                    val storageKey = key
                    vm.submit(duration) { success ->
                        if (success && mounted && currentKey == storageKey) {
                            focus.clearFocus(); keyboard?.hide()
                            if (newNote && currentMode && currentEpoch() == epoch) currentResume()
                        }
                    }
                },
                reselect = vm::reselect, later = vm::later, editNote = { pause(); vm.editNote(it) },
                delete = vm::delete, discardDraft = vm::discardDraft,
                seek = { pause(); vm.later(); seek(it) },
                retry = vm::retry, saveFile = { export(false) }, attach = { export(true) },
            )
            val panel: @Composable (Modifier) -> Unit = { modifier ->
                VideoFeedbackPanel(vm.state, duration, playerPosition, canMark, actions, busy || pendingSave != null, modifier)
            }
            if (keyboardVisible && vm.state.editing) panel(Modifier.fillMaxSize())
            else if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                panel(Modifier.widthIn(max = 420.dp).weight(1f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                if (vm.state.editing || vm.state.screen == VideoFeedbackScreen.LIST) {
                    Box(Modifier.fillMaxWidth().weight(.32f)) { content() }
                    panel(Modifier.fillMaxWidth().weight(.68f))
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    panel(Modifier.fillMaxWidth().heightIn(max = toolHeight))
                }
            }
        } else content()
    }
    exportError?.let { failure -> AlertDialog(onDismissRequest = { exportError = null },
        text = { Text(failure.localizedMessage(language)) },
        confirmButton = { TextButton(onClick = { exportError = null }) { Text(localized(language, "返回批注", "Back to notes")) } },
        dismissButton = { TextButton(onClick = {
            androidContext.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("Hermes diagnostics", failure.sanitizedDiagnostic()))
        }) { Text(localized(language, "复制诊断", "Copy diagnostics")) } }) }
}
