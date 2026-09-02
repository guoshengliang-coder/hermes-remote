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
private val MEDIA_DELIVERY_EXTENSIONS = listOf(
    // Keep this aligned with Hermes gateway.platforms.base.MEDIA_DELIVERY_EXTS. Android previews
    // the four formats supported by ChatImage; every other format remains downloadable.
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg",
    "mp4", "mov", "avi", "mkv", "webm", "3gp",
    "mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac",
    "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub",
    "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml",
    "kmz", "kml", "geojson", "gpx",
    "pptx", "ppt", "odp", "key",
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa",
    "html", "htm",
)
private val MEDIA_DELIVERY_EXTENSION_PATTERN = MEDIA_DELIVERY_EXTENSIONS
    .sortedByDescending(String::length)
    .joinToString("|") { Regex.escape(it) }
private val MEDIA_TAG = Regex(
    """[`\"'*_]{0,3}MEDIA:\s*(?:`((?:file://)?/[^`\r\n]+?\.(?:$MEDIA_DELIVERY_EXTENSION_PATTERN))`|\"((?:file://)?/[^\"\r\n]+?\.(?:$MEDIA_DELIVERY_EXTENSION_PATTERN))\"|'((?:file://)?/[^'\r\n]+?\.(?:$MEDIA_DELIVERY_EXTENSION_PATTERN))'|((?:file://)?/\S+?(?:[^\S\r\n]+\S+?)*?\.(?:$MEDIA_DELIVERY_EXTENSION_PATTERN)))(?=[\s`\"'*_,;:)\]}\[（）〈〉《》：，。；！？、“”‘’【】]|MEDIA:|\.(?:\s|$)|$)[`\"'*_]{0,3}\.?""",
    RegexOption.IGNORE_CASE,
)
private val MEDIA_GLOBAL_DIRECTIVE = Regex("\\[\\[(?:as_document|audio_as_voice)]]", RegexOption.IGNORE_CASE)
private val FILE_SIZE_AT_START = Regex(
    "^\\s*[（(]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB|KIB|MIB|GIB)\\s*[）)]",
    RegexOption.IGNORE_CASE,
)
private val LOCAL_MARKDOWN_FILE = Regex(
    "(?<!!)\\[([^]\\r\\n]*)]\\(\\s*(<?(?:file://)?/[^)\\r\\n>]*?\\.(?:$MEDIA_DELIVERY_EXTENSION_PATTERN)>?)(?:\\s+[\"'][^)\\r\\n]*[\"'])?\\s*\\)",
    RegexOption.IGNORE_CASE,
)
private val LABELED_IMAGE_PATH = Regex(
    "^\\s*(?:图片(?:的)?保存(?:路径|位置)|图片路径|图片(?:已)?保存(?:到|至|在)|生成(?:的)?图片(?:保存)?(?:路径|位置)|image\\s+(?:saved|written|stored)(?:\\s+(?:to|at))?|generated\\s+image\\s+(?:path|location)|已生成|generated)\\s*[：:]?\\s*(.*)$",
    RegexOption.IGNORE_CASE,
)
private val LABELED_FILE_PATH = Regex(
    "^\\s*(?:文件(?:的)?保存(?:路径|位置)|文件路径|文件(?:已)?保存(?:到|至|在)|生成(?:的)?文件(?:保存)?(?:路径|位置)|输出(?:文件)?(?:路径|位置)|file\\s+(?:saved|written|stored)(?:\\s+(?:to|at))?|generated\\s+file(?:\\s+(?:path|location))?|output\\s+file(?:\\s+(?:path|location))?|已生成|generated)\\s*[：:]?\\s*(.*)$",
    RegexOption.IGNORE_CASE,
)
private val IMAGE_PATH_EXTENSION = Regex(
    "\\.(?:png|jpe?g|gif|webp)(?=$|[\\s`\"'<>，。；;）)\\]])",
    RegexOption.IGNORE_CASE,
)
private val FILE_PATH_EXTENSION = Regex(
    "\\.([A-Za-z][A-Za-z0-9]{0,11})(?=$|[\\s`\"'<>，。；;）)\\]])",
    RegexOption.IGNORE_CASE,
)
private val FILE_SIZE_SUFFIX = Regex(
    "[（(]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB|KIB|MIB|GIB)\\s*[）)]",
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

private data class LabeledFileReference(
    val path: String,
    val sizeBytes: Long?,
)

private data class LabeledFileExtraction(
    val text: String,
    val references: List<LabeledFileReference>,
)

private data class ExplicitMediaExtraction(
    val text: String,
    val paths: List<String>,
)

/** Hermes persists attachments as `@image:/absolute/path`; keep the path out of visible chat. */
internal fun parseMessageContent(raw: String): ParsedMessageContent {
    val explicitMedia = extractExplicitMediaReferences(raw)
    val labeled = extractLabeledImagePaths(explicitMedia.text)
    val labeledFiles = extractLabeledFilePaths(labeled.text)
    val pathImages = IMAGE_DIRECTIVE.findAll(labeledFiles.text).mapNotNull { match ->
        match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            ?.let(::normalizeLocalImagePath)
            ?.let(::remoteImage)
    }.toList()
    val labeledImages = labeled.paths.map(::remoteImage)
    val explicitImages = explicitMedia.paths.mapNotNull { path ->
        path.takeIf { imageMimeTypeForPath(it) != null }?.let(::remoteImage)
    }
    val localMarkdownImages = LOCAL_MARKDOWN_IMAGE.findAll(labeledFiles.text).mapNotNull { match ->
        normalizeLocalImagePath(match.groupValues[2])?.let(::remoteImage)
    }.toList()
    val webImages = MARKDOWN_IMAGE.findAll(labeledFiles.text).mapIndexed { index, match ->
        val url = match.groupValues[2]
        ChatImage(
            id = "web-${url.hashCode()}-$index",
            sourceUrl = url,
        )
    }.toList()
    val images = (explicitImages + pathImages + labeledImages + localMarkdownImages + webImages)
        .distinctBy { it.remotePath ?: it.sourceUrl ?: it.id }
    val directiveFiles = FILE_DIRECTIVE.findAll(labeledFiles.text).mapIndexed { index, match ->
        val path = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
        ChatFile(
            id = "file-${path.hashCode()}-$index",
            name = name,
            mimeType = mimeTypeForName(name),
            remotePath = path,
        )
    }.toList()
    val naturalFiles = labeledFiles.references.map { reference ->
        val name = reference.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
        ChatFile(
            id = "file-${reference.path.hashCode()}",
            name = name,
            mimeType = mimeTypeForName(name),
            sizeBytes = reference.sizeBytes,
            remotePath = reference.path,
        )
    }
    val explicitFiles = explicitMedia.paths
        .filter { imageMimeTypeForPath(it) == null }
        .map(::remoteFile)
    val localMarkdownFiles = LOCAL_MARKDOWN_FILE.findAll(labeledFiles.text).map { match ->
        normalizeExplicitMediaPath(match.groupValues[2]).let(::remoteFile)
    }.toList()
    val files = (explicitFiles + directiveFiles + naturalFiles + localMarkdownFiles)
        .distinctBy { it.remotePath ?: it.localPath ?: it.id }
    val visible = labeledFiles.text
        .replace(IMAGE_DIRECTIVE, "")
        .replace(ATTACHED_IMAGE_PLACEHOLDER, "")
        .replace(FILE_DIRECTIVE, "")
        .replace(ATTACHED_FILE_PLACEHOLDER, "")
        .replace(LOCAL_MARKDOWN_IMAGE) { it.groupValues[1].takeIf(String::isNotBlank).orEmpty() }
        .replace(LOCAL_MARKDOWN_FILE) { it.groupValues[1].takeIf(String::isNotBlank).orEmpty() }
        .replace(MARKDOWN_IMAGE) { it.groupValues[1].takeIf(String::isNotBlank).orEmpty() }
        .lines()
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")
    return ParsedMessageContent(visible, images, files)
}

/**
 * Parse Hermes' canonical outbound attachment grammar: `MEDIA:/absolute/path.ext`.
 *
 * This deliberately runs before natural-language compatibility rules. One protocol parser therefore
 * covers images, documents, archives, audio/video and multiple attachments without depending on the
 * model's surrounding Chinese/English wording. Like Hermes itself, fenced examples and blockquotes
 * are protected so documentation containing a sample MEDIA tag does not create a broken card.
 * Unknown extensions stay visible rather than being silently removed.
 */
private fun extractExplicitMediaReferences(raw: String): ExplicitMediaExtraction {
    if (!raw.contains("MEDIA:", ignoreCase = true)) {
        return ExplicitMediaExtraction(raw.replace(MEDIA_GLOBAL_DIRECTIVE, ""), emptyList())
    }

    val masked = maskProtectedMediaExamples(raw)
    val matches = MEDIA_TAG.findAll(masked).toList()
    if (matches.isEmpty()) {
        return ExplicitMediaExtraction(raw.replace(MEDIA_GLOBAL_DIRECTIVE, ""), emptyList())
    }

    val paths = mutableListOf<String>()
    val removalRanges = mutableListOf<IntRange>()
    matches.forEach { match ->
        val rawPath = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
        val path = normalizeExplicitMediaPath(rawPath)
        if (!path.startsWith('/') || path.substringAfterLast('/').isBlank()) return@forEach
        paths += path

        var endInclusive = match.range.last
        FILE_SIZE_AT_START.find(raw.substring(endInclusive + 1))?.let { suffix ->
            endInclusive += suffix.value.length
        }
        removalRanges += match.range.first..endInclusive
    }

    val cleaned = buildString(raw.length) {
        var cursor = 0
        removalRanges.sortedBy { it.first }.forEach { range ->
            if (range.first > cursor) append(raw, cursor, range.first)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < raw.length) append(raw, cursor, raw.length)
    }
        .replace(MEDIA_GLOBAL_DIRECTIVE, "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    return ExplicitMediaExtraction(cleaned, paths.distinct())
}

/** Offset-preserving mask used only to locate real MEDIA tags in the original string. */
private fun maskProtectedMediaExamples(raw: String): String {
    val chars = raw.toCharArray()
    val protected = mutableListOf<IntRange>()
    Regex("```[^\\n]*\\n.*?```", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(raw).forEach {
        protected += it.range
    }
    Regex("^\\s*>.*$", setOf(RegexOption.MULTILINE)).findAll(raw).forEach {
        protected += it.range
    }
    protected.forEach { range ->
        range.forEach { index -> if (chars[index] != '\n') chars[index] = ' ' }
    }
    return chars.concatToString()
}

private fun normalizeExplicitMediaPath(raw: String): String {
    val reference = raw.trim().trim('<', '>')
    return runCatching { URI(reference.replace(" ", "%20")).path }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: reference.removePrefix("file://")
}

/** Natural-language compatibility for generated non-image artifacts such as markdown reports. */
private fun extractLabeledFilePaths(raw: String): LabeledFileExtraction {
    val lines = raw.lines()
    val visible = mutableListOf<String>()
    val references = mutableListOf<LabeledFileReference>()
    var index = 0
    while (index < lines.size) {
        val match = LABELED_FILE_PATH.matchEntire(lines[index])
        if (match == null) {
            visible += lines[index++]
            continue
        }

        var candidate = match.groupValues[1].trim()
        var end = index
        var reference = normalizeLocalFileReference(candidate)
        var fenced = false
        while (reference == null && end + 1 < lines.size && end - index < 6 &&
            (candidate.isBlank() || looksLikeLocalPathStart(candidate))
        ) {
            val continuation = lines[end + 1].trim()
            if (continuation.isBlank()) {
                end += 1
                continue
            }
            if (candidate.isBlank() && continuation.startsWith("```")) {
                fenced = true
                end += 1
                continue
            }
            if (NEXT_FIELD_LABEL.matches(continuation)) break
            candidate += continuation
            end += 1
            reference = normalizeLocalFileReference(candidate)
        }
        if (reference == null) {
            visible += lines[index++]
        } else {
            references += reference
            if (fenced && end + 1 < lines.size && lines[end + 1].trim().startsWith("```")) {
                end += 1
            }
            while (end + 1 < lines.size && lines[end + 1].isBlank()) end += 1
            index = end + 1
        }
    }
    return LabeledFileExtraction(visible.joinToString("\n"), references)
}

/**
 * Compatibility for tool/model prose such as `图片保存路径： /Users/me/output.png`. The path may
 * have real line breaks inserted by an upstream formatter, and Hermes commonly emits the label as
 * a Markdown hard break followed by an inline-code path on the next line. Join a small bounded
 * number of continuation lines until a raster-image extension appears. Requiring an explicit
 * image-path label avoids turning arbitrary filesystem examples in ordinary assistant prose into
 * downloadable attachments.
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
        var fenced = false
        while (path == null && end + 1 < lines.size && end - index < 6 &&
            (candidate.isBlank() || looksLikeLocalPathStart(candidate))
        ) {
            val continuation = lines[end + 1].trim()
            if (continuation.isBlank()) {
                // Permit one or two presentation-only gaps after an otherwise empty label without
                // scanning arbitrarily far into the following answer.
                end += 1
                continue
            }
            if (candidate.isBlank() && continuation.startsWith("```")) {
                fenced = true
                end += 1
                continue
            }
            if (NEXT_FIELD_LABEL.matches(continuation)) break
            candidate += continuation
            end += 1
            path = normalizeLocalImagePath(candidate)
        }
        if (path == null) {
            visible += lines[index++]
        } else {
            paths += path
            if (fenced && end + 1 < lines.size && lines[end + 1].trim().startsWith("```")) {
                end += 1
            }
            // The path block is normally followed by a presentation-only blank line. Consume it
            // with the hidden block so removing the path does not leave a three-line visual gap.
            while (end + 1 < lines.size && lines[end + 1].isBlank()) end += 1
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

private fun normalizeLocalFileReference(raw: String): LabeledFileReference? {
    val unwrapped = raw.trim().trimStart('`', '"', '\'', '<')
    val extension = FILE_PATH_EXTENSION.findAll(unwrapped).lastOrNull() ?: return null
    val extensionName = extension.groupValues[1]
    if (imageMimeTypeForPath("attachment.$extensionName") != null) return null
    val reference = unwrapped.substring(0, extension.range.last + 1)
    val path = runCatching {
        URI(reference.replace(" ", "%20")).path
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: reference.removePrefix("file://")
    if (!path.startsWith('/') || path.substringAfterLast('/').isBlank()) return null
    return LabeledFileReference(path, parseFileSizeBytes(unwrapped.substring(extension.range.last + 1)))
}

private fun parseFileSizeBytes(raw: String): Long? {
    val match = FILE_SIZE_SUFFIX.find(raw) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase()) {
        "B" -> 1L
        "KB", "KIB" -> 1024L
        "MB", "MIB" -> 1024L * 1024L
        "GB", "GIB" -> 1024L * 1024L * 1024L
        else -> return null
    }
    return (value * multiplier).toLong().takeIf { it >= 0 }
}

private fun remoteImage(path: String) = ChatImage(
    id = "remote-${path.hashCode()}",
    mimeType = imageMimeTypeForPath(path),
    remotePath = path,
)

private fun remoteFile(path: String): ChatFile {
    val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
    return ChatFile(
        id = "file-${path.hashCode()}",
        name = name,
        mimeType = mimeTypeForName(name),
        remotePath = path,
    )
}

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
    "odt" -> "application/vnd.oasis.opendocument.text"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    "rtf" -> "application/rtf"
    "epub" -> "application/epub+zip"
    "tsv" -> "text/tab-separated-values"
    "xml" -> "application/xml"
    "yaml", "yml" -> "application/yaml"
    "html", "htm" -> "text/html"
    "svg" -> "image/svg+xml"
    "bmp" -> "image/bmp"
    "tiff" -> "image/tiff"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "zip" -> "application/zip"
    "gz", "tgz" -> "application/gzip"
    "7z" -> "application/x-7z-compressed"
    "rar" -> "application/vnd.rar"
    "apk" -> "application/vnd.android.package-archive"
    else -> null
}

/** Lenient ISO-8601 parse: with or without offset; null on anything unexpected. */
internal fun parseIsoTimestampMillis(raw: String): Long? = runCatching {
    java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
}.getOrNull() ?: runCatching {
    java.time.LocalDateTime.parse(raw).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

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
        timestamp = createdAt?.let(::parseIsoTimestampMillis),
        displayKind = displayKind?.ifBlank { null },
        displayTaskCount = displayMetadata?.intOrNull("task_count"),
        displayFailedCount = displayMetadata?.intOrNull("failed_count"),
    )
}

private fun kotlinx.serialization.json.JsonObject.intOrNull(key: String): Int? =
    (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

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
