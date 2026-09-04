package com.hermes.client.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.HermesLightColors
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the whole conversation into a GraphicsLayer on a zero-sized, non-clipping host — the
 * content records without ever reaching the screen — then shares the capture as a PNG.
 *
 * Two deliberate choices (docs/DESIGN.md §5): the image is ALWAYS rendered in the light scheme
 * regardless of the app's theme (a dark full-page image reads heavy in someone else's chat and
 * survives messenger recompression worse), and a footer stamps the conversation title and export
 * date so the recipient knows what they are looking at. Callers must check
 * [transcriptImageFitsBudget] BEFORE mounting this: over budget the capture would fail or OOM.
 */
@Composable
internal fun OffscreenTranscriptExporter(
    title: String?,
    messages: List<ChatMessage>,
    exportedAtMillis: Long,
    onDone: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val layer = rememberGraphicsLayer()
    val body = remember(messages) { messages.filter { it.text.isNotBlank() || it.images.isNotEmpty() } }
    val heading = title?.trim()?.ifBlank { null } ?: localized(language, "对话记录", "Chat transcript")
    val stamp = remember(exportedAtMillis) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(exportedAtMillis))
    }

    Box(
        Modifier
            .size(0.dp)
            .wrapContentSize(align = Alignment.TopStart, unbounded = true),
    ) {
        // Force the light scheme for the export; the surrounding app may be dark.
        androidx.compose.material3.MaterialTheme(colorScheme = HermesLightColors) {
            Column(
                Modifier
                    .width(TRANSCRIPT_IMAGE_WIDTH_DP.dp)
                    .background(MaterialTheme.colorScheme.background)
                    // record only — no drawLayer, so nothing appears on screen.
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() } }
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "$stamp · Hermes GO",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )

                body.forEach { message -> TranscriptTurn(message) }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                Text(
                    localized(language, "由 Hermes GO 导出", "Exported from Hermes GO"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    LaunchedEffect(messages, exportedAtMillis) {
        // Two frames: one for layout, one for draw, before the layer holds the full content.
        withFrameNanos { }
        withFrameNanos { }
        val ok = runCatching {
            val bitmap = layer.toImageBitmap().asAndroidBitmap()
            TranscriptShare.shareImage(
                context = context,
                baseName = transcriptFileBaseName(title, exportedAtMillis),
                bitmap = bitmap,
                chooserTitle = localized(language, "分享对话长图", "Share transcript image"),
                subject = heading,
            )
        }.getOrDefault(false)
        onDone(ok)
    }
}

@Composable
private fun TranscriptTurn(message: ChatMessage) {
    val language = LocalAppLanguage.current
    val label = when {
        message.isError -> localized(language, "错误", "Error")
        message.role == Role.SYSTEM -> localized(language, "系统", "System")
        message.role == Role.USER -> localized(language, "你", "You")
        else -> localized(language, "助手", "Assistant")
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (message.isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        )
        if (message.text.isNotBlank()) {
            if (message.role == Role.USER) {
                // Mirrors the on-screen user bubble so the export reads like the conversation.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        message.text.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                val mdState = com.mikepenz.markdown.model.rememberMarkdownState(
                    message.text.trim(),
                    immediate = true,
                )
                Markdown(
                    markdownState = mdState,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = markdownColor(),
                    typography = markdownTypography(
                        text = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
                    ),
                )
            }
        }
        message.images.forEach { image -> TranscriptImage(image) }
    }
}

/**
 * Already-cached images render for real; anything not on disk yet becomes a labelled placeholder.
 * Export never triggers a download — a share action must not block on the network.
 */
@Composable
private fun TranscriptImage(image: ChatImage) {
    val language = LocalAppLanguage.current
    val bitmap = remember(image.id, image.localPath) {
        image.localPath
            ?.takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    localized(language, "[图片未下载]", "[Image not downloaded]"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
