package com.hermes.client.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.ui.localization.l10n

/**
 * Shown on the first launch after a crash (the trace was saved by [CrashReporter]). Lets the user
 * share the stack trace so the crash can be diagnosed without adb, then continue into the app.
 *
 * [onReport] files the trace with MissionGo instead of handing it to a share target; it is null
 * when the build has no feedback configuration, and the button is then absent rather than inert.
 */
@Composable
fun CrashReportScreen(
    report: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onReport: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(l10n("Hermes GO 崩溃了", "Hermes GO crashed"), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (onReport != null) {
                    l10n(
                        "这是崩溃详情。可以直接上报给开发者，或分享出去，然后继续。",
                        "This is the crash details. Report it to the developer, or share it, then continue.",
                    )
                } else {
                    l10n("这是崩溃详情。点击分享发送给开发者，然后继续。", "This is the crash details. Tap Share to send it to the developer, then continue.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                Modifier.weight(1f).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    report,
                    modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Reporting is the primary action when it is available: it reaches the tracker
                // directly, where sharing depends on the user picking a target and following up.
                if (onReport != null) {
                    Button(onClick = onReport) { Text(l10n("上报", "Report")) }
                    OutlinedButton(onClick = onShare) { Text(l10n("分享", "Share")) }
                } else {
                    Button(onClick = onShare) { Text(l10n("分享", "Share")) }
                    Spacer(Modifier.width(4.dp))
                }
                OutlinedButton(onClick = onDismiss) { Text(l10n("继续使用", "Continue to app")) }
            }
        }
    }
}
