package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.data.voice.VoiceRecording
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/** A persistent recovery action, unlike a Toast that vanishes before the network returns. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceRecoveryCard(
    recording: VoiceRecording, recovered: Boolean, language: AppLanguage, busy: Boolean, canSend: Boolean,
    onRetry: () -> Unit, onTranscribe: () -> Unit, onDiscard: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(when {
                recording.active -> localized(language, "这段录音仍在录制，请在原页面松手后重试。", "This recording is still active. Release it in the original view first.")
                recording.text != null -> localized(language, "上次语音文字已保留；请核对发送记录后恢复。", "Previous voice text kept. Check sent messages before restoring it.")
                recording.complete -> localized(language, "完整录音已保留 · ${recording.bytes / 32000} 秒", "Complete recording kept · ${recording.bytes / 32000}s")
                else -> localized(language, "录音已中止，已保存片段可转文字 · ${recording.bytes / 32000} 秒", "Recording interrupted. Saved audio can be transcribed · ${recording.bytes / 32000}s")
            }, style = MaterialTheme.typography.bodyMedium)
            Text(localized(language, "仅本机保留，24 小时后清理。恢复后请检查文字和附件。", "Kept on this device for 24 hours. Review restored text and attachments."), style = MaterialTheme.typography.bodySmall)
            FlowRow {
                if (recording.complete && !recovered && recording.text == null) TextButton(onClick = onRetry, enabled = !recording.active && !busy && canSend) { Text(localized(language, "重试发送", "Retry send")) }
                TextButton(onClick = onTranscribe, enabled = !recording.active && !busy) { Text(if (recording.text == null) localized(language, "转文字", "Transcribe") else localized(language, "恢复文字", "Restore text")) }
                TextButton(onClick = onDiscard, enabled = !recording.active && !busy) { Text(localized(language, "丢弃", "Discard")) }
            }
        }
    }
}
