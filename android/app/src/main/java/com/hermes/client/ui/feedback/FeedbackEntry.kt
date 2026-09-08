package com.hermes.client.ui.feedback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hermes.client.data.feedback.FeedbackOutcome
import com.hermes.client.data.feedback.FeedbackPrefill
import com.hermes.client.data.feedback.FeedbackReporter
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage

/** The Activity hosting this composition, or null if the composable is previewed outside one. */
internal tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/**
 * Opens the feedback editor and reports the outcome, for every entry point that wants the
 * interactive flow.
 *
 * The result is a toast rather than an inline error block: the editor is a full-screen Activity of
 * its own, so by the time an outcome arrives the caller's surface may be a drawer that is already
 * closing, with nowhere to put a persistent banner. A failed submission keeps its `HR-FEEDBACK-*`
 * code in the toast so a report about the report is still actionable.
 */
@Composable
fun rememberFeedbackLauncher(reporter: FeedbackReporter): (FeedbackPrefill) -> Unit {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    return remember(reporter, context, language, scope) {
        { prefill ->
            val activity = context.hostActivity()
            if (activity == null) {
                Toast.makeText(context, unavailableText(language), Toast.LENGTH_SHORT).show()
            } else scope.launch {
                // prepare() waits on the log writer and reads the rolling files, so it cannot run
                // on the click. The editor opens a moment later, which is invisible next to the
                // Activity transition that follows it.
                val prepared = withContext(Dispatchers.IO) { reporter.prepare(prefill) }
                reporter.open(activity, prepared) { outcome ->
                    when (outcome) {
                        is FeedbackOutcome.Submitted -> Toast.makeText(
                            context,
                            localized(language, "已提交 ${outcome.itemKey}", "Submitted as ${outcome.itemKey}"),
                            Toast.LENGTH_LONG,
                        ).show()
                        FeedbackOutcome.Cancelled -> Unit
                        is FeedbackOutcome.Failed -> Toast.makeText(
                            context,
                            outcome.error.localizedMessage(language),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                Unit
            }
            Unit
        }
    }
}

private fun unavailableText(language: AppLanguage): String =
    localized(language, "这个版本没有开启反馈功能。", "Feedback is not enabled in this build.")
