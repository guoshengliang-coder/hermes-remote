package com.hermes.client.update

import com.hermes.client.notifications.HermesNotifier
import com.hermes.client.notifications.Notif
import com.hermes.client.notifications.NotificationSpec
import com.hermes.client.ui.localization.localized
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two hand-offs a download can make while the update page is not in front:
 * [notifyDownloaded] when the bytes are on disk but not yet verified, and [notifyReady] once
 * verification passed (foreground completions auto-launch the installer instead — see
 * UpdateViewModel). Both route back to the update page, which restores the persisted task.
 */
@Singleton
class UpdateReadyNotifier @Inject constructor(
    private val notifier: HermesNotifier,
    private val languages: com.hermes.client.ui.localization.AppLanguageProvider,
) {
    /**
     * The transfer finished but nothing verified it yet (the page was not around to do that).
     * Saying "已下载并校验通过" here would be a lie; verification still has to run.
     */
    fun notifyDownloaded(version: UpdateVersion) {
        val language = languages.current
        notifier.post(
            NotificationSpec(
                id = Notif.UPDATE_NOTIFICATION_ID,
                channelId = Notif.CHANNEL_UPDATES,
                title = localized(language, "Hermes GO ${version.versionName} 已下载", "Hermes GO ${version.versionName} downloaded"),
                body = localized(language, "返回应用完成校验后即可安装。", "Open the app to verify and install."),
                route = "app_update",
                whenMs = System.currentTimeMillis(),
                category = androidx.core.app.NotificationCompat.CATEGORY_RECOMMENDATION,
                publicTitle = localized(language, "Hermes GO 更新已下载", "Hermes GO update downloaded"),
            ),
        )
    }

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
