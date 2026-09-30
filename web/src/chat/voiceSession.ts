import { appError, type AppError } from "../errors";
import { captureVoice, type VoiceCapture } from "./voiceCapture";
import { parseVoiceFrame, voiceAudioFrame, voiceInitialFrame } from "./voiceProtocol";

export type VoiceEvent = { kind: "waiting" } | { kind: "partial"; text: string } | { kind: "final"; text: string }
  | { kind: "failed"; text: string; error: AppError };
export interface VoiceSessionOptions {
  endpoint: () => Promise<string>;
  onEvent: (event: VoiceEvent) => void;
  capture?: typeof captureVoice;
  socket?: (url: string) => WebSocket;
}

// One recording, bounded through every async stage. No reconnect or replay of microphone audio.
export class BrowserVoiceSession {
  private readonly captureAbort = new AbortController();
  private socket: WebSocket | null = null;
  private capture: VoiceCapture | null = null;
  private ended = false;
  private finishing = false;
  private ready = false;
  private sequence = 2;
  private held: Uint8Array | null = null;
  private pending: Uint8Array[] = [];
  private pendingBytes = 0;
  private tail = new Uint8Array(0);
  private latestText = "";
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private messages = Promise.resolve();
  constructor(private readonly options: VoiceSessionOptions) {}

  start(): void {
    this.deadline(10_000, () => this.fail("HR-VOICE-001"));
    this.deadline(60_000, () => this.fail("HR-VOICE-002"));
    // Start capture synchronously in the gesture so AudioContext.resume is allowed on WebKit.
    void (this.options.capture ?? captureVoice)((pcm) => this.onPcm(pcm), this.captureAbort.signal).then((capture) => {
      if (this.ended || this.finishing) capture.stop();
      else this.capture = capture;
    }, () => { if (!this.ended && !this.finishing) this.fail("HR-PERM-006"); });
    void this.options.endpoint().then((url) => {
      if (this.ended) return;
      const socket = (this.options.socket ?? ((target) => new WebSocket(target)))(url);
      this.socket = socket; socket.binaryType = "arraybuffer";
      socket.onopen = () => { if (!this.ended) socket.send(voiceInitialFrame()); };
      socket.onmessage = (event: MessageEvent) => {
        this.messages = this.messages.then(async () => {
          if (this.ended) return;
          if (!(event.data instanceof ArrayBuffer)) throw new Error("non-binary speech response");
          const result = await parseVoiceFrame(event.data);
          if (this.ended) return;
          if (result.error) { this.fail("HR-VOICE-002"); return; }
          if (!this.ready) {
            this.ready = true;
            // Only the startup timer is cleared; the recording deadline remains.
            if (this.startupTimer) { clearTimeout(this.startupTimer); this.timers.delete(this.startupTimer); }
            for (const pcm of this.pending) this.sendChunk(pcm);
            this.pending = []; this.pendingBytes = 0;
            if (this.finishing) this.flushFinal();
          }
          if (result.text !== null) {
            this.latestText = result.text;
            this.options.onEvent({ kind: "partial", text: result.text });
          }
          if (result.finished) {
            const text = this.latestText.trim();
            // A premature final must never send while the user still holds/cancels the gesture.
            if (!this.finishing || !text) { this.fail("HR-VOICE-002"); return; }
            this.cleanup(); this.options.onEvent({ kind: "final", text });
          }
        }).catch(() => this.fail("HR-VOICE-002"));
      };
      socket.onerror = () => this.fail(this.latestText ? "HR-VOICE-002" : "HR-VOICE-001");
      socket.onclose = () => this.fail(this.latestText ? "HR-VOICE-002" : "HR-VOICE-001");
    }).catch(() => this.fail("HR-VOICE-001"));
  }
  private startupTimer: ReturnType<typeof setTimeout> | null = null;
  private deadline(ms: number, callback: () => void): void {
    const timer = setTimeout(() => { this.timers.delete(timer); if (!this.ended) callback(); }, ms);
    this.timers.add(timer);
    if (ms === 10_000) this.startupTimer = timer;
  }
  private onPcm(pcm: Uint8Array): void {
    if (this.ended || this.finishing) return;
    const bytes = new Uint8Array(this.tail.length + pcm.length);
    bytes.set(this.tail); bytes.set(pcm, this.tail.length);
    let offset = 0;
    while (offset + 6400 <= bytes.length) { this.enqueue(bytes.slice(offset, offset + 6400)); offset += 6400; }
    this.tail = bytes.slice(offset);
  }
  private enqueue(pcm: Uint8Array): void {
    if (this.ended) return;
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
    if (this.ended) return;
    if (!ws || ws.readyState !== 1 || ws.bufferedAmount + frame.byteLength > 512 * 1024) {
      this.fail("HR-VOICE-001"); return;
    }
    try { ws.send(frame); } catch { this.fail("HR-VOICE-001"); }
  }
  finish(): void {
    if (this.ended || this.finishing) return;
    this.finishing = true; this.options.onEvent({ kind: "waiting" }); this.captureAbort.abort(); this.capture?.stop(); this.capture = null;
    if (this.tail.length) { this.enqueue(this.tail); this.tail = new Uint8Array(0); }
    if (this.ready) this.flushFinal();
    // This also bounds a finish that occurs before provider initialization.
    this.deadline(8_000, () => this.fail("HR-VOICE-002"));
  }
  private flushFinal(): void {
    this.send(voiceAudioFrame(this.sequence, this.held ?? new Uint8Array(0), true));
    this.held = null;
  }
  cancel(): string { const text = this.latestText; this.cleanup(); return text; }
  private fail(code: "HR-VOICE-001" | "HR-VOICE-002" | "HR-PERM-006"): void {
    if (this.ended) return;
    const text = this.latestText;
    this.cleanup();
    this.options.onEvent({ kind: "failed", text, error: appError(text && code !== "HR-PERM-006" ? "HR-VOICE-002" : code) });
  }
  private cleanup(): void {
    if (this.ended) return;
    this.ended = true;
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear(); this.captureAbort.abort(); this.capture?.stop(); this.capture = null;
    const socket = this.socket;
    if (socket) {
      socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null;
      // Closing a CONNECTING browser socket aborts its handshake.
      if (socket.readyState < 2) socket.close();
    }
    this.socket = null; this.pending = []; this.pendingBytes = 0; this.held = null; this.tail = new Uint8Array(0);
  }
}
