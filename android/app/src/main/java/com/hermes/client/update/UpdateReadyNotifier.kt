package com.hermes.client.update

import com.hermes.client.notifications.HermesNotifier
import com.hermes.client.notifications.Notif
import com.hermes.client.notifications.NotificationSpec
import com.hermes.client.ui.localization.localized
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "update downloaded and verified" hand-off when the download finishes while the
 * update page is NOT in front (foreground completions auto-launch the installer instead —
 * see UpdateViewModel). Tapping the notification routes back to the update page, which
 * restores the persisted task in the INSTALLABLE state.
 */
@Singleton
class UpdateReadyNotifier @Inject constructor(
    private val notifier: HermesNotifier,
    private val languages: com.hermes.client.ui.localization.AppLanguageProvider,
) {
    fun notifyReady(version: UpdateVersion) {
        val language = languages.current
        notifier.post(
            NotificationSpec(
                // Stable id: a re-post for a newer version replaces the stale card instead of stacking.
                id = Notif.UPDATE_NOTIFICATION_ID,
                channelId = Notif.CHANNEL_UPDATES,
                title = localized(language, "Hermes GO ${version.versionName} 已就绪", "Hermes GO ${version.versionName} is ready"),
                body = localized(
                    language,
                    "已下载并校验通过，点击安装。",
                    "Downloaded and verified. Tap to install.",
                ),
                route = "app_update",
                whenMs = System.currentTimeMillis(),
                category = androidx.core.app.NotificationCompat.CATEGORY_RECOMMENDATION,
                publicTitle = localized(language, "Hermes GO ${version.versionName} 已就绪", "Hermes GO ${version.versionName} is ready"),
            ),
        )
    }
}
