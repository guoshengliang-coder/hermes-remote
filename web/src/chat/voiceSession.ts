import { appError, type AppError } from "../errors";
import { captureVoice, type VoiceCapture } from "./voiceCapture";
import { parseVoiceFrame, voiceAudioFrame, voiceInitialFrame } from "./voiceProtocol";

import { VOICE_MAX_BYTES } from "./voiceRecordings";
import { VOICE_RECORDING_LIMIT_MS } from "./voicePolicy";
export type VoiceEvent = { kind: "recording" } | { kind: "waiting"; limited?: boolean } | { kind: "partial"; text: string } | { kind: "final"; text: string; limited?: boolean }
  | { kind: "degraded"; error: AppError } | { kind: "progress"; sentMs: number; totalMs: number }
  | { kind: "failed"; text: string; error: AppError };
export interface VoiceSessionOptions {
  endpoint: () => Promise<string>;
  onEvent: (event: VoiceEvent) => void;
  capture?: typeof captureVoice;
  onAudio?: (pcm: Uint8Array) => void;
  replay?: { chunks: Uint8Array[]; limited: boolean };
  socket?: (url: string) => WebSocket;
}

// HG-255: capture survives transport failure. A retry is a new attempt over the saved PCM.
export class BrowserVoiceSession {
  private readonly captureAbort = new AbortController();
  private socket: WebSocket | null = null;
  private capture: VoiceCapture | null = null;
  private ended = false;
  private finishing = false;
  private finalSent = false;
  private ready = false;
  private receivedPcm = false;
  private limited = false;
  private recordingTimer: ReturnType<typeof setTimeout> | null = null;
  private recordingNotified = false;
  private sequence = 2;
  private held: Uint8Array | null = null;
  private pending: Uint8Array[] = [];
  private pendingBytes = 0;
  private tail = new Uint8Array(0);
  private latestText = "";
  private audioBytes = 0;
  private transportFailure: AppError | null = null;
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private messages = Promise.resolve();
  constructor(private readonly options: VoiceSessionOptions) {}

