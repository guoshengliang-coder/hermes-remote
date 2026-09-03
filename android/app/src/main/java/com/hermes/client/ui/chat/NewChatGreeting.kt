package com.hermes.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.statusColor
import androidx.compose.ui.graphics.luminance

/**
 * The new-session empty state (design 2026-09-03): one centred greeting group — identity
 * avatar, a time-of-day greeting, a fixed subline, and a DISPLAY-ONLY status pill (model ·
 * reasoning effort, with a connection dot). Nothing here is tappable: model and effort switch
 * in the composer exactly as before; the pill merely mirrors them. The group shrinks one step
 * while the IME is up and the whole thing fades out with the first message.
 */

/** Time-of-day greeting bucket. Pure — [hourOfDay] is 0..23 — so it is unit-testable. */
fun greetingForHour(hourOfDay: Int, name: String?, language: AppLanguage): String {
    val base = when (hourOfDay) {
        in 5..8 -> localized(language, "早上好", "Good morning")
        in 9..13 -> localized(language, "上午好", "Hello")
        in 14..18 -> localized(language, "下午好", "Good afternoon")
        in 19..23 -> localized(language, "晚上好", "Good evening")
        else -> localized(language, "夜深了", "Up late")
    }
    // A custom identity name joins the greeting; no placeholder name otherwise.
    return if (name.isNullOrBlank()) base else localized(language, "$base，$name", "$base, $name")
}

@Composable
internal fun NewChatGreeting(
    profile: String?,
    identityName: String?,
    modelLabel: String,
    connection: ConnectionState,
    imeVisible: Boolean,
    modifier: Modifier = Modifier,
    // Injectable so screenshot tests are stable across the time of day they run at.
    hourOfDay: Int = java.time.LocalTime.now().hour,
) {
    val language = LocalAppLanguage.current
    val connected = connection is ConnectionState.Connected
    val greeting = greetingForHour(hourOfDay, identityName, language)
    val subline = if (connected) localized(language, "有什么要做的，直接说。", "Whatever you need — just say it.")
    else localized(language, "连接恢复后就能开始。", "Ready as soon as we reconnect.")
    val pillText = if (connected) modelLabel else localized(language, "等待连接…", "Waiting for connection…")
    Box(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        // Optical centre: the group sits a little above geometric centre (bias -0.12).
        contentAlignment = androidx.compose.ui.BiasAlignment(0f, -0.12f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics {
                contentDescription = localized(
                    language,
                    "新会话。$greeting。$subline 当前 $pillText",
                    "New session. $greeting. $subline Currently $pillText",
                )
            },
        ) {
            ProfileAvatar(
                profile,
                size = if (imeVisible) 48.dp else 64.dp,
                modifier = if (connected) Modifier else Modifier.alpha(0.7f),
            )
            Spacer(Modifier.height(if (imeVisible) 12.dp else 18.dp))
            Text(
                greeting,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (imeVisible) 19.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                textAlign = TextAlign.Center,
            )
            if (!imeVisible) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(if (imeVisible) 8.dp else 16.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor(if (connected) StatusTone.GOOD else StatusTone.WARN, dark)),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        pillText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
