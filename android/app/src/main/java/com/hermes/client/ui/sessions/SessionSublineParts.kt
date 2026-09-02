package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session

/** What the shared session subline leads with, before the model name. */
enum class SublineLead {
    /** Project label (basename of repo root / cwd); nothing for the default project. */
    PROJECT,
    /** Git branch — used inside a project scope, where the project is already the page header. */
    BRANCH,
}

/** Pure inputs of the shared subline so the rule is unit-testable without Compose. */
data class SessionSublineParts(val lead: String?, val model: String?) {
    val isEmpty: Boolean get() = lead == null && model == null
}

/**
 * One rule for every list surface (sessions, archived, search, project scope): `<lead> · <model>`,
 * where either side is dropped when absent. A blank model name is treated as absent.
 */
fun sessionSublineParts(
    session: Session,
    lead: SublineLead = SublineLead.PROJECT,
    defaultProjectPath: String? = null,
): SessionSublineParts {
    val leadText = when (lead) {
        SublineLead.PROJECT -> projectLabelOf(session, defaultProjectPath)
        SublineLead.BRANCH -> session.gitBranch?.ifBlank { null }
    }
    return SessionSublineParts(lead = leadText, model = session.model?.ifBlank { null })
}
