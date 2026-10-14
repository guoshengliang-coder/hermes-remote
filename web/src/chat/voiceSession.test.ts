import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { BrowserVoiceSession, type VoiceEvent } from "./voiceSession";
class Socket {
  readyState = 1; bufferedAmount = 0; binaryType = "";
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: ArrayBuffer }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  sent: ArrayBuffer[] = [];
  send(data: ArrayBuffer) { this.sent.push(data); }
  close = vi.fn(() => { this.readyState = 3; });
}
function frame(text?: string, final = false): ArrayBuffer {
  const json = new TextEncoder().encode(JSON.stringify(text === undefined ? { code: 0 } : { result: { text } }));
  const bytes = new Uint8Array(12 + json.length); bytes.set([0x11, final ? 0x93 : 0x91, 0x10, 0]);
  new DataView(bytes.buffer).setInt32(4, final ? -2 : 2); new DataView(bytes.buffer).setUint32(8, json.length); bytes.set(json, 12); return bytes.buffer;
}
const settle = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
const sessions: BrowserVoiceSession[] = [];
beforeEach(() => vi.useFakeTimers());
afterEach(() => { sessions.forEach((s) => s.cancel()); sessions.length = 0; vi.useRealTimers(); });
function harness() {
  const socket = new Socket(), events: VoiceEvent[] = [], stop = vi.fn();
  let pcm!: (bytes: Uint8Array) => void;
  const session = new BrowserVoiceSession({ endpoint: async () => "wss://same.test/v2/devices/mac/voice", socket: () => socket as unknown as WebSocket,
    capture: async (onPcm) => { pcm = onPcm; return { stop }; }, onEvent: (e) => events.push(e) });
  sessions.push(session); session.start();
  return { socket, events, stop, session, pcm: (bytes: Uint8Array) => pcm(bytes) };
}
it("buffers initialization, streams correct audio sequences and releases the mic before final recognition", async () => {
  const h = harness(); await settle(); h.socket.onopen!(); h.pcm(new Uint8Array(12800));
  expect(h.socket.sent).toHaveLength(1);
  h.socket.onmessage!({ data: frame() }); await settle();
  expect(h.socket.sent).toHaveLength(2);
  expect(new DataView(h.socket.sent[1]!).getInt32(4)).toBe(2);
  h.session.finish(); expect(h.stop).toHaveBeenCalledTimes(1);
  expect(new DataView(h.socket.sent[2]!).getInt32(4)).toBe(-3);
  h.socket.onmessage!({ data: frame("你好", true) }); await settle();
  expect(h.events.at(-1)).toEqual({ kind: "final", text: "你好" });
  expect(h.socket.close).toHaveBeenCalledTimes(1);
});
it("keeps partial text on bounded final timeout; never submits a partial as final", async () => {
  const h = harness(); await settle(); h.socket.onopen!(); h.socket.onmessage!({ data: frame("半截文字") }); await settle();
  h.session.finish(); await vi.advanceTimersByTimeAsync(8000);
  expect(h.events.at(-1)).toMatchObject({ kind: "failed", text: "半截文字", error: { code: "HR-VOICE-004", retryable: true } });
  expect(h.events.some((e) => e.kind === "final")).toBe(false);
});
it("cancels before endpoint or microphone resolution without accepting late callbacks", async () => {
  let resolveEndpoint!: (url: string) => void, resolveCapture!: (capture: { stop: () => void }) => void;
  const factory = vi.fn(), events: VoiceEvent[] = [], stop = vi.fn();
  const s = new BrowserVoiceSession({ endpoint: () => new Promise((resolve) => resolveEndpoint = resolve),
    capture: () => new Promise((resolve) => resolveCapture = resolve), socket: factory, onEvent: (e) => events.push(e) });
  sessions.push(s); s.start(); s.cancel(); resolveEndpoint("wss://same.test/voice"); resolveCapture({ stop }); await settle();
  expect(factory).not.toHaveBeenCalled(); expect(stop).toHaveBeenCalledTimes(1); expect(events).toEqual([]);
});
it("bounds a stuck endpoint and releases a live microphone", async () => {
  const stop = vi.fn(), events: VoiceEvent[] = [];
  const s = new BrowserVoiceSession({ endpoint: () => new Promise(() => {}), capture: async () => ({ stop }), onEvent: (e) => events.push(e) });
  sessions.push(s); s.start(); await settle(); await vi.advanceTimersByTimeAsync(10000);
  expect(stop).toHaveBeenCalled(); expect(events.at(-1)).toMatchObject({ kind: "failed", error: { code: "HR-VOICE-001" } });
});
it("refuses backpressure and malformed provider frames without leaking their content", async () => {
  const h = harness(); await settle(); h.socket.onopen!(); h.socket.onmessage!({ data: frame() }); await settle();
  h.socket.bufferedAmount = 512 * 1024; h.pcm(new Uint8Array(12800));
  expect(h.events.at(-1)).toMatchObject({ kind: "failed", error: { code: "HR-VOICE-001" } });
  const bad = harness(); await settle(); bad.socket.onmessage!({ data: new Uint8Array([1, 2]).buffer }); await settle();
  expect(bad.events.at(-1)).toMatchObject({ kind: "failed", error: { code: "HR-VOICE-002" } });
});
it("cancel returns partial text and a late final can no longer send", async () => {
  const h = harness(); await settle(); h.socket.onmessage!({ data: frame("草稿") }); await settle();
  const callback = h.socket.onmessage!; expect(h.session.cancel()).toBe("草稿");
  callback({ data: frame("迟到", true) }); await settle();
  expect(h.events.filter((e) => e.kind === "final")).toEqual([]);
});
it("a provider final while still holding cannot send and retains text for review", async () => {
  const h = harness(); await settle(); h.socket.onmessage!({ data: frame("尚未松手", true) }); await settle();
  expect(h.events.at(-1)).toMatchObject({ kind: "failed", text: "尚未松手", error: { code: "HR-VOICE-002" } });
  expect(h.events.some((e) => e.kind === "final")).toBe(false); expect(h.stop).toHaveBeenCalledOnce();
});
it("survives 60 seconds and gracefully finalizes five minutes of real audio to a draft", async () => {
  const h = harness(); await settle(); h.socket.onopen!(); h.socket.onmessage!({ data: frame("达到录音时限") }); await settle();
  await vi.advanceTimersByTimeAsync(5000); h.pcm(new Uint8Array(6400));
  await vi.advanceTimersByTimeAsync(60_000); expect(h.stop).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(240_000);
  expect(h.stop).toHaveBeenCalledOnce(); expect(h.socket.close).not.toHaveBeenCalled();
  expect(h.events.at(-1)).toEqual({ kind: "waiting", limited: true });
  expect(new DataView(h.socket.sent.at(-1)!).getInt32(4)).toBeLessThan(0);
  h.session.finish(); // Late release must not change the limit disposition.
  h.socket.onmessage!({ data: frame("完整文字", true) }); await settle();
  expect(h.events.at(-1)).toEqual({ kind: "final", text: "完整文字", limited: true });
  expect(h.socket.close).toHaveBeenCalledOnce(); expect(vi.getTimerCount()).toBe(0);
});
it("a release just before the limit has its own full final-result deadline", async () => {
  const h = harness(); await settle(); h.socket.onmessage!({ data: frame() }); await settle(); h.pcm(new Uint8Array(6400));
  await vi.advanceTimersByTimeAsync(299_000); h.session.finish();
  await vi.advanceTimersByTimeAsync(7000); expect(h.events.at(-1)).toEqual({ kind: "waiting" });
  h.socket.onmessage!({ data: frame("松手发送", true) }); await settle();
  expect(h.events.at(-1)).toEqual({ kind: "final", text: "松手发送" });
});
it.each(["close", "error"])("reports established connection %s separately, retaining partial text", async (event) => {
  const h = harness(); await settle(); h.socket.onmessage!({ data: frame("已有文字") }); await settle();
  if (event === "close") h.socket.onclose!(); else h.socket.onerror!();
  expect(h.events.at(-1)).toMatchObject({ kind: "failed", text: "已有文字", error: { code: "HR-VOICE-003" } });
  expect(vi.getTimerCount()).toBe(0);
});
it.each([false, true])("handles an empty final accurately (limited=%s)", async (limited) => {
  const h = harness(); await settle(); h.socket.onmessage!({ data: frame() }); await settle(); h.session.finish(limited);
  h.socket.onmessage!({ data: frame("", true) }); await settle();
  expect(h.events.at(-1)).toMatchObject(limited ? { kind: "final", text: "", limited: true } : { kind: "failed", error: { code: "HR-VOICE-005" } });
});
it("reports recording once, only after capture succeeds and nonempty PCM arrives", async () => {
  const h = harness(); await settle(); expect(h.events).toEqual([]);
  h.pcm(new Uint8Array(0)); expect(h.events).toEqual([]);
  h.pcm(new Uint8Array(320)); h.pcm(new Uint8Array(320));
  expect(h.events).toEqual([{ kind: "recording" }]);
});
it.each(["cancel", "finish", "failure"])("ignores PCM after %s, including a late capture resolution", async (ending) => {
  let pcm!: (bytes: Uint8Array) => void, resolve!: (capture: { stop(): void }) => void;
  const stop = vi.fn(), events: VoiceEvent[] = [];
  const s = new BrowserVoiceSession({ endpoint: () => new Promise(() => {}),
    capture: (callback) => { pcm = callback; return new Promise((done) => resolve = done); }, onEvent: (e) => events.push(e) });
  sessions.push(s); s.start(); pcm(new Uint8Array(320));
  expect(events).toEqual([]);
  if (ending === "cancel") s.cancel();
  else if (ending === "finish") s.finish();
  else await vi.advanceTimersByTimeAsync(10_000);
  resolve({ stop }); await settle(); pcm(new Uint8Array(320));
  expect(stop).toHaveBeenCalledOnce(); expect(events.some((e) => e.kind === "recording")).toBe(false);
});
it("a failed capture never reports recording, even if it supplied PCM before rejecting", async () => {
  const events: VoiceEvent[] = [];
  const s = new BrowserVoiceSession({ endpoint: () => new Promise(() => {}),
    capture: async (pcm) => { pcm(new Uint8Array(320)); throw new Error("capture failed"); }, onEvent: (e) => events.push(e) });
  sessions.push(s); s.start(); await settle();
  expect(events).toEqual([expect.objectContaining({ kind: "failed", error: expect.objectContaining({ code: "HR-PERM-006" }) })]);
});
