package com.hermes.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.l10n
import com.hermes.client.data.error.AppError
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.coroutines.delay

// Shared screen-state surfaces so loading/empty/error look identical everywhere instead of
// the ad-hoc CircularProgressIndicator + bare `Text(error!!)` that was copy-pasted across
// ~12 screens. Every screen should route through these.

/** A wait long enough to explain itself; below this the mark stands alone (docs/DESIGN.md §5.6). */
internal const val LOADING_LABEL_DELAY_MS = 3_000L

/** Page-level indeterminate wait for screens whose content shape is not known in advance. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DelayedReveal {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HermesMark(size = 32.dp, contentDescription = l10n("正在加载", "Loading"))
                if (label != null) {
                    // "Loading…" is noise on a fast page; the label is for waits that overstay.
                    var explain by remember(label) { mutableStateOf(false) }
                    LaunchedEffect(label) {
                        delay(LOADING_LABEL_DELAY_MS)
                        explain = true
                    }
                    if (explain) {
                        Spacer(Modifier.height(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * First load of a list whose row shape is known (sessions / projects / archived / search share
 * one). Skeleton rows keep the layout still, so content arrival is not a jump from empty to full.
 */
@Composable
fun ListLoadingState(modifier: Modifier = Modifier, rows: Int = SKELETON_MAX_ROWS) {
    DelayedReveal { SkeletonRows(rows = rows, modifier = modifier) }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) { Text(l10n("重试", "Retry")) }
        }
    }
}

@Composable
fun ErrorState(
    error: AppError,
    modifier: Modifier = Modifier.fillMaxSize(),
    onRetry: (() -> Unit)? = null,
) = ErrorState(
    message = error.localizedMessage(LocalAppLanguage.current),
    modifier = modifier,
    onRetry = onRetry,
)

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    subtitle: String? = null,
    icon: ImageVector = Icons.Rounded.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
