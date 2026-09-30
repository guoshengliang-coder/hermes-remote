import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { Composer, type ComposerProps } from "./Composer";
import { AppContext, type AppContextValue } from "../app/store";
import type { VoiceEvent } from "../chat/voiceSession";
const mocks = vi.hoisted(() => ({ events: null as null | ((e: VoiceEvent) => void), finish: vi.fn(), cancel: vi.fn(() => "半截"), microphone: vi.fn(async () => {}) }));
vi.mock("../chat/voiceCapture", () => ({ voiceCaptureSupported: () => true, allowMicrophone: mocks.microphone }));
vi.mock("../chat/voiceSession", () => ({ BrowserVoiceSession: class {
  constructor(options: { onEvent: (e: VoiceEvent) => void }) { mocks.events = options.onEvent; }
  start() {} finish() { mocks.finish(); mocks.events?.({ kind: "waiting" }); } cancel() { return mocks.cancel(); }
} }));
const hosts: HTMLElement[] = [];
afterEach(() => { hosts.forEach((host) => { act(() => render(null, host)); host.remove(); }); hosts.length = 0; vi.clearAllMocks(); vi.restoreAllMocks(); mocks.events = null; localStorage.clear(); });
const context = { t: (zh: string) => zh, language: "zh", device: { deviceId: "mac" }, client: { settled: async () => {} }, features: new Set(["voice-input"]) } as unknown as AppContextValue;
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
