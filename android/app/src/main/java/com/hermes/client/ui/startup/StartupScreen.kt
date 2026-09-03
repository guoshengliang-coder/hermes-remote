package com.hermes.client.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hermes.client.BuildConfig
import com.hermes.client.R
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.Motion
import kotlinx.coroutines.delay

// Startup gate palette (DESIGN.md §5.11). The background is the one colour in the app that
// must equal a window resource — @color/startup_background (light) and its values-night twin —
// so the system splash hands over to this composable without a colour step. Both modes are
// therefore literal pairs here; the wordmark and error copy follow MaterialTheme, and the mode
// is decided by the effective theme (DESIGN.md §2.2), never by isSystemInDarkTheme().
private data class StartupPalette(
    val background: Color,
    val secondary: Color,
    val track: Color,
)

private val LightStartupPalette = StartupPalette(
    background = Color(0xFFF8FAFD),
    secondary = Color(0xFF74777F),
    track = Color(0xFFE7ECF6),
)

private val DarkStartupPalette = StartupPalette(
    background = Color(0xFF0D141B),
    secondary = Color(0xFF9AA0A8),
    track = Color(0xFF2B323A),
)

/** Icon-derived progress gradient, anchored to the full track width (not the filled part). */
private val ProgressColors = listOf(Color(0xFF1F84FD), Color(0xFF3DA340), Color(0xFFFEC302))

/** A fast launch shows the brand only; operational status appears after this much waiting. */
internal const val STATUS_REVEAL_DELAY_MS = 700L

private const val ENTRANCE_MS = 320
private const val ENTRANCE_START_SCALE = 2f
private const val LOCKUP_TOP_FRACTION = 0.225f
private const val COMPACT_LOCKUP_TOP_FRACTION = 0.10f
private val CompactHeightThreshold = 560.dp
private val IconSize = 144.dp
private val CompactIconSize = 96.dp
private val ProgressWidth = 144.dp
private val ActionWidth = 240.dp

private data class ProgressBand(val activeTarget: Float, val waitingCap: Float)

private fun StartupPhase.progressBand(): ProgressBand = when (this) {
    StartupPhase.CONFIGURATION -> ProgressBand(0.14f, 0.19f)
    StartupPhase.NETWORK -> ProgressBand(0.26f, 0.31f)
    StartupPhase.AUTHENTICATION -> ProgressBand(0.42f, 0.47f)
    StartupPhase.CONNECTION -> ProgressBand(0.65f, 0.73f)
    StartupPhase.INITIAL_DATA -> ProgressBand(0.86f, 0.93f)
    StartupPhase.READY -> ProgressBand(1f, 1f)
}

/**
 * Operational copy for the gate. The four pre-connection phases share one line: the phase
 * granularity still drives the progress bar, but a launch that takes two seconds should not read
 * like a checklist flashing past.
 */
// The CONNECTION_RECOVERY wording is kept but currently unreachable through the gate: a warm
// reconnect no longer renders it (§5.11). It belongs to whatever surface reports recovery next.
internal fun startupStatusText(phase: StartupPhase, reason: StartupReason, language: AppLanguage): String {
    val recovery = reason == StartupReason.CONNECTION_RECOVERY
    return when (phase) {
        StartupPhase.CONFIGURATION,
        StartupPhase.NETWORK,
        StartupPhase.AUTHENTICATION,
        StartupPhase.CONNECTION,
        -> if (recovery) {
            localized(language, "正在恢复连接", "Restoring connection")
        } else {
            localized(language, "正在连接", "Connecting")
        }
        StartupPhase.INITIAL_DATA -> if (recovery) {
            localized(language, "正在恢复当前页面", "Restoring the current screen")
        } else {
            localized(language, "正在准备会话", "Preparing conversations")
        }
        StartupPhase.READY -> localized(language, "连接就绪", "Connection ready")
    }
}

/** Bottom label on the gate: `0.1.81 · DEBUG`. Testers report against this line. */
internal fun startupVersionLabel(versionName: String, buildType: String): String =
    "$versionName · ${buildType.uppercase()}"

