package com.hermes.client.data.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** The platform boundary is replaceable in tests; an accepted request is not motor evidence. */
interface OutputHapticDevice {
    val sdk: Int
    val hasVibrator: Boolean
    val amplitudeControl: Boolean
    val touchEnabled: Boolean
    val foreground: Boolean
    fun systemFeedback(effect: Int): Boolean
    fun pulse(durationMs: Int, amplitude: Int)
    fun cancel()
}

enum class HapticRequestResult { REQUESTED, SUPPRESSED, UNSUPPORTED, REJECTED, FAILED }

fun systemHapticEffect(type: OutputHapticType, sdk: Int): Int = when (type) {
    OutputHapticType.SYSTEM_SOFT -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    OutputHapticType.KEYBOARD -> HapticFeedbackConstants.KEYBOARD_TAP
    else -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CONTEXT_CLICK
}

class OutputHapticPlayer(
    val device: OutputHapticDevice,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    private var ownedPulseUntil = 0L
    var failureCause: String? = null
        private set
    fun supports(type: OutputHapticType): Boolean = type != OutputHapticType.CUSTOM_PULSE ||
        (device.hasVibrator && device.amplitudeControl)

    fun request(parameters: OutputHapticConfig): HapticRequestResult {
        failureCause = null
        val config = parameters.normalized()
        return try {
            if (!device.foreground || !device.touchEnabled) {
                cancel()
                HapticRequestResult.SUPPRESSED
            } else if (!supports(config.type)) {
                HapticRequestResult.UNSUPPORTED
            } else if (config.type == OutputHapticType.CUSTOM_PULSE) {
                device.pulse(config.durationMs, config.amplitude)
                ownedPulseUntil = now() + config.durationMs
                HapticRequestResult.REQUESTED
            } else if (device.systemFeedback(systemHapticEffect(config.type, device.sdk))) {
                HapticRequestResult.REQUESTED
            } else HapticRequestResult.REJECTED
        } catch (cause: Exception) {
            // Only exposed via AppError.sanitizedDiagnostic behind the details action.
            failureCause = "${cause.javaClass.simpleName}: ${cause.message}"
            HapticRequestResult.FAILED
        }
    }

    fun cancel() {
        if (now() < ownedPulseUntil) runCatching { device.cancel() }
        ownedPulseUntil = 0L
    }
}

class AndroidOutputHapticDevice(private val view: View) : OutputHapticDevice {
    private val context: Context = view.context
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else context.getSystemService(Vibrator::class.java)
    override val sdk: Int get() = Build.VERSION.SDK_INT
    override val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true
    override val amplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true
    override val foreground: Boolean get() = view.isShown && view.hasWindowFocus()
    override val touchEnabled: Boolean get() = view.isHapticFeedbackEnabled && runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(false)
    override fun systemFeedback(effect: Int) = view.performHapticFeedback(effect)
    override fun pulse(durationMs: Int, amplitude: Int) {
        val effect = VibrationEffect.createOneShot(durationMs.toLong(), amplitude)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator?.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
        } else {
            vibrator?.vibrate(effect, AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
    }
    override fun cancel() { vibrator?.cancel() }
}
