package com.hermes.client.data.video

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

const val VIDEO_NOTE_LIMIT = 500
const val VIDEO_NOTE_TEXT_LIMIT = 8_000

@Serializable
data class VideoNote(val id: String, val startMs: Long, val endMs: Long? = null, val text: String) {
    fun valid(durationMs: Long): Boolean = id.isNotBlank() && startMs in 0..durationMs &&
        (endMs == null || endMs > startMs && endMs <= durationMs) &&
        text.isNotBlank() && text.length <= VIDEO_NOTE_TEXT_LIMIT
}

@Serializable
data class VideoNoteDraft(val id: String? = null, val start: String, val end: String = "", val range: Boolean = false,
    val text: String = "", val draftKey: String = java.util.UUID.randomUUID().toString(),
    val retained: Boolean = false)

@Serializable
data class VideoFeedbackDocument(val schemaVersion: Int = 1, val notes: List<VideoNote> = emptyList(),
    val draft: VideoNoteDraft? = null, val parkedDrafts: List<VideoNoteDraft> = emptyList()) {
    val allDrafts get() = listOfNotNull(draft) + parkedDrafts
    val workCount get() = notes.size + allDrafts.count { it.id == null }
    fun validated(): VideoFeedbackDocument = apply {
        require(schemaVersion == 1 && workCount <= VIDEO_NOTE_LIMIT)
        require(notes.map { it.id }.distinct().size == notes.size)
        require(notes.all { it.valid(Long.MAX_VALUE) })
        require(allDrafts.size <= VIDEO_NOTE_LIMIT)
        require(allDrafts.map { it.draftKey }.distinct().size == allDrafts.size)
        require(allDrafts.all { it.draftKey.isNotBlank() && it.start.length <= 32 && it.end.length <= 32 && it.text.length <= VIDEO_NOTE_TEXT_LIMIT })
        require(allDrafts.all { d -> d.id == null || notes.any { it.id == d.id } })
        require(allDrafts.mapNotNull { it.id }.distinct().size == allDrafts.count { it.id != null })
    }
}

/** Length framing prevents collisions between scope, source path and generation components. */
fun videoFeedbackKey(scope: String, reference: String, generation: String): String {
    require(scope.isNotBlank() && reference.isNotBlank() && generation.isNotBlank())
    val framed = listOf(scope, reference, generation).joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Millisecond precision is retained even though the player's normal clock only shows seconds. */
fun videoNoteTime(ms: Long): String {
    val value = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", value / 3_600_000,
        value / 60_000 % 60, value / 1_000 % 60, value % 1_000)
}

/** Human-facing locator; storage and seeking continue to use the original milliseconds. */
fun videoNoteReferenceTime(ms: Long): String = videoNoteTime(ms).substringBefore('.').let {
    if (ms < 3_600_000) it.removePrefix("00:") else it
}

fun parseVideoNoteTime(text: String): Long? {
    val match = Regex("^(?:(\\d{1,5}):)?(\\d{1,3}):([0-5]\\d)(?:\\.(\\d{1,3}))?$").matchEntire(text.trim()) ?: return null
    val (hours, minutes, seconds, millis) = match.destructured
    if (hours.isNotEmpty() && minutes.toInt() > 59) return null
    return ((hours.toLongOrNull() ?: 0) * 3_600 + minutes.toLong() * 60 + seconds.toLong()) * 1_000 +
        millis.padEnd(3, '0').toLong()
}

/** No network lookup or credentials: only the source reference the user is already viewing. */
fun videoFeedbackMarkdown(name: String, reference: String, conversation: String, durationMs: Long,
    document: VideoFeedbackDocument, generation: String? = null): String = buildString {
    fun metadata(value: String) = value.replace("\r", " ").replace("\n", " ")
    append("# 视频修改反馈 / Video feedback\n\n")
    append("- 视频 / Video: ").append(metadata(name)).append('\n')
    append("- 来源 / Source: ").append(metadata(reference)).append('\n')
    append("- 会话 / Conversation: ").append(metadata(conversation)).append('\n')
    generation?.let { append("- 版本 / Version: ").append(metadata(it)).append('\n') }
    append("- 时长 / Duration: ").append(videoNoteTime(durationMs)).append("\n\n")
    append("> **时间标记说明 / About time references**\n")
    append("> 时间用于辅助定位，不保证逐帧精确。单点指向该时间附近的内容；片段表示大致范围，起止位置可能存在偏差。请结合批注文字，适当前后播放确认。\n")
    append("> Times are approximate viewing references, not frame-accurate edit boundaries. A point refers to nearby content; a range indicates an approximate span. Read the feedback and review the surrounding footage.\n\n")
    document.validated().notes.sortedWith(compareBy<VideoNote> { it.startMs }.thenBy { it.endMs ?: it.startMs }.thenBy { it.id })
        .forEachIndexed { index, note ->
            val start = videoNoteReferenceTime(note.startMs)
            val end = note.endMs?.let(::videoNoteReferenceTime)
            append("## ").append(index + 1).append(". ")
            when {
                end == null -> append("单点：约 $start 附近 / Point: around $start")
                end == start -> append("片段：约 $start 附近 / Range near $start")
                else -> append("片段：约 $start–$end / Approximate range: $start–$end")
            }
            append("\n\n").append(note.text).append("\n\n")
        }
}
