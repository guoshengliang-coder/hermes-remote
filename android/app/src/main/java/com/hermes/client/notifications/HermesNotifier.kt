package com.hermes.client.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.hermes.client.MainActivity
import com.hermes.client.R
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.AppLanguageProvider
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.avatarAccentArgb

/**
 * Owns notification channels and turns a [NotificationSpec] into a posted Android notification.
 * Rendering only: what a card says and when it exists is decided by the projector/planner.
 */
class HermesNotifier(
    private val context: Context,
    private val languages: AppLanguageProvider,
) {
    private val mgr = NotificationManagerCompat.from(context)

    fun ensureChannels(language: AppLanguage = languages.current) {
        val sys = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, importance: Int, description: String) {
            sys.createNotificationChannel(NotificationChannel(id, name, importance).apply {
                this.description = description
            })
        }
        channel(
            Notif.CHANNEL_ATTENTION,
            localized(language, "需要处理", "Needs you"),
            NotificationManager.IMPORTANCE_HIGH,
            localized(language, "审批请求、提问和等待你处理的任务", "Approval requests, questions, and tasks waiting for you"),
        )
        channel(
            Notif.CHANNEL_COMPLETED,
            localized(language, "任务完成", "Task finished"),
            NotificationManager.IMPORTANCE_DEFAULT,
            localized(language, "智能体运行完成", "An agent run finished"),
        )
        channel(
            Notif.CHANNEL_FAILURES,
            localized(language, "运行失败", "Run failed"),
            NotificationManager.IMPORTANCE_DEFAULT,
            localized(language, "运行失败或停止后未确认完成", "A run failed or stopped without a confirmed completion"),
        )
        channel(
            Notif.CHANNEL_RUN_PROGRESS,
            localized(language, "运行进度", "Run progress"),
            NotificationManager.IMPORTANCE_LOW,
            localized(language, "运行中的任务进度，静默常驻", "Silent, ongoing progress of running tasks"),
        )
        channel(
            Notif.CHANNEL_SERVICE,
            localized(language, "后台连接", "Background connection"),
            NotificationManager.IMPORTANCE_MIN,
            localized(language, "后台保持连接时的常驻通知", "Shown while the background connection is kept alive"),
        )
        channel(
            Notif.CHANNEL_UPDATES,
            localized(language, "应用更新", "App updates"),
            NotificationManager.IMPORTANCE_DEFAULT,
            localized(language, "新版本下载并校验完成", "A new version was downloaded and verified"),
        )
        // Channel importance cannot be changed after creation, so the old approvals/activity
        // channels are retired rather than reused; their user settings do not carry over.
        Notif.LEGACY_CHANNELS.forEach { runCatching { sys.deleteNotificationChannel(it) } }
    }

    /** Foreground-service card. [monitoring] = sessions with active work right now. */
    fun serviceNotification(monitoring: Int = 0): Notification {
        val language = languages.current
        val body = if (monitoring > 0) {
            localized(language, "后台保持连接 · 正在监控 $monitoring 个任务", "Connected in the background · monitoring $monitoring task(s)")
        } else localized(language, "后台保持连接", "Connected in the background")
        return NotificationCompat.Builder(context, Notif.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes GO")
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun post(spec: NotificationSpec) {
        if (!mgr.areNotificationsEnabled()) {
            com.hermes.client.data.diagnostics.DebugLog.log("notif", "dropped ${spec.kind ?: "update"} id=${spec.id}: notifications disabled")
            return
        }
        val n = if (Build.VERSION.SDK_INT >= 36 && spec.ongoing && spec.progress != null) {
            Api36ProgressBuilder.build(context, spec, openIntent(spec.route, spec.id), deleteIntent(spec), publicVersion(spec), sortKeyFor(spec.kind!!))
        } else buildCompat(spec)
        mgr.notify(spec.id, n)
    }

    fun cancel(id: Int) = mgr.cancel(id)

    fun postSummary(summary: NotificationSummary) {
        if (!mgr.areNotificationsEnabled()) return
        val label = summary.label(languages.current)
        val n = NotificationCompat.Builder(context, Notif.CHANNEL_COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes GO")
            .setContentText(label)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(label))
            .setGroup(Notif.GROUP_SESSIONS)
            .setGroupSummary(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, Notif.SUMMARY_NOTIFICATION_ID))
            .build()
        mgr.notify(Notif.SUMMARY_NOTIFICATION_ID, n)
    }

    fun cancelSummary() = mgr.cancel(Notif.SUMMARY_NOTIFICATION_ID)

    /** Remove every session card and the group summary (used once at process start). */
    fun cancelSessionCards() {
        mgr.activeNotifications
            .filter { it.notification.group == Notif.GROUP_SESSIONS }
            .forEach { mgr.cancel(it.id) }
    }

    private fun buildCompat(spec: NotificationSpec): Notification {
        val b = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            // Android has a single sub-text slot and it is taken by the header line, so the error
            // code / duration renders as the last line of the expanded body instead.
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(spec.body, spec.subText).joinToString("\n")))
            .setAutoCancel(spec.autoCancel)
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(spec.onlyAlertOnce)
            .setSilent(spec.silent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(spec.route, spec.id))
        headerText(spec)?.let { b.setSubText(it) }
        spec.category?.let { b.setCategory(it) }
        spec.groupKey?.let { b.setGroup(it) }
        spec.kind?.let { b.setSortKey(sortKeyFor(it)) }
        spec.accentProfile?.let { b.setColor(avatarAccentArgb(it)) }
        spec.whenMs?.let { b.setWhen(it).setShowWhen(true) }
        if (spec.chronometer && spec.whenMs != null) b.setUsesChronometer(true)
        spec.progress?.let { b.setProgress(it.total, it.done, it.indeterminate) }
        if (spec.sessionKey != null) b.setDeleteIntent(deleteIntent(spec))
        publicVersion(spec)?.let { b.setPublicVersion(it) }
        spec.actions.forEach { a ->
            when {
                a.reply -> {
                    val remoteInput = RemoteInput.Builder(Notif.KEY_REPLY_TEXT)
                        .setLabel(localized(languages.current, "回复…", "Reply…"))
                        .build()
                    b.addAction(
                        NotificationCompat.Action.Builder(0, a.label, replyIntent(a, spec.id))
                            .addRemoteInput(remoteInput)
                            .setAllowGeneratedReplies(false)
                            .build(),
                    )
                }
                a.action == Notif.ACTION_OPEN -> b.addAction(0, a.label, openIntent(spec.route, spec.id))
                else -> b.addAction(0, a.label, actionIntent(a, spec.id))
            }
        }
        return b.build()
    }

    /** Header line after the app name: "identity · state word" (the time is appended by Android). */
    private fun headerText(spec: NotificationSpec): String? =
        listOfNotNull(spec.profileLabel, spec.stateLabel).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /**
     * Lock-screen version when the system hides sensitive content: icon, state word, and session
     * title only — never the command, question, or reply text.
     */
    private fun publicVersion(spec: NotificationSpec): Notification? {
        val title = spec.publicTitle ?: return null
        val b = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        spec.publicBody?.let { b.setContentText(it) }
        spec.accentProfile?.let { b.setColor(avatarAccentArgb(it)) }
        spec.whenMs?.let { b.setWhen(it).setShowWhen(true) }
        return b.build()
    }

    private fun openIntent(route: String?, id: Int): PendingIntent {
        // Built with direct calls on the variable rather than inside an `apply { }` block, and
        // targeted by class name rather than via Intent(Context, Class). All forms are equally
        // explicit at runtime, but this is the one static analysis reliably attributes to the
        // intent — an implicit PendingIntent handed to the notification shade IS redirectable,
        // so it is worth making the component unmistakable. `.name` keeps it rename-safe.
        val intent = Intent()
        intent.setClassName(context, MainActivity::class.java.name)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        route?.let { intent.putExtra("extra_route", it) }
        // PendingIntent identity ignores extras. Give every notification destination stable,
        // route-specific data so Android can never reuse a stale/new-chat target merely because a
        // request-code hash collides or a previous notification used the same component.
        intent.data = Uri.Builder()
            .scheme("hermes-internal")
            .authority("notification")
            .appendPath(id.toString())
            .appendQueryParameter("route", route.orEmpty())
            .build()
        // Flags spelled out at the creation site rather than via a helper: static analysis
        // constant-folds a literal here, but not a value returned from a function, and an
        // unprovable FLAG_IMMUTABLE reads as a mutable PendingIntent.
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun receiverIntent(a: NotifAction, notifId: Int): Intent {
        // Direct calls, explicit component — see the note in openIntent().
        val intent = Intent()
        intent.setClassName(context, NotificationActionReceiver::class.java.name)
        intent.action = a.action
        intent.putExtra(Notif.EXTRA_SESSION_ID, a.sessionId)
        intent.putExtra(Notif.EXTRA_STORED_SESSION_ID, a.storedSessionId)
        intent.putExtra(Notif.EXTRA_PROFILE, a.profile)
        intent.putExtra(Notif.EXTRA_NOTIF_ID, notifId)
        intent.putExtra(Notif.EXTRA_REQUEST_ID, a.requestId.orEmpty())
        intent.putExtra(Notif.EXTRA_QUESTION_ID, a.questionId.orEmpty())
        intent.putExtra(Notif.EXTRA_ANSWER, a.answer.orEmpty())
        return intent
    }

    private fun actionIntent(a: NotifAction, notifId: Int): PendingIntent =
        // Flags inline — see the note in openIntent().
        PendingIntent.getBroadcast(
            context,
            ("$notifId:${a.action}:${a.questionId.orEmpty()}:${a.answer.orEmpty()}").hashCode(),
            receiverIntent(a, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun replyIntent(a: NotifAction, notifId: Int): PendingIntent =
        // Direct reply requires FLAG_MUTABLE so the system can attach the RemoteInput results.
        // The intent is explicit (our own receiver), so it can't be redirected — mutability is safe.
        PendingIntent.getBroadcast(
            context,
            // Distinct namespace from actionIntent()'s request code so a reply (MUTABLE) and a
            // button (IMMUTABLE) can never share PendingIntent identity (would crash on Android 12+).
            ("reply:$notifId:${a.questionId.orEmpty()}").hashCode(),
            receiverIntent(a, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun deleteIntent(spec: NotificationSpec): PendingIntent {
        val key = spec.sessionKey!!
        val intent = Intent()
        intent.setClassName(context, NotificationActionReceiver::class.java.name)
        intent.action = Notif.ACTION_DISMISSED
        intent.putExtra(Notif.EXTRA_STORED_SESSION_ID, key.sessionId)
        intent.putExtra(Notif.EXTRA_PROFILE, key.profile)
        intent.putExtra(Notif.EXTRA_NOTIF_ID, spec.id)
        intent.putExtra(Notif.EXTRA_KIND, spec.kind?.name)
        return PendingIntent.getBroadcast(
            context,
            ("dismiss:${spec.id}").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun sortKeyFor(kind: NotificationKind): String = when {
        kind.needsUser -> "0"
        kind == NotificationKind.FAILED || kind == NotificationKind.UNCONFIRMED -> "1"
        kind == NotificationKind.COMPLETED -> "2"
        else -> "3"
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = Notif.SERVICE_NOTIFICATION_ID
    }
}

/**
 * Isolates the API-36-only `Notification.ProgressStyle` construction in its own class so that
 * [HermesNotifier]'s own method bodies never name an API-36 type. ART verifies dex classes
 * lazily and per-class, so this is defensive hardening against class-verification issues rather
 * than a behaviour change — [HermesNotifier.post] only reaches this class from behind the
 * `Build.VERSION.SDK_INT >= 36` guard. Promotion to a status-bar Live Update is requested with
 * `requestPromotedOngoing`; the system still decides, and with several running sessions it
 * picks one.
 */
@androidx.annotation.RequiresApi(36)
private object Api36ProgressBuilder {
    fun build(
        context: Context,
        spec: NotificationSpec,
        contentIntent: PendingIntent,
        deleteIntent: PendingIntent,
        publicVersion: Notification?,
        sortKey: String,
    ): Notification {
        val progress = spec.progress!!
        val accent = spec.accentProfile?.let { avatarAccentArgb(it) }
        val style = Notification.ProgressStyle().setProgressIndeterminate(progress.indeterminate)
        if (!progress.indeterminate) {
            // ProgressStyle has no setProgressMax(): the bar's maximum is the SUM of its segment
            // lengths, so one segment of `total` gives a bar of exactly that length.
            val segment = Notification.ProgressStyle.Segment(progress.total)
            accent?.let { segment.setColor(it) }
            style.addProgressSegment(segment)
            style.setProgress(progress.done)
        }
        val b = Notification.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setSortKey(sortKey)
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            b.setRequestPromotedOngoing(true)
        }
        publicVersion?.let { b.setPublicVersion(it) }
        listOfNotNull(spec.profileLabel, spec.stateLabel).takeIf { it.isNotEmpty() }?.let { b.setSubText(it.joinToString(" · ")) }
        spec.groupKey?.let { b.setGroup(it) }
        spec.category?.let { b.setCategory(it) }
        accent?.let { b.setColor(it) }
        spec.whenMs?.let { b.setWhen(it).setShowWhen(true) }
        if (spec.chronometer && spec.whenMs != null) b.setUsesChronometer(true)
        // Status-bar chip text on a promoted notification.
        progress.shortText?.let { b.setShortCriticalText(it) }
        return b.build()
    }
}
