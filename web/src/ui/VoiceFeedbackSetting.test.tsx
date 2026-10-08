import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { resetOverlays } from "../app/overlayHistory";
import { AppContext, type AppContextValue } from "../app/store";
import { readVoiceFeedback, saveVoiceFeedback } from "../app/voiceFeedback";
import { AccountDrawer } from "./AccountDrawer";
const hosts: HTMLElement[] = [];
beforeEach(() => resetOverlays());
afterEach(() => {
  hosts.splice(0).forEach((host) => { act(() => render(null, host)); host.remove(); });
  localStorage.clear(); vi.unstubAllGlobals(); resetOverlays();
});
function mount(supported = true, english = false) {
  vi.stubGlobal("navigator", supported ? { vibrate: () => false } : {});
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const saved = vi.fn((choice) => { saveVoiceFeedback(choice); app.voiceFeedback = choice; update(); });
  const close = vi.fn();
  const app = { t: (zh: string, en: string) => english ? en : zh, features: new Set(), device: null, voiceFeedback: readVoiceFeedback(), setVoiceFeedback: saved } as unknown as AppContextValue;
  const update = () => act(() => render(<AppContext.Provider value={{ ...app }}><AccountDrawer onClose={close} /></AppContext.Provider>, host));
  update();
  const button = (label: string) => {
    const buttons = [...host.querySelectorAll<HTMLButtonElement>("button")];
    return buttons.find((node) => node.getAttribute("role") === "radio" && node.getAttribute("aria-label") === label)
      ?? buttons.find((node) => node.getAttribute("aria-label") === label || node.textContent === label)!;
  };
  return { host, saved, close, button };
}
it("defaults on and keeps choices pending until Save, including cancel and reopening", () => {
  const h = mount(); const row = h.button("语音震动"); expect(row.textContent).toContain("开启");
  act(() => row.click()); act(() => h.button("关闭").click());
  expect(h.saved).not.toHaveBeenCalled(); expect(readVoiceFeedback()).toBe("on");
  expect(h.host.querySelector('[role="radio"][aria-label="开启"]')?.textContent).toContain("当前使用");
  act(() => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); });
  expect(h.close).not.toHaveBeenCalled();
  act(() => h.button("语音震动").click());
  expect(h.host.querySelector('[role="radio"][aria-label="开启"]')?.getAttribute("aria-checked")).toBe("true");
  act(() => h.button("关闭").click()); act(() => h.button("保存").click());
  expect(h.saved).toHaveBeenCalledWith("off"); expect(readVoiceFeedback()).toBe("off");
  expect(h.button("语音震动").textContent).toContain("关闭");
  act(() => h.button("语音震动").click()); act(() => h.button("开启").click());
  act(() => h.host.querySelector<HTMLElement>(".drawer-theme-scrim")!.click());
  expect(readVoiceFeedback()).toBe("off");
});
it.each([false, true])("hides missing vibration support in either language (English %s)", (english) => {
  const h = mount(false, english);
  expect(h.host.querySelector(`button[aria-label="${english ? "Voice vibration" : "语音震动"}"]`)).toBeNull();
  expect(h.host.querySelector(".drawer-theme-sheet")).toBeNull();
});
it.each(["close", "back"])("cancels a pending change through %s and keeps the drawer open", (action) => {
  const h = mount(); const drawerState = history.state;
  act(() => h.button("语音震动").click()); act(() => h.button("关闭").click());
  if (action === "close") act(() => h.host.querySelector<HTMLButtonElement>(".drawer-theme-close")!.click());
  else act(() => { history.replaceState(drawerState, ""); window.dispatchEvent(new PopStateEvent("popstate")); });
  expect(h.saved).not.toHaveBeenCalled(); expect(h.close).not.toHaveBeenCalled();
  expect(h.host.querySelector(".drawer-theme-sheet")).toBeNull(); expect(readVoiceFeedback()).toBe("on");
});
