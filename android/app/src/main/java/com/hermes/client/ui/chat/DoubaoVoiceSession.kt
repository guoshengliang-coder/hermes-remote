package com.hermes.client.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.network.GatewayWebSocketEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface VoiceEvent {
    data object Started : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Failed(val partialText: String, val reason: VoiceFailure) : VoiceEvent
}

internal enum class VoiceFailure { MICROPHONE, CONNECTION, RECOGNITION, TIMEOUT }

private class RecognitionFailure : IllegalStateException("recognition failed")
private class MicrophoneFailure(cause: Throwable) : IllegalStateException("microphone unavailable", cause)

/**
 * One press, one microphone and one WebSocket. No audio is persisted or added to diagnostics;
 * neither is the transcript — the voice log lines below carry only event names, durations and
 * character counts, so a hung recognition is diagnosable without recording what was said.
 */
internal class DoubaoVoiceSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val endpoint: suspend () -> GatewayWebSocketEndpoint,
    private val endpointTimeoutMs: Long = ENDPOINT_TIMEOUT_MS,
    private val onEvent: (VoiceEvent) -> Unit,
) {
    private companion object {
        val client = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 6_400 // 200 ms, mono PCM16

        /**
         * Endpoint resolution runs before every `withTimeout` below and is the one unbounded
         * segment of the session: in account mode it can wait on the token-refresh mutex and a
         * network refresh round trip, which on a VPN-flapping network (HG-144: 195 self-heals
         * in one afternoon) can hang far past any user patience — and with it the
         * "Finishing recognition…" banner, whose only exits are the Final/Failed callbacks this
         * hang never reaches. Bounding it turns the hang into Failed(TIMEOUT).
         */
        const val ENDPOINT_TIMEOUT_MS = 10_000L
    }

    private val stopped = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val chunks = Channel<ByteArray>(capacity = 16)
    private val opened = CompletableDeferred<WebSocket>()
    private val initialized = CompletableDeferred<Unit>()
    private val finalText = CompletableDeferred<String>()
    private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var latestText = ""
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            DebugLog.log("voice") { "start" }
            val capture = launch { captureAudio() }
            try {
                val target = withTimeout(endpointTimeoutMs) { endpoint() }
                DebugLog.log("voice") { "endpoint ready (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                val request = Request.Builder().url(target.url).apply {
                    target.bearerToken?.let { header("Authorization", "Bearer $it") }
                    target.sessionToken?.let { header("X-Hermes-Session-Token", it) }
                }.build()
                socket = client.newWebSocket(request, listener)
                val ws = withTimeout(10_000) { opened.await() }
                check(ws.send(DoubaoSpeechProtocol.initialRequest()))
                withTimeout(10_000) { initialized.await() }
                DebugLog.log("voice") { "asr initialized (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                var sequence = DoubaoSpeechProtocol.FIRST_AUDIO_SEQUENCE
                var held: ByteArray? = null
                for (chunk in chunks) {
                    held?.let { check(ws.send(DoubaoSpeechProtocol.audio(sequence++, it, last = false))) }
                    held = chunk
                }
                if (!cancelled.get()) {
                    // Last audio packet has a negative sequence. Keep one packet back so its data
                    // carries the end marker rather than sending an empty trailing packet.
                    check(ws.send(DoubaoSpeechProtocol.audio(sequence, held ?: byteArrayOf(), last = true)))
                    DebugLog.log("voice") { "audio flushed, awaiting final (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                    val final = withTimeout(8_000) { finalText.await() }
                    DebugLog.log("voice") { "final ${final.length} chars (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                    withContext(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.Final(final)) }
                }
            } catch (error: Exception) {
                if (!cancelled.get()) {
                    val reason = when (error) {
                        is SecurityException, is IllegalArgumentException, is MicrophoneFailure -> VoiceFailure.MICROPHONE
                        is RecognitionFailure -> VoiceFailure.RECOGNITION
                        is TimeoutCancellationException -> VoiceFailure.TIMEOUT
                        else -> VoiceFailure.CONNECTION
                    }
                    DebugLog.log("voice") {
                        "failed reason=$reason partialChars=${latestText.length} (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)"
                    }
                    withContext(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.Failed(latestText, reason)) }
                }
            } finally {
                stopped.set(true)
                stopRecorder()
                capture.cancel()
                socket?.close(1000, "done")
            }
        }
    }

    fun finish() {
        DebugLog.log("voice") { "finish" }
        stopped.set(true)
        stopRecorder()
    }

    fun cancel() {
        DebugLog.log("voice") { "cancelled" }
        cancelled.set(true)
        stopped.set(true)
        stopRecorder()
        chunks.close()
        socket?.cancel()
        job?.cancel()
    }

    private suspend fun captureAudio() {
        try {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) { "microphone permission revoked" }
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBytes > 0) { "microphone unavailable" }
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(CHUNK_BYTES * 2, minBytes),
            )
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone unavailable" }
            var announced = false
            while (!stopped.get() && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val buffer = ByteArray(CHUNK_BYTES)
                val n = audio.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (n > 0) {
                    if (!announced) {
                        announced = true
                        withContext(Dispatchers.Main) {
                            if (!cancelled.get() && !stopped.get()) onEvent(VoiceEvent.Started)
                        }
                    }
                    chunks.send(buffer.copyOf(n))
                }
            }
        } catch (error: Exception) {
            if (!cancelled.get() && !stopped.get()) {
                val failure = MicrophoneFailure(error)
                opened.completeExceptionally(failure)
                initialized.completeExceptionally(failure)
                chunks.close(failure)
            }
        } finally {
            stopRecorder()
            chunks.close()
        }
    }

    private fun stopRecorder() {
        val audio = recorder ?: return
        recorder = null
        runCatching { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }
        audio.release()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = runCatching { DoubaoSpeechProtocol.parse(bytes.toByteArray()) }
                .getOrElse {
                    finalText.completeExceptionally(it)
                    return
                }
            if (frame.error) {
                val failure = RecognitionFailure()
                initialized.completeExceptionally(failure)
                finalText.completeExceptionally(failure)
                return
            }
            initialized.complete(Unit)
            frame.text?.let { text ->
                latestText = text
                scope.launch(Dispatchers.Main) {
                    if (!cancelled.get()) onEvent(VoiceEvent.Partial(text))
                }
            }
            if (frame.finished) finalText.complete(latestText)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val failure = IllegalStateException("voice connection failed")
            opened.completeExceptionally(failure)
            initialized.completeExceptionally(failure)
            finalText.completeExceptionally(failure)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!finalText.isCompleted && !cancelled.get()) finalText.completeExceptionally(IllegalStateException("voice closed"))
        }
    }
}