@Composable
fun StartupScreen(
    state: StartupUiState,
    onRetry: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
) {
    // A warm reconnect recovers SILENTLY: the conversation stays on screen with the content it
    // already committed while the socket comes back. Only a cold start (which has the system
    // splash to hand over from) and an actual failure (which needs Retry / connection settings)
    // may own the screen. Covering the chat on every reconnect turned a self-healing blip into
    // "the app threw me back to the launch screen" — dozens of times a day (reported 2026-09-03).
    val visible = when (state) {
        is StartupUiState.Loading -> state.reason != StartupReason.CONNECTION_RECOVERY
        is StartupUiState.Failed -> true
        is StartupUiState.RepairRequired, StartupUiState.Hidden -> false
    }
    // Keep the last visible state so the exit fade renders the frame we are leaving from.
    val lastVisible = remember { arrayOfNulls<StartupUiState>(1) }
    if (visible) lastVisible[0] = state
    val shown = lastVisible[0] ?: return
    val reason = shown.reasonOrNull() ?: StartupReason.COLD_START
    // A cold gate follows the system splash and must appear on the very first frame; the
    // warm recovery overlay has nothing to hand over from, so it fades in briefly instead.
    val enter = if (reason == StartupReason.CONNECTION_RECOVERY) {
        fadeIn(tween(Motion.DurationShort))
    } else {
        EnterTransition.None
    }
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = fadeOut(tween(Motion.DurationMedium)) +
            scaleOut(targetScale = 0.98f, animationSpec = tween(Motion.DurationMedium, easing = Motion.Standard)),
    ) {
        StartupGate(
            state = shown,
            reason = reason,
            onRetry = onRetry,
            onOpenConnectionSettings = onOpenConnectionSettings,
        )
    }
}

private fun StartupUiState.reasonOrNull(): StartupReason? = when (this) {
    is StartupUiState.Loading -> reason
    is StartupUiState.Failed -> reason
    is StartupUiState.RepairRequired -> reason
    StartupUiState.Hidden -> null
}

@Composable
private fun StartupGate(
    state: StartupUiState,
    reason: StartupReason,
    onRetry: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
) {
    BackHandler(enabled = true) {}
    val palette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DarkStartupPalette else LightStartupPalette
    // Entrance: the icon lands from the system splash (screen centre, larger) into the lockup
    // while the wordmark and slogan rise in behind it. Recovery overlays start settled.
    // ENTRANCE_START_SCALE matches the system splash glyph: Android draws the adaptive icon in a
    // 288dp container, our lockup icon is 144dp, so the splash glyph is 2× ours. Measured on a
    // HONOR CLK-AN00 (Android 14): 118dp vs 58dp visible H. 1.5× left a visible size step.
    val entrance = remember { Animatable(if (reason == StartupReason.CONNECTION_RECOVERY) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entrance.value < 1f) entrance.animateTo(1f, tween(ENTRANCE_MS, easing = Motion.Standard))
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(palette.background),
    ) {
        val compact = maxHeight < CompactHeightThreshold
        val lockupTop = maxHeight * (if (compact) COMPACT_LOCKUP_TOP_FRACTION else LOCKUP_TOP_FRACTION)
        val iconSize = if (compact) CompactIconSize else IconSize
        val density = LocalDensity.current
        val iconDropPx = with(density) { (maxHeight / 2 - lockupTop - iconSize / 2).toPx() }
        val textLiftPx = with(density) { 12.dp.toPx() }
        val progress = entrance.value
        val textProgress = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = lockupTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val icon: @Composable () -> Unit = {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            val scale = ENTRANCE_START_SCALE + (1f - ENTRANCE_START_SCALE) * progress
                            scaleX = scale
                            scaleY = scale
                            translationY = iconDropPx * (1f - progress)
                        },
                )
            }
            val wordmark: @Composable () -> Unit = {
                Column(
                    horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = textProgress
                        translationY = textLiftPx * (1f - textProgress)
                    },
                ) {
                    Text(
                        text = "HERMES GO", // l10n-allow: official product name is language-invariant
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = if (compact) 24.sp else 32.sp,
                            lineHeight = if (compact) 32.sp else 40.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.16.em,
                        ),
                        textAlign = if (compact) TextAlign.Start else TextAlign.Center,
                    )
                    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                    Text(
                        text = "Your AI agent, in your pocket.", // l10n-allow: official English brand slogan
                        color = palette.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = if (compact) TextAlign.Start else TextAlign.Center,
                    )
                }
            }
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon()
                    Spacer(Modifier.width(20.dp))
                    wordmark()
                }
            } else {
                icon()
                Spacer(Modifier.height(12.dp))
                wordmark()
            }

            when (state) {
                is StartupUiState.Loading -> LoadingGroup(state, palette, compact)
                is StartupUiState.Failed -> FailureGroup(
                    state = state,
                    palette = palette,
                    compact = compact,
                    onRetry = onRetry,
                    onOpenConnectionSettings = onOpenConnectionSettings,
                )
                is StartupUiState.RepairRequired, StartupUiState.Hidden -> Unit
            }
        }

        Text(
            text = startupVersionLabel(BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE),
            color = palette.secondary,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.04.em),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (compact) 16.dp else 32.dp),
        )
    }
}

