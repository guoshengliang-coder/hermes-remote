package com.hermes.client.domain

import com.hermes.client.data.network.LaneDto
import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.ProjectNodeDto
import com.hermes.client.data.network.ProjectTreeDto
import com.hermes.client.data.network.RepoDto
import com.hermes.client.data.network.SessionDto

fun SessionDto.toDomain() = Session(
    id = sessionId,
    title = title?.ifBlank { "Untitled" } ?: "Untitled",
    model = model,
    provider = provider,
    messageCount = messageCount,
    // Normalize the default profile to a stable "default" label: the gateway may report it as
    // null or blank with is_default_profile=true, but grouping/pin-tokens/switching need one key.
    profile = profile?.ifBlank { null } ?: if (isDefaultProfile) "default" else null,
    workspace = cwd?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "No workspace",
    archived = archived,
    source = source,
    lastActive = com.hermes.client.ui.util.secondsToEpochMs(lastActive),
    cwd = cwd?.ifBlank { null },
    gitBranch = gitBranch?.ifBlank { null },
    gitRepoRoot = gitRepoRoot?.ifBlank { null },
)

private val IMAGE_DIRECTIVE = Regex(
    "(?m)^\\s*@image:(?:\\\"([^\\\"]+)\\\"|'([^']+)'|`([^`]+)`|(.+?))\\s*$",
)
private val ATTACHED_IMAGE_PLACEHOLDER = Regex(
    "(?m)^\\s*\\[User attached image:[^]]+]\\s*$",
    RegexOption.IGNORE_CASE,
)

internal data class ParsedMessageContent(val text: String, val images: List<ChatImage>)

/** Hermes persists attachments as `@image:/absolute/path`; keep the path out of visible chat. */
internal fun parseMessageContent(raw: String): ParsedMessageContent {
    val images = IMAGE_DIRECTIVE.findAll(raw).mapIndexed { index, match ->
        val path = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        ChatImage(
            id = "remote-${path.hashCode()}-$index",
            mimeType = when (path.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> null
            },
            remotePath = path,
        )
    }.toList()
    val visible = raw
        .replace(IMAGE_DIRECTIVE, "")
        .replace(ATTACHED_IMAGE_PLACEHOLDER, "")
        .lines()
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")
    return ParsedMessageContent(visible, images)
}

fun MessageDto.toDomain(): ChatMessage {
    val parsed = parseMessageContent(content.orEmpty())
    return ChatMessage(
        id = id?.toString() ?: "m-${hashCode()}",
        role = when (role.lowercase()) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> Role.SYSTEM
        },
        text = parsed.text,
        images = parsed.images,
    )
}

fun ProjectTreeDto.toDomain() = ProjectTree(
    projects = projects.map { it.toDomain() },
    activeId = activeId,
)

fun ProjectNodeDto.toDomain() = Project(
    id = id,
    label = label,
    path = path,
    color = color?.ifBlank { null },
    isAuto = isAuto,
    sessionCount = sessionCount,
    lastActive = com.hermes.client.ui.util.secondsToEpochMs(lastActive),
    repos = repos.map { it.toDomain() },
    previewSessions = previewSessions.map { it.toDomain() },
)

fun RepoDto.toDomain() = ProjectRepo(
    id = id,
    label = label,
    path = path,
    sessionCount = sessionCount,
    lanes = groups.map { it.toDomain() },
)

fun LaneDto.toDomain() = ProjectLane(
    id = id,
    label = label,
    path = path,
    isMain = isMain,
    sessions = sessions.map { it.toDomain() },
)
