import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { Composer, type ComposerProps } from "./Composer";
import { AppContext, type AppContextValue } from "../app/store";
import { overlayDepth, resetOverlays } from "../app/overlayHistory";
import type { VoiceEvent } from "../chat/voiceSession";
import { clearAllDrafts, loadDraft, saveDraft } from "../app/drafts";
const mocks = vi.hoisted(() => ({ events: null as null | ((e: VoiceEvent) => void), finish: vi.fn(), cancel: vi.fn(() => "半截"), microphone: vi.fn(async () => {}) }));
vi.mock("../chat/voiceCapture", () => ({ voiceCaptureSupported: () => true, allowMicrophone: mocks.microphone }));
vi.mock("../chat/voiceSession", () => ({ BrowserVoiceSession: class {
  constructor(options: { onEvent: (e: VoiceEvent) => void }) { mocks.events = options.onEvent; }
  start() {} finish() { mocks.finish(); mocks.events?.({ kind: "waiting" }); } cancel() { return mocks.cancel(); }
} }));
const hosts: HTMLElement[] = [];
afterEach(() => { hosts.forEach((host) => { act(() => render(null, host)); host.remove(); }); hosts.length = 0; vi.clearAllMocks(); vi.restoreAllMocks(); vi.unstubAllGlobals(); mocks.events = null; localStorage.clear(); });
const context = { t: (zh: string) => zh, language: "zh", device: { deviceId: "mac" }, client: { settled: async () => {} }, voiceFeedback: "on", features: new Set(["voice-input"]) } as unknown as AppContextValue;
function mount(overrides: Partial<ComposerProps> = {}) {
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const props: ComposerProps = { t: (zh) => zh, language: "zh", generating: false, disabled: false, onSend: vi.fn(), onInterrupt: vi.fn(), ...overrides };
  const update = (change: Partial<ComposerProps>) => { Object.assign(props, change); act(() => render(<AppContext.Provider value={context}><Composer {...props} /></AppContext.Provider>, host)); };
  update({}); return { host, props, update };
}
function type(host: HTMLElement, text: string) {
  const area = host.querySelector("textarea")!;
  act(() => { area.focus(); area.value = text; area.dispatchEvent(new Event("input", { bubbles: true })); });
  return area;
}
const settle = async () => act(async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); });
it.each([7 * 1024 * 1024, 50 * 1024 * 1024])("passes an ordinary %s-byte file to send unchanged", async (size) => {
  const h = mount(); const file = new File(["fixture"], "report.pdf", { type: "application/pdf" });
  Object.defineProperty(file, "size", { value: size });
  const input = h.host.querySelector<HTMLInputElement>('input[type="file"]:not([accept])')!;
  Object.defineProperty(input, "files", { value: [file] });
  act(() => { input.dispatchEvent(new Event("change", { bubbles: true })); }); await settle();
  expect(h.host.querySelector(".attachment-file")!.textContent).toContain("report.pdf");
  act(() => h.host.querySelector<HTMLButtonElement>(".send-button")!.click());
  expect(h.props.onSend).toHaveBeenCalledWith("", [expect.objectContaining({ file, kind: "file", mimeType: "application/pdf" })]);
});
it("rejects an ordinary file above 50 MiB with the registered local error", async () => {
  const h = mount(); const file = new File(["fixture"], "report.pdf", { type: "application/pdf" });
  Object.defineProperty(file, "size", { value: 50 * 1024 * 1024 + 1 });
  const input = h.host.querySelector<HTMLInputElement>('input[type="file"]:not([accept])')!;
  Object.defineProperty(input, "files", { value: [file] });
  act(() => { input.dispatchEvent(new Event("change", { bubbles: true })); }); await settle();
  expect(h.host.textContent).toContain("HR-FILE-008");
  expect(h.host.textContent).toContain("50 MiB");
  expect(h.host.querySelector(".attachment-file")).toBeNull();
  expect(h.props.onSend).not.toHaveBeenCalled();
});
it("saves when the page becomes hidden", () => {
  const h = mount({ draftKey: "one" }); type(h.host, "隐藏前输入");
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
  act(() => { document.dispatchEvent(new Event("visibilitychange")); });
  expect(loadDraft("one")).toBe("隐藏前输入");
});
it("backgrounding voice after switching chats saves the partial text to the current draft only", async () => {
  const h = mount({ draftKey: "one" }); type(h.host, "第一份");
  h.update({ draftKey: "two" }); type(h.host, "第二份");
  act(() => h.host.querySelector<HTMLButtonElement>(".composer-voice")!.click()); await settle();
  act(() => { h.host.querySelector<HTMLButtonElement>(".voice-hold")!.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
  act(() => { document.dispatchEvent(new Event("visibilitychange")); });
  expect(loadDraft("one")).toBe("第一份");
  expect(loadDraft("two")).toBe("第二份\n半截");
});
it("flushes the newest draft when leaving within the 400 ms debounce", () => {
  const h = mount({ draftKey: "one" });
  type(h.host, "刚输入");
  act(() => render(null, h.host));
  expect(loadDraft("one")).toBe("刚输入");
});
it("flushes pagehide and conversation changes without copying text to the next draft", () => {
  saveDraft("two", "第二份");
  const h = mount({ draftKey: "one" });
  type(h.host, "第一份");
  act(() => { window.dispatchEvent(new Event("pagehide")); });
  expect(loadDraft("one")).toBe("第一份");
  type(h.host, "第一份更新");
  h.update({ draftKey: "two" });
  expect(loadDraft("one")).toBe("第一份更新");
  expect(h.host.querySelector("textarea")!.value).toBe("第二份");
  expect(loadDraft("two")).toBe("第二份");
});
it("sending or signing out cannot resurrect a draft during unmount", () => {
  const h = mount({ draftKey: "one" });
  type(h.host, "发送");
  act(() => h.host.querySelector<HTMLButtonElement>(".send-button")!.click());
  act(() => render(null, h.host));
  expect(loadDraft("one")).toBe("");
  h.update({}); type(h.host, "退出登录");
  clearAllDrafts();
  act(() => render(null, h.host));
  expect(loadDraft("one")).toBe("");
});
it("keeps attach/send fixed beside a long model label and collapses after sending", () => {
  const h = mount({ chip: { label: "long-model-".repeat(40), onClick: () => {} } });
  const area = type(h.host, "你好");
  expect(h.host.querySelector(".composer-actions .composer-voice")).not.toBeNull();
  expect(h.host.querySelector(".composer-actions .composer-add")).not.toBeNull();
  act(() => h.host.querySelector<HTMLButtonElement>('.send-button')!.click());
  expect(h.props.onSend).toHaveBeenCalledWith("你好", []);
  expect(h.host.querySelector(".composer.expanded")).toBeNull();
  expect(document.activeElement).not.toBe(area);
  expect(area.value).toBe("");
});
it("IME Enter cannot submit; touch Enter is a newline rather than a submit", () => {
  vi.spyOn(window, "matchMedia").mockReturnValue({ matches: false } as MediaQueryList);
  const h = mount(), area = type(h.host, "中文输入");
  act(() => { area.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", isComposing: true, bubbles: true, cancelable: true })); });
  expect(h.props.onSend).not.toHaveBeenCalled();
  act(() => { area.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true })); });
  expect(h.props.onSend).not.toHaveBeenCalled();
  expect(area.getAttribute("enterkeyhint")).toBe("enter");
});
it("empty expanded draft has disabled Send; generation swaps the same slot to Stop", () => {
  const h = mount(); type(h.host, "");
  expect(h.host.querySelector<HTMLButtonElement>('.send-button')!.disabled).toBe(true);
  h.update({ generating: true });
  act(() => h.host.querySelector<HTMLButtonElement>('.send-button.stop')!.click());
  expect(h.props.onInterrupt).toHaveBeenCalledOnce();
});
it("model and attachment actions release textarea focus before opening their sheet", () => {
  const open = vi.fn(), h = mount({ chip: { label: "model", onClick: open } }); const area = type(h.host, "draft");
  act(() => h.host.querySelector<HTMLButtonElement>('.model-chip')!.click());
  expect(open).toHaveBeenCalledOnce(); expect(document.activeElement).not.toBe(area);
  type(h.host, "draft"); act(() => h.host.querySelector<HTMLButtonElement>('.composer-add')!.click());
  expect(document.activeElement).not.toBe(area); expect(area.value).toBe("draft");
});
it("permission denial shows a bilingual registered recoverable error and keeps the text draft", async () => {
  mocks.microphone.mockRejectedValueOnce(new Error("permission denied"));
  const h = mount(); type(h.host, "保留草稿");
  act(() => h.host.querySelector<HTMLButtonElement>('.composer-voice')!.click()); await settle();
  expect(h.host.textContent).toContain("HR-PERM-006"); expect(h.host.textContent).toContain("重试");
  expect(h.host.querySelector("textarea")!.value).toBe("保留草稿");
});
it("starts feedback only on the live capture event and ignores old recordings after pagehide or chat changes", async () => {
  const vibrate = vi.fn((_duration: number) => true);
  vi.stubGlobal("navigator", { vibrate });
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
  const h = mount({ draftKey: "one" });
  act(() => h.host.querySelector<HTMLButtonElement>('.composer-voice')!.click()); await settle();
  const hold = h.host.querySelector<HTMLButtonElement>('.voice-hold')!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  expect(vibrate).not.toHaveBeenCalled();
  const old = mocks.events!;
  act(() => old({ kind: "recording" })); expect(vibrate).toHaveBeenLastCalledWith(25);
  act(() => { window.dispatchEvent(new Event("pagehide")); });
  expect(mocks.cancel).toHaveBeenCalled(); expect(vibrate).toHaveBeenLastCalledWith(0);
  const count = vibrate.mock.calls.length;
  act(() => old({ kind: "recording" })); expect(vibrate).toHaveBeenCalledTimes(count);
  h.update({ draftKey: "two" }); act(() => old({ kind: "recording" }));
  expect(vibrate).toHaveBeenCalledTimes(count); expect(h.props.onSend).not.toHaveBeenCalled();
});
it("partial results never submit, edit appends, and a callback from a previous conversation is ignored", async () => {
  const h = mount({ draftKey: "one" }); type(h.host, "草稿");
  act(() => h.host.querySelector<HTMLButtonElement>('.composer-voice')!.click()); await settle();
  const hold = h.host.querySelector<HTMLButtonElement>('.voice-hold')!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  const oldCallback = mocks.events!;
  act(() => oldCallback({ kind: "partial", text: "说话" }));
  expect(h.props.onSend).not.toHaveBeenCalled();
  act(() => h.host.querySelector<HTMLButtonElement>('[aria-label="转为文字"]')!.click());
  act(() => oldCallback({ kind: "final", text: "说话" }));
  expect(h.host.querySelector("textarea")!.value).toBe("草稿\n说话");
  expect(h.props.onSend).not.toHaveBeenCalled();
  h.update({ draftKey: "two" });
  act(() => oldCallback({ kind: "final", text: "迟到" }));
  expect(h.host.querySelector("textarea")!.value).toBe(""); expect(h.props.onSend).not.toHaveBeenCalled();
});
it("a released voice message sends only recognized speech and preserves an existing text draft", async () => {
  const h = mount(); type(h.host, "已有草稿");
  act(() => h.host.querySelector<HTMLButtonElement>('.composer-voice')!.click()); await settle();
  const hold = h.host.querySelector<HTMLButtonElement>('.voice-hold')!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  vi.spyOn(Date, "now").mockReturnValueOnce(Date.now() + 400);
  act(() => { hold.dispatchEvent(new KeyboardEvent("keyup", { key: " ", bubbles: true })); });
  act(() => mocks.events?.({ kind: "final", text: "语音消息" }));
  expect(h.props.onSend).toHaveBeenCalledWith("语音消息", []);
  act(() => h.host.querySelector<HTMLButtonElement>('[aria-label="切换键盘输入"]')!.click());
  expect(h.host.querySelector("textarea")!.value).toBe("已有草稿");
  vi.restoreAllMocks();
});

it("keyboard right-arrow changes a held recording to edit and restores textarea focus", async () => {
  const h = mount();
  act(() => h.host.querySelector<HTMLButtonElement>('.composer-voice')!.click()); await settle();
  const hold = h.host.querySelector<HTMLButtonElement>('.voice-hold')!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true })); });
  vi.spyOn(Date, "now").mockReturnValue(Date.now() + 400);
  act(() => { hold.dispatchEvent(new KeyboardEvent("keyup", { key: " ", bubbles: true })); });
  act(() => mocks.events?.({ kind: "final", text: "检查后发送" })); await settle();
  expect(h.props.onSend).not.toHaveBeenCalled();
  expect(h.host.querySelector("textarea")!.value).toBe("检查后发送");
  expect(document.activeElement).toBe(h.host.querySelector("textarea"));
});

