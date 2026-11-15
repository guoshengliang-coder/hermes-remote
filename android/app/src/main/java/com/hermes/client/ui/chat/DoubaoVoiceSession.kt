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
import com.hermes.client.data.voice.VoiceRecording
import com.hermes.client.data.voice.VoiceRecordingStore
import kotlinx.coroutines.delay
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
    data object LimitReached : VoiceEvent
    data class Degraded(val reason: VoiceFailure) : VoiceEvent
    data class Progress(val sentMs: Long, val totalMs: Long) : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String, val limited: Boolean = false) : VoiceEvent
    data class Failed(val partialText: String, val reason: VoiceFailure) : VoiceEvent
}

internal enum class VoiceFailure { MICROPHONE, CONNECTION, DISCONNECTED, RECOGNITION, TIMEOUT, FINAL_TIMEOUT, STORAGE }

private class RecordingStorageFailure(cause: Throwable) : IllegalStateException("voice storage failed", cause)
private class RecognitionFailure : IllegalStateException("recognition failed")
private class MicrophoneFailure(cause: Throwable) : IllegalStateException("microphone unavailable", cause)

/**
 * Capture and the recognition attempt have independent lifetimes. Audio is journaled only on
 * this device for bounded retry; no audio is added to diagnostics;
 * neither is the transcript — the voice log lines below carry only event names, durations and
 * character counts, so a hung recognition is diagnosable without recording what was said.
 */
