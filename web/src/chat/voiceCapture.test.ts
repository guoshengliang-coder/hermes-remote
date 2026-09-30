import { expect, it } from "vitest";
import { Pcm16Converter } from "./voiceCapture";
it.each([16000, 44100, 48000])("converts %i Hz to mono little-endian PCM16 independent of chunk boundaries", (rate) => {
  const samples = Float32Array.from({ length: rate }, (_, i) => Math.sin(i / 25) * 0.5);
  const whole = new Pcm16Converter(rate).push(samples);
  const converter = new Pcm16Converter(rate), chunks: number[] = [];
  for (let i = 0; i < samples.length; i += 128) chunks.push(...converter.push(samples.slice(i, i + 128)));
  expect(whole.length).toBe(32000);
  expect([...whole]).toEqual(chunks);
});
it("clamps microphone peaks, averages samples and refuses impossible rates", () => {
  const pcm = new Pcm16Converter(48000).push(new Float32Array([2, 2, 2, -2, -2, -2, NaN, NaN, NaN]));
  const view = new DataView(pcm.buffer);
  expect([view.getInt16(0, true), view.getInt16(2, true), view.getInt16(4, true)]).toEqual([32767, -32768, 0]);
  expect(() => new Pcm16Converter(0)).toThrow();
});

it("cancellation while browser permission is pending closes the context and stops late microphone tracks", async () => {
  const { vi } = await import("vitest");
  const { captureVoice } = await import("./voiceCapture");
  let grant!: (stream: MediaStream) => void;
  const close = vi.fn(async () => {}), stop = vi.fn(), module = vi.fn(async () => {});
  vi.stubGlobal("AudioContext", class { resume = async () => {}; close = close; audioWorklet = { addModule: module }; });
  vi.stubGlobal("navigator", { mediaDevices: { getUserMedia: () => new Promise<MediaStream>((resolve) => { grant = resolve; }) } });
  try {
    const abort = new AbortController(), pcm = vi.fn();
    const pending = captureVoice(pcm, abort.signal);
    const rejection = expect(pending).rejects.toThrow("capture cancelled");
    abort.abort(); expect(close).toHaveBeenCalledOnce();
    grant({ getTracks: () => [{ stop }] } as unknown as MediaStream);
    await rejection;
    expect(stop).toHaveBeenCalledOnce(); expect(module).not.toHaveBeenCalled(); expect(pcm).not.toHaveBeenCalled();
  } finally { vi.unstubAllGlobals(); }
});
