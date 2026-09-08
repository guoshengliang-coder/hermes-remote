package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.network.SessionStatsDto
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Session
import com.hermes.client.domain.toDomain
import com.hermes.client.data.auth.AccountRoutingContext
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.ConversationDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Mirror the desktop sidebar session list: show interactive, used sessions only. Sessions whose
 * source is in [SessionRepository.EXCLUDED_SOURCES] — cron (shown in the Cron view), the internal
 * subagent/tool sources, and every messaging platform (telegram/slack/email/… live in their own
 * surfaces) — plus empty (0-message) scratch sessions are hidden, matching the desktop's
 * SIDEBAR_EXCLUDED_SOURCES so the two lists agree. A null/unknown source is kept.
 */
private fun Session.isInteractive(): Boolean =
    messageCount > 0 && (source == null || source !in SessionRepository.EXCLUDED_SOURCES)

class SessionRepository(
    private val rest: HermesRestApi,
    private val scope: CoroutineScope,
    private val accountSessions: AccountSessionManager? = null,
    private val conversationDevices: ConversationDeviceStore? = null,
) {
    @Volatile private var allProfilesCache: List<Session> = emptyList()
    @Volatile private var allProfilesLoaded: Boolean = false
    @Volatile private var allProfilesCacheRoute: String? = null
    private val historyCache = object : LinkedHashMap<String, List<ChatMessage>>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChatMessage>>?): Boolean =
            size > 10
    }

    // A transcript and the cross-profile session list are each fetched by several independent
    // owners (chat open, history reconciliation, foreground recovery, the sessions screen, the
    // startup coordinator). They all wake up together after a reconnect, so before coalescing a
    // single recovery downloaded the SAME 0.5 MB transcript up to seven times in five seconds
    // (measured 2026-09-03 in the gateway access log). Identical concurrent fetches now share one
    // round trip; sequential retries still issue a fresh request, which is what the "wait for
    // Hermes to commit the turn" reconciliation ladder depends on.
    private val inFlightFetches = mutableMapOf<String, Deferred<*>>()

    /**
     * Runs [block] once per [key] while a call is in flight, handing every concurrent caller the
     * same result. The work runs on [scope], not the caller, so one caller giving up (the startup
     * gate abandons recovery after its own budget) neither cancels the shared fetch nor fails the
     * other waiters — and the response finishes instead of leaving the Connector streaming into an
     * aborted request. Each REST call carries its own deadline, so an entry cannot linger.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesced(key: String, block: suspend () -> T): T {
        val deferred = synchronized(inFlightFetches) {
            inFlightFetches[key] ?: scope.async { block() }.also { started ->
                inFlightFetches[key] = started
                started.invokeOnCompletion {
                    synchronized(inFlightFetches) {
                        if (inFlightFetches[key] === started) inFlightFetches.remove(key)
                    }
                }
            }
        }
        return deferred.await() as T
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

        // Coalescing keys. The two list keys are distinct because they are different queries;
        // `activityFeed` keeps cron sessions, so it deliberately does NOT share the list key.
        private const val LIST_ALL_KEY = "sessions:all"
        private const val ARCHIVED_ALL_KEY = "sessions:archived"
        private const val HISTORY_KEY_PREFIX = "history:"
    }

    suspend fun list(profile: String? = null, deviceId: String? = null): List<Session> {
        val defaultContext = accountSessions?.routingContext()
        val context = if (!deviceId.isNullOrBlank() && defaultContext != null) {
            defaultContext.copy(deviceId = deviceId)
        } else {
            defaultContext
        }
        return bindToRoute(
            rest.sessions(limit = 50, offset = 0, profile = profile, deviceId = context?.deviceId)
                .map { it.toDomain() },
            context,
        )
    }

    /**
     * All non-archived sessions across every profile, each tagged with its true profile.
     * This is the desktop-mirror list source — it replaces the single-profile [list] for the
     * sessions screen. The endpoint already excludes archived; the filter is defensive.
     * [isInteractive] hides cron + empty sessions so the counts match the desktop dashboard.
     */
    suspend fun listAllProfiles(): List<Session> {
        val context = accountSessions?.routingContext()
        val route = routeKey(context)
        return coalesced("$LIST_ALL_KEY:$route") {
            val loaded = bindToRoute(
                rest.profileSessions(deviceId = context?.deviceId).sessions.map { it.toDomain() },
                context,
            ).filter { !it.archived && it.isInteractive() }
            allProfilesCache = loaded
            allProfilesCacheRoute = route
            allProfilesLoaded = true
            loaded
        }
    }

    fun cachedAllProfiles(): List<Session> =
        if (allProfilesCacheRoute == routeKey(accountSessions?.routingContext())) allProfilesCache else emptyList()

    /** Distinguishes a successfully loaded empty list from a list that has not been fetched yet. */
    fun hasLoadedAllProfiles(): Boolean = allProfilesLoaded &&
        allProfilesCacheRoute == routeKey(accountSessions?.routingContext())

    fun cachedSession(sessionId: String, profile: String? = null, deviceId: String? = null): Session? =
        allProfilesCache.firstOrNull {
            it.id == sessionId &&
                (profile.isNullOrBlank() || it.profile == profile) &&
                (deviceId.isNullOrBlank() || it.deviceId == deviceId)
        }

    /**
     * Mission Control feed source: like [listAllProfiles] but KEEPS cron-produced sessions, so a
     * scheduled run's actual output (which the gateway stores as a real `source="cron"` session)
     * is openable straight from the activity feed. Still drops archived + empty sessions.
     */
    suspend fun activityFeed(): List<Session> {
        val context = accountSessions?.routingContext()
        return bindToRoute(
            rest.profileSessions(deviceId = context?.deviceId).sessions.map { it.toDomain() },
            context,
        )
            .filter { !it.archived && it.messageCount > 0 }
    }

    /** All archived sessions across every profile (the cross-profile archived view). */
    suspend fun archivedAllProfiles(): List<Session> {
        val context = accountSessions?.routingContext()
        return coalesced("$ARCHIVED_ALL_KEY:${routeKey(context)}") {
            bindToRoute(
                rest.profileSessions(
                    archivedOnly = true,
                    deviceId = context?.deviceId,
                ).sessions.map { it.toDomain() },
                context,
            ).filter { it.archived && it.isInteractive() }
        }
    }
    suspend fun stats(profile: String? = null): SessionStatsDto = rest.sessionStats(profile)
    /** Message-content search over the same interactive sources the list shows. */
    suspend fun search(query: String, profile: String? = null): List<SearchResultDto> =
        rest.searchSessions(
            query,
            profile,
            excludeSources = EXCLUDED_SOURCES,
            deviceId = accountSessions?.routingContext()?.deviceId,
        )
    suspend fun archived(profile: String? = null): List<Session> =
        accountSessions?.routingContext().let { context ->
            bindToRoute(rest.archivedSessions(profile, context?.deviceId).map { it.toDomain() }, context)
        }
    // Tool/function turns are model context, not conversation turns. Their payload format is not
    // stable (untrusted wrappers, command result JSON, escaped markdown, skill documents, etc.),
    // so trying to recognize individual payload shapes will always leak the next variant. Remove
    // these roles at the data boundary and render only user/assistant/system conversation history.
    // Live tool activity still appears through tool.start/tool.complete as compact status cards.
    suspend fun history(
        sessionId: String,
        profile: String? = null,
        deviceId: String? = null,
    ): List<ChatMessage> =
        coalesced("$HISTORY_KEY_PREFIX${historyKey(sessionId, profile, deviceId)}") {
            val loaded = rest.messages(sessionId, profile, deviceId)
                .filterNot { it.role.lowercase() in INTERNAL_TOOL_ROLES }
                .mapIndexed { i, dto ->
                    val m = dto.toDomain()
                    m.copy(id = "h-$i-${m.id}")
                }
            synchronized(historyCache) { historyCache[historyKey(sessionId, profile, deviceId)] = loaded }
            loaded
        }

    fun cachedHistory(sessionId: String, profile: String? = null, deviceId: String? = null): List<ChatMessage>? =
        synchronized(historyCache) { historyCache[historyKey(sessionId, profile, deviceId)] }

    private fun historyKey(sessionId: String, profile: String?, deviceId: String?): String =
        "${deviceId.orEmpty()}/${profile.orEmpty()}/$sessionId"

    fun currentDeviceId(): String? = accountSessions?.routingContext()?.deviceId

    fun bindConversation(profile: String?, sessionId: String, deviceId: String? = currentDeviceId()) {
        val account = accountSessions?.session?.value ?: return
        val device = deviceId?.takeIf { it.isNotBlank() } ?: return
        conversationDevices?.bind(account.accountId, profile, sessionId, device)
    }

    private fun bindToRoute(items: List<Session>, context: AccountRoutingContext?): List<Session> {
        if (context == null) return items
        return items.map { session ->
            conversationDevices?.bind(context.accountId, session.profile, session.id, context.deviceId)
            session.copy(deviceId = context.deviceId)
        }
    }

    private fun routeKey(context: AccountRoutingContext?): String =
        context?.let { "${it.accountId}:${it.deviceId}" } ?: "legacy"

    // All mutations carry the session's profile so the gateway hits the right per-profile DB
    // (otherwise the call 404s and the change silently no-ops).
    suspend fun rename(sessionId: String, title: String, profile: String?, deviceId: String? = null) =
        rest.patchSession(sessionId, title = title, profile = profile, deviceId = deviceId)
    suspend fun archive(
        sessionId: String,
        archived: Boolean,
        profile: String?,
        deviceId: String? = null,
    ) = rest.patchSession(sessionId, archived = archived, profile = profile, deviceId = deviceId)
    suspend fun delete(sessionId: String, profile: String?, deviceId: String? = null) {
        rest.deleteSession(sessionId, profile, deviceId)
        val account = accountSessions?.session?.value ?: return
        conversationDevices?.remove(account.accountId, profile, sessionId)
    }

}
