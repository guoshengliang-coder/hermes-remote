package com.hermes.client.data.network

import com.hermes.client.data.auth.normalizeGatewayBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class AccountApiException(
    val statusCode: Int,
    val errorCode: String,
    val retryable: Boolean,
    val recoveryAction: String,
    val correlationId: String? = null,
) : Exception(errorCode)

/** Account control-plane client. It never carries the legacy App Token or dashboard cookies. */
open class AccountApi(
    private val okHttp: OkHttpClient,
    private val json: Json,
) {
    suspend fun capabilities(baseUrl: String): AccountCapabilitiesDto =
        get(baseUrl, "/v2/capabilities", bearer = null)

    suspend fun requestEmailChallenge(
        baseUrl: String,
        email: String,
        clientInstallationId: String,
    ): EmailChallengeDto {
        val payload = buildJsonObject {
            put("email", email)
            put("platform", "android")
            put("clientInstallationId", clientInstallationId)
        }
        return post<EmailChallengeResponseDto>(baseUrl, "/v2/auth/email/challenges", payload).challenge
    }

    suspend fun exchangeEmailCode(
        baseUrl: String,
        challengeId: String,
        email: String,
        code: String,
        clientInstallationId: String,
        displayName: String,
        appVersion: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): AccountExchangeResponseDto {
        val payload = buildJsonObject {
            put("challengeId", challengeId)
            put("email", email)
            put("code", code)
            put("platform", "android")
            put("clientInstallationId", clientInstallationId)
            put("displayName", displayName)
            put("appVersion", appVersion)
        }
        return post(baseUrl, "/v2/auth/email/exchange", payload, idempotencyKey = idempotencyKey)
    }

    suspend fun refresh(
        baseUrl: String,
        refreshToken: String,
        clientInstallationId: String,
        idempotencyKey: String,
    ): AccountTokensDto {
        val payload = buildJsonObject {
            put("refreshToken", refreshToken)
            put("clientInstallationId", clientInstallationId)
        }
        return post<AccountRefreshResponseDto>(
            baseUrl,
            "/v2/auth/refresh",
            payload,
            idempotencyKey = idempotencyKey,
        ).session
    }

    suspend fun devices(baseUrl: String, bearer: String): AccountDevicesResponseDto =
        get(baseUrl, "/v2/devices", bearer)

    /** Compatibility view used while an account can own exactly one Mac binding. */
    suspend fun binding(baseUrl: String, bearer: String): AccountBindingSnapshotDto =
        get(baseUrl, "/v2/connector-binding", bearer)

    suspend fun selectDefaultDevice(
        baseUrl: String,
        bearer: String,
        deviceId: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): AccountDeviceDto = post<AccountDeviceResponseDto>(
        baseUrl,
        "/v2/devices/${encodePathSegment(deviceId)}/select-default",
        buildJsonObject {},
        bearer,
        idempotencyKey,
    ).device

    /**
     * Probe the explicit device route without collapsing authorization/revocation failures into
     * an “offline” result. The caller needs the stable server error code to choose the right
     * recovery action (for example, sign in again versus start the Mac).
     */
    suspend fun probeDevice(baseUrl: String, bearer: String, deviceId: String) {
        get<GatewayStatusDto>(
            baseUrl,
            "/v2/devices/${encodePathSegment(deviceId)}/api/status",
            bearer,
        )
    }

    /** Probe the singular account route before replacing a known-working Legacy transport. */
    suspend fun probeSingleBinding(baseUrl: String, bearer: String) {
        get<GatewayStatusDto>(baseUrl, "/api/status", bearer)
    }

    suspend fun signOut(
        baseUrl: String,
        bearer: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) {
        deleteNoContent(baseUrl, "/v2/installations/current", bearer, idempotencyKey)
    }

    suspend fun requestEmailReauthenticationChallenge(
        baseUrl: String,
        email: String,
        bearer: String,
    ): EmailChallengeDto = post<EmailChallengeResponseDto>(
        baseUrl,
        "/v2/auth/reauth/email/challenges",
        buildJsonObject { put("email", email) },
        bearer,
    ).challenge

    suspend fun reauthenticateEmail(
        baseUrl: String,
        challengeId: String,
        email: String,
        code: String,
        scope: String,
        bearer: String,
        idempotencyKey: String,
    ): AccountReauthenticationGrantDto = post(
        baseUrl,
        "/v2/auth/reauth/email",
        buildJsonObject {
            put("challengeId", challengeId)
            put("email", email)
            put("code", code)
            put("scope", scope)
        },
        bearer,
        idempotencyKey,
    )

    suspend fun deleteAccount(
        baseUrl: String,
        bearer: String,
        grant: String,
        idempotencyKey: String,
    ) {
        deleteNoContent(
            baseUrl,
            "/v2/account",
            bearer,
            idempotencyKey,
            buildJsonObject {
                put("grant", grant)
                put("acknowledgedPermanentCloudDeletion", true)
            },
        )
    }

    private suspend inline fun <reified T> get(
        baseUrl: String,
        path: String,
        bearer: String?,
    ): T = execute(Request.Builder().url(url(baseUrl, path)).get().apply {
        bearer?.let { header("Authorization", "Bearer $it") }
    }.build())

    private suspend inline fun <reified T> post(
        baseUrl: String,
        path: String,
        body: JsonObject,
        bearer: String? = null,
        idempotencyKey: String? = null,
    ): T {
        val request = Request.Builder().url(url(baseUrl, path)).apply {
            bearer?.let { header("Authorization", "Bearer $it") }
            idempotencyKey?.let { header("Idempotency-Key", it) }
        }.post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA)).build()
        return execute(request)
    }

    private suspend fun deleteNoContent(
        baseUrl: String,
        path: String,
        bearer: String,
        idempotencyKey: String,
        body: JsonObject? = null,
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url(baseUrl, path))
            .header("Authorization", "Bearer $bearer")
            .header("Idempotency-Key", idempotencyKey)
        val request = if (body == null) {
            builder.delete().build()
        } else {
            builder.delete(
                json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA),
            ).build()
        }
        call(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw accountError(response.code, body)
        }
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        call(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw accountError(response.code, body)
            json.decodeFromString<T>(body)
        }
    }

    private fun call(request: Request) = okHttp.newCall(request).apply {
        timeout().timeout(ACCOUNT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun accountError(statusCode: Int, body: String): AccountApiException {
        val error = runCatching { json.decodeFromString<AccountErrorEnvelopeDto>(body).error }.getOrNull()
        return AccountApiException(
            statusCode = statusCode,
            errorCode = error?.code ?: "HR-ACCOUNT-001",
            retryable = error?.retryable ?: (statusCode >= 500),
            recoveryAction = error?.recoveryAction ?: "retry",
            correlationId = error?.correlationId,
        )
    }

    private fun url(baseUrl: String, path: String): String =
        "${normalizeGatewayBaseUrl(baseUrl).trimEnd('/')}$path"

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val ACCOUNT_TIMEOUT_SECONDS = 20L
    }
}
