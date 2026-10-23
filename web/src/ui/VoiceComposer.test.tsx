import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { VoiceComposer, type VoiceComposerProps } from "./VoiceComposer";

const hosts: HTMLElement[] = [];
afterEach(() => {
  hosts.splice(0).forEach((host) => { act(() => render(null, host)); host.remove(); });
  vi.restoreAllMocks(); vi.unstubAllGlobals();
});
function mount(prepare?: (hold: HTMLButtonElement) => void) {
  const vibrate = vi.fn((_duration: number) => true);
  vi.stubGlobal("navigator", { vibrate });
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const props: VoiceComposerProps = { t: (zh) => zh, phase: "idle", recording: false, feedbackEnabled: true, text: "", disabled: false,
    onBegin: () => update({ phase: "held" }), onRelease: vi.fn(() => update({ phase: "waiting", recording: false })),
    onInterrupted: vi.fn(() => update({ phase: "idle", recording: false })), onCancelWait: vi.fn() };
  const update = (patch: Partial<VoiceComposerProps>) => { Object.assign(props, patch); act(() => render(<VoiceComposer {...props} />, host)); };
  update({});
  const hold = host.querySelector<HTMLButtonElement>(".voice-hold")!;
  prepare?.(hold);
  const pointer = (type: string, x = 180, y = 600) => act(() => {
    hold.dispatchEvent(new PointerEvent(type, { pointerId: 1, pointerType: "touch", button: 0, clientX: x, clientY: y, bubbles: true, cancelable: true }));
  });
  pointer("pointerdown");
  const targets = host.querySelectorAll<HTMLButtonElement>(".voice-target");
  targets.forEach((target, index) => vi.spyOn(target, "getBoundingClientRect").mockReturnValue({ left: index ? 248 : 48, top: 468, width: 64, height: 64 } as DOMRect));
  return { host, props, update, pointer, vibrate };
}
it("commits highlight, copy and haptics together, without vibrating on edge jitter or waiting", () => {
  const h = mount(); expect(h.vibrate).not.toHaveBeenCalled();
  h.update({ recording: true }); expect(h.vibrate).toHaveBeenLastCalledWith(25);
  h.pointer("pointermove", 280, 543);
  expect(h.host.querySelector('[aria-label="转为文字"]')?.classList.contains("selected")).toBe(true);
  expect(h.host.textContent).toContain("松手转文字"); expect(h.vibrate).toHaveBeenLastCalledWith(30);
  h.pointer("pointermove", 280, 545); h.pointer("pointermove", 280, 543);
  expect(h.vibrate).toHaveBeenCalledTimes(2);
  h.pointer("pointermove", 280, 557); expect(h.vibrate).toHaveBeenLastCalledWith(10);
  expect(h.host.textContent).toContain("松手发送");
  h.pointer("pointermove", 80, 500); expect(h.vibrate).toHaveBeenLastCalledWith(30);
  expect(h.host.textContent).toContain("松手取消");
  h.pointer("pointermove", 280, 500); expect(h.vibrate).toHaveBeenLastCalledWith(30);
  expect(h.host.textContent).toContain("松手转文字");
  vi.spyOn(Date, "now").mockReturnValue(Date.now() + 400);
  h.pointer("pointerup", 280, 500); expect(h.props.onRelease).toHaveBeenCalledWith("edit");
  expect(h.vibrate).toHaveBeenLastCalledWith(0);
  const count = h.vibrate.mock.calls.length;
  h.pointer("pointermove", 80, 500); expect(h.vibrate).toHaveBeenCalledTimes(count);
});
it.each(["pagehide", "hidden", "unmount", "idle", "off"])("cancels pending browser vibration on %s", (reason) => {
  const h = mount(); h.update({ recording: true });
  if (reason === "pagehide") act(() => { window.dispatchEvent(new Event("pagehide")); });
  if (reason === "hidden") {
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    act(() => { document.dispatchEvent(new Event("visibilitychange")); });
  }
  if (reason === "unmount") act(() => render(null, h.host));
  if (reason === "idle") h.update({ phase: "idle", recording: false });
  if (reason === "off") h.update({ feedbackEnabled: false });
  expect(h.vibrate.mock.calls.map(([n]) => n)).toEqual([25, 0]);
});
it.each(["missing", "false", "throw", "disabled"])("keeps gestures and visual cues usable when feedback is %s", (reason) => {
  const h = mount();
  if (reason === "missing") vi.stubGlobal("navigator", {});
  if (reason === "false") h.vibrate.mockReturnValue(false);
  if (reason === "throw") h.vibrate.mockImplementation(() => { throw new Error("blocked"); });
  h.update({ recording: true, feedbackEnabled: reason !== "disabled" });
  h.pointer("pointermove", 80, 500);
  expect(h.host.querySelector('[aria-label="取消录音"]')?.classList.contains("selected")).toBe(true);
  expect(h.host.textContent).toContain("松手取消");
  h.pointer("pointercancel"); expect(h.props.onInterrupted).toHaveBeenCalledOnce();
  expect(h.props.onRelease).not.toHaveBeenCalled();
});

