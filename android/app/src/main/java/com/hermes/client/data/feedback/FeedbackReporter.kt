package com.hermes.client.data.feedback

import android.app.Activity
import java.io.File
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.repository.ThemeMode

/** What the host suggests for one report. The user edits all of it in the MissionGo editor. */
data class FeedbackPrefill(
    val title: String = "",
    val description: String = "",
    val context: Map<String, String> = emptyMap(),
    /**
     * Files uploaded after the report is created. Upload is best effort — a rejected file leaves
     * the report itself intact — which is why the inline log entries and the description still
     * carry the same account rather than deferring to the attachment.
     */
    val attachments: List<File> = emptyList(),
)

sealed interface FeedbackOutcome {
    data class Submitted(val itemKey: String) : FeedbackOutcome

    data object Cancelled : FeedbackOutcome

    data class Failed(val error: AppError) : FeedbackOutcome
}

/** Light or dark for the feedback editor, mirrored from the in-app theme. */
enum class FeedbackAppearance { FollowSystem, Light, Dark }

/**
 * The only seam between Hermes and the MissionGo feedback SDK.
 *
 * Everything `io.missiongo.*` lives behind this interface, for three reasons that are not about
 * guarding against an uninitialized SDK — the SDK handles that state itself:
 *
 * 1. The SDK is young and its contract still moves (0.2.0 → 0.2.3 in a day, one of them adding a
 *    field to a result type). A version bump should touch one file.
 * 2. `docs/ERROR_HANDLING.md` requires low-level failures to become `HR-*` errors at a component
 *    boundary. This is that boundary; no MissionGo error code reaches a ViewModel.
 * 3. Tests get a stand-in instead of a network call.
 */
interface FeedbackReporter {

    /** True when an endpoint and token were configured at build time. Gates every entry point. */
    val isAvailable: Boolean

    /** Semantic screen name, not a route with arguments. */
    fun setScreen(name: String?)

    fun setContext(namespace: String, values: Map<String, String>)

    /** Mirrors the in-app theme; the editor cannot see it from the resource configuration. */
    fun setAppearance(appearance: FeedbackAppearance)

    /**
     * Adds what this reporter can supply for the coming report — currently a snapshot of the
     * rolling diagnostic log — and returns the prefill to submit.
     *
     * **Blocking file I/O; call off the main thread.** It waits for queued log appends to reach
     * the file and then reads the rolling pair, which is bounded but not instant. Separate from
     * [open] so the caller decides where that wait happens.
     */
    fun prepare(prefill: FeedbackPrefill): FeedbackPrefill

    /**
     * Opens the editor. Attaches the current diagnostic-log snapshot first, so a report carries
     * the run that produced it.
     */
    fun open(activity: Activity, prefill: FeedbackPrefill, onOutcome: (FeedbackOutcome) -> Unit)

    /**
     * Hands a report to the background queue, for cases with no one waiting on the result — a
     * crash the user chose to send. Returns false when the report could not be queued at all.
     */
    fun enqueue(prefill: FeedbackPrefill): Boolean
}

/** Used when no endpoint/token was configured. Every entry point checks [isAvailable] first. */
object UnavailableFeedbackReporter : FeedbackReporter {
    override val isAvailable: Boolean = false

    override fun setScreen(name: String?) = Unit

    override fun setContext(namespace: String, values: Map<String, String>) = Unit

    override fun setAppearance(appearance: FeedbackAppearance) = Unit

    override fun prepare(prefill: FeedbackPrefill): FeedbackPrefill = prefill

    override fun open(
        activity: Activity,
        prefill: FeedbackPrefill,
        onOutcome: (FeedbackOutcome) -> Unit,
    ) {
        onOutcome(FeedbackOutcome.Failed(AppError(AppErrorCode.FEEDBACK_UNAVAILABLE, retryable = false)))
    }

    override fun enqueue(prefill: FeedbackPrefill): Boolean = false
}

/**
 * Maps a MissionGo failure onto the product error contract.
 *
 * `code` is a string from three sources — the SDK's own codes, a code the server sent, or
 * `http_<status>` — so matching on an HTTP number would never fire. Retryability is not inferred
 * from the code either: the SDK reports what it already used to decide its own retries, and a
 * local table would go stale the moment the server adds a code.
 */
internal fun feedbackErrorFor(code: String, retryable: Boolean, stage: String): AppError = when (code) {
    "not_initialized" -> AppError(AppErrorCode.FEEDBACK_UNAVAILABLE, retryable = false, stage = stage)
    // The token was revoked or mistyped. Retrying cannot help; the build has to be fixed.
    "http_401", "http_403" ->
        AppError(AppErrorCode.FEEDBACK_REJECTED, retryable = false, technicalCause = code, stage = stage)
    "http_429" ->
        AppError(AppErrorCode.FEEDBACK_RATE_LIMITED, retryable = true, technicalCause = code, stage = stage)
    else ->
        AppError(AppErrorCode.FEEDBACK_SUBMIT_FAILED, retryable = retryable, technicalCause = code, stage = stage)
}

/**
 * The editor opens in its own Activity, which resolves resources from the system configuration —
 * an in-app theme never reaches it. Mirroring the app's own choice is therefore required, not a
 * nicety: a user on "dark" inside a light system would otherwise land on a white page.
 */
fun ThemeMode.toFeedbackAppearance(): FeedbackAppearance = when (this) {
    ThemeMode.SYSTEM -> FeedbackAppearance.FollowSystem
    ThemeMode.LIGHT -> FeedbackAppearance.Light
    ThemeMode.DARK -> FeedbackAppearance.Dark
}

/** The server rejects a draft whose description exceeds this; it does not truncate for us. */
const val FEEDBACK_DESCRIPTION_LIMIT = 20_000

/**
 * Fits a crash report into [limit] while keeping both ends.
 *
 * A plain `take` would drop the tail, and the tail is the stack trace — the one part that says
 * where the process died. The head is worth keeping too (version, device, the breadcrumb trail),
 * so an over-long report loses its middle, which is the diagnostic log that the `logs` channel
 * already carries separately.
 */
fun trimCrashReport(report: String, limit: Int = FEEDBACK_DESCRIPTION_LIMIT): String {
    if (report.length <= limit) return report
    val marker = "\n\n… 中间已省略 / trimmed …\n\n"
    val head = limit / 4
    val tail = (limit - head - marker.length).coerceAtLeast(0)
    return report.take(head) + marker + report.takeLast(tail)
}