  start(): void {
    this.deadline(10_000, () => this.fail("HR-VOICE-001"));
    if (this.options.replay) {
      this.finishing = true; this.limited = this.options.replay.limited;
      this.options.onEvent({ kind: "waiting", limited: this.limited });
    }
    // Start capture synchronously in the gesture so AudioContext.resume is allowed on WebKit.
    if (!this.options.replay) void (this.options.capture ?? captureVoice)((pcm) => this.onPcm(pcm), this.captureAbort.signal).then((capture) => {
      if (this.ended || this.finishing) capture.stop();
      else { this.capture = capture; this.notifyRecording(); }
    }, () => { if (!this.ended && !this.finishing) this.fail("HR-PERM-006"); });
    void this.options.endpoint().then((url) => {
      if (this.ended || this.transportFailure) return;
      const socket = (this.options.socket ?? ((target) => new WebSocket(target)))(url);
      this.socket = socket; socket.binaryType = "arraybuffer";
      socket.onopen = () => { if (!this.ended) socket.send(voiceInitialFrame()); };
      socket.onmessage = (event: MessageEvent) => {
        // Capture arrival order, not the later asynchronous parse/queue completion time.
        const finalSentAtArrival = this.finalSent;
        this.messages = this.messages.then(async () => {
          if (this.ended || this.transportFailure) return;
          if (!(event.data instanceof ArrayBuffer)) throw new Error("non-binary speech response");
          const result = await parseVoiceFrame(event.data);
          if (this.ended || this.transportFailure) return;
          if (result.error) { this.fail("HR-VOICE-002"); return; }
          if (result.finished && !finalSentAtArrival) {
            if (result.text !== null) { this.latestText = result.text; this.options.onEvent({ kind: "partial", text: result.text }); }
            this.fail("HR-VOICE-002"); return;
          }
          if (!this.ready) {
            this.ready = true;
            // Only the startup timer is cleared; the recording deadline remains.
            if (this.startupTimer) { clearTimeout(this.startupTimer); this.timers.delete(this.startupTimer); }
            for (const pcm of this.pending) this.sendChunk(pcm);
            this.pending = []; this.pendingBytes = 0;
            if (this.options.replay) this.replay();
            else if (this.finishing) this.flushFinal();
          }
          if (result.text !== null) {
            this.latestText = result.text;
            this.options.onEvent({ kind: "partial", text: result.text });
          }
          if (result.finished) {
            const text = this.latestText.trim();
            // A premature final must never send while the user still holds/cancels the gesture.
            if (!this.finishing) { this.fail("HR-VOICE-002"); return; }
            if (!text && !this.limited) { this.fail("HR-VOICE-005"); return; }
            this.cleanup(); this.options.onEvent({ kind: "final", text, ...(this.limited ? { limited: true } : {}) });
          }
        }).catch(() => this.fail("HR-VOICE-002"));
      };
      socket.onerror = () => this.fail(this.ready ? "HR-VOICE-003" : "HR-VOICE-001");
      socket.onclose = () => this.fail(this.ready ? "HR-VOICE-003" : "HR-VOICE-001");
    }).catch(() => this.fail("HR-VOICE-001"));
  }
  private startupTimer: ReturnType<typeof setTimeout> | null = null;
  private deadline(ms: number, callback: () => void): ReturnType<typeof setTimeout> {
    const timer = setTimeout(() => { this.timers.delete(timer); if (!this.ended) callback(); }, ms);
    this.timers.add(timer);
    if (ms === 10_000) this.startupTimer = timer;
    return timer;
  }
  private onPcm(pcm: Uint8Array): void {
    if (this.ended || this.finishing) return;
    pcm = pcm.slice(0, Math.max(0, VOICE_MAX_BYTES - this.audioBytes));
    this.audioBytes += pcm.length;
    try { if (pcm.length) this.options.onAudio?.(pcm); }
    catch { this.storageFailed(); return; }
    if (pcm.length) {
      if (!this.receivedPcm) this.recordingTimer = this.deadline(VOICE_RECORDING_LIMIT_MS, () => this.finish(true));
      this.receivedPcm = true; this.notifyRecording();
    }
    const bytes = new Uint8Array(this.tail.length + pcm.length);
    bytes.set(this.tail); bytes.set(pcm, this.tail.length);
    let offset = 0;
    while (offset + 6400 <= bytes.length) { this.enqueue(bytes.slice(offset, offset + 6400)); offset += 6400; }
    this.tail = bytes.slice(offset);
    if (this.audioBytes >= VOICE_MAX_BYTES) this.finish(true);
  }
  private notifyRecording(): void {
    if (this.ended || this.finishing || !this.capture || !this.receivedPcm || this.recordingNotified) return;
    this.recordingNotified = true;
    this.options.onEvent({ kind: "recording" });
  }
  private enqueue(pcm: Uint8Array): void {
    if (this.ended || this.transportFailure) return;
    if (this.ready) this.sendChunk(pcm);
    else {
      this.pendingBytes += pcm.length;
      if (this.pendingBytes > 512 * 1024) { this.fail("HR-VOICE-001"); return; }
      this.pending.push(pcm);
    }
  }
  private sendChunk(pcm: Uint8Array): void {
    if (this.held) this.send(voiceAudioFrame(this.sequence++, this.held));
    if (!this.ended) this.held = pcm;
  }
  private send(frame: ArrayBuffer): void {
    const ws = this.socket;
    if (this.ended || this.transportFailure) return;
    if (!ws || ws.readyState !== 1 || ws.bufferedAmount + frame.byteLength > 512 * 1024) {
      this.fail("HR-VOICE-001"); return;
    }
    try { ws.send(frame); } catch { this.fail("HR-VOICE-001"); }
  }
  finish(limited = false): void {
    if (this.ended || this.finishing) return;
    this.limited = limited;
    if (this.recordingTimer) { clearTimeout(this.recordingTimer); this.timers.delete(this.recordingTimer); }
    this.finishing = true; this.options.onEvent({ kind: "waiting", ...(limited ? { limited: true } : {}) }); this.captureAbort.abort(); this.capture?.stop(); this.capture = null;
    if (this.tail.length) { this.enqueue(this.tail); this.tail = new Uint8Array(0); }
    if (this.transportFailure) { this.terminalFailure(this.transportFailure); return; }
    if (this.ready) this.flushFinal();
    // This also bounds a finish that occurs before provider initialization.
    this.deadline(8_000, () => this.fail("HR-VOICE-004"));
  }
  private flushFinal(): void {
    this.finalSent = true;
    this.send(voiceAudioFrame(this.sequence, this.held ?? new Uint8Array(0), true));
    this.held = null;
  }
  cancel(): string { const text = this.latestText; this.cleanup(); return text; }
  private replay(): void {
    const source = this.options.replay!;
    // Repack journal chunks into the provider's 200ms packets without concatenating the file.
    let index = 0, offset = 0, sent = 0;
    const total = source.chunks.reduce((sum, pcm) => sum + pcm.length, 0);
    this.deadline(total / 32 + 30_000, () => this.fail("HR-VOICE-004"));
    const next = () => {
      if (this.ended) return;
      const packet = new Uint8Array(Math.min(6400, total - sent));
      let filled = 0;
      while (filled < packet.length) {
        const chunk = source.chunks[index]!;
        const count = Math.min(chunk.length - offset, packet.length - filled);
        packet.set(chunk.subarray(offset, offset + count), filled); filled += count; offset += count;
        if (offset === chunk.length) { index++; offset = 0; }
      }
      sent += filled;
      const last = sent >= total;
      if (last) this.finalSent = true;
      this.send(voiceAudioFrame(this.sequence++, packet, last));
      if (this.ended) return;
      this.options.onEvent({ kind: "progress", sentMs: sent / 32, totalMs: total / 32 });
      if (last) this.deadline(8_000, () => this.fail("HR-VOICE-004"));
      else this.deadline(200, next);
    };
    next();
  }
  storageFailed(): void { this.terminalFailure(appError("HR-VOICE-006")); }
  private fail(code: "HR-VOICE-001" | "HR-VOICE-002" | "HR-VOICE-003" | "HR-VOICE-004" | "HR-VOICE-005" | "HR-PERM-006"): void {
    if (this.ended) return;
    const error = appError(code);
    if (!this.finishing && code !== "HR-PERM-006") {
      if (this.transportFailure) return;
      this.transportFailure = error;
      if (this.startupTimer) { clearTimeout(this.startupTimer); this.timers.delete(this.startupTimer); }
      this.closeSocket(); this.pending = []; this.pendingBytes = 0; this.held = null;
      this.options.onEvent({ kind: "degraded", error });
      return;
    }
    this.terminalFailure(error);
  }
  private terminalFailure(error: AppError): void {
    if (this.ended) return;
    const text = this.latestText;
    this.cleanup();
    this.options.onEvent({ kind: "failed", text, error });
  }
  private closeSocket(): void {
    const socket = this.socket;
    if (socket) {
      socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null;
      if (socket.readyState < 2) socket.close();
    }
    this.socket = null;
  }
  private cleanup(): void {
    if (this.ended) return;
    this.ended = true;
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear(); this.captureAbort.abort(); this.capture?.stop(); this.capture = null;
    this.closeSocket(); this.pending = []; this.pendingBytes = 0; this.held = null; this.tail = new Uint8Array(0);
  }
}