// HG-231: dispatch to the actual release target, not always the original hold button.
function dispatchPointer(target: EventTarget, type: string, pointerId = 1, x = 180, y = 600, extra: PointerEventInit = {}) {
  act(() => { target.dispatchEvent(new PointerEvent(type, { pointerId, button: 0, clientX: x, clientY: y, bubbles: true, ...extra })); });
}
it.each([[180, 600, "send"], [80, 500, "cancel"], [280, 500, "edit"]] as const)(
  "uses the final outside-button release position %s,%s for %s exactly once", (x, y, action) => {
    const h = mount(); vi.spyOn(Date, "now").mockReturnValue(Date.now() + 400);
    const overlay = h.host.querySelector(".voice-overlay")!;
    // No intermediate pointermove: the last coordinates still determine the action.
    dispatchPointer(overlay, "pointerup", 1, x, y);
    dispatchPointer(document.body, "pointerup", 1, x, y);
    dispatchPointer(h.host.querySelector(".voice-hold")!, "lostpointercapture");
    expect(h.props.onRelease).toHaveBeenCalledExactlyOnceWith(action);
    expect(h.props.onInterrupted).not.toHaveBeenCalled();
  });
it("tracks retargeted movement and ignores another finger's up, cancel and capture loss", () => {
  const h = mount(), overlay = h.host.querySelector(".voice-overlay")!;
  dispatchPointer(overlay, "pointermove", 1, 280, 500);
  expect(h.host.textContent).toContain("松手转文字");
  for (const type of ["pointerup", "pointercancel", "lostpointercapture"]) dispatchPointer(overlay, type, 2);
  expect(h.props.onRelease).not.toHaveBeenCalled(); expect(h.props.onInterrupted).not.toHaveBeenCalled();
});
it.each(["pointercancel", "lostpointercapture", "blur"])("interrupts %s once instead of sending or discarding", (type) => {
  const h = mount();
  act(() => {
    if (type === "blur") window.dispatchEvent(new Event(type));
    else document.body.dispatchEvent(new PointerEvent(type, { pointerId: 1, bubbles: true }));
  });
  dispatchPointer(document.body, "pointerup");
  expect(h.props.onInterrupted).toHaveBeenCalledOnce(); expect(h.props.onRelease).not.toHaveBeenCalled();
});
it.each(["cancel", "edit"])("keeps the explicit %s recovery usable even without gesture bookkeeping", (action) => {
  const h = mount(); h.update({ phase: "idle" }); h.update({ phase: "held" });
  act(() => h.host.querySelector<HTMLButtonElement>(action === "cancel" ? '[aria-label="取消录音"]' : '[aria-label="转为文字"]')!.click());
  expect(h.props.onRelease).toHaveBeenCalledExactlyOnceWith(action);
});
it("cleans up global handlers when the control unmounts", () => {
  const h = mount(); act(() => render(null, h.host));
  dispatchPointer(document.body, "pointerup"); act(() => { window.dispatchEvent(new Event("blur")); });
  expect(h.props.onRelease).not.toHaveBeenCalled(); expect(h.props.onInterrupted).not.toHaveBeenCalled();
});
it("still releases outside when pointer capture throws", () => {
  const capture = vi.fn(() => { throw new DOMException("inactive pointer", "NotFoundError"); });
  const h = mount(hold => { hold.setPointerCapture = capture; });
  expect(capture).toHaveBeenCalledWith(1);
  vi.spyOn(Date, "now").mockReturnValue(Date.now() + 400);
  dispatchPointer(document.body, "pointerup");
  expect(h.props.onRelease).toHaveBeenCalledExactlyOnceWith("send");
});
function dispatchTouch(target: EventTarget, type: string, identifier: number, x = 180, y = 600) {
  const event = new Event(type, { bubbles: true });
  Object.defineProperty(event, "changedTouches", { value: [{ identifier, clientX: x, clientY: y }] });
  act(() => { target.dispatchEvent(event); });
}
it.each(["touchend", "touchcancel"])("uses matching %s when WebKit omits the pointer end", (type) => {
  const h = mount(); dispatchTouch(h.host.querySelector(".voice-hold")!, "touchstart", 42);
  dispatchTouch(document.body, type, 99);
  expect(h.props.onRelease).not.toHaveBeenCalled(); expect(h.props.onInterrupted).not.toHaveBeenCalled();
  vi.spyOn(Date, "now").mockReturnValue(Date.now() + 400);
  dispatchTouch(document.body, type, 42, 280, 500);
  if (type === "touchend") expect(h.props.onRelease).toHaveBeenCalledExactlyOnceWith("edit");
  else expect(h.props.onInterrupted).toHaveBeenCalledOnce();
  dispatchPointer(document.body, "pointerup");
  expect(vi.mocked(h.props.onRelease).mock.calls.length + vi.mocked(h.props.onInterrupted).mock.calls.length).toBe(1);
});
it("interrupts a mouse drag when a later move reports that its button is already up", () => {
  const h = mount();
  dispatchPointer(document.body, "pointermove", 1, 180, 600, { pointerType: "mouse", buttons: 0 });
  expect(h.props.onInterrupted).toHaveBeenCalledOnce(); expect(h.props.onRelease).not.toHaveBeenCalled();
});
it("owns only the initiating keyboard key and releases even after the target changes", () => {
  const h = mount(); h.pointer("pointercancel"); vi.mocked(h.props.onInterrupted).mockClear();
  const hold = h.host.querySelector(".voice-hold")!;
  act(() => { hold.dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true })); });
  act(() => { document.body.dispatchEvent(new KeyboardEvent("keyup", { key: "Enter", bubbles: true })); });
  expect(h.props.onRelease).not.toHaveBeenCalled();
  vi.spyOn(Date, "now").mockReturnValue(Date.now() + 400);
  act(() => { document.body.dispatchEvent(new KeyboardEvent("keyup", { key: " ", bubbles: true })); });
  expect(h.props.onRelease).toHaveBeenCalledExactlyOnceWith("send");
});

it("starts the visible clock with real capture and shows the last 15 seconds without releasing again", async () => {
  vi.useFakeTimers();
  try {
    const h = mount(); await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(h.host.textContent).toContain("00:00 / 05:00");
    h.update({ recording: true }); await act(async () => { await vi.advanceTimersByTimeAsync(285_000); });
    expect(h.host.textContent).toContain("04:45 / 05:00");
    expect(h.host.textContent).toContain("还可录 15 秒，到时转为草稿");
    h.update({ phase: "waiting", recording: false, limited: true });
    expect(h.host.textContent).toContain("05:00 / 05:00"); h.pointer("pointerup"); h.pointer("pointercancel");
    expect(h.props.onRelease).not.toHaveBeenCalled();
  } finally { vi.useRealTimers(); }
});