it("a hold on the hold-to-talk control swallows the browser's long-press default", async () => {
  // HG-172: the held touch belongs to the control. If `contextmenu` is allowed through, Android's
  // WebView and iOS run their own selection/sheet gesture on top of it and cancel the recording.
  const h = mount();
  act(() => h.host.querySelector<HTMLButtonElement>('.composer-voice')!.click()); await settle();
  const hold = h.host.querySelector<HTMLButtonElement>('.voice-hold')!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  const overlay = h.host.querySelector<HTMLElement>('.voice-overlay')!;
  expect(overlay).not.toBeNull();
  for (const target of [hold, overlay]) {
    const menu = new Event("contextmenu", { bubbles: true, cancelable: true });
    act(() => { target.dispatchEvent(menu); });
    expect(menu.defaultPrevented).toBe(true);
  }
});

it("an unanswered permission prompt times out and a late grant cannot enable recording", async () => {
  vi.useFakeTimers(); let grant!: () => void;
  mocks.microphone.mockImplementationOnce(() => new Promise<void>((resolve) => { grant = resolve; }));
  try {
    const h = mount();
    act(() => h.host.querySelector<HTMLButtonElement>('.composer-voice')!.click()); await settle();
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
    expect(h.host.textContent).toContain("HR-PERM-006");
    grant(); await settle();
    expect(h.host.querySelector('.voice-hold')).toBeNull();
    expect(h.host.querySelector<HTMLButtonElement>('.composer-voice')!.disabled).toBe(false);
  } finally { vi.useRealTimers(); }
});

