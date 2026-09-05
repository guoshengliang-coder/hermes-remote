package com.hermes.client.data

import com.hermes.client.data.network.SessionDto
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.MEDIA_DELIVERY_EXTENSIONS
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the surfaces this app consumes from **upstream Hermes**, which it does not own and cannot
 * version-negotiate: wire field names, RPC method names, text grammars, and one constant copied
 * from Hermes' source. See docs/HERMES_CONTRACT.md for the inventory and the upgrade checklist.
 *
 * These assertions are not testing our own logic — they exist so that changing a name silently, or
 * discovering an upstream rename after an upgrade, fails here instead of in a user's chat. When a
 * Hermes upgrade legitimately changes one of these, update the code, this test, and the recorded
 * version in docs/HERMES_CONTRACT.md together.
 */
class HermesContractTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun session_wire_field_names_match_upstream() {
        // Hermes returns the id as "id", not "session_id" — and the app has been bitten by the
        // difference before (see SessionDto's own comment).
        val names = SessionDto.serializer().descriptor.elementNames.toSet()
        assertEquals(
            setOf(
                "id", "title", "model", "provider", "last_active", "message_count", "profile",
                "is_default_profile", "archived", "cwd", "source", "git_branch", "git_repo_root",
            ),
            names,
        )
    }

    // `source` drives which sessions the list shows. Upstream owns these values; a new one appearing
    // is safe (unknown sources are kept), but losing one of these silently un-hides a whole class.
    @Test fun excluded_session_sources_are_pinned() {
        assertEquals(22, SessionRepository.EXCLUDED_SOURCES.size)
        listOf("cron", "subagent", "tool", "dingtalk", "feishu", "telegram", "email").forEach {
            assertTrue("$it must stay excluded from the interactive list", it in SessionRepository.EXCLUDED_SOURCES)
        }
        // Deliberately NOT excluded: the phone's own sessions arrive as one of these.
        listOf("tui", "cli", "desktop", "hermes-dispatch").forEach {
            assertTrue("$it must remain visible", it !in SessionRepository.EXCLUDED_SOURCES)
        }
    }

    /**
     * Mirrors `gateway/platforms/base.py` `MEDIA_DELIVERY_EXTS` in upstream Hermes. Verified
     * item-for-item against Hermes 0.21.0 on 2026-09-05.
     *
     * Do NOT align this with `gateway/run.py`'s `_TOOL_MEDIA_RE`: that regex only auto-tags output
     * from text_to_speech/image_generate, carries a much shorter list, and aligning to it would
     * silently drop html/md attachments. A stale note in the project's own docs claimed otherwise.
     */
    @Test fun media_delivery_extensions_mirror_the_upstream_delivery_whitelist() {
        assertEquals(
            listOf(
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg",
                "mp4", "mov", "avi", "mkv", "webm", "3gp",
                "mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac",
                "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub",
                "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml",
                "kmz", "kml", "geojson", "gpx",
                "pptx", "ppt", "odp", "key",
                "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa",
                "html", "htm",
            ),
            MEDIA_DELIVERY_EXTENSIONS,
        )
        // The two formats this project actually delivers reports as.
        assertTrue("html" in MEDIA_DELIVERY_EXTENSIONS)
        assertTrue("md" in MEDIA_DELIVERY_EXTENSIONS)
    }
}