internal class DoubaoVoiceSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val endpoint: suspend () -> GatewayWebSocketEndpoint,
    private val endpointTimeoutMs: Long = ENDPOINT_TIMEOUT_MS,
    private val recordingLimitMs: Long = VOICE_RECORDING_LIMIT_MS,
    private val recordingScope: String = "local-test",
    private val replay: VoiceRecording? = null,
    private val store: VoiceRecordingStore = VoiceRecordingStore(context),
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
    // At most 16 seconds of startup/transport backlog, matching the browser's 512KB bound.
    private val chunks = Channel<ByteArray>(capacity = 80)
    private val opened = CompletableDeferred<WebSocket>()
    private val initialized = CompletableDeferred<Unit>()
    private val finalText = CompletableDeferred<String>()
    private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var latestText = ""
    private var job: Job? = null
    private val limited = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val degraded = AtomicBoolean(false)
    private val invalidRecognition = AtomicBoolean(false)
    @Volatile private var finalSent = false
    @Volatile private var captureFailure: VoiceFailure? = null
    @Volatile var savedRecording: VoiceRecording? = replay
        private set
    val waitingBudgetMs: Long get() = replay?.let { it.bytes / 32 + 45_000 } ?: VOICE_WAITING_BACKSTOP_MS
    private fun notifyDegraded(reason: VoiceFailure = if (ready.get()) VoiceFailure.DISCONNECTED else VoiceFailure.CONNECTION) {
        if (degraded.compareAndSet(false, true)) scope.launch(Dispatchers.Main) {
            if (!cancelled.get() && !stopped.get()) onEvent(VoiceEvent.Degraded(reason))
        }
    }
    private fun sealRecording(complete: Boolean) {
        savedRecording?.let { saved ->
            // If the metadata write fails, the UI may only advertise the actual saved prefix.
            savedRecording = saved.copy(bytes = store.audio(saved.id).length(), complete = false, limited = limited.get())
            try { savedRecording = store.seal(saved.id, complete, limited.get()) }
            catch (error: Exception) { throw RecordingStorageFailure(error) }
        }
    }
    private val recordingDeadline = VoiceRecordingDeadline(scope, recordingLimitMs) {
        if (stopped.compareAndSet(false, true) && !cancelled.get()) {
            limited.set(true)
            scope.launch(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.LimitReached) }
            stopRecorder()
        }
    }

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            DebugLog.log("voice") { "start" }
            if (replay != null) { stopped.set(true); limited.set(replay.limited) }
            val capture = if (replay == null) launch { captureAudio() } else null
            try {
                if (replay != null) {
                    try { savedRecording = store.load(replay.id) }
                    catch (error: Exception) { throw RecordingStorageFailure(error) }
                }
                val target = withTimeout(endpointTimeoutMs) { endpoint() }
                DebugLog.log("voice") { "endpoint ready (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                val request = Request.Builder().url(target.url).apply {
                    target.bearerToken?.let { header("Authorization", "Bearer $it") }
                }.build()
                socket = client.newWebSocket(request, listener)
                val ws = withTimeout(10_000) { opened.await() }
                check(ws.send(DoubaoSpeechProtocol.initialRequest()))
                withTimeout(10_000) { initialized.await() }
                DebugLog.log("voice") { "asr initialized (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                var sequence = DoubaoSpeechProtocol.FIRST_AUDIO_SEQUENCE
                var held: ByteArray? = null
                if (replay != null) {
                    // A new ASR stream reads the saved file, never opens a second microphone.
                    withTimeout(replay.bytes / 32 + 30_000) {
                        store.audio(replay.id).inputStream().use { input ->
                            var sent = 0L
                            while (true) {
                                val buffer = ByteArray(CHUNK_BYTES)
                                val count = input.read(buffer)
                                if (count < 0) break
                                held?.let {
                                    check(ws.send(DoubaoSpeechProtocol.audio(sequence++, it, last = false)))
                                    sent += it.size
                                    withContext(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.Progress(sent / 32, replay.bytes / 32)) }
                                    delay(200)
                                }
                                held = buffer.copyOf(count)
                                check(!degraded.get()) { "voice disconnected" }
                            }
                        }
                    }
                } else {
                    for (chunk in chunks) {
                        held?.let { check(ws.send(DoubaoSpeechProtocol.audio(sequence++, it, last = false))) }
                        held = chunk
                    }
                    capture?.join()
                    sealRecording(true)
                }
                if (!cancelled.get()) {
                    // Last audio packet has a negative sequence. Keep one packet back so its data
                    // carries the end marker rather than sending an empty trailing packet.
                    finalSent = true
                    check(ws.send(DoubaoSpeechProtocol.audio(sequence, held ?: byteArrayOf(), last = true)))
                    DebugLog.log("voice") { "audio flushed, awaiting final (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                    val final = try { withTimeout(8_000) { finalText.await() } }
                    catch (_: TimeoutCancellationException) {
                        DebugLog.log("voice") { "failed reason=FINAL_TIMEOUT partialChars=${latestText.length} (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                        withContext(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.Failed(latestText, VoiceFailure.FINAL_TIMEOUT)) }
                        return@launch
                    }
                    DebugLog.log("voice") { "final ${final.length} chars (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)" }
                    withContext(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.Final(final, limited.get())) }
                }
            } catch (error: Exception) {
                if (!cancelled.get()) {
                    val reason = when (error) {
                        is RecordingStorageFailure -> VoiceFailure.STORAGE
                        is SecurityException, is IllegalArgumentException, is MicrophoneFailure -> VoiceFailure.MICROPHONE
                        is RecognitionFailure -> VoiceFailure.RECOGNITION
                        is TimeoutCancellationException -> VoiceFailure.TIMEOUT
                        else -> if (invalidRecognition.get()) VoiceFailure.RECOGNITION else if (ready.get()) VoiceFailure.DISCONNECTED else VoiceFailure.CONNECTION
                    }
                    if (replay == null && !stopped.get() && reason != VoiceFailure.MICROPHONE && reason != VoiceFailure.STORAGE) {
                        notifyDegraded(reason)
                        socket?.cancel()
                        // The network attempt has ended. Continue writing PCM until release/limit.
                        capture?.join()
                    }
                    val settledReason = captureFailure ?: reason
                    val sealed = runCatching { if (replay == null) sealRecording(settledReason != VoiceFailure.MICROPHONE && settledReason != VoiceFailure.STORAGE) }
                    val reported = if (sealed.exceptionOrNull() is RecordingStorageFailure) VoiceFailure.STORAGE else settledReason
                    DebugLog.log("voice") {
                        "failed reason=$reported partialChars=${latestText.length} (${android.os.SystemClock.elapsedRealtime() - startedAt}ms)"
                    }
                    withContext(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.Failed(latestText, reported)) }
                }
            } finally {
                recordingDeadline.stop()
                stopped.set(true)
                stopRecorder()
                capture?.cancel()
                socket?.close(1000, "done")
            }
        }
    }

    fun finish() {
        recordingDeadline.stop()
        DebugLog.log("voice") { "finish" }
        stopped.set(true)
        stopRecorder()
    }

    fun cancel(keepRecording: Boolean = false) {
        recordingDeadline.stop()
        DebugLog.log("voice") { "cancelled" }
        cancelled.set(true)
        stopped.set(true)
        stopRecorder()
        chunks.close()
        socket?.cancel()
        job?.invokeOnCompletion {
            savedRecording?.let { saved ->
                runCatching {
                    if (!keepRecording || (store.audio(saved.id).exists() && store.audio(saved.id).length() == 0L)) store.remove(saved.id)
                }
            }
        }
        job?.cancel()
    }

    suspend fun retainedRecording(): VoiceRecording? {
        job?.join()
        return savedRecording
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
            savedRecording = try { store.begin(recordingScope) } catch (error: Exception) { throw RecordingStorageFailure(error) }
            var announced = false
            while (!stopped.get() && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val buffer = ByteArray(CHUNK_BYTES)
                val n = audio.read(buffer, 0, buffer.size)
                if (n < 0) {
                    check(stopped.get()) { "microphone unavailable" }
                    break
                }
                if (n > 0) {
                    recordingDeadline.audioReceived()
                    if (!announced) {
                        announced = true
                        withContext(Dispatchers.Main) {
                            if (!cancelled.get() && !stopped.get()) onEvent(VoiceEvent.Started)
                        }
                    }
                    val saved = checkNotNull(savedRecording)
                    val remaining = VoiceRecordingStore.MAX_BYTES - store.audio(saved.id).length()
                    val pcm = buffer.copyOf(minOf(n.toLong(), remaining).toInt())
                    try { store.append(saved.id, pcm) } catch (error: Exception) { throw RecordingStorageFailure(error) }
                    if (!degraded.get() && chunks.trySend(pcm).isFailure) {
                        notifyDegraded()
                        val failure = IllegalStateException("voice transport backlog")
                        initialized.completeExceptionally(failure)
                        chunks.close(failure)
                        socket?.cancel()
                    }
                    if (remaining <= n) {
                        limited.set(true)
                        stopped.set(true)
                        withContext(Dispatchers.Main) { if (!cancelled.get()) onEvent(VoiceEvent.LimitReached) }
                    }
                }
            }
        } catch (error: Exception) {
            if (!cancelled.get() && (!stopped.get() || error is RecordingStorageFailure)) {
                captureFailure = if (error is RecordingStorageFailure) VoiceFailure.STORAGE else VoiceFailure.MICROPHONE
                val failure = if (error is RecordingStorageFailure) error else MicrophoneFailure(error)
                opened.completeExceptionally(failure)
                initialized.completeExceptionally(failure)
                chunks.close(failure)
            }
        } finally {
            stopRecorder()
            runCatching { sealRecording(stopped.get() && !cancelled.get() && captureFailure == null) }
            chunks.close()
        }
    }

    @Synchronized private fun stopRecorder() {
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
            if (cancelled.get() || degraded.get()) return
            val frame = runCatching { DoubaoSpeechProtocol.parse(bytes.toByteArray()) }
                .getOrElse {
                    recognitionFailed()
                    return
                }
            if (frame.error) {
                recognitionFailed()
                return
            }
            frame.text?.let { text ->
                latestText = text
                scope.launch(Dispatchers.Main) {
                    if (!cancelled.get()) onEvent(VoiceEvent.Partial(text))
                }
            }
            if (frame.finished && !finalSent) {
                recognitionFailed()
                return
            }
            initialized.complete(Unit)
            ready.set(true)
            if (frame.finished) {
                finalText.complete(latestText)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            notifyDegraded()
            val failure = IllegalStateException("voice connection failed")
            opened.completeExceptionally(failure)
            initialized.completeExceptionally(failure)
            finalText.completeExceptionally(failure)
            chunks.close(failure)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connectionClosed()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            connectionClosed()
            webSocket.close(code, reason)
        }

        private fun connectionClosed() {
            if (!finalText.isCompleted && !cancelled.get()) {
                notifyDegraded()
                val failure = IllegalStateException("voice closed")
                opened.completeExceptionally(failure)
                initialized.completeExceptionally(failure)
                finalText.completeExceptionally(failure)
                chunks.close(failure)
            }
        }
    }

    private fun recognitionFailed() {
        invalidRecognition.set(true)
        notifyDegraded(VoiceFailure.RECOGNITION)
        val failure = RecognitionFailure()
        initialized.completeExceptionally(failure)
        finalText.completeExceptionally(failure)
        chunks.close(failure)
    }
}
