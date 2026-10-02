package com.hermes.client.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.haptics.*
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.localization.l10n
import kotlin.math.roundToInt

@Composable
fun OutputHapticTuningScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.outputHaptics.state.collectAsStateWithLifecycle()
    // Keep a draft through rotation without mutating persisted chat settings.
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && draftJson == null) draftJson = OutputHapticParameters.encode(state.config)
    }
    val draft = draftJson?.let(OutputHapticParameters::decode) ?: state.config
    val view = LocalView.current
    val player = remember(view) { OutputHapticPlayer(AndroidOutputHapticDevice(view)) }
    var previewError by remember { mutableStateOf<AppError?>(null) }
    var copied by remember { mutableStateOf(false) }
    var lastRhythm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val preview = remember(player, scope) {
        HapticPreview(scope, player::request, player::cancel) { result ->
            previewError = AppError(AppErrorCode.OUTPUT_HAPTIC_PREVIEW_FAILED, true,
                technicalCause = player.failureCause ?: "request=$result", stage = "output_haptic_preview")
        }
    }
    val running by preview.running.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, preview) {
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) { preview.stop() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); preview.stop() }
    }
    val focused = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(focused, preview) {
        if (!focused) preview.stop()
        onDispose { preview.stop() }
    }
    val clipboard = LocalClipboardManager.current
    val audition: (Boolean) -> Unit = { rhythm ->
        lastRhythm = rhythm
        previewError = null
        preview.start(draft, rhythm)
    }
    OutputHapticTuningContent(
        state = state, draft = draft, customSupported = player.supports(OutputHapticType.CUSTOM_PULSE),
        running = running, copied = copied, previewError = previewError,
        onBack = onBack,
        onDraft = { preview.stop(); previewError = null; copied = false; draftJson = OutputHapticParameters.encode(it) },
        onSave = { preview.stop(); vm.outputHaptics.setConfig(draft) },
        onCopy = { clipboard.setText(AnnotatedString(OutputHapticParameters.encode(draft))); copied = true },
        onPreview = audition, onStop = preview::stop, onPreviewRetry = { audition(lastRhythm) },
        onEnabled = vm.outputHaptics::setEnabled, onSaveRetry = vm.outputHaptics::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutputHapticTuningContent(
    state: OutputHapticsSettingsState, draft: OutputHapticConfig, customSupported: Boolean,
    running: Boolean, copied: Boolean = false, previewError: AppError? = null,
    onBack: () -> Unit = {}, onDraft: (OutputHapticConfig) -> Unit = {}, onSave: () -> Unit = {},
    onCopy: () -> Unit = {}, onPreview: (Boolean) -> Unit = {}, onStop: () -> Unit = {},
    onPreviewRetry: () -> Unit = {}, onEnabled: (Boolean) -> Unit = {}, onSaveRetry: () -> Unit = {},
) {
    val ready = state.loaded && !state.saving
    val custom = draft.type == OutputHapticType.CUSTOM_PULSE
    val supported = !custom || customSupported
    Scaffold(topBar = {
        HermesTopBar(title = l10n("输出触觉调参", "Output haptics tuning"), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, l10n("返回", "Back")) }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutputHapticsSetting(state, onEnabled, onSaveRetry)
                Text(l10n("先调整并试听，保存后用于聊天。试听不受应用输出开关影响，仍遵循系统触觉设置。",
                    "Adjust and preview, then save for chat. Preview works independently of the app's output toggle and follows system haptics settings."),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
            }
            item {
                Text(l10n("反馈类型", "Feedback type"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(OutputHapticType.entries.size) { index ->
                val type = OutputHapticType.entries[index]
                val title = when (type) {
                    OutputHapticType.SYSTEM_TICK -> l10n("系统轻触", "System tick")
                    OutputHapticType.SYSTEM_SOFT -> l10n("系统柔和", "System soft tick")
                    OutputHapticType.KEYBOARD -> l10n("键盘触感", "Keyboard tap")
                    OutputHapticType.CUSTOM_PULSE -> l10n("自定义短脉冲", "Custom short pulse")
                }
                val available = ready && (type != OutputHapticType.CUSTOM_PULSE || customSupported)
                ListItem(headlineContent = { Text(title, color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) },
                    supportingContent = if (type == OutputHapticType.CUSTOM_PULSE && !customSupported) ({ Text(l10n("本机不支持独立力度控制，请选择系统反馈。", "This device cannot control pulse strength. Choose a system effect.")) }) else null,
                    leadingContent = { RadioButton(selected = draft.type == type, onClick = null, enabled = available) },
                    modifier = Modifier.selectable(draft.type == type, available, Role.RadioButton) { onDraft(draft.copy(type = type)) })
            }
            item {
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HapticSlider(l10n("最短间隔", "Minimum interval"), draft.intervalMs, "ms", 40..500, ready) { onDraft(draft.copy(intervalMs = it)) }
                    Text(l10n("仅跟随新增可见回答文字。实际反馈速度也受文字显示速度限制。", "Follows new visible answer text. Text reveal speed also limits feedback frequency."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (custom) {
                        HapticSlider(l10n("单次时长", "Pulse duration"), draft.durationMs, "ms", 1..30, ready && supported) { onDraft(draft.copy(durationMs = it)) }
                        HapticSlider(l10n("力度", "Strength"), draft.amplitude, "/ 255", 1..255, ready && supported) { onDraft(draft.copy(amplitude = it)) }
                    } else {
                        Text(l10n("系统反馈的力度与时长由手机决定；选择自定义短脉冲后可独立调整。", "The phone determines system effects' strength and duration. Choose Custom short pulse to adjust both."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPreview(false) }, enabled = ready && supported && !running, modifier = Modifier.weight(1f)) { Text(l10n("试听一下", "Preview once")) }
                        OutlinedButton(onClick = { onPreview(true) }, enabled = ready && supported && !running, modifier = Modifier.weight(1f)) { Text(l10n("试听 1 秒", "Preview 1 second")) }
                    }
                    if (running) OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(l10n("停止试听", "Stop preview")) }
                    previewError?.let { error ->
                        ErrorState(error, onRetry = onPreviewRetry)
                        var details by remember(error) { mutableStateOf(false) }
                        TextButton(onClick = { details = !details }) { Text(l10n("查看详情", "View details")) }
                        if (details) Text(error.sanitizedDiagnostic(), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onSave, enabled = ready && supported && draft != state.config, modifier = Modifier.fillMaxWidth()) {
                        Text(l10n("保存为本机默认", "Save as device default"))
                    }
                    Text(if (!state.loaded) l10n("正在读取本机参数…", "Loading device parameters…")
                        else if (state.saving) l10n("正在保存…", "Saving…")
                        else if (state.error != null) l10n("保存未完成，请重试。", "Save incomplete. Please retry.")
                        else if (draft == state.config) l10n("当前参数已是本机默认。", "These parameters are the device default.")
                        else l10n("有未保存的调整，聊天仍使用已保存参数。", "Unsaved changes. Chat still uses the saved parameters."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onCopy, enabled = state.loaded, modifier = Modifier.fillMaxWidth()) { Text(if (copied) l10n("参数已复制", "Parameters copied") else l10n("复制参数", "Copy parameters")) }
                    TextButton(onClick = { onDraft(OutputHapticConfig()) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text(l10n("恢复内置参数（需保存）", "Reset to built-in parameters (save to apply)")) }
                    Text(l10n("保存只影响这台手机。选定后复制参数，可在后续版本中固化为内置默认。", "Saving affects this device. Once chosen, copy the parameters for a future release's built-in defaults."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HapticSlider(label: String, value: Int, unit: String, range: IntRange, enabled: Boolean, onValue: (Int) -> Unit) {
    Text("$label · $value $unit", style = MaterialTheme.typography.bodyMedium) // l10n-allow: localized label plus numeric haptic parameters and universal units.
    Slider(value = value.toFloat(), onValueChange = { onValue(it.roundToInt().coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}
