package com.hermes.client.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.R
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

private val StartupBackground = Color(0xFFFBFAF7)
private val StartupText = Color(0xFF777775)
private val ProgressTrack = Color(0xFFE8E8E5)
private val ProgressColors = listOf(Color(0xFF1689F5), Color(0xFF3CB672), Color(0xFFFFC400))

@Composable
fun StartupScreen(
    state: StartupUiState,
    onRetry: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    if (state is StartupUiState.Hidden) return
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
                            colors = listOf(Color(0x14258BF4), Color.Transparent),
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
                    onContinueOffline = onContinueOffline,
                )
                StartupUiState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun LoadingFooter(state: StartupUiState.Loading) {
    val language = LocalAppLanguage.current
    val progress by animateFloatAsState(
        targetValue = state.phase.progress,
        animationSpec = tween(320),
        label = "startup-progress",
    )
    Text(
        text = when (state.phase) {
            StartupPhase.CONFIGURATION -> localized(language, "正在检查配置…", "Checking configuration…")
            StartupPhase.NETWORK -> localized(language, "正在检查网络…", "Checking network…")
            StartupPhase.AUTHENTICATION -> localized(language, "正在验证连接凭据…", "Verifying connection credentials…")
            StartupPhase.CONNECTION -> if (state.reason == StartupReason.COLD_START) {
                localized(language, "正在建立安全连接…", "Establishing a secure connection…")
            } else {
                localized(language, "正在恢复安全连接…", "Restoring the secure connection…")
            }
            StartupPhase.INITIAL_DATA -> localized(language, "正在准备会话…", "Preparing conversations…")
            StartupPhase.READY -> localized(language, "连接就绪", "Connection ready")
        },
        color = StartupText,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    StartupProgress(progress)
}

@Composable
private fun StartupProgress(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(ProgressTrack)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Brush.horizontalGradient(ProgressColors)),
        )
    }
}

@Composable
private fun FailureFooter(
    state: StartupUiState.Failed,
    onRetry: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onContinueOffline: () -> Unit,
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
        StartupFailure.INITIAL_DATA_FAILED -> localized(
            language,
            "无法加载首屏数据，请重试。",
            "Couldn't load the initial screen. Retry.",
        )
    }
    Text(
        text = "$summary (${state.failure.code})",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(localized(language, "重新连接", "Reconnect"))
    }
    TextButton(onClick = onOpenConnectionSettings, modifier = Modifier.fillMaxWidth()) {
        Text(localized(language, "检查连接设置", "Check connection settings"))
    }
    TextButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth()) {
        Text(localized(language, "暂时进入应用", "Continue for now"))
    }
}
