package com.hermes.client.ui.sessions

import androidx.compose.runtime.staticCompositionLocalOf
import com.hermes.client.domain.Session

/**
 * How a row names the project a chat belongs to.
 *
 * A CompositionLocal rather than a parameter because the answer comes from ONE cache
 * ([com.hermes.client.data.repository.ProjectCatalog]) and is needed by four unrelated surfaces —
 * the session list, the archive, search results and the chat's workspace subtitle. Passing it down
 * meant every one of those screens' ViewModels holding a copy of the project list, which is how
 * they drifted apart in the first place (0.1.112 shipped a Projects page showing real project
 * names beside pickers and rows still showing folder basenames).
 *
 * The default resolves nothing, so a preview or a test renders the folder basename exactly as
 * before; [com.hermes.client.ui.nav.HermesNav] provides the real one.
 */
val LocalProjectNames = staticCompositionLocalOf<(Session) -> String?> { { null } }
