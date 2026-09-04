package com.hermes.client.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.core.net.toUri
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localizedMessage

/**
 * Schemes an answer is allowed to hand to the system. Answers are model output, so the target is
 * not trusted: `intent:` can address an arbitrary component, and `file:` can point at local
 * storage. Everything outside this list is refused before it reaches `startActivity`.
 */
private val OPENABLE_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

private val URI_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

/**
 * The address to hand to the system for [raw], or null when the link must not be opened.
 *
 * A GFM autolink written as `www.example.com` reaches us with no scheme at all; the system cannot
 * open that, so it gets the https prefix every other client applies. Anything else without a
 * scheme is relative or anchor-only and has no meaning outside a document.
 */
internal fun openableChatLink(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val scheme = URI_SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
    return when {
        scheme != null -> trimmed.takeIf { scheme in OPENABLE_LINK_SCHEMES }
        trimmed.startsWith("www.", ignoreCase = true) -> "https://$trimmed"
        else -> null
    }
}

/**
 * A [UriHandler] for links inside message content.
 *
 * Compose's default Android handler rethrows `ActivityNotFoundException` as
 * `IllegalArgumentException`, i.e. a device with no browser crashes the app on a tap. It also
 * opens whatever scheme it is given. This one refuses non-web targets (`HR-LINK-002`) and turns a
 * failed launch into a message plus the link on the clipboard (`HR-LINK-001`).
 */
@Composable
internal fun rememberChatUriHandler(): UriHandler {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val language = LocalAppLanguage.current
    return remember(context, clipboard, language) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val target = openableChatLink(uri)
                val failure = when {
                    target == null -> AppError(AppErrorCode.LINK_NOT_OPENABLE, retryable = false, technicalCause = uri)
                    else -> runCatching {
                        // No NEW_TASK flag: matches the platform handler, so a link still opens
                        // in this task and Back returns to the conversation.
                        context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
                    }.fold(
                        onSuccess = { null },
                        onFailure = { cause ->
                            // Recovery for "nothing can open this": the address itself, ready to paste.
                            clipboard.setText(AnnotatedString(target))
                            AppError(AppErrorCode.LINK_NO_HANDLER, retryable = false, technicalCause = cause.toString())
                        },
                    )
                }
                failure?.let {
                    android.widget.Toast.makeText(context, it.localizedMessage(language), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