it("one tap on the expanded composer's voice button switches to voice instead of collapsing (HG-174)", async () => {
  const h = mount();
  const area = type(h.host, "草稿");
  const mic = h.host.querySelector<HTMLButtonElement>(".composer-actions .composer-voice")!;
  // Safari, Firefox and iOS never move focus onto a pressed button, so the textarea blurs with no
  // relatedTarget before the click arrives — the same shape as a tap on the transcript. The press
  // is still ours, so the layout and this very button must survive it; otherwise the click lands on
  // an unmounted node and all the tap does is collapse the composer.
  act(() => { mic.dispatchEvent(new Event("pointerdown", { bubbles: true })); });
  act(() => area.blur());
  expect(h.host.querySelector(".composer.expanded")).not.toBeNull();
  expect(h.host.querySelector(".composer-actions .composer-voice")).toBe(mic);
  act(() => mic.click());
  await settle();
  // Straight to the voice bar: no collapsed keyboard row in between.
  expect(h.host.querySelector(".voice-hold")).not.toBeNull();
  expect(h.host.querySelector("textarea")).toBeNull();
});

it("a denied microphone leaves the expanded composer and its draft where the user had them (HG-174)", async () => {
  mocks.microphone.mockRejectedValueOnce(new Error("permission denied"));
  const h = mount();
  const area = type(h.host, "保留草稿");
  act(() => h.host.querySelector<HTMLButtonElement>(".composer-actions .composer-voice")!.click());
  await settle();
  expect(h.host.textContent).toContain("HR-PERM-006");
  expect(h.host.querySelector(".composer.expanded")).not.toBeNull();
  expect(area.value).toBe("保留草稿");
});

