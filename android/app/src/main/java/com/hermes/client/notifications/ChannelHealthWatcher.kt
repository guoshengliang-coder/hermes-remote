package com.hermes.client.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.ui.localization.AppLanguageProvider
import com.hermes.client.ui.messaging.MessagingRowStatus
import com.hermes.client.ui.messaging.messagingRowStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.channelHealthStore by preferencesDataStore(name = "channel_health")

/**
 * Watches for a messaging channel that has stopped connecting.
 *
 * Deliberately NOT its own polling loop. It rides the fifteen-minute wake-up the notification
 * fallback already schedules ([LifecycleEventJobService]), adding one REST call to a wake that was
 * happening anyway — a separate timer for this is how the history-refetch storm started.
 *
 * It reports outages only. New messages inside a channel are not announced: DingTalk already
 * buzzed the phone once for those, and a second buzz minutes later, gated by a fifteen-minute
 * poll, would be both redundant and late.
 */
@Singleton
class ChannelHealthWatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tools: ToolsRepository,
    private val profiles: ProfileManager,
    private val notifier: HermesNotifier,
    private val languages: AppLanguageProvider,
) {
    private val key = stringSetPreferencesKey("reported_down")

    suspend fun check() {
        val platforms = runCatching { tools.messagingPlatforms(profiles.active.value) }.getOrNull() ?: return
        val down = platforms.filter {
            it.needsAttention || messagingRowStatus(it) in setOf(
                MessagingRowStatus.FAILED, MessagingRowStatus.GATEWAY_STOPPED,
            )
        }
        val decision = decideChannelHealth(down.map { it.id }.toSet(), reported())
        if (decision.newlyDown.isNotEmpty()) {
            // Name every channel that is currently down, not only the new one: the notification
            // replaces its predecessor, and a reader should see the whole outage.
            notifier.messagingHealth(down.map { it.name ?: it.id }, languages.current)
        } else if (decision.clearAll) {
            notifier.messagingHealth(emptyList(), languages.current)
        }
        remember(decision.remembered)
    }

    private suspend fun reported(): Set<String> = runCatching {
        context.channelHealthStore.data.first()[key] ?: emptySet()
    }.getOrElse { if (it is IOException) emptySet() else throw it }

    private suspend fun remember(ids: Set<String>) {
        runCatching { context.channelHealthStore.edit { it[key] = ids } }
    }
}
