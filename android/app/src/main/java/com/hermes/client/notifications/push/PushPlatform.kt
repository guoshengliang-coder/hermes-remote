package com.hermes.client.notifications.push

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.hermes.client.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The four Firebase client values this build was compiled with (HG-94).
 *
 * They come from the gitignored `android/local.properties` or `HERMES_FCM_*` environment variables
 * (app/build.gradle.kts), never from a google-services.json in Git. All four empty is the normal
 * state of a fresh clone or CI, and means "no push in this build" — not an error.
 */
data class FcmConfig(
    val apiKey: String,
    val appId: String,
    val projectId: String,
    val senderId: String,
) {
    val isComplete: Boolean
        get() = listOf(apiKey, appId, projectId, senderId).all { it.isNotBlank() }

    companion object {
        fun fromBuildConfig(): FcmConfig = FcmConfig(
            apiKey = BuildConfig.FCM_API_KEY,
            appId = BuildConfig.FCM_APP_ID,
            projectId = BuildConfig.FCM_PROJECT_ID,
            senderId = BuildConfig.FCM_SENDER_ID,
        )
    }
}

/** Whether this process can use FCM at all, decided once at startup. */
enum class PushAvailability {
    /** The build carries no (or incomplete) Firebase values. */
    NOT_CONFIGURED,

    /** Configured, but the phone has no usable Google Play services (e.g. HONOR/Huawei without GMS). */
    NO_GOOGLE_PLAY_SERVICES,

    /** Firebase was initialized; tokens can be requested. */
    AVAILABLE,
}

/**
 * Decides and performs the manual Firebase initialization. Pure apart from the two injected
 * effects, so "config absent → Firebase never touched" is a unit test, not a hope.
 */
fun initializePush(
    config: FcmConfig,
    googlePlayServicesAvailable: () -> Boolean,
    initializeFirebase: (FcmConfig) -> Unit,
): PushAvailability {
    if (!config.isComplete) return PushAvailability.NOT_CONFIGURED
    if (!googlePlayServicesAvailable()) return PushAvailability.NO_GOOGLE_PLAY_SERVICES
    initializeFirebase(config)
    return PushAvailability.AVAILABLE
}

/** The Firebase surface the registration logic needs; faked in JVM tests. */
interface PushPlatform {
    /** Idempotent. Initializes Firebase when the build and phone allow it. */
    fun initialize(): PushAvailability

    /** The current FCM registration token. Throws when it cannot be obtained. */
    suspend fun fetchToken(): String

    /** Invalidates the token locally and at FCM, so a signed-out phone stops being addressable. */
    suspend fun deleteToken()
}

class FirebasePushPlatform(
    private val context: Context,
    private val config: FcmConfig = FcmConfig.fromBuildConfig(),
) : PushPlatform {
    @Volatile private var availability: PushAvailability? = null

    @Synchronized
    override fun initialize(): PushAvailability {
        availability?.let { return it }
        val result = initializePush(
            config = config,
            googlePlayServicesAvailable = {
                runCatching {
                    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                        ConnectionResult.SUCCESS
                }.getOrDefault(false)
            },
            initializeFirebase = { values ->
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(
                        context,
                        FirebaseOptions.Builder()
                            .setApiKey(values.apiKey)
                            .setApplicationId(values.appId)
                            .setProjectId(values.projectId)
                            .setGcmSenderId(values.senderId)
                            .build(),
                    )
                }
            },
        )
        availability = result
        return result
    }

    override suspend fun fetchToken(): String {
        check(initialize() == PushAvailability.AVAILABLE) { "FCM unavailable" }
        return FirebaseMessaging.getInstance().token.await()
    }

    override suspend fun deleteToken() {
        if (initialize() != PushAvailability.AVAILABLE) return
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> @Suppress("UNCHECKED_CAST") continuation.resume(task.result as T)
        }
    }
}
