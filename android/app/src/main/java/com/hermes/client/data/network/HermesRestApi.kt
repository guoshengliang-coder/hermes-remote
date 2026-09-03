package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.auth.normalizeGatewayBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.TimeUnit
import java.io.File

class HermesApiException(val code: Int, message: String) : Exception(message)
data class UploadedArtifact(val path: String, val name: String, val sizeBytes: Long)

/** Result of the lightweight startup/settings status probe. */
sealed interface GatewayProbeResult {
    data object Reachable : GatewayProbeResult
    data class Unauthorized(val statusCode: Int) : GatewayProbeResult
    data class ServerFailure(val statusCode: Int, val errorCode: String? = null) : GatewayProbeResult
    data class InvalidEndpoint(val statusCode: Int) : GatewayProbeResult
    data class Unreachable(val cause: String?) : GatewayProbeResult
}

class HermesRestApi(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val configProvider: () -> GatewayConfig?,
) {
    private companion object {
        const val REST_TIMEOUT_SECONDS = 20L
        const val CONNECTION_TEST_TIMEOUT_SECONDS = 12L
    }

    private fun config(): GatewayConfig =
        configProvider() ?: throw HermesApiException(0, "no gateway configured")

    private fun builder(path: String): Request.Builder {
        val cfg = config().let { it.copy(baseUrl = normalizeGatewayBaseUrl(it.baseUrl)) }
        // Keep the diagnostic log's redaction current with whatever token is active, so a
        // shared log can never contain the session token in plain text.
        com.hermes.client.data.diagnostics.DebugLog.setTokenToRedact(cfg.token)
        // Trim trailing slashes so a user-entered "http://host:9119/" doesn't produce
        // "//api/..." — the gateway routes a double slash to its web UI (HTML), not the API.
        val b = Request.Builder().url("${cfg.baseUrl.trimEnd('/')}$path")
        if (cfg.token.isNotBlank()) b.header("X-Hermes-Session-Token", cfg.token)
        return b
    }

    /** The shared client has no read timeout for WebSockets; every REST call gets a deadline. */
    private fun restCall(request: Request): Call = okHttp.newCall(request).apply {
        timeout().timeout(REST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        com.hermes.client.data.diagnostics.DebugLog.log("rest", "GET $path")
        val call = restCall(builder(path).get().build())
        // The shared client deliberately has no read timeout because WebSockets are long-lived.
        // A per-call deadline is essential for REST, otherwise a stalled Relay/Connector request
        // leaves a Compose loading screen spinning forever.
        call.timeout().timeout(REST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        call.execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "rest", "GET $path ← ${resp.code} ${body.take(200)}",
                )
                throw HermesApiException(resp.code, body.ifBlank { "HTTP ${resp.code}" })
            }
            com.hermes.client.data.diagnostics.DebugLog.log("rest", "GET $path ← ${resp.code}")
            json.decodeFromString<T>(body)
        }
    }

    /**
     * T10b: test connectivity using explicitly supplied credentials WITHOUT reading from
     * configProvider. Used by SetupViewModel.test() so unverified creds are never persisted.
     */
    suspend fun probeStatusFor(baseUrl: String, token: String): GatewayProbeResult =
        withContext(Dispatchers.IO) {
        try {
            val rb = Request.Builder().url("${baseUrl.trimEnd('/')}/api/status").get()
            if (token.isNotBlank()) rb.header("X-Hermes-Session-Token", token)
            val call = okHttp.newCall(rb.build())
            call.timeout().timeout(CONNECTION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            call.execute().use { response ->
                when {
                    response.isSuccessful -> GatewayProbeResult.Reachable
                    response.code == 401 || response.code == 403 ->
                        GatewayProbeResult.Unauthorized(response.code)
                    response.code in 500..599 -> {
                        val errorCode = runCatching {
                            val body = response.body.string()
                            ((json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)
                                ?.content
                        }.getOrNull()
                        GatewayProbeResult.ServerFailure(response.code, errorCode)
                    }
                    else -> GatewayProbeResult.InvalidEndpoint(response.code)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GatewayProbeResult.Unreachable(error.javaClass.simpleName)
        }
    }

    suspend fun statusFor(baseUrl: String, token: String): Boolean =
        probeStatusFor(baseUrl, token) is GatewayProbeResult.Reachable

    /** Delegates to [statusFor] using the current stored config. */
    suspend fun status(): Boolean {
        val cfg = configProvider() ?: return false
        return statusFor(cfg.baseUrl, cfg.token)
    }

    /** Public /api/status — gateway version + running state. */
    suspend fun gatewayStatus(): GatewayStatusDto = get("/api/status")

    /** Relay health — which Mac connectors are currently attached (deviceId + online).
     *  The production edge maps the gateway's /health to /relay-health (bare /health belongs to
     *  the release server there); a direct gateway connection only has /health. Try the edge
     *  path first, then fall back. */
    suspend fun relayHealth(): RelayHealthDto =
        runCatching { get<RelayHealthDto>("/relay-health") }.getOrNull()
            ?.takeIf { it.ok }
            ?: get("/health")

    /** Durable Relay-owned lifecycle inbox; available even while the Mac Connector is offline. */
    suspend fun lifecycleEvents(after: Long, limit: Int = 100): LifecycleEventPageDto {
        require(after >= 0) { "after must be non-negative" }
        require(limit in 1..500) { "limit must be between 1 and 500" }
        return get("/api/mobile/events?after=$after&limit=$limit")
    }

    suspend fun markLifecycleEventsDelivered(eventIds: List<String>) =
        postLifecycleEventIds("/api/mobile/events/ack", eventIds)

    suspend fun markLifecycleEventsRead(eventIds: List<String>) =
        postLifecycleEventIds("/api/mobile/events/read", eventIds)

    private suspend fun postLifecycleEventIds(path: String, eventIds: List<String>) =
        withContext(Dispatchers.IO) {
            require(eventIds.size <= 500) { "too many lifecycle event ids" }
            val payload = buildJsonObject {
                put("event_ids", kotlinx.serialization.json.JsonArray(
                    eventIds.distinct().map { JsonPrimitive(it) },
                ))
            }
            val requestBody = json.encodeToString(JsonObject.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            restCall(builder(path).post(requestBody).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HermesApiException(
                        response.code,
                        response.body.string().ifBlank { "HTTP ${response.code}" },
                    )
                }
            }
        }

    suspend fun sessions(limit: Int, offset: Int, profile: String? = null): List<SessionDto> =
        get<SessionListDto>(
            "/api/sessions?limit=$limit&offset=$offset&order=recent${profileParam(profile)}",
        ).sessions

    /**
     * Cross-profile session list — every session tagged with its true `profile`, plus
     * `profile_totals` for group headers. The active list excludes archived by default;
     * pass [archivedOnly] to fetch only archived sessions (`?archived=only`). One page of
     * [limit] covers current volume (no offset paging in MVP).
     */
    suspend fun profileSessions(limit: Int = 500, archivedOnly: Boolean = false): ProfileSessionsDto =
        get("/api/profiles/sessions?limit=$limit&order=recent${if (archivedOnly) "&archived=only" else ""}")

    suspend fun messages(sessionId: String, profile: String? = null): List<MessageDto> =
        get<MessagesDto>("/api/sessions/$sessionId/messages${profileParam(profile, first = true)}").messages

    /** Stream a Connector-authorized artifact to disk; large files never become strings/ByteArrays. */
    suspend fun downloadArtifact(
        path: String,
        destination: File,
        maxBytes: Long = 100L * 1024L * 1024L,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        val call = okHttp.newCall(builder("/api/files?path=$encoded").get().build()).apply {
            timeout().timeout(10, TimeUnit.MINUTES)
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw HermesApiException(response.code, response.body.string().ifBlank { "HTTP ${response.code}" })
                }
                val body = response.body
                val total = body.contentLength().takeIf { it >= 0 }
                if (total != null && total > maxBytes) throw HermesApiException(413, "file is too large")
                destination.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            if (downloaded > maxBytes) throw HermesApiException(413, "file is too large")
                            output.write(buffer, 0, read)
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        destination
    }

    /** Upload raw bytes over HTTPS; only the tunnel layer Base64-encodes them once. */
    suspend fun uploadArtifact(bytes: ByteArray, name: String, mimeType: String): UploadedArtifact =
        withContext(Dispatchers.IO) {
            val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
            val request = builder("/api/files/upload?name=$encodedName")
                .post(bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
                .build()
            okHttp.newCall(request).apply {
                timeout().timeout(10, TimeUnit.MINUTES)
            }.execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw HermesApiException(response.code, body.ifBlank { "HTTP ${response.code}" })
                }
                val obj = json.parseToJsonElement(body).jsonObject
                UploadedArtifact(
                    path = obj["path"]?.jsonPrimitive?.content
                        ?: throw HermesApiException(0, "upload response contained no path"),
                    name = obj["name"]?.jsonPrimitive?.content ?: name,
                    sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: bytes.size.toLong(),
                )
            }
        }

    /** "&profile=x" (or "?profile=x" when [first]) — empty when profile is null/blank. */
    private fun profileParam(profile: String?, first: Boolean = false): String {
        if (profile.isNullOrBlank()) return ""
        val sep = if (first) "?" else "&"
        return "${sep}profile=${java.net.URLEncoder.encode(profile, "UTF-8")}"
    }

    suspend fun profiles(): List<ProfileDto> = get<ProfilesDto>("/api/profiles").profiles

    /** The gateway's currently-active profile name (current takes precedence over active). */
    suspend fun activeProfile(): String? =
        get<ActiveProfileDto>("/api/profiles/active").let { it.current ?: it.active }

    /** Raw provider list (each provider carries its model strings + an is_current flag).
     *  [profile] scopes the picker to that profile's config — upstream reads the SAME profile
     *  /api/model/set writes, so options and set must carry the same value. */
    suspend fun modelProviders(profile: String? = null): List<ModelProviderDto> =
        get<ModelOptionsDto>("/api/model/options${profileParam(profile, first = true)}").providers

    suspend fun modelOptions(profile: String? = null): List<ModelOptionDto> =
        get<ModelOptionsDto>("/api/model/options${profileParam(profile, first = true)}").providers.flatMap { p ->
            p.models.map { m -> ModelOptionDto(provider = p.slug, model = m, label = m) }
        }

    /** Writes model.provider + model.default into [profile]'s config; affects NEW sessions only. */
    suspend fun setModel(provider: String, model: String, profile: String? = null) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            put("scope", "main")   // required by /api/model/set (the primary model slot)
            put("provider", provider)
            put("model", model)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/model/set${profileParam(profile, first = true)}").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set model failed")
        }
    }

    /**
     * Rename and/or archive a session via PATCH /api/sessions/{id}. [profile] MUST identify the
     * session's profile (sent in the body): the gateway resolves the session against a per-profile
     * DB, and without it a session in a non-default profile returns 404 — so the archive/rename
     * silently no-ops and the session never leaves the list.
     */
    suspend fun patchSession(
        sessionId: String,
        title: String? = null,
        archived: Boolean? = null,
        profile: String? = null,
    ) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            if (title != null) put("title", title)
            if (archived != null) put("archived", archived)
            if (!profile.isNullOrBlank()) put("profile", profile)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/sessions/$sessionId").patch(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "update session failed")
        }
    }

    /** Delete a session. [profile] (query param) scopes it to the right per-profile DB. */
    suspend fun deleteSession(sessionId: String, profile: String? = null) = withContext(Dispatchers.IO) {
        val path = "/api/sessions/$sessionId${profileParam(profile, first = true)}"
        restCall(builder(path).delete().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "delete session failed")
        }
    }

    suspend fun sessionStats(profile: String? = null): SessionStatsDto =
        get("/api/sessions/stats${profileParam(profile, first = true)}")

    /**
     * Gateway full-text search. [query] is the user's raw text; [buildSearchQuery] quotes CJK
     * tokens so the gateway's prefix wildcard does not break them (see SearchQuery.kt).
     * [excludeSources] mirrors the list's source filter so cron/subagent/platform sessions do
     * not surface as hits the list cannot show.
     */
    suspend fun searchSessions(
        query: String,
        profile: String? = null,
        excludeSources: Collection<String> = emptyList(),
    ): List<SearchResultDto> {
        val q = java.net.URLEncoder.encode(buildSearchQuery(query), "UTF-8")
        val exclude = excludeSources.filter { it.isNotBlank() }.joinToString(",")
        val excludeParam = if (exclude.isEmpty()) "" else "&exclude_sources=${java.net.URLEncoder.encode(exclude, "UTF-8")}"
        return get<SearchResultsDto>("/api/sessions/search?q=$q&limit=30$excludeParam${profileParam(profile)}").results
    }

    suspend fun archivedSessions(profile: String? = null): List<SessionDto> =
        get<SessionListDto>(
            "/api/sessions?archived=only&limit=50&order=recent${profileParam(profile)}",
        ).sessions

    suspend fun cronJobs(profile: String? = null): List<CronJobDto> =
        get("/api/cron/jobs${profileParam(profile, first = true)}")

    suspend fun cronJob(jobId: String, profile: String? = null): CronJobDto =
        get("/api/cron/jobs/$jobId${profileParam(profile, first = true)}")

    suspend fun cronRuns(jobId: String, profile: String? = null): List<CronRunDto> =
        get<CronRunsDto>("/api/cron/jobs/$jobId/runs${profileParam(profile, first = true)}").runs

    /** POST a cron action (pause | resume | trigger); empty body. */
    private suspend fun cronAction(jobId: String, action: String, profile: String?) =
        withContext(Dispatchers.IO) {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val path = "/api/cron/jobs/$jobId/$action${profileParam(profile, first = true)}"
            restCall(builder(path).post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, "$action failed")
            }
        }

    suspend fun pauseCron(jobId: String, profile: String? = null) = cronAction(jobId, "pause", profile)
    suspend fun resumeCron(jobId: String, profile: String? = null) = cronAction(jobId, "resume", profile)
    suspend fun triggerCron(jobId: String, profile: String? = null) = cronAction(jobId, "trigger", profile)

    suspend fun createCron(prompt: String, schedule: String, name: String, profile: String? = null) =
        withContext(Dispatchers.IO) {
            val obj = buildJsonObject {
                put("prompt", prompt); put("schedule", schedule)
                if (name.isNotBlank()) put("name", name)
            }
            val payload = json.encodeToString(JsonObject.serializer(), obj)
                .toRequestBody("application/json".toMediaType())
            restCall(builder("/api/cron/jobs${profileParam(profile, first = true)}").post(payload).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(160)
                        throw HermesApiException(resp.code, "create cron failed: $body")
                    }
                }
        }

    suspend fun updateCron(jobId: String, prompt: String, schedule: String, name: String, profile: String? = null) =
        withContext(Dispatchers.IO) {
            val obj = buildJsonObject {
                put("prompt", prompt); put("schedule", schedule); put("name", name)
            }
            val payload = json.encodeToString(JsonObject.serializer(), obj)
                .toRequestBody("application/json".toMediaType())
            restCall(builder("/api/cron/jobs/$jobId${profileParam(profile, first = true)}").put(payload).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(160)
                        throw HermesApiException(resp.code, "update cron failed: $body")
                    }
                }
        }

    suspend fun deleteCron(jobId: String, profile: String? = null) = withContext(Dispatchers.IO) {
        restCall(builder("/api/cron/jobs/$jobId${profileParam(profile, first = true)}").delete().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, "delete cron failed")
            }
    }

    /** [days] is clamped upstream to 1-365; the UI only ever offers 7 / 30 / 90. */
    suspend fun analyticsUsage(profile: String? = null, days: Int = 30): UsageDto =
        get("/api/analytics/usage?days=${days.coerceIn(1, 365)}${profileParam(profile)}")


    // ---- Config (whole-object GET-modify-PUT so no fields are ever dropped) ----

    suspend fun getConfig(profile: String? = null): JsonObject =
        get("/api/config${profileParam(profile, first = true)}")

    suspend fun putConfig(config: JsonObject, profile: String? = null) = withContext(Dispatchers.IO) {
        // PUT /api/config expects the config wrapped as {"config": {...}} (422 otherwise).
        val wrapped = JsonObject(mapOf("config" to config))
        val payload = json.encodeToString(JsonObject.serializer(), wrapped)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/config${profileParam(profile, first = true)}").put(payload).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(180)
                    throw HermesApiException(resp.code, "HTTP ${resp.code}: $body")
                }
            }
    }

    suspend fun setProfileModel(name: String, provider: String, model: String) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("provider", provider); put("model", model) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/profiles/$name/model").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set profile model failed")
        }
    }

    // ---- Env / API keys (per-key endpoints — safe by design) ----

    suspend fun envVars(profile: String? = null): Map<String, EnvVarDto> =
        get("/api/env${profileParam(profile, first = true)}")

    suspend fun setEnv(key: String, value: String, profile: String? = null) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            put("key", key); put("value", value); if (profile != null) put("profile", profile)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/env").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set env failed")
        }
    }

    suspend fun revealEnv(key: String, profile: String? = null): String = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("key", key); if (profile != null) put("profile", profile) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/env/reveal").post(payload).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "reveal env failed")
            json.decodeFromString<JsonObject>(body)["value"]?.jsonPrimitive?.content ?: ""
        }
    }

    /**
     * Transcribe a recorded voice note. [dataUrl] is a base64 data URL (data:<mime>;base64,<b64>)
     * the gateway's POST /api/audio/transcribe accepts; returns the trimmed transcript ("" if the
     * STT backend returned nothing). Throws HermesApiException on a non-2xx (e.g. no STT configured).
     */
    suspend fun transcribe(dataUrl: String, mimeType: String): String = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("data_url", dataUrl); put("mime_type", mimeType) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/audio/transcribe").post(payload).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "transcription failed")
            // Treat an explicit JSON null (out-of-contract, but guards against a phantom "null"
            // transcript) the same as a missing field → "".
            val el = json.decodeFromString<JsonObject>(body)["transcript"]
            if (el == null || el is JsonNull) "" else el.jsonPrimitive.content.trim()
        }
    }

    suspend fun skills(profile: String? = null): List<SkillDto> =
        get("/api/skills${profileParam(profile, first = true)}")

    suspend fun toggleSkill(name: String, enabled: Boolean, profile: String? = null) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject { put("name", name); put("enabled", enabled) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/skills/toggle${profileParam(profile, first = true)}").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "toggle skill failed")
        }
    }

    suspend fun toolsets(profile: String? = null): List<ToolsetDto> =
        get("/api/tools/toolsets${profileParam(profile, first = true)}")

    suspend fun messagingPlatforms(profile: String? = null): List<MessagingPlatformDto> =
        get<MessagingPlatformsDto>("/api/messaging/platforms${profileParam(profile, first = true)}").platforms

    suspend fun setMessagingEnabled(platformId: String, enabled: Boolean, profile: String? = null) =
        configureMessaging(platformId, emptyMap(), enabled, profile)

    /** Configure a messaging platform: set env vars and/or enable/disable it. */
    suspend fun configureMessaging(
        platformId: String,
        env: Map<String, String>,
        enabled: Boolean?,
        profile: String? = null,
    ) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            if (enabled != null) put("enabled", enabled)
            if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/messaging/platforms/$platformId${profileParam(profile, first = true)}").put(payload).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(180)
                    throw HermesApiException(resp.code, "configure platform failed: $body")
                }
            }
    }

    suspend fun setActiveProfile(name: String) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject { put("name", name) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        restCall(builder("/api/profiles/active").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set active profile failed")
        }
    }
}
