package com.hermes.client.ui.chat

import com.hermes.client.data.error.AppError

/** Local action feedback, separate from the authoritative/unknown delivery state. */
data class MessageDeliveryRecovery(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val removing: Boolean = false,
    val error: AppError? = null,
)
