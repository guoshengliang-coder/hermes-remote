package com.hermes.client.ui.sessions

import android.widget.Toast
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
    return SessionCreator(creating) { cwd ->
        if (!creating) {
            creating = true
            scope.launch {
                try {
                    vm.createSession(cwd)?.let { created ->
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
                        onOpen(ChatLaunch.new(created.id, activeProfile, created.deviceId))
                    }
                } finally {
                    creating = false
                }
            }
        }
    }
}

/**
 * Opens an existing session. Sessions span profiles, so the active profile is switched (and
 * awaited) first — otherwise the chat resumes against the wrong state.db. Latest tap wins: a
 * second tap cancels the first, and a stale result never navigates.
 */
@Composable
internal fun rememberSessionOpener(
    vm: SessionsViewModel,
    onOpen: (ChatLaunch) -> Unit,
): (Session) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var job by remember { mutableStateOf<Job?>(null) }
    var serial by remember { mutableLongStateOf(0L) }
    return { session ->
        val request = ++serial
        job?.cancel()
        job = scope.launch {
            if (vm.prepareOpen(session) && request == serial) {
                onOpen(ChatLaunch.existing(session))
            } else if (request == serial) {
                Toast.makeText(
                    context,
                    localized(
                        language,
                        "无法切换到该会话所属身份，请稍后重试",
                        "Could not switch to this session's profile. Try again.",
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

/** The new-chat FAB. One definition so the Chats list and the Projects page cannot diverge. */
@Composable
internal fun NewSessionFab(creator: SessionCreator, cwd: String?) {
    val language = LocalAppLanguage.current
    FloatingActionButton(
        onClick = { creator.create(cwd) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        if (creator.creating) {
            // Button-internal wait keeps the M3 spinner: the brand mark is drawn in one
            // colour and reads poorly on onPrimary (docs/DESIGN.md §5.6).
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.onPrimary,
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
