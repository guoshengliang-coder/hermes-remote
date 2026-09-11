package com.hermes.client.ui.chat

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/**
 * What the composer's model chip says.
 *
 * Three states, not two. A blank model on an ordinary conversation means "no override, the
 * profile's default applies" — which is true and useful. On a bot conversation it means something
 * else entirely: the row came from another app and we simply do not know what model answered
 * there. Saying "默认模型" in that case is a claim about someone else's turn that we cannot make,
 * and the app used to go further still — it wrote the profile default into the session's own
 * model state, so the chip named a model that had never touched that conversation.
 */
fun modelChipLabel(
    model: String?,
    effortSuffix: String,
    isBot: Boolean,
    language: AppLanguage,
): String = when {
    !model.isNullOrBlank() -> compactModelLabel(model) + effortSuffix
    isBot -> localized(language, "模型未知", "Model unknown")
    else -> localized(language, "默认模型", "Default model")
}
