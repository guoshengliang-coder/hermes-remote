package com.hermes.client.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.client.data.video.VideoNote
import com.hermes.client.data.video.VideoPrecacheState
import com.hermes.client.ui.localization.*

internal data class VideoAnnotationTimeline(
    val notes: List<VideoNote> = emptyList(), val enabled: Boolean = true,
    val select: (List<String>) -> Unit = {},
)
internal data class VideoNoteCluster(val notes: List<VideoNote>, val centerDp: Float)

/** Cluster in screen space, including clamped edge targets; keep every hit area >=48dp and disjoint. */
internal fun clusterVideoNotes(notes: List<VideoNote>, duration: Long, widthDp: Float): List<VideoNoteCluster> {
    if (duration <= 0 || widthDp < 48f || !widthDp.isFinite()) return emptyList()
    val groups = mutableListOf<MutableList<Pair<VideoNote, Float>>>()
    notes.filter { it.valid(duration) }.sortedWith(compareBy<VideoNote> { it.startMs }.thenBy { it.id }).forEach { note ->
        val x = (6f + note.startMs.toDouble() / duration * (widthDp - 12f)).toFloat().coerceIn(24f, widthDp - 24f)
        val previous = groups.lastOrNull()
        if (previous != null && x - previous.last().second < 48f) previous += note to x
        else groups += mutableListOf(note to x)
    }
    return groups.map { group -> VideoNoteCluster(group.map { it.first }, group.map { it.second.toDouble() }.average().toFloat()) }
}

@Composable
internal fun VideoAnnotationTrack(timeline: VideoAnnotationTimeline, duration: Long, position: Long, enabled: Boolean) {
    val language = LocalAppLanguage.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).testTag("video-note-track")) {
        val width = maxWidth.value
        val clusters = remember(timeline.notes, duration, width) { clusterVideoNotes(timeline.notes, duration, width) }
        val available = enabled && timeline.enabled
        Canvas(Modifier.fillMaxSize()) {
            fun x(ms: Long) = 6.dp.toPx() + (size.width - 12.dp.toPx()) * (ms.toDouble() / duration).toFloat()
            timeline.notes.filter { it.valid(duration) }.forEach { note ->
                note.endMs?.let { end ->
                    drawLine(Color.White.copy(alpha = if (available) .4f else .18f),
                        Offset(x(note.startMs), size.height / 2), Offset(x(end), size.height / 2), 3.dp.toPx(), StrokeCap.Round)
                }
            }
            // The touch target is clamped at edges, while the visual locator stays at its true time.
            clusters.filter { it.notes.size == 1 }.forEach { cluster ->
                val note = cluster.notes.single()
                val active = position >= note.startMs && position <= (note.endMs ?: (note.startMs + 1_000))
                drawCircle(Color.White.copy(alpha = if (!available) .3f else if (active) 1f else .7f),
                    5.dp.toPx(), Offset(x(note.startMs), size.height / 2))
            }
        }
        clusters.forEach { cluster ->
            val note = cluster.notes.first()
            val active = cluster.notes.any { position >= it.startMs && position <= (it.endMs ?: (it.startMs + 1_000)) }
            val label = if (cluster.notes.size > 1) localized(language, "${cluster.notes.size} 条批注", "${cluster.notes.size} notes")
                else localized(language, "查看批注：", "View note: ") + referenceTime(note.startMs, note.endMs)
            Box(Modifier.offset(x = (cluster.centerDp - 24f).dp).size(48.dp)
                .testTag("video-note-marker:${cluster.notes.joinToString(",") { it.id }}")
                .semantics { contentDescription = label }
                .clickable(enabled = available, role = Role.Button) { timeline.select(cluster.notes.map { it.id }) },
                contentAlignment = Alignment.Center) {
                val color = Color.White.copy(alpha = if (!available) .3f else if (active) 1f else .7f)
                if (cluster.notes.size > 1) Text(cluster.notes.size.toString(),
                    Modifier.background(Color.Black.copy(alpha = .85f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Read-only until the user explicitly chooses Edit; it never requests input focus. */
@Composable
internal fun VideoNotePreview(notes: List<VideoNote>, select: (String) -> Unit, edit: (String) -> Unit,
    close: () -> Unit, modifier: Modifier = Modifier) {
    val language = LocalAppLanguage.current
    Surface(modifier.testTag("video-note-preview")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp)) {
            Text(localized(language, "批注 · ${notes.size}", "Notes · ${notes.size}"), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(notes, key = { it.id }) { note ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        TextButton(onClick = { select(note.id) }, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("video-note-preview-time:${note.id}")) {
                            Text(referenceTime(note.startMs, note.endMs))
                        }
                        Text(note.text, style = MaterialTheme.typography.bodyMedium)
                        if (notes.size > 1) TextButton(onClick = { edit(note.id) }) { Text(localized(language, "编辑", "Edit")) }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notes.size == 1) OutlinedButton(onClick = { edit(notes.single().id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(localized(language, "编辑", "Edit"))
                }
                TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp).testTag("video-note-preview-close")) {
                    Text(localized(language, "关闭批注", "Close notes"))
                }
            }
        }
    }
}

@Composable
internal fun VideoCacheProgress(cache: VideoPrecacheState, speed: String?, onDetails: () -> Unit) {
    val language = LocalAppLanguage.current
    if (cache.size <= 0 && speed == null) return
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("video-cache-progress")
        .then(if (cache.size > 0) Modifier.semantics { contentDescription = localized(language, "缓存详情", "Cache details") }
            .clickable(role = Role.Button, onClick = onDetails) else Modifier), verticalArrangement = Arrangement.Center) {
        if (cache.size > 0) LinearProgressIndicator(progress = { cache.percent / 100f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = Color.White.copy(alpha = .7f), trackColor = Color.White.copy(alpha = .2f))
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (cache.size > 0) Text(if (cache.cleared) localized(language, "预缓存已停止", "Precaching stopped")
                else localized(language, "预览缓存 ${cache.percent}%", "Preview cache ${cache.percent}%"),
                color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
            speed?.let { Text(it, Modifier.testTag("video-cache-speed"), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall) }
        }
    }
}
