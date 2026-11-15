import "fake-indexeddb/auto";
import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { Composer } from "./Composer";
import { AppContext, type AppContextValue } from "../app/store";
import { VoiceRecordingStore, clearVoiceRecordings } from "../chat/voiceRecordings";
import { clearAllDrafts } from "../app/drafts";
const capture = vi.hoisted(() => ({ pcm: null as null | ((pcm: Uint8Array) => void), starts: vi.fn(), stop: vi.fn() }));
vi.mock("../chat/voiceCapture", () => ({
  voiceCaptureSupported: () => true, allowMicrophone: async () => {},
  captureVoice: async (pcm: (pcm: Uint8Array) => void) => { capture.starts(); capture.pcm = pcm; return { stop: capture.stop }; },
}));
class Socket {
  static all: Socket[] = [];
  readyState = 1; bufferedAmount = 0; sent: ArrayBuffer[] = []; binaryType = "";
  onopen: (() => void) | null = null; onclose: (() => void) | null = null; onerror: (() => void) | null = null;
  onmessage: ((event: { data: ArrayBuffer }) => void) | null = null;
  constructor() { Socket.all.push(this); }
  send(bytes: ArrayBuffer) { this.sent.push(bytes); }
  close() { this.readyState = 3; }
}
function frame(text = "", final = false): ArrayBuffer {
  const payload = new TextEncoder().encode(JSON.stringify({ result: { text } }));
  const bytes = new Uint8Array(12 + payload.length); bytes.set([0x11, final ? 0x93 : 0x91, 0x10, 0]);
  const view = new DataView(bytes.buffer); view.setInt32(4, final ? -2 : 2); view.setUint32(8, payload.length); bytes.set(payload, 12); return bytes.buffer;
}
const context = { t: (zh: string) => zh, language: "zh", account: { id: "account" }, device: { deviceId: "mac" },
  client: { settled: async () => {} }, voiceFeedback: "off", features: new Set(["voice-input"]) } as unknown as AppContextValue;
