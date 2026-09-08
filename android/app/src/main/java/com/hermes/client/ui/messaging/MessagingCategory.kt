package com.hermes.client.ui.messaging

import androidx.compose.ui.graphics.vector.ImageVector
import com.hermes.client.ui.components.ApiChannelIcon
import com.hermes.client.ui.components.ChatChannelIcon
import com.hermes.client.ui.components.MailChannelIcon
import com.hermes.client.ui.components.PushChannelIcon
import com.hermes.client.ui.components.SmsChannelIcon

/** What kind of channel a platform is — the row's leading glyph, not its brand. */
enum class MessagingCategory { CHAT, MAIL, SMS, PUSH, API }

private val MAIL_IDS = setOf("email", "msgraph_webhook")
private val SMS_IDS = setOf("sms")
private val PUSH_IDS = setOf("ntfy", "homeassistant")
private val API_IDS = setOf("api_server", "webhook", "a2a", "relay", "raft", "buzz")

/**
 * Hermes knows 33 platforms and their brand marks are filled, multi-colour and trademarked, so the
 * list identifies a channel by its NAME and uses the glyph to say what KIND it is. Anything the
 * app has not classified is chat: that is what the overwhelming majority of them are, and a
 * conversation glyph on a webhook is a smaller lie than a webhook glyph on a chat.
 */
fun messagingCategory(platformId: String): MessagingCategory = when (platformId.trim().lowercase()) {
    in MAIL_IDS -> MessagingCategory.MAIL
    in SMS_IDS -> MessagingCategory.SMS
    in PUSH_IDS -> MessagingCategory.PUSH
    in API_IDS -> MessagingCategory.API
    else -> MessagingCategory.CHAT
}

fun messagingCategoryIcon(category: MessagingCategory): ImageVector = when (category) {
    MessagingCategory.CHAT -> ChatChannelIcon
    MessagingCategory.MAIL -> MailChannelIcon
    MessagingCategory.SMS -> SmsChannelIcon
    MessagingCategory.PUSH -> PushChannelIcon
    MessagingCategory.API -> ApiChannelIcon
}
