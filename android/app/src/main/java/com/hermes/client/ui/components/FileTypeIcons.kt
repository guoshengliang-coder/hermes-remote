package com.hermes.client.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import java.util.Locale

enum class FileIconKind { VIDEO, AUDIO, DOCUMENT, WEB, CODE, PDF, TABLE, SLIDES, ARCHIVE, OTHER }

fun fileIconKind(name: String, mimeType: String?): FileIconKind {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v" -> FileIconKind.VIDEO
        "mp3", "m4a", "wav", "ogg", "opus", "flac", "aac" -> FileIconKind.AUDIO
        "md", "txt", "log", "doc", "docx", "odt", "rtf", "epub" -> FileIconKind.DOCUMENT
        "html", "htm" -> FileIconKind.WEB
        "json", "xml", "yaml", "yml", "js", "ts", "py", "kt", "css", "sh" -> FileIconKind.CODE
        "pdf" -> FileIconKind.PDF
        "csv", "tsv", "xls", "xlsx", "ods" -> FileIconKind.TABLE
        "ppt", "pptx", "odp" -> FileIconKind.SLIDES
        "zip", "gz", "tgz", "7z", "rar", "tar" -> FileIconKind.ARCHIVE
        else -> when {
            mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) in setOf("text/html", "application/xhtml+xml") -> FileIconKind.WEB
            mimeType?.startsWith("video/", ignoreCase = true) == true -> FileIconKind.VIDEO
            mimeType?.startsWith("audio/", ignoreCase = true) == true -> FileIconKind.AUDIO
            mimeType?.substringBefore(';')?.equals("application/pdf", ignoreCase = true) == true -> FileIconKind.PDF
            mimeType?.startsWith("text/", ignoreCase = true) == true -> FileIconKind.DOCUMENT
            else -> FileIconKind.OTHER
        }
    }
}

private val paths = mapOf(
    FileIconKind.VIDEO to "M4 5 H20 V19 H4 Z M10 9 L16 12 L10 15 Z",
    FileIconKind.AUDIO to "M10 17 V6 L19 4 V15 M10 6 V9 L19 7 M10 17 C10 20 4 21 4 18 C4 15 10 14 10 17 M19 15 C19 18 13 19 13 16 C13 13 19 12 19 15",
    FileIconKind.CODE to "M8 8 L4 12 L8 16 M16 8 L20 12 L16 16 M14 5 L10 19",
    FileIconKind.WEB to "M5 3 H19 Q21 3 21 5 V19 Q21 21 19 21 H5 Q3 21 3 19 V5 Q3 3 5 3 Z M3 8 H21 M7 5.5 H7.1 M10 5.5 H10.1 M7 12 H17 M7 16 H13",
    FileIconKind.TABLE to "M4 4 H20 V20 H4 Z M4 9 H20 M4 14 H20 M10 9 V20",
    FileIconKind.SLIDES to "M3 5 H21 M5 5 V17 H19 V5 M12 17 V21 M8 21 H16 M8 13 L11 10 L14 12 L17 8",
    FileIconKind.ARCHIVE to "M4 4 H20 V20 H4 Z M12 4 V6 M12 9 V11 M12 14 H14 V17 H10 V14 H12",
    FileIconKind.PDF to "M5 3 H14 L19 8 V21 H5 Z M14 3 V8 H19 M8 17 V12 H10 Q13 12 13 14 Q13 16 10 16 H8",
    FileIconKind.DOCUMENT to "M5 3 H14 L19 8 V21 H5 Z M14 3 V8 H19 M8 12 H16 M8 16 H14",
    FileIconKind.OTHER to "M5 3 H14 L19 8 V21 H5 Z M14 3 V8 H19",
)

private val fileIcons by lazy {
    paths.mapValues { (kind, path) ->
        // Same 24dp/1.7/Round brush as strokeIcon. Parsed SVG nodes must be passed to addPath;
        // addPathNodes is a parser, not a mutating PathBuilder operation.
        ImageVector.Builder("Attachment${kind.name}", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(path), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}

fun attachmentIcon(kind: FileIconKind): ImageVector = fileIcons.getValue(kind)
