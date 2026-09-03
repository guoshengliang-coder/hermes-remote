package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.network.SessionStatsDto
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Session
import com.hermes.client.domain.toDomain

/**
 * Mirror the desktop sidebar session list: show interactive, used sessions only. Sessions whose
 * source is in [SessionRepository.EXCLUDED_SOURCES] — cron (shown in the Cron view), the internal
 * subagent/tool sources, and every messaging platform (telegram/slack/email/… live in their own
 * surfaces) — plus empty (0-message) scratch sessions are hidden, matching the desktop's
 * SIDEBAR_EXCLUDED_SOURCES so the two lists agree. A null/unknown source is kept.
 */
private fun Session.isInteractive(): Boolean =
    messageCount > 0 && (source == null || source !in SessionRepository.EXCLUDED_SOURCES)

class SessionRepository(private val rest: HermesRestApi) {
    @Volatile private var allProfilesCache: List<Session> = emptyList()
    @Volatile private var allProfilesLoaded: Boolean = false
    private val historyCache = object : LinkedHashMap<String, List<ChatMessage>>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChatMessage>>?): Boolean =
            size > 10
    }

    companion object {
        /**
         * Sources hidden from the sessions list, matching the desktop's SIDEBAR_EXCLUDED_SOURCES:
         * cron + subagent + tool + every messaging platform. Local sources (cli/tui/desktop/…) and
         * the app's own `hermes-dispatch` sessions are NOT excluded — they show in the list.
         */
        val EXCLUDED_SOURCES: Set<String> = setOf(
            "cron", "subagent", "tool",
            "telegram", "discord", "slack", "mattermost", "matrix", "signal", "whatsapp",
            "bluebubbles", "homeassistant", "email", "sms", "webhook", "api_server",
            "weixin", "wecom", "qqbot", "yuanbao", "dingtalk", "feishu",
        )
        private val INTERNAL_TOOL_ROLES = setOf("tool", "function", "tool_result", "tool_call")
    }

    suspend fun list(profile: String? = null): List<Session> =
        rest.sessions(limit = 50, offset = 0, profile = profile).map { it.toDomain() }

    /**
     * All non-archived sessions across every profile, each tagged with its true profile.
     * This is the desktop-mirror list source — it replaces the single-profile [list] for the
     * sessions screen. The endpoint already excludes archived; the filter is defensive.
     * [isInteractive] hides cron + empty sessions so the counts match the desktop dashboard.
     */
    suspend fun listAllProfiles(): List<Session> {
        val loaded = rest.profileSessions().sessions.map { it.toDomain() }
            .filter { !it.archived && it.isInteractive() }
        allProfilesCache = loaded
        allProfilesLoaded = true
        return loaded
    }

    fun cachedAllProfiles(): List<Session> = allProfilesCache

    /** Distinguishes a successfully loaded empty list from a list that has not been fetched yet. */
    fun hasLoadedAllProfiles(): Boolean = allProfilesLoaded

    fun cachedSession(sessionId: String, profile: String? = null): Session? =
        allProfilesCache.firstOrNull {
            it.id == sessionId && (profile.isNullOrBlank() || it.profile == profile)
        }

    /**
     * Mission Control feed source: like [listAllProfiles] but KEEPS cron-produced sessions, so a
     * scheduled run's actual output (which the gateway stores as a real `source="cron"` session)
     * is openable straight from the activity feed. Still drops archived + empty sessions.
     */
    suspend fun activityFeed(): List<Session> =
        rest.profileSessions().sessions.map { it.toDomain() }
            .filter { !it.archived && it.messageCount > 0 }

    /** All archived sessions across every profile (the cross-profile archived view). */
    suspend fun archivedAllProfiles(): List<Session> =
        rest.profileSessions(archivedOnly = true).sessions.map { it.toDomain() }
            .filter { it.archived && it.isInteractive() }
    suspend fun stats(profile: String? = null): SessionStatsDto = rest.sessionStats(profile)
    /** Message-content search over the same interactive sources the list shows. */
    suspend fun search(query: String, profile: String? = null): List<SearchResultDto> =
        rest.searchSessions(query, profile, excludeSources = EXCLUDED_SOURCES)
    suspend fun archived(profile: String? = null): List<Session> =
        rest.archivedSessions(profile).map { it.toDomain() }
    // Tool/function turns are model context, not conversation turns. Their payload format is not
    // stable (untrusted wrappers, command result JSON, escaped markdown, skill documents, etc.),
    // so trying to recognize individual payload shapes will always leak the next variant. Remove
    // these roles at the data boundary and render only user/assistant/system conversation history.
    // Live tool activity still appears through tool.start/tool.complete as compact status cards.
    suspend fun history(sessionId: String, profile: String? = null): List<ChatMessage> {
        val loaded = rest.messages(sessionId, profile)
            .filterNot { it.role.lowercase() in INTERNAL_TOOL_ROLES }
            .mapIndexed { i, dto ->
            val m = dto.toDomain()
            m.copy(id = "h-$i-${m.id}")
        }
        synchronized(historyCache) { historyCache[historyKey(sessionId, profile)] = loaded }
        return loaded
    }

    fun cachedHistory(sessionId: String, profile: String? = null): List<ChatMessage>? =
        synchronized(historyCache) { historyCache[historyKey(sessionId, profile)] }

    private fun historyKey(sessionId: String, profile: String?): String =
        "${profile.orEmpty()}/$sessionId"

    // All mutations carry the session's profile so the gateway hits the right per-profile DB
    // (otherwise the call 404s and the change silently no-ops).
    suspend fun rename(sessionId: String, title: String, profile: String?) =
        rest.patchSession(sessionId, title = title, profile = profile)
    suspend fun archive(sessionId: String, archived: Boolean, profile: String?) =
        rest.patchSession(sessionId, archived = archived, profile = profile)
    suspend fun delete(sessionId: String, profile: String?) = rest.deleteSession(sessionId, profile)

}
