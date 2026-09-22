package com.hermes.client.notifications

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.repository.SessionReadStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
 * [knownSessions] is null before any list has ever loaded. Counting zero then would blank the
 * badge and bring it back a moment later, so the caller skips the update instead. Once one has
 * loaded, the caller passes the persisted set from that load until this process loads its own —
 * see [knownSessionsForBadge].
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
 * The session set [badgeCount] intersects with: this process's own list when it has loaded one,
 * otherwise the set persisted by the last list load on this install.
 *
 * The fallback is what lets a push-woken process count at all (HG-103). It never loads the session
 * list, so the badge used to stay untouched on every push and first moved after the user opened the
 * app — which looked like "+1 after reading". The persisted set still only holds sessions a list
 * actually returned, so the 0.1.131 rule stands: nothing the list cannot show is counted.
 */
fun knownSessionsForBadge(loaded: Set<String>?, persisted: Set<String>?): Set<String>? = loaded ?: persisted

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
    private val publisher by lazy { LatestCountPublisher(scope, io, ::pushOemBadge) }

    fun apply(count: Int) {
        if (!supportsOemBadge) return
        publisher.offer(count)
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
        val OEM_AUTHORITIES = listOf(
            "com.hihonor.android.launcher.settings",
            "com.huawei.android.launcher.settings",
        )
        val OEM_BRANDS = listOf("honor", "huawei")
    }
}

/**
 * Hands counts to a launcher one at a time, newest first, and skips a count the launcher already
 * holds.
 *
 * Every push used to be its own coroutine on the IO pool, so `apply(1)` then `apply(0)` were two
 * binder calls racing each other. When the 1 reached the HONOR launcher last the icon showed 1
 * while the dedup believed 0 was published, and every later `apply(0)` was skipped as a duplicate:
 * a badge that would not come down after the user read the session (HG-103). One consumer over a
 * conflated channel keeps the calls in order and drops counts that were already superseded.
 *
 * The dedup compares against what the launcher actually accepted. A failed push forgets it, so the
 * next offer of the same count tries again instead of being suppressed.
 */
internal class LatestCountPublisher(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val push: (Int) -> Boolean,
) {
    private val pending = Channel<Int>(Channel.CONFLATED)
    private var published = NOT_PUBLISHED

    init {
        scope.launch(dispatcher) {
            for (count in pending) {
                if (count == published) continue
                published = if (push(count)) count else NOT_PUBLISHED
            }
        }
    }

    fun offer(count: Int) {
        pending.trySend(count)
    }

    private companion object {
        const val NOT_PUBLISHED = -1
    }
}