it("a blur no press of ours caused still collapses the composer (HG-174)", () => {
  const h = mount();
  const area = type(h.host, "草稿");
  expect(h.host.querySelector(".composer.expanded")).not.toBeNull();
  act(() => area.blur());
  expect(h.host.querySelector(".composer.expanded")).toBeNull();
});

it("a focused text field owns the first back step, so back exits the input before leaving (HG-180)", async () => {
  resetOverlays();
  // Drive history like the browser would: happy-dom does not deliver popstate for go().
  const entries: unknown[] = [null];
  let index = 0;
  vi.spyOn(history, "pushState").mockImplementation((state: unknown) => { entries.splice(index + 1); entries.push(state); index++; });
  vi.spyOn(history, "replaceState").mockImplementation((state: unknown) => { entries[index] = state; });
  vi.spyOn(history, "go").mockImplementation((delta?: number) => {
    index = Math.max(0, index + (delta ?? 0));
    queueMicrotask(() => window.dispatchEvent(new PopStateEvent("popstate", { state: entries[index] })));
  });
  vi.spyOn(history, "state", "get").mockImplementation(() => entries[index] ?? null);
  const back = async () => act(async () => { history.go(-1); await Promise.resolve(); await Promise.resolve(); });

  const h = mount();
  const area = type(h.host, "草稿");
  expect(document.activeElement).toBe(area);
  expect(h.host.querySelector(".composer.expanded")).not.toBeNull();
  // The open input owns one back step: the first system back exits the input and returns to the
  // browsing state — it must not leave the conversation. Only the next back leaves the chat.
  expect(overlayDepth()).toBe(1);
  await back();
  expect(h.host.querySelector(".composer.expanded")).toBeNull();
  expect(overlayDepth()).toBe(0);
});

it("an in-progress voice capture still owns the back step that cancels it (HG-180)", async () => {
  resetOverlays();
  const h = mount();
  act(() => h.host.querySelector<HTMLButtonElement>(".composer-voice")!.click()); await settle();
  expect(overlayDepth()).toBe(0);
  const hold = h.host.querySelector<HTMLButtonElement>(".voice-hold")!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  expect(overlayDepth()).toBe(1);
});
