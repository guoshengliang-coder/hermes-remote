// Streaming box-filter resampling. Fractional sample windows survive worklet message boundaries.
export class Pcm16Converter {
  private filled = 0;
  private sum = 0;
  private readonly width: number;
  constructor(sourceRate: number) {
    if (!Number.isFinite(sourceRate) || sourceRate < 16000 || sourceRate > 192000) throw new Error("invalid sample rate");
    this.width = sourceRate / 16000;
  }
  push(samples: Float32Array): Uint8Array {
    const out: number[] = [];
    for (const sample of samples) {
      let remaining = 1;
      while (remaining > 1e-9) {
        const part = Math.min(remaining, this.width - this.filled);
        this.sum += (Number.isFinite(sample) ? sample : 0) * part;
        this.filled += part; remaining -= part;
        if (this.filled >= this.width - 1e-9) {
          const value = Math.max(-1, Math.min(1, this.sum / this.width));
          out.push(Math.round(value * (value < 0 ? 32768 : 32767)));
          this.filled = 0; this.sum = 0;
        }
      }
    }
    const bytes = new Uint8Array(out.length * 2), view = new DataView(bytes.buffer);
    out.forEach((value, i) => view.setInt16(i * 2, value, true));
    return bytes;
  }
}
export interface VoiceCapture { stop(): void }
export function voiceCaptureSupported(): boolean {
  return globalThis.isSecureContext === true && typeof navigator.mediaDevices?.getUserMedia === "function"
    && typeof AudioContext === "function" && typeof AudioWorkletNode === "function"
    && typeof DecompressionStream === "function";
}
export async function allowMicrophone(): Promise<void> {
  if (!voiceCaptureSupported()) throw new Error("microphone unavailable");
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  stream.getTracks().forEach((track) => track.stop());
}
export async function captureVoice(onPcm: (pcm: Uint8Array) => void, signal?: AbortSignal): Promise<VoiceCapture> {
  const context = new AudioContext();
  let stream: MediaStream | null = null, node: AudioWorkletNode | null = null;
  let stopped = false;
  const stop = () => {
    if (stopped) return;
    stopped = true;
    if (node) { node.port.onmessage = null; node.disconnect(); }
    stream?.getTracks().forEach((track) => track.stop());
    signal?.removeEventListener("abort", stop);
    void context.close().catch(() => {});
  };
  signal?.addEventListener("abort", stop, { once: true });
  try {
    if (signal?.aborted) { stop(); throw new Error("capture cancelled"); }
    // Resume during the user's gesture; do not wait for getUserMedia first (WebKit).
    const resumed = context.resume();
    // Attach a handler immediately: cancellation can close the context while permission is pending.
    void resumed.catch(() => {});
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
    if (stopped) { stream.getTracks().forEach((track) => track.stop()); throw new Error("capture cancelled"); }
    await resumed;
    await context.audioWorklet.addModule(new URL("../../voice-capture.js", import.meta.url));
    if (stopped) throw new Error("capture cancelled");
    node = new AudioWorkletNode(context, "hermes-voice-capture");
    const converter = new Pcm16Converter(context.sampleRate);
    node.port.onmessage = (event: MessageEvent<Float32Array>) => { if (!stopped) onPcm(converter.push(event.data)); };
    const source = context.createMediaStreamSource(stream), silent = context.createGain();
    silent.gain.value = 0;
    source.connect(node); node.connect(silent); silent.connect(context.destination);
    return { stop };
  } catch (error) { stop(); throw error; }
}