@Composable
private fun LoadingGroup(state: StartupUiState.Loading, palette: StartupPalette, compact: Boolean) {
    val language = LocalAppLanguage.current
    // Status is withheld for the first STATUS_REVEAL_DELAY_MS. If the gate reaches READY before
    // then, it stays withheld: a fast launch is one quiet fade, not a line that blinks past.
    var revealed by remember(state.reason) { mutableStateOf(false) }
    var suppressed by remember(state.reason) { mutableStateOf(false) }
    LaunchedEffect(state.reason) {
        delay(STATUS_REVEAL_DELAY_MS)
        if (!suppressed) revealed = true
    }
    LaunchedEffect(state.phase) {
        if (state.phase == StartupPhase.READY && !revealed) suppressed = true
    }

    val progress = remember(state.reason) { Animatable(0.05f) }
    val band = state.phase.progressBand()
    LaunchedEffect(state.phase) {
        if (band.activeTarget > progress.value) {
            progress.animateTo(band.activeTarget, tween(480))
        }
        if (band.waitingCap > progress.value) {
            progress.animateTo(
                band.waitingCap,
                tween(if (state.phase == StartupPhase.READY) 360 else 3_800, easing = LinearEasing),
            )
        }
    }

    Spacer(Modifier.height(if (compact) 24.dp else 44.dp))
    AnimatedVisibility(
        visible = revealed,
        enter = fadeIn(tween(Motion.DurationMedium)),
        exit = ExitTransition.None,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Crossfade(
                targetState = startupStatusText(state.phase, state.reason, language),
                animationSpec = tween(180),
                label = "startup-status",
            ) { status ->
                Text(
                    text = status,
                    color = palette.secondary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            StartupProgress(progress.value, active = state.phase != StartupPhase.READY, palette = palette)
        }
    }
}

@Composable
private fun StartupProgress(progress: Float, active: Boolean, palette: StartupPalette) {
    val fraction = progress.coerceIn(0f, 1f)
    // The moving highlight is the gate's only continuous motion; it stops once READY.
    var sweep: Float? = null
    if (active) {
        sweep = rememberInfiniteTransition(label = "startup-progress-sweep").animateFloat(
            initialValue = -0.25f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "startup-progress-highlight",
        ).value
    }
    Box(
        modifier = Modifier
            .width(ProgressWidth)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.track)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            .drawWithContent {
                drawContent()
                clipRect(right = size.width * fraction) {
                    drawRect(Brush.horizontalGradient(ProgressColors, startX = 0f, endX = size.width))
                    if (sweep != null) {
                        drawRect(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                                startX = (sweep - 0.18f) * size.width,
                                endX = (sweep + 0.18f) * size.width,
                            ),
                        )
                    }
                }
            },
    )
}

@Composable
private fun FailureGroup(
    state: StartupUiState.Failed,
    palette: StartupPalette,
    compact: Boolean,
    onRetry: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val summary = when (state.failure) {
        StartupFailure.DEVICE_OFFLINE -> localized(
            language,
            "当前网络不可用，请检查网络连接。",
            "No usable network is available. Check your connection.",
        )
        StartupFailure.CONNECTION_FAILED -> localized(
            language,
            "无法连接 Relay，请重试。",
            "Couldn't connect to the Relay. Retry.",
        )
        StartupFailure.CONNECTOR_OFFLINE -> localized(
            language,
            "Mac 端当前离线，请确认 Hermes Go Desktop 正在运行。",
            "The Mac is offline. Make sure Hermes Go Desktop is running.",
        )
        StartupFailure.INITIAL_DATA_FAILED -> localized(
            language,
            "无法加载首屏数据，请重试。",
            "Couldn't load the initial screen. Retry.",
        )
        StartupFailure.CONFIGURATION_FAILED -> localized(
            language,
            "无法读取连接配置，请检查设置。",
            "Couldn't read the connection configuration. Check settings.",
        )
        StartupFailure.INVALID_URL -> localized(
            language,
            "Relay 地址无效，请检查设置。",
            "The Relay URL is invalid. Check settings.",
        )
        StartupFailure.AUTHENTICATION_FAILED -> localized(
            language,
            "连接凭据无效，请检查设置。",
            "The connection credentials are invalid. Check settings.",
        )
    }
    Spacer(Modifier.height(if (compact) 20.dp else 36.dp))
    Text(
        text = summary,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = state.failure.code,
        color = palette.secondary,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
        ),
    )
    Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
    Button(onClick = onRetry, modifier = Modifier.width(ActionWidth).height(44.dp)) {
        Text(localized(language, "重新连接", "Reconnect"))
    }
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onOpenConnectionSettings, modifier = Modifier.width(ActionWidth)) {
        Text(localized(language, "检查连接设置", "Check connection settings"))
    }
}
