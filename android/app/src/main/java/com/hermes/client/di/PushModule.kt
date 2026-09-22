package com.hermes.client.di

import android.content.Context
import com.hermes.client.data.auth.AccountControlConnection
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.repository.LifecycleEventRepository
import com.hermes.client.data.repository.NotificationSettings
import com.hermes.client.notifications.LifecycleNotificationDispatcher
import com.hermes.client.notifications.push.FirebasePushPlatform
import com.hermes.client.notifications.push.PROVIDER_FCM
import com.hermes.client.notifications.push.PushAccountSource
import com.hermes.client.notifications.push.PushIdentity
import com.hermes.client.notifications.push.PushMessageHandler
import com.hermes.client.notifications.push.PushPlatform
import com.hermes.client.notifications.push.PushRegistrationApi
import com.hermes.client.notifications.push.PushRegistrationManager
import com.hermes.client.notifications.push.SharedPreferencesPushRegistrationRecord
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** FCM wake hints (HG-94). Everything here is inert when the build carries no Firebase values. */
@Module
@InstallIn(SingletonComponent::class)
object PushModule {
    @Provides
    @Singleton
    fun providePushPlatform(@ApplicationContext context: Context): PushPlatform = FirebasePushPlatform(context)

    @Provides
    @Singleton
    fun providePushRegistrationManager(
        @ApplicationContext context: Context,
        platform: PushPlatform,
        accountSessions: AccountSessionManager,
        api: AccountApi,
        settings: NotificationSettings,
        appScope: CoroutineScope,
    ): PushRegistrationManager = PushRegistrationManager(
        platform = platform,
        accounts = AccountSessionPushSource(accountSessions),
        api = AccountPushRegistrationApi(api),
        record = SharedPreferencesPushRegistrationRecord(context),
        notificationsEnabled = settings.prefs.map { it.enabled }.distinctUntilChanged(),
        scope = appScope,
    )

    @Provides
    @Singleton
    fun providePushMessageHandler(
        settings: NotificationSettings,
        events: LifecycleEventRepository,
        dispatcher: LifecycleNotificationDispatcher,
    ): PushMessageHandler = PushMessageHandler(
        notificationsEnabled = { settings.prefs.first().enabled },
        // The very call LifecycleEventJobService makes: one inbox, one card path.
        syncInbox = { events.sync { batch -> dispatcher.dispatch(batch) } },
        dispatch = dispatcher::dispatch,
    )
}

private class AccountSessionPushSource(private val sessions: AccountSessionManager) : PushAccountSource {
    // A getter, not a property initializer: reading `session` opens AndroidKeyStore, which must
    // not happen while Hilt builds the graph (Robolectric has no keystore; see AccountSessionManager).
    override val identity: Flow<PushIdentity?> get() = sessions.session.map { session ->
        // lifecycleCursorScope() is non-null exactly in the modes that own the phone inbox
        // (ACCOUNT, DEVICE_SELECTION_REQUIRED); Legacy and a pending migration get no push.
        if (session == null || session.activationPending || sessions.lifecycleCursorScope() == null) {
            null
        } else {
            PushIdentity(session.baseUrl, session.accountId, session.installationId)
        }
    }.distinctUntilChanged()

    override suspend fun controlConnection(): AccountControlConnection? = sessions.accountControlConnection()
}

private class AccountPushRegistrationApi(private val api: AccountApi) : PushRegistrationApi {
    override suspend fun serverSupportsFcm(baseUrl: String): Boolean =
        api.capabilities(baseUrl).push?.providers?.contains(PROVIDER_FCM) == true

    override suspend fun register(connection: AccountControlConnection, token: String) =
        api.putPushRegistration(connection.baseUrl, connection.bearer, PROVIDER_FCM, token)

    override suspend fun unregister(connection: AccountControlConnection) =
        api.deletePushRegistration(connection.baseUrl, connection.bearer)
}
