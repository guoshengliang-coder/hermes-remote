package com.hermes.client.ui.models

import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText

/**
 * Upstream hermes-agent reasoning-effort levels, ascending. The wire values are fixed by
 * `VALID_REASONING_EFFORTS` upstream; [REASONING_OFF] ("none") is thinking disabled and is
 * modelled as the 思考 toggle in the UI rather than a point on the scale.
 */
val REASONING_LEVELS = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
const val REASONING_OFF = "none"

/** Fallback level when the 思考 toggle turns thinking back on with no better signal. */
const val REASONING_DEFAULT = "medium"

/**
 * Display label for a wire value; null for blank/unknown values (provider default — show no
 * label rather than a wrong one). Chinese labels match the Hermes desktop client.
 */
fun reasoningLabel(value: String?): LocalizedText? = when (value) {
    "minimal" -> localizedText("最小", "Min")
    "low" -> localizedText("低", "Low")
    "medium" -> localizedText("中", "Med")
    "high" -> localizedText("高", "High")
    "xhigh" -> localizedText("极高", "XHigh")
    "max" -> localizedText("最高", "Max")
    "ultra" -> localizedText("超高", "Ultra")
    REASONING_OFF -> localizedText("关", "Off")
    else -> null
}
