import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { VoiceComposer, type VoiceComposerProps } from "./VoiceComposer";

const hosts: HTMLElement[] = [];
afterEach(() => {
  hosts.splice(0).forEach((host) => { act(() => render(null, host)); host.remove(); });
  vi.restoreAllMocks(); vi.unstubAllGlobals();
});
function mount() {
  const vibrate = vi.fn((_duration: number) => true);
  vi.stubGlobal("navigator", { vibrate });
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const props: VoiceComposerProps = { t: (zh) => zh, phase: "idle", recording: false, feedbackEnabled: true, text: "", disabled: false,
    onBegin: () => update({ phase: "held" }), onRelease: vi.fn(() => update({ phase: "waiting", recording: false })), onCancelWait: vi.fn() };
  const update = (patch: Partial<VoiceComposerProps>) => { Object.assign(props, patch); act(() => render(<VoiceComposer {...props} />, host)); };
  update({});
  const hold = host.querySelector<HTMLButtonElement>(".voice-hold")!;
  const pointer = (type: string, x = 180, y = 600) => act(() => {
    hold.dispatchEvent(new PointerEvent(type, { pointerId: 1, button: 0, clientX: x, clientY: y, bubbles: true, cancelable: true }));
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
  h.pointer("pointerup"); expect(h.props.onRelease).toHaveBeenCalledWith("edit");
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
  h.pointer("pointercancel"); expect(h.props.onRelease).toHaveBeenCalledWith("cancel");
});
