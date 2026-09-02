package com.hermes.client.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Project
import com.hermes.client.domain.Session
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.components.HomeFolderStrokeIcon
import com.hermes.client.ui.components.ThinChevronIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.util.relativeTimeLabel

/** Parse the gateway's "#RRGGBB" project color; fall back to the tenant accent when null/invalid. */
@Composable
private fun projectTint(color: String?): Color {
    val accent = MaterialTheme.colorScheme.primary
    return color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: accent
}

/** Projects overview: the default project first, then one tappable row per project. */
@Composable
fun ProjectOverview(projects: List<Project>, nowMs: Long, onOpenProject: (Project) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(projects, key = { it.id }) { p -> ProjectCard(p, nowMs, onClick = { onOpenProject(p) }) }
    }
}

/**
 * Entry-row paradigm (docs/DESIGN.md §5.1): stroke glyph + title (+ subline) + thin chevron. The
 * default project uses the house-folder in the muted colour; real projects the folder with the
 * project colour on the LINE (the filled glyph read as a solid block).
 */
@Composable
fun ProjectCard(project: Project, nowMs: Long, onClick: () -> Unit) {
    val language = LocalAppLanguage.current
    val isDefault = project.id == DEFAULT_PROJECT_ID
    val count = l10n(
        "${project.sessionCount} 个会话",
        "${project.sessionCount} session${if (project.sessionCount == 1) "" else "s"}",
    )
    val parts = buildList {
        if (isDefault) add(l10n("网关启动目录", "Gateway launch directory"))
        add(count)
        if (project.lastActive != null) add(relativeTimeLabel(project.lastActive, nowMs, language))
    }
    ListItem(
        headlineContent = { Text(projectDisplayLabel(project), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                if (isDefault) HomeFolderStrokeIcon else FolderStrokeIcon,
                contentDescription = null,
                tint = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant else projectTint(project.color),
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = {
            Icon(ThinChevronIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Drill-in for one project: a back row with the project's path beneath it, then its sessions with
 * a `branch · model` subline (the project itself is the page header, so it is not repeated). A
 * single-lane project renders a flat list; multi-lane projects show repo/branch sub-headers.
 */
@Composable
fun ProjectScopeView(
    project: Project,
    defaultProjectPath: String?,
    onBack: () -> Unit,
    onOpenSession: (Session) -> Unit,
) {
    val lanes = project.repos.flatMap { repo -> repo.lanes.map { repo to it } }
    val multi = lanes.size > 1
    val isDefault = project.id == DEFAULT_PROJECT_ID
    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "back") {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onBack).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                    Text(projectDisplayLabel(project), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val pathLine = when {
                    isDefault -> {
                        val where = project.path ?: l10n("网关启动目录", "Gateway launch directory")
                        l10n("$where · 会话页新建的会话都在这里", "$where · Chats created from Sessions live here")
                    }
                    else -> project.path.orEmpty()
                }
                if (pathLine.isNotBlank()) {
                    Text(
                        pathLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
            }
        }
        lanes.forEach { (repo, lane) ->
            if (multi) {
                item(key = "lane-${repo.id}-${lane.id}") {
                    Text(
                        "${repo.label} · ${lane.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(lane.sessions, key = { "sess-${it.id}" }) { s ->
                ListItem(
                    headlineContent = { Text(s.title) },
                    supportingContent = { SessionSubline(s, lead = SublineLead.BRANCH, defaultProjectPath = defaultProjectPath) },
                    modifier = Modifier.clickable { onOpenSession(s) },
                )
            }
        }
        if (lanes.all { it.second.sessions.isEmpty() }) {
            item(key = "empty-scope") {
                Text(
                    l10n("这个项目中还没有会话。", "No sessions in this project."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
