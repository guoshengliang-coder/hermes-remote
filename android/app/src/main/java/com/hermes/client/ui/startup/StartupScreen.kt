package com.hermes.client.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.R
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

// This screen was already icon-coloured while the app itself was mint, so the splash used to
// change colour on the first frame. The palette now agrees with the brand: the same cool white
// as @color/startup_background (the window background behind this composable) and a lead
// progress colour aligned to the brand hue instead of the old 209 degree, slightly cyan blue.
private val StartupBackground = Color(0xFFF8FAFD)
private val StartupText = Color(0xFF74777F)
private val ProgressTrack = Color(0xFFE7ECF6)
private val ProgressColors = listOf(Color(0xFF1F84FD), Color(0xFF3DA340), Color(0xFFFEC302))

private data class ProgressBand(val activeTarget: Float, val waitingCap: Float)

private fun StartupPhase.progressBand(): ProgressBand = when (this) {
    StartupPhase.CONFIGURATION -> ProgressBand(0.14f, 0.19f)
    StartupPhase.NETWORK -> ProgressBand(0.26f, 0.31f)
    StartupPhase.AUTHENTICATION -> ProgressBand(0.42f, 0.47f)
    StartupPhase.CONNECTION -> ProgressBand(0.65f, 0.73f)
    StartupPhase.INITIAL_DATA -> ProgressBand(0.86f, 0.93f)
    StartupPhase.READY -> ProgressBand(1f, 1f)
}

@Composable
fun StartupScreen(
    state: StartupUiState,
    onRetry: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
) {
    if (state is StartupUiState.Hidden || state is StartupUiState.RepairRequired) return
    BackHandler(enabled = true) {}

    Box(
        modifier = Modifier.fillMaxSize().background(StartupBackground),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-48).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x141F84FD), Color.Transparent),
                        ),
                        RoundedCornerShape(150.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(250.dp),
                )
            }
            Text(
                text = "HERMES GO", // l10n-allow: official product name is language-invariant
                color = Color(0xFF252B33),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Your AI agent, in your pocket.", // l10n-allow: official English brand slogan
                color = StartupText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is StartupUiState.Loading -> LoadingFooter(state)
                is StartupUiState.Failed -> FailureFooter(
                    state = state,
                    onRetry = onRetry,
                    onOpenConnectionSettings = onOpenConnectionSettings,
                )
                is StartupUiState.RepairRequired -> Unit
                StartupUiState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun LoadingFooter(state: StartupUiState.Loading) {
    val language = LocalAppLanguage.current
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
    val status = when (state.phase) {
        StartupPhase.CONFIGURATION -> localized(language, "正在检查配置", "Checking configuration")
        StartupPhase.NETWORK -> localized(language, "正在检查网络", "Checking network")
        StartupPhase.AUTHENTICATION -> localized(language, "正在验证连接凭据", "Verifying connection credentials")
        StartupPhase.CONNECTION -> if (state.reason != StartupReason.CONNECTION_RECOVERY) {
            localized(language, "正在建立安全连接", "Establishing a secure connection")
        } else {
            localized(language, "正在恢复安全连接", "Restoring the secure connection")
        }
        StartupPhase.INITIAL_DATA -> if (state.reason == StartupReason.CONNECTION_RECOVERY) {
            localized(language, "正在恢复当前页面", "Restoring the current screen")
        } else {
            localized(language, "正在准备会话", "Preparing conversations")
        }
        StartupPhase.READY -> localized(language, "连接就绪", "Connection ready")
    }
    Crossfade(
        targetState = state.phase to status,
        animationSpec = tween(180),
        label = "startup-status",
    ) { (currentPhase, currentStatus) ->
        if (currentPhase == StartupPhase.READY) {
            Text(
                text = currentStatus,
                color = StartupText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            AnimatedLoadingStatus(currentStatus)
        }
    }
    Spacer(Modifier.height(18.dp))
    StartupProgress(progress.value, active = state.phase != StartupPhase.READY)
}
@Composable
private fun AnimatedLoadingStatus(status: String) {
    var dotCount by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(350L)
            dotCount = dotCount % 3 + 1
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = status },
    ) {
        Text(
            text = status,
            color = StartupText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.size(width = 24.dp, height = 20.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = ".".repeat(dotCount),
                color = StartupText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StartupProgress(progress: Float, active: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(ProgressTrack)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Brush.horizontalGradient(ProgressColors)),
        ) {
            if (active) {
                val sweep by rememberInfiniteTransition(label = "startup-progress-sweep").animateFloat(
                    initialValue = -0.25f,
                    targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1_200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "startup-progress-highlight",
                )
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                                startX = (sweep - 0.18f) * widthPx,
                                endX = (sweep + 0.18f) * widthPx,
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun FailureFooter(
    state: StartupUiState.Failed,
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
    Text(
        text = "$summary (${state.failure.code})",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    StartupProgress(state.failurePhase().progressBand().waitingCap, active = false)
    Spacer(Modifier.height(18.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(localized(language, "重新连接", "Reconnect"))
    }
    TextButton(onClick = onOpenConnectionSettings, modifier = Modifier.fillMaxWidth()) {
        Text(localized(language, "检查连接设置", "Check connection settings"))
    }
}

private fun StartupUiState.Failed.failurePhase(): StartupPhase = when (failure) {
    StartupFailure.DEVICE_OFFLINE -> StartupPhase.NETWORK
    StartupFailure.CONNECTION_FAILED -> StartupPhase.CONNECTION
    StartupFailure.CONNECTOR_OFFLINE -> StartupPhase.CONNECTION
    StartupFailure.INITIAL_DATA_FAILED -> StartupPhase.INITIAL_DATA
    StartupFailure.CONFIGURATION_FAILED,
    StartupFailure.INVALID_URL -> StartupPhase.CONFIGURATION
    StartupFailure.AUTHENTICATION_FAILED -> StartupPhase.AUTHENTICATION
}
