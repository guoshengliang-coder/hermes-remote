package com.hermes.client.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
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
    imeInsets: WindowInsets = WindowInsets.ime,
    video: @Composable (annotate: (() -> Unit)?, editing: Boolean, selectionStart: Long?, playbackLocked: Boolean,
        review: (() -> Unit)?, annotations: VideoAnnotationTimeline) -> Unit,
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
    var previewIds by rememberSaveable(key) { mutableStateOf<List<String>>(emptyList()) }
    var reviewing by rememberSaveable(key) { mutableStateOf(false) }
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
    LaunchedEffect(editing, vm?.state?.editing) {
        if (!editing || vm?.state?.editing != true) { focus.clearFocus(); keyboard?.hide() }
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
    val keyboardVisible = imeInsets.getBottom(LocalDensity.current) > 0
    fun leaveInput(keepEmpty: Boolean = false) {
        if (vm?.state?.committing == true) return
        previewIds = emptyList()
        focus.clearFocus(); keyboard?.hide()
        vm?.returnToTools(keepEmpty)
    }
    fun returnToVideo() {
        if (vm?.state?.committing == true) return
        leaveInput(); editing = false; reviewing = false
    }
    fun showSummary() {
        if (vm?.state?.committing == true || busy || pendingSave != null) return
        leaveInput(); editing = false
        reviewing = vm?.state?.document?.notes?.isNotEmpty() == true
        if (reviewing) { pause(); vm?.showList() }
    }
    fun startMarking() {
        if (vm?.state?.committing == true) return
        // A surviving ViewModel may still hold a previous viewer's editor. Restore work on demand.
        leaveInput(); reviewing = false; editing = true
    }
    fun back() {
        if (vm?.state?.committing == true) return
        when {
            previewIds.isNotEmpty() -> { previewIds = emptyList() }
            keyboardVisible -> { focus.clearFocus(); keyboard?.hide() }
            reviewing -> returnToVideo()
            vm?.state?.screen != VideoFeedbackScreen.TOOLS -> leaveInput()
            else -> returnToVideo()
        }
    }
    BackHandler(enabled = editing || reviewing) { back() }
    val previewNotes = vm?.state?.document?.notes.orEmpty().filter { it.id in previewIds }
        .sortedWith(compareBy<VideoNote> { it.startMs }.thenBy { it.id })
    fun preview(ids: List<String>) {
        val state = vm?.state ?: return
        if (!editing || !canMark || !state.loaded || state.editing || state.committing || busy || pendingSave != null ||
            state.screen !in listOf(VideoFeedbackScreen.TOOLS, VideoFeedbackScreen.SELECTING)) return
        val notes = state.document.notes.filter { it.id in ids }
        if (notes.isEmpty()) return
        pause()
        previewIds = notes.map { it.id }
        if (notes.size == 1) seek(notes.single().startMs)
    }
    val content: @Composable () -> Unit = {
        video(if (vm != null) { { if (editing) showSummary() else startMarking() } } else null,
            editing, if (editing) vm?.state?.selectionStart else null,
            reviewing || editing && vm?.state?.let {
                it.editing || it.committing || it.screen == VideoFeedbackScreen.DRAFTS
            } == true,
            if (!editing && !reviewing && vm?.state?.document?.notes?.isNotEmpty() == true) ({ showSummary() }) else null,
            VideoAnnotationTimeline(if (editing) vm?.state?.document?.notes.orEmpty() else emptyList(),
                enabled = canMark && vm?.state?.let { it.loaded && !it.editing && !it.committing &&
                    it.screen in listOf(VideoFeedbackScreen.TOOLS, VideoFeedbackScreen.SELECTING) } == true && !busy && pendingSave == null,
                select = ::preview))
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(imeInsets)) {
        if ((editing || reviewing) && vm != null) {
            val actions = VideoFeedbackActions(
                point = { if (canMark) position()?.let { vm.point(it, duration); if (vm.state.editing) pause() } },
                start = { if (canMark) position()?.let { vm.markStart(it, duration) } },
                end = { if (canMark) position()?.let { vm.markEnd(it, duration); if (vm.state.editing) pause() } },
                cancelSelection = vm::cancelSelection, list = ::showSummary, drafts = { pause(); vm.showDrafts() },
                back = { leaveInput() }, returnToVideo = ::returnToVideo, continueMarking = ::startMarking,
                resumeDraft = { pause(); vm.resumeDraft(it) }, text = vm::text,
                commit = {
                    val newNote = vm.state.document.draft?.id == null
                    val epoch = playbackEpoch()
                    val storageKey = key
                    vm.submit(duration) { success ->
                        if (success && mounted && currentKey == storageKey) {
                            focus.clearFocus(); keyboard?.hide()
                            if (newNote && currentMode && currentEpoch() == epoch) currentResume()
                            if (!newNote) { editing = false; reviewing = true }
                        }
                    }
                },
                cancel = { vm.cancelEdit { success -> if (success && mounted && currentKey == key) { focus.clearFocus(); keyboard?.hide() } } }, later = { leaveInput(keepEmpty = true) },
                editNote = { pause(); previewIds = emptyList(); reviewing = false; editing = true; vm.editNote(it) },
                delete = vm::delete, discardDraft = vm::discardDraft,
                seek = { pause(); returnToVideo(); seek(it) },
                retry = vm::retry, saveFile = { export(false) }, attach = { export(true) },
            )
            val expanded = previewNotes.isNotEmpty() || vm.state.editing || reviewing || vm.state.screen == VideoFeedbackScreen.DRAFTS
            VideoFeedbackLayout(keyboardVisible && vm.state.editing, expanded, video = content) {
                if (previewNotes.isNotEmpty()) VideoNotePreview(previewNotes,
                    select = { id -> previewNotes.find { it.id == id }?.let { pause(); seek(it.startMs); previewIds = listOf(id) } },
                    edit = actions.editNote, close = { previewIds = emptyList() }, modifier = Modifier.fillMaxSize())
                else VideoFeedbackPanel(vm.state, duration, playerPosition, canMark, actions, busy || pendingSave != null,
                    if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
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

/** Both slots keep their composition identity across IME and window changes. No focus effects rerun. */
@Composable
private fun VideoFeedbackLayout(editorOnly: Boolean, expanded: Boolean, video: @Composable () -> Unit,
    panel: @Composable () -> Unit) {
    Layout(content = {
        Box(Modifier.clipToBounds()) { video() }
        Box(Modifier.clipToBounds()) { panel() }
    }, modifier = Modifier.fillMaxSize()) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val landscape = width > height
        val panelWidth = if (!editorOnly && landscape) minOf(width / 2, 420.dp.roundToPx()) else width
        val panelConstraints = when {
            editorOnly || landscape -> Constraints.fixed(panelWidth, height)
            expanded -> Constraints.fixed(width, (height * .68f).toInt())
            else -> Constraints(minWidth = width, maxWidth = width, maxHeight = (height * .58f).toInt())
        }
        val tools = children[1].measure(panelConstraints)
        val videoWidth = if (editorOnly) 0 else if (landscape) width - panelWidth else width
        val videoHeight = if (editorOnly) 0 else if (landscape) height else height - tools.height
        val media = children[0].measure(Constraints.fixed(videoWidth, videoHeight))
        layout(width, height) {
            media.placeRelative(0, 0)
            tools.placeRelative(if (!editorOnly && landscape) videoWidth else 0, if (!editorOnly && !landscape) videoHeight else 0)
        }
    }
}
