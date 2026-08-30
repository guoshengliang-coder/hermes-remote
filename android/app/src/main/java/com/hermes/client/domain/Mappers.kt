package com.hermes.client.domain

import com.hermes.client.data.network.LaneDto
import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.ProjectNodeDto
import com.hermes.client.data.network.ProjectTreeDto
import com.hermes.client.data.network.RepoDto
import com.hermes.client.data.network.SessionDto
import java.net.URI

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
private val FILE_DIRECTIVE = Regex(
    "(?m)^\\s*@file:(?:\\\"([^\\\"]+)\\\"|'([^']+)'|`([^`]+)`|(.+?))\\s*$",
)
private val ATTACHED_FILE_PLACEHOLDER = Regex(
    "(?m)^\\s*\\[User attached (?:file|PDF):[^]]+]\\s*$",
    RegexOption.IGNORE_CASE,
)
private val MARKDOWN_IMAGE = Regex("!\\[([^]]*)]\\((https://[^\\s)]+)(?:\\s+[\"'][^)]*)?\\)")
private val LOCAL_MARKDOWN_IMAGE = Regex(
    "!\\[([^]\\r\\n]*)]\\(\\s*(<?(?:file://)?/[^)\\r\\n>]*?\\.(?:png|jpe?g|gif|webp)>?)(?:\\s+[\"'][^)\\r\\n]*[\"'])?\\s*\\)",
    RegexOption.IGNORE_CASE,
)
private val LABELED_IMAGE_PATH = Regex(
    "^\\s*(?:图片(?:的)?保存(?:路径|位置)|图片路径|图片(?:已)?保存(?:到|至|在)|生成(?:的)?图片(?:保存)?(?:路径|位置)|image\\s+(?:saved|written|stored)(?:\\s+(?:to|at))?|generated\\s+image\\s+(?:path|location))\\s*(?:[：:]|\\s)\\s*(.+?)\\s*$",
    RegexOption.IGNORE_CASE,
)
private val IMAGE_PATH_EXTENSION = Regex(
    "\\.(?:png|jpe?g|gif|webp)(?=$|[\\s`\"'<>，。；;）)\\]])",
    RegexOption.IGNORE_CASE,
)
private val NEXT_FIELD_LABEL = Regex("^[^/\\\\]{1,40}[：:].*$")

internal data class ParsedMessageContent(
    val text: String,
    val images: List<ChatImage>,
    val files: List<ChatFile>,
)

private data class LabeledImageExtraction(
    val text: String,
    val paths: List<String>,
)

/** Hermes persists attachments as `@image:/absolute/path`; keep the path out of visible chat. */
internal fun parseMessageContent(raw: String): ParsedMessageContent {
    val labeled = extractLabeledImagePaths(raw)
    val pathImages = IMAGE_DIRECTIVE.findAll(labeled.text).mapNotNull { match ->
        match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            ?.let(::normalizeLocalImagePath)
            ?.let(::remoteImage)
    }.toList()
    val labeledImages = labeled.paths.map(::remoteImage)
    val localMarkdownImages = LOCAL_MARKDOWN_IMAGE.findAll(labeled.text).mapNotNull { match ->
        normalizeLocalImagePath(match.groupValues[2])?.let(::remoteImage)
    }.toList()
    val webImages = MARKDOWN_IMAGE.findAll(labeled.text).mapIndexed { index, match ->
        val url = match.groupValues[2]
        ChatImage(
            id = "web-${url.hashCode()}-$index",
            sourceUrl = url,
        )
    }.toList()
    val images = (pathImages + labeledImages + localMarkdownImages + webImages)
        .distinctBy { it.remotePath ?: it.sourceUrl ?: it.id }
    val files = FILE_DIRECTIVE.findAll(labeled.text).mapIndexed { index, match ->
        val path = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
        ChatFile(
            id = "file-${path.hashCode()}-$index",
            name = name,
            mimeType = mimeTypeForName(name),
            remotePath = path,
        )
    }.toList()
    val visible = labeled.text
        .replace(IMAGE_DIRECTIVE, "")
        .replace(ATTACHED_IMAGE_PLACEHOLDER, "")
        .replace(FILE_DIRECTIVE, "")
        .replace(ATTACHED_FILE_PLACEHOLDER, "")
        .replace(LOCAL_MARKDOWN_IMAGE) { it.groupValues[1].takeIf(String::isNotBlank).orEmpty() }
        .replace(MARKDOWN_IMAGE) { it.groupValues[1].takeIf(String::isNotBlank).orEmpty() }
        .lines()
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")
    return ParsedMessageContent(visible, images, files)
}

/**
 * Compatibility for tool/model prose such as `图片保存路径： /Users/me/output.png`. The path may
 * have real line breaks inserted by an upstream formatter, so join at most four continuation lines
 * until a raster-image extension appears. Requiring an explicit image-path label avoids turning
 * arbitrary filesystem examples in ordinary assistant prose into downloadable attachments.
 */
private fun extractLabeledImagePaths(raw: String): LabeledImageExtraction {
    val lines = raw.lines()
    val visible = mutableListOf<String>()
    val paths = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        val match = LABELED_IMAGE_PATH.matchEntire(lines[index])
        if (match == null) {
            visible += lines[index++]
            continue
        }

        var candidate = match.groupValues[1].trim()
        var end = index
        var path = normalizeLocalImagePath(candidate)
        while (path == null && end + 1 < lines.size && end - index < 3 && looksLikeLocalPathStart(candidate)) {
            val continuation = lines[end + 1].trim()
            if (continuation.isBlank() || NEXT_FIELD_LABEL.matches(continuation)) break
            candidate += continuation
            end += 1
            path = normalizeLocalImagePath(candidate)
        }
        if (path == null) {
            visible += lines[index++]
        } else {
            paths += path
            index = end + 1
        }
    }
    return LabeledImageExtraction(visible.joinToString("\n"), paths)
}

private fun looksLikeLocalPathStart(raw: String): Boolean {
    val value = raw.trim().trimStart('`', '"', '\'', '<')
    return value.startsWith("/") || value.startsWith("file://", ignoreCase = true)
}

private fun normalizeLocalImagePath(raw: String): String? {
    val unwrapped = raw.trim().trimStart('`', '"', '\'', '<')
    val extension = IMAGE_PATH_EXTENSION.find(unwrapped) ?: return null
    val reference = unwrapped.substring(0, extension.range.last + 1)
    val path = runCatching {
        URI(reference.replace(" ", "%20")).path
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: reference.removePrefix("file://")
    return path.takeIf { it.startsWith('/') && imageMimeTypeForPath(it) != null }
}

private fun remoteImage(path: String) = ChatImage(
    id = "remote-${path.hashCode()}",
    mimeType = imageMimeTypeForPath(path),
    remotePath = path,
)

private fun imageMimeTypeForPath(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> null
}

private fun mimeTypeForName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "txt", "log" -> "text/plain"
    "md" -> "text/markdown"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "zip" -> "application/zip"
    else -> null
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
        files = parsed.files,
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