const hosts: HTMLElement[] = [];
const settle = async () => act(async () => { for (let i = 0; i < 12; i++) await new Promise(resolve => setTimeout(resolve, 0)); });
function mount(key = "account/mac/profile/session") {
  vi.stubGlobal("WebSocket", Socket);
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const onSend = vi.fn();
  const update = (nextKey: string) => act(() => render(<AppContext.Provider value={context}><Composer t={zh => zh} language="zh" disabled={false} generating={false} onSend={onSend} onInterrupt={() => {}} draftKey={nextKey} /></AppContext.Provider>, host));
  update(key);
  return { host, onSend, update };
}
function click(host: HTMLElement, text: string) {
  act(() => [...host.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === text)!.click());
}
afterEach(async () => {
  hosts.forEach(host => { act(() => render(null, host)); host.remove(); }); hosts.length = 0;
  await settle(); await clearVoiceRecordings(); clearAllDrafts(); Socket.all = [];
  vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks();
});
it("keeps a whole recording through disconnect and retry, preserving the original draft exactly once", async () => {
  const { host, onSend } = mount(); await settle();
  const area = host.querySelector("textarea")!;
  act(() => { area.value = "typed draft"; area.dispatchEvent(new Event("input", { bubbles: true })); });
  const file = new File(["original bytes"], "notes.pdf", { type: "application/pdf" });
  const picker = host.querySelector<HTMLInputElement>('input[type="file"]:not([accept="image/*"])')!;
  Object.defineProperty(picker, "files", { configurable: true, value: [file] });
  act(() => { picker.dispatchEvent(new Event("change", { bubbles: true })); }); await settle();
  act(() => host.querySelector<HTMLButtonElement>(".composer-voice")!.click()); await settle();
  const hold = host.querySelector(".voice-hold")!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); }); await settle();
  const first = Socket.all[0]!;
  act(() => { first.onopen!(); first.onmessage!({ data: frame("partial") }); }); await settle();
  act(() => capture.pcm!(new Uint8Array([1, 2]))); await settle();
  act(() => first.onerror!());
  act(() => capture.pcm!(new Uint8Array([3, 4]))); await settle();
  expect(capture.stop).not.toHaveBeenCalled(); expect(onSend).not.toHaveBeenCalled();
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 260)); });
  act(() => { window.dispatchEvent(new KeyboardEvent("keyup", { key: " ", bubbles: true })); }); await settle();
  expect(host.textContent).toContain("完整录音已保留");
  const store = new VoiceRecordingStore(), [saved] = await store.list("account/mac/profile/session");
  expect(await store.audio(saved!.id)).toEqual([new Uint8Array([1, 2]), new Uint8Array([3, 4])]);
  click(host, "重试发送"); click(host, "重试发送"); await settle();
  expect(Socket.all).toHaveLength(2); expect(capture.starts).toHaveBeenCalledOnce();
  const retry = Socket.all[1]!;
  act(() => { retry.onopen!(); retry.onmessage!({ data: frame() }); }); await settle();
  expect(new Uint8Array(retry.sent[1]!).slice(12)).toEqual(new Uint8Array([1, 2, 3, 4]));
  act(() => retry.onmessage!({ data: frame("full final", true) })); await settle();
  expect(onSend).toHaveBeenCalledOnce(); expect(onSend.mock.calls[0]![0]).toBe("typed draft\nfull final");
  expect(onSend.mock.calls[0]![1]).toMatchObject([{ name: "notes.pdf", file }]);
  expect((await store.list(saved!.scope))[0]!.text).toBe("typed draft\nfull final");
  act(() => onSend.mock.calls[0]![2]()); await settle();
  expect(await store.list(saved!.scope)).toEqual([]);
});
it("restores completed audio after reopening but only transcribes it for review", async () => {
  const store = new VoiceRecordingStore(), saved = await store.begin("account/mac/profile/session");
  await store.append(saved.id, 0, new Uint8Array([1, 2])); await store.seal(saved.id, true);
  const { host, onSend } = mount(); await settle();
  expect(host.textContent).toContain("完整录音已保留");
  expect([...host.querySelectorAll("button")].some(b => b.textContent === "重试发送")).toBe(false);
  click(host, "转文字"); await settle(); const socket = Socket.all[0]!;
  act(() => { socket.onopen!(); socket.onmessage!({ data: frame() }); }); await settle();
  act(() => socket.onmessage!({ data: frame("restored", true) })); await settle();
  expect(host.querySelector("textarea")!.value).toBe("restored"); expect(onSend).not.toHaveBeenCalled();
  expect(capture.starts).not.toHaveBeenCalled(); expect(await store.list(saved.scope)).toEqual([]);
});
it("restores a durable final-text handoff without duplicating its already saved typed prefix", async () => {
  const store = new VoiceRecordingStore(), saved = await store.begin("account/mac/profile/session");
  await store.append(saved.id, 0, new Uint8Array([1, 2])); await store.seal(saved.id, true);
  await store.handoff(saved.id, "typed\nspoken", "typed");
  const { saveDraft } = await import("../app/drafts"); saveDraft(saved.scope, "typed");
  const { host, onSend } = mount(); await settle();
  click(host, "恢复文字"); await settle();
  expect(host.querySelector("textarea")!.value).toBe("typed\nspoken");
  expect(onSend).not.toHaveBeenCalled(); expect(Socket.all).toHaveLength(0);
  expect(await store.list(saved.scope)).toEqual([]);
});
it("moves the new-chat recovery text to the assigned durable conversation ID", async () => {
  const { draftKey } = await import("../app/drafts");
  const { conversationScope } = await import("../app/conversationMemory");
  const key = draftKey("mac", null, conversationScope("account", "mac", null, null));
  const { host, onSend } = mount(key); await settle();
  act(() => host.querySelector<HTMLButtonElement>(".composer-voice")!.click()); await settle();
  act(() => { host.querySelector(".voice-hold")!.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); }); await settle();
  const socket = Socket.all[0]!;
  act(() => { socket.onopen!(); socket.onmessage!({ data: frame() }); }); await settle();
  act(() => capture.pcm!(new Uint8Array([1, 2])));
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 260)); });
  act(() => { window.dispatchEvent(new KeyboardEvent("keyup", { key: " ", bubbles: true })); }); await settle();
  act(() => socket.onmessage!({ data: frame("new chat final", true) })); await settle();
  act(() => onSend.mock.calls[0]![2].onStored("assigned")); await settle();
  const store = new VoiceRecordingStore();
  expect(await store.list(key)).toEqual([]);
  expect((await store.list(draftKey("mac", "assigned", conversationScope("account", "mac", null, "assigned"))))[0]!.text).toBe("new chat final");
});
it("a late handoff in the previous chat cannot unlock the new chat's pending handoff", async () => {
  const store = new VoiceRecordingStore(), a = await store.begin("chat-a"), b = await store.begin("chat-b");
  await store.append(a.id, 0, new Uint8Array([1, 2])); await store.seal(a.id, true);
  await store.append(b.id, 0, new Uint8Array([3, 4])); await store.seal(b.id, true);
  let releaseA!: () => void, releaseB!: () => void;
  const gateA = new Promise<void>(r => releaseA = r), gateB = new Promise<void>(r => releaseB = r);
  const handoff = VoiceRecordingStore.prototype.handoff;
  vi.spyOn(VoiceRecordingStore.prototype, "handoff").mockImplementation(function(this: VoiceRecordingStore, id, text, base) {
    return handoff.call(this, id, text, base).then(record => (id === a.id ? gateA : gateB).then(() => record));
  });
  const h = mount("chat-a"); await settle(); click(h.host, "转文字"); await settle();
  act(() => { Socket.all[0]!.onopen!(); Socket.all[0]!.onmessage!({ data: frame() }); }); await settle();
  act(() => Socket.all[0]!.onmessage!({ data: frame("a final", true) })); await settle();
  h.update("chat-b"); await settle(); click(h.host, "转文字"); await settle();
  act(() => { Socket.all[1]!.onopen!(); Socket.all[1]!.onmessage!({ data: frame() }); }); await settle();
  act(() => Socket.all[1]!.onmessage!({ data: frame("b final", true) })); await settle();
  const transcribe = () => [...h.host.querySelectorAll<HTMLButtonElement>("button")].find(b => b.textContent === "转文字")!;
  expect(transcribe().disabled).toBe(true);
  releaseA(); await settle();
  const remainedLocked = transcribe().disabled;
  releaseB(); await settle();
  expect(remainedLocked).toBe(true);
  expect(h.host.querySelector("textarea")!.value).toBe("b final"); expect(h.onSend).not.toHaveBeenCalled();
});
it("another view shows a live recording as active and cannot consume or discard it", async () => {
  const store = new VoiceRecordingStore(), saved = await store.begin("account/mac/profile/session");
  await store.append(saved.id, 0, new Uint8Array([1, 2]));
  const { host, onSend } = mount(); await settle();
  expect(host.textContent).toContain("仍在录制");
  for (const label of ["转文字", "丢弃"]) {
    expect([...host.querySelectorAll<HTMLButtonElement>("button")].find(b => b.textContent === label)!.disabled).toBe(true);
  }
  expect(onSend).not.toHaveBeenCalled(); expect(capture.starts).not.toHaveBeenCalled();
  await store.append(saved.id, 1, new Uint8Array([3, 4])); await store.seal(saved.id, true);
  expect(await store.audio(saved.id)).toEqual([new Uint8Array([1, 2]), new Uint8Array([3, 4])]);
});
