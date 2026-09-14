package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session

/** What the shared session subline leads with, before the model name. */
enum class SublineLead {
    /** Project label (basename of repo root / cwd); nothing for the default project. */
    PROJECT,
    /** Git branch — used inside a project scope, where the project is already the page header. */
    BRANCH,
}

/**
 * Pure inputs of the shared subline so the rule is unit-testable without Compose.
 *
 * [modelUnknown] is the bot list's third state and is never set for an ordinary session. A blank
 * model normally means "no override, the profile default applies", which is true and useful; on a
 * conversation that happened in another app it means we do not know what answered there. The
 * wording lives in the Compose layer — this file stays free of i18n — but the DISTINCTION is made
 * here so a test can hold it (docs/DESIGN.md §5.16, and `ui/chat/ModelChipLabel.kt` for the same
 * rule on the composer chip).
 */
data class SessionSublineParts(
    val lead: String?,
    val model: String?,
    val modelUnknown: Boolean = false,
) {
    val isEmpty: Boolean get() = lead == null && model == null && !modelUnknown
}

/**
 * One rule for every list surface (sessions, archived, search, project scope): `<lead> · <model>`,
 * where either side is dropped when absent. A blank model name is treated as absent.
 *
 * [isBot] switches to the Bots list's reading of the same session: no lead segment (a bot
 * conversation's cwd belongs to Hermes, not to anything the reader picked), and a missing model
 * becomes [SessionSublineParts.modelUnknown] rather than silently vanishing.
 */
fun sessionSublineParts(
    session: Session,
    lead: SublineLead = SublineLead.PROJECT,
    defaultProjectPath: String? = null,
    /** The project's own name, when something upstream of here knows it. */
    projectName: String? = null,
    isBot: Boolean = false,
): SessionSublineParts {
    val model = session.model?.ifBlank { null }
    if (isBot) return SessionSublineParts(lead = null, model = model, modelUnknown = model == null)
    val leadText = when (lead) {
        SublineLead.PROJECT -> projectName?.ifBlank { null } ?: projectLabelOf(session, defaultProjectPath)
        SublineLead.BRANCH -> session.gitBranch?.ifBlank { null }
    }
    return SessionSublineParts(lead = leadText, model = model)
}
