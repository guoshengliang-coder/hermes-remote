package com.hermes.client.ui.sessions

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.domain.Session
import com.hermes.client.ui.chat.ChatLaunch
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Creating and opening a chat is identical on the Chats list, the Projects page and the Archive
// page — same in-flight guard, same fallback toast, same profile-switch-before-open. It lived
// inside SessionsScreen while those were segments of one screen; now that they are three screens
// it lives here, so the three cannot drift apart.

/** The new-chat action plus its in-flight flag, shared by every screen that shows the FAB. */
@Stable
class SessionCreator internal constructor(
    val creating: Boolean,
    val create: (cwd: String?) -> Unit,
)

/**
 * The FAB's behaviour: create a chat in [cwd] (null = the gateway's launch directory, i.e. the
 * default project), then open it. Re-entrant taps are dropped while one is in flight.
 */
@Composable
internal fun rememberSessionCreator(
    vm: SessionsViewModel,
    activeProfile: String?,
    onOpen: (ChatLaunch) -> Unit,
): SessionCreator {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var creating by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<String?>(null) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    if (choosing) com.hermes.client.ui.chat.NewProfileChat(profiles, connection, creating, { choosing = false }) { owner, turn ->
        val cwd = folder.takeIf { owner == activeProfile }
        if (!creating) {
            creating = true
            scope.launch {
                try {
                    val created = vm.createSession(cwd, owner, turn)
                    if (created.fellBackToDefault) {
                        // The folder is gone on the Mac and the gateway silently used its own
                        // launch dir instead — say so rather than leave the chat in a surprise
                        // workspace (HR-SESS-006).
                        val error = AppError(
                            AppErrorCode.PROJECT_FELL_BACK_TO_DEFAULT,
                            retryable = false,
                            stage = "session_create",
                        )
                        Toast.makeText(context, error.localizedMessage(language), Toast.LENGTH_LONG).show()
                    }
                    choosing = false
                    onOpen(ChatLaunch.new(created.id, owner, created.deviceId))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val error = com.hermes.client.data.error.newChatFailure(
                        e, vm.consecutiveDroppedConnections,
                    )
                    Toast.makeText(context, error.localizedMessage(language), Toast.LENGTH_LONG).show()
                } finally {
                    creating = false
                }
            }
        }
    }
    return SessionCreator(creating) { cwd -> if (!creating) { folder = cwd; choosing = true } }
}

/** The new-chat FAB. One definition so the Chats list and the Projects page cannot diverge. */
@Composable
internal fun NewSessionFab(creator: SessionCreator, cwd: String?) {
    val language = LocalAppLanguage.current
    FloatingActionButton(
        onClick = { creator.create(cwd) },
        // Dark carries a hairline because the near-black fill alone is 1.19:1 on the obsidian
        // ground; light passes Color.Transparent and draws none.
        modifier = Modifier.border(
            1.dp,
            com.hermes.client.ui.theme.fabOutlineColor(),
            androidx.compose.material3.FloatingActionButtonDefaults.shape,
        ),
        // Neutral near-black, not the brand blue — DESIGN.md §2.7 item 1.
        containerColor = com.hermes.client.ui.theme.fabContainerColor(),
        contentColor = androidx.compose.ui.graphics.Color.White,
    ) {
        if (creator.creating) {
            // Button-internal wait keeps the M3 spinner: the brand mark is drawn in one
            // colour and reads poorly on onPrimary (docs/DESIGN.md §5.6).
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = androidx.compose.ui.graphics.Color.White,
            )
        } else {
            Icon(
                Icons.Rounded.Add,
                contentDescription = localized(language, "新建会话", "New session"),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
