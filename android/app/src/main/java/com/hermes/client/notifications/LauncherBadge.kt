package com.hermes.client.notifications

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.repository.SessionReadStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The number drawn on the home-screen icon, or null when it cannot be known yet and the current
 * badge must be left alone.
 *
 * Unread and needs-you are unioned per session rather than added: a session that is both unread
 * and waiting for an approval is still one thing to go look at, and adding them would overcount
 * exactly the sessions that matter most.
 *
 * [unread] is intersected with [knownSessions] because the persisted unread set is unbounded and
 * only shrinks when the user opens that exact conversation. A token whose session no longer
 * reaches the list can therefore never be cleared, and counting it puts a number on the icon that
 * nothing inside the app explains or can act on — 0.1.131 shipped exactly that, and one phone sat
 * at 40 with not a single unread dot anywhere in the app. The rule is now simply: the badge shows
 * what the session list shows.
 *
 * [knownSessions] is null before any list has loaded. Counting zero then would blank the badge on
 * every cold start and bring it back a moment later, so the caller skips the update instead.
 *
 * Sessions waiting on the user are counted whether or not the list knows them: each one has a
 * notification card the user can act on directly, so the badge is never asking about something
 * unreachable.
 */
fun badgeCount(
    unread: Set<String>,
    cards: Map<SessionRuntimeKey, NotificationSpec>,
    knownSessions: Set<String>?,
): Int? {
    if (knownSessions == null) return null
    val needsUser = cards.entries
        .filter { (_, spec) -> spec.kind?.needsUser == true }
        .map { (key, _) -> SessionReadStore.token(key.profile, key.sessionId, key.deviceId) }
    return (unread.intersect(knownSessions) + needsUser).size
}

/**
 * Publishes [badgeCount] to the launcher.
 *
 * Two mechanisms, because neither covers the phones this app runs on by itself:
 *
 *  - **AOSP.** A channel with `showBadge` gets a dot for free once a notification is posted, and
 *    launchers that draw numbers read `Notification.number`. That part lives in [HermesNotifier].
 *  - **Huawei / HONOR.** EMUI and MagicOS do not draw the AOSP dot at all. The count has to be
 *    handed to the launcher's own provider, and nothing appears until the app asks for it.
 *
 * Measured on HONOR CLK-AN00 (MagicOS, Android 14) on 2026-09-19: system badge switch on, the
 * app's own 「显示角标」 on, every channel's `showBadge` true, a completion notification posted
 * and visible in the status bar — and no badge, because this call did not exist. WeChat sat two
 * icons away showing 55.
 */
class LauncherBadge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    // Skip the IPC when the count has not moved; refresh() runs on every runtime change.
    private val published = AtomicInteger(NOT_PUBLISHED)

    fun apply(count: Int) {
        if (!supportsOemBadge) return
        if (published.getAndSet(count) == count) return
        scope.launch(io) {
            // A failed push must not be remembered as published, or the dedup above would suppress
            // every later attempt at the same count and the icon would stay wrong until it changed.
            if (!pushOemBadge(count)) published.set(NOT_PUBLISHED)
        }
    }

    /** True when some launcher accepted the count. */
    private fun pushOemBadge(count: Int): Boolean {
        val launcher = context.packageManager
            .getLaunchIntentForPackage(context.packageName)?.component?.className
            ?: return false
        val extras = Bundle().apply {
            putString("package", context.packageName)
            putString("class", launcher)
            putInt("badgenumber", count)
        }
        // HONOR split from Huawei and kept the interface under its own authority; a HONOR phone
        // answers on `hihonor` and an older Huawei one on `huawei`. Try both — the wrong one is a
        // failed call, not a wrong badge. `call` throws on an unknown authority, which is the
        // normal case on every other brand and must never reach the notification path.
        var delivered = false
        for (authority in OEM_AUTHORITIES) {
            runCatching {
                context.contentResolver.call(Uri.parse("content://$authority/badge/"), "change_badge", null, extras)
            }.onSuccess { delivered = true }
        }
        if (!delivered) DebugLog.log("badge", "no OEM launcher accepted count=$count")
        return delivered
    }

    private val supportsOemBadge: Boolean
        get() = OEM_BRANDS.any { brand ->
            Build.MANUFACTURER.contains(brand, ignoreCase = true) ||
                Build.BRAND.contains(brand, ignoreCase = true)
        }

    private companion object {
        const val NOT_PUBLISHED = -1
        val OEM_AUTHORITIES = listOf(
            "com.hihonor.android.launcher.settings",
            "com.huawei.android.launcher.settings",
        )
        val OEM_BRANDS = listOf("honor", "huawei")
    }
}
