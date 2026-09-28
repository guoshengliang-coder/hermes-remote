package com.hermes.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.isDarkSurface
import kotlin.math.abs
import kotlin.math.sin

/** Bars in the recording card's central waveform. */
internal const val VOICE_WAVE_BARS = 21

/** How often the recording clock — and the waveform it drives — refreshes while held. */
internal const val VOICE_WAVE_TICK_MS = 120L

/**
 * Deterministic waveform heights (dp) for the recording card.
 *
 * [DoubaoVoiceSession] exposes no microphone amplitude, and the Stitch design drives its bars from
 * a `setInterval` script for the same reason. Deriving the heights from [elapsedMs] rather than an
 * infinite transition keeps the card identical for a given [elapsedMs], which is what the
 * screenshot tests rely on; in the app the clock ticks every [VOICE_WAVE_TICK_MS] and the bars
 * move.
 */
internal fun voiceWaveHeights(
    elapsedMs: Long,
    bars: Int = VOICE_WAVE_BARS,
    maxDp: Float = 34f,
): List<Float> {
    val t = elapsedMs / VOICE_WAVE_TICK_MS.toFloat()
    val mid = (bars - 1) / 2f
    return List(bars) { i ->
        // A travelling sine for motion, times a triangular window so the column reads as a
        // central peak (the design's "中央声波").
        val wave = sin(t + i * 1.7f) * 0.5f + 0.5f
        val window = 0.35f + 0.65f * (1f - abs(i - mid) / mid.coerceAtLeast(1f))
        4f + wave * window * maxDp
    }
}

/** `mm:ss` for the recording clock (the design's "00:04"). */
internal fun voiceClock(elapsedMs: Long): String {
    val total = (elapsedMs / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(total / 60L, total % 60L)
}

/**
 * HG-146: the hold-to-talk recording surface, redrawn from the Stitch screen
 * "Hermes 聊天页 - 微信式双滑语音录制态 (取消/转文字/中央声波)".
 *
 * A scrim over the whole chat carries a floating card (live clock, waveform, streamed
 * transcript), the two swipe targets, and the "正在聆听" dome at the bottom. [onTargetsMeasured]
 * hands those two drawn circles back to the composer in root coordinates, which is what the
 * release judgement now uses (HG-153) — the target the user aims at and the target that selects
 * are then the same thing by construction. While [waiting] the layer is the HG-144 exit: tapping
 * it cancels and hands any partial transcript to the draft.
 */
@Composable
internal fun VoiceRecordingOverlay(
    language: AppLanguage,
    held: Boolean,
    waiting: Boolean,
    transcript: String,
    cancelZone: Boolean,
    editZone: Boolean,
    elapsedMs: Long,
    onDismissWaiting: () -> Unit,
    onTargetsMeasured: (VoiceTargets) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Labels sit on the scrim, which is dark in both themes, so they are white in both — the
    // same escape-hatch reasoning as the annotation palette (docs/DESIGN.md §2.8).
    val onScrim = Color.White
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable(
                enabled = waiting && !held,
                indication = null,
                interactionSource = interaction,
                onClick = onDismissWaiting,
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VoiceRecordingCard(
                language = language,
                waiting = waiting,
                transcript = transcript,
                elapsedMs = elapsedMs,
            )
            Spacer(Modifier.height(20.dp))
            VoiceSwipeTargets(
                language = language,
                cancelZone = cancelZone,
                editZone = editZone,
                onScrim = onScrim,
                onTargetsMeasured = onTargetsMeasured,
            )
            Spacer(Modifier.height(20.dp))
            VoiceListeningDome(language = language, waiting = waiting)
        }
    }
}

@Composable
private fun VoiceRecordingCard(
    language: AppLanguage,
    waiting: Boolean,
    transcript: String,
    elapsedMs: Long,
) {
    val scheme = MaterialTheme.colorScheme
    // The raised layer on warm paper is white; on an already-dark theme white would glare, so
    // dark takes the next container up instead (docs/DESIGN.md §2.2 判定用生效主题，不用 isSystemInDarkTheme).
    val cardColor = if (isDarkSurface()) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardColor.copy(alpha = 0.97f),
        shadowElevation = 16.dp,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 270.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = scheme.primaryContainer.copy(alpha = 0.14f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(scheme.primaryContainer, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            voiceClock(elapsedMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    localized(
                        language,
                        if (waiting) "点按取消等待" else "松手 发送",
                        if (waiting) "Tap to stop" else "Release to send",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            VoiceWaveform(elapsedMs, scheme.primary)
            if (transcript.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(scheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "“$transcript”",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceWaveform(elapsedMs: Long, color: Color) {
    val heights = voiceWaveHeights(elapsedMs)
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEach { h ->
            Box(Modifier.width(3.dp).height(h.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun VoiceSwipeTargets(
    language: AppLanguage,
    cancelZone: Boolean,
    editZone: Boolean,
    onScrim: Color,
    onTargetsMeasured: (VoiceTargets) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // HG-153: the two drawn circles are the hot area, so the composer is told where they landed
    // rather than being asked to guess the overlay's geometry. Reported in root coordinates; equal
    // values do not re-trigger the writer below.
    var cancelTarget by remember { mutableStateOf<VoiceTarget?>(null) }
    var editTarget by remember { mutableStateOf<VoiceTarget?>(null) }
    val report = rememberUpdatedState(onTargetsMeasured)
    LaunchedEffect(cancelTarget, editTarget) {
        val cancel = cancelTarget ?: return@LaunchedEffect
        val edit = editTarget ?: return@LaunchedEffect
        report.value(VoiceTargets(cancel, edit))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        VoiceSwipeTarget(
            icon = Icons.Rounded.Close,
            label = localized(language, "移到这里取消", "Move here to cancel"),
            active = cancelZone,
            activeContainer = scheme.errorContainer,
            activeContent = scheme.error,
            onScrim = onScrim,
            onBounds = { center, radius ->
                cancelTarget = VoiceTarget(VoiceReleaseAction.CANCEL, center.x, center.y, radius)
            },
        )
        VoiceSwipeTarget(
            icon = Icons.Rounded.Translate,
            label = localized(language, "滑到这里转文字", "Slide here for text"),
            active = editZone,
            activeContainer = scheme.primaryContainer,
            activeContent = scheme.onPrimaryContainer,
            onScrim = onScrim,
            onBounds = { center, radius ->
                editTarget = VoiceTarget(VoiceReleaseAction.EDIT, center.x, center.y, radius)
            },
        )
    }
}

@Composable
private fun VoiceSwipeTarget(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeContainer: Color,
    activeContent: Color,
    onScrim: Color,
    onBounds: (Offset, Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val idleContainer = if (isDarkSurface()) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (active) activeContainer else idleContainer.copy(alpha = 0.92f),
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(64.dp)
                .onGloballyPositioned { coordinates ->
                    val box = coordinates.size
                    val topLeft = coordinates.positionInRoot()
                    onBounds(
                        Offset(topLeft.x + box.width / 2f, topLeft.y + box.height / 2f),
                        box.width / 2f,
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(26.dp),
                    tint = if (active) activeContent else scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = onScrim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VoiceListeningDome(language: AppLanguage, waiting: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            localized(
                language,
                if (waiting) "正在完成识别…" else "正在聆听",
                if (waiting) "Finishing recognition…" else "Listening…",
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
    }
}
