package com.hermes.client.data.feedback

import android.app.Activity
import android.app.Application
import com.hermes.client.BuildConfig
import com.hermes.client.data.diagnostics.DebugLog
import io.missiongo.feedback.FeedbackOptions
import io.missiongo.feedback.FeedbackResult
import io.missiongo.feedback.MissionGo
import io.missiongo.feedback.MissionGoAppearance
import io.missiongo.feedback.MissionGoLogLevel
import io.missiongo.feedback.MissionGoOptions

/**
 * MissionGo-backed [FeedbackReporter].
 *
 * Created only when the build carries an endpoint and a token; otherwise the app uses
 * [UnavailableFeedbackReporter] and no entry point is shown.
 */
class MissionGoFeedbackReporter private constructor() : FeedbackReporter {

    override val isAvailable: Boolean get() = MissionGo.isInitialized

    override fun setScreen(name: String?) {
        MissionGo.setCurrentScreen(name)
    }

    override fun setContext(namespace: String, values: Map<String, String>) {
        MissionGo.setContext(namespace, values.mapValues { it.value.take(CONTEXT_VALUE_LIMIT) })
    }

    override fun setAppearance(appearance: FeedbackAppearance) {
        MissionGo.setEditorAppearance(
            when (appearance) {
                FeedbackAppearance.FollowSystem -> MissionGoAppearance.FollowSystem
                FeedbackAppearance.Light -> MissionGoAppearance.Light
                FeedbackAppearance.Dark -> MissionGoAppearance.Dark
            },
        )
    }

    override fun open(
        activity: Activity,
        prefill: FeedbackPrefill,
        onOutcome: (FeedbackOutcome) -> Unit,
    ) {
        attachDiagnosticSnapshot()
        MissionGo.openFeedback(activity, prefill.toOptions()) { result ->
            onOutcome(
                when (result) {
                    is FeedbackResult.Submitted -> FeedbackOutcome.Submitted(result.submission.itemKey)
                    FeedbackResult.Cancelled -> FeedbackOutcome.Cancelled
                    is FeedbackResult.Failed ->
                        FeedbackOutcome.Failed(feedbackErrorFor(result.code, result.retryable, "feedback_open"))
                },
            )
        }
    }

    override fun enqueue(prefill: FeedbackPrefill): Boolean {
        attachDiagnosticSnapshot()
        return MissionGo.enqueueFeedback(prefill.toOptions()) != null
    }

    private fun FeedbackPrefill.toOptions() = FeedbackOptions(
        title = title.take(TITLE_LIMIT),
        description = description.take(DESCRIPTION_LIMIT),
        context = context.mapValues { it.value.take(CONTEXT_VALUE_LIMIT) },
    )

    /**
     * Copies the diagnostic ring into the SDK's own buffer, which is snapshotted when the draft is
     * created. Deliberately a one-shot copy at report time rather than a live bridge: diagnostic
     * logging is off by default, and mirroring every entry would duplicate data for the runs — the
     * overwhelming majority — that never produce a report.
     *
     * Only entries newer than the previous copy are sent, so opening the editor twice does not
     * repeat the whole ring. Entries sharing the boundary millisecond can repeat; that is cheaper
     * than threading a sequence number through the log for a cosmetic gain.
     */
    private fun attachDiagnosticSnapshot() {
        val entries = DebugLog.entries.value
        if (entries.isEmpty()) return
        val fresh = entries.filter { it.timeMillis > lastAttachedAtMillis }.takeLast(LOG_ENTRY_LIMIT)
        if (fresh.isEmpty()) return
        fresh.forEach { entry ->
            // The entry's OWN time has to travel inside the payload: MissionGo.log() has no
            // timestamp parameter (checked against SDK 0.2.3 — all four overloads), so the SDK
            // stamps each line at the moment it is called. Because the whole ring is copied in
            // this one loop, every line used to land on the same millisecond: HG-19 arrived with
            // 500 log lines carrying 16 distinct timestamps across a six-minute incident, which
            // is ordering without timing — and timing is most of what a diagnostic log is for.
            //
            // Carried twice on purpose, for two readers. The message prefix is what a person sees
            // on the MissionGo page, and it survives whether or not that page renders attributes;
            // it reuses the shared-text export's format so one entry reads identically in a .txt
            // and in a filed report. The `at` attribute is ISO-8601 UTC, unambiguous for anything
            // parsing it.
            val shown = DebugLog.formatTimestamp(entry.timeMillis)
            MissionGo.log(
                level = if (entry.category == "error") MissionGoLogLevel.Error else MissionGoLogLevel.Debug,
                // The server rejects the whole draft over this limit rather than truncating, so the
                // cap is applied here. The prefix is part of the message, so it is inside the cap.
                message = "$shown ${entry.message}".take(LOG_MESSAGE_LIMIT),
                attributes = buildMap {
                    put("at", java.time.Instant.ofEpochMilli(entry.timeMillis).toString())
                    put("category", entry.category)
                    if (entry.fromPreviousRun) put("run", "previous")
                },
            )
        }
        lastAttachedAtMillis = fresh.last().timeMillis
    }

    @Volatile private var lastAttachedAtMillis = Long.MIN_VALUE

    companion object {
        // Server-side limits (services/server validateFeedbackDraft / validateFeedbackLogs): the
        // draft is rejected outright when any of them is exceeded, so trimming happens on our side.
        private const val TITLE_LIMIT = 500
        private const val DESCRIPTION_LIMIT = 20_000
        private const val CONTEXT_VALUE_LIMIT = 2_000
        private const val LOG_MESSAGE_LIMIT = 4_000
        private const val LOG_ENTRY_LIMIT = 500

        /**
         * Initializes the SDK when this build was configured for it, and returns the reporter to
         * use either way.
         *
         * `initialize` validates the endpoint and token format and throws on a malformed value.
         * That must not escape: a typo in a local properties file would otherwise crash the app on
         * launch, for a feature that is meant to be optional.
         */
        fun createFor(application: Application): FeedbackReporter {
            val endpoint = BuildConfig.MISSIONGO_ENDPOINT
            val token = BuildConfig.MISSIONGO_SDK_TOKEN
            if (endpoint.isBlank() || token.isBlank()) return UnavailableFeedbackReporter
            val started = runCatching {
                MissionGo.initialize(
                    application = application,
                    options = MissionGoOptions(
                        endpoint = endpoint,
                        sdkToken = token,
                        sourceRevision = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        buildFlavor = BuildConfig.BUILD_TYPE,
                        distributionChannel = "internal",
                    ),
                )
            }
            started.onFailure { error ->
                DebugLog.log("feedback", "MissionGo init failed: ${error.javaClass.simpleName}: ${error.message}")
            }
            return if (started.isSuccess) MissionGoFeedbackReporter() else UnavailableFeedbackReporter
        }
    }
}
