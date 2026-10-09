import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { initialInbox } from "../app/inbox";
import { resetOverlays } from "../app/overlayHistory";
import { useWorkspace } from "../app/workspace";
import { ConversationWorkspace } from "./ConversationWorkspace";
import { AccountDrawer } from "./AccountDrawer";

vi.mock("./SessionList", () => ({ SessionList: () => <AccountDrawer onClose={() => {}}/> }));
let host: HTMLElement;
afterEach(() => { act(() => render(null, host)); host.remove(); resetOverlays(); localStorage.clear(); vi.restoreAllMocks(); });
function Probe() { const value = useWorkspace(); return <button onClick={value.toggleList} aria-label="切换会话栏">聊天</button>; }
function mount() {
  localStorage.clear();
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 1000 });
  host = document.createElement("div"); document.body.append(host);
  const app = { t: (zh: string) => zh, language: "zh", features: new Set(), device: null, inbox: initialInbox,
    themeMode: "system", languagePreference: "system", fontSize: "standard", flash: vi.fn(), client: {} } as unknown as AppContextValue;
  act(() => render(<AppContext.Provider value={app}><ConversationWorkspace route={{ name: "chat", sessionId: "a" }}><Probe/></ConversationWorkspace></AppContext.Provider>, host));
}
function button(text: string) { return [...host.querySelectorAll<HTMLButtonElement>("button")].find(node => node.getAttribute("aria-label") === text || node.textContent === text)!; }
it("keyboard and pointer resize clamp both panes, reset, and retain preference through collapse", () => {
  mount();
  const divider = host.querySelector<HTMLElement>('[role="separator"]')!;
  const width = () => Number(divider.getAttribute("aria-valuenow"));
  expect(width()).toBe(300);
  act(() => { divider.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true })); });
  expect(width()).toBe(316);
  act(() => { divider.dispatchEvent(new KeyboardEvent("keydown", { key: "End", bubbles: true })); });
  expect(width()).toBe(480);
  act(() => button("切换会话栏").click());
  expect(host.querySelector('.conversation-workspace')?.getAttribute("data-split")).toBe("false");
  act(() => button("切换会话栏").click());
  expect(width()).toBe(480);
  act(() => { divider.dispatchEvent(new KeyboardEvent("keydown", { key: "Home", bubbles: true })); });
  expect(width()).toBe(300);
  act(() => {
    divider.dispatchEvent(new PointerEvent("pointerdown", { pointerId: 4, isPrimary: true, button: 0, clientX: 300, bubbles: true }));
    divider.dispatchEvent(new PointerEvent("pointermove", { pointerId: 4, clientX: 2000, bubbles: true }));
    divider.dispatchEvent(new PointerEvent("pointerup", { pointerId: 4, bubbles: true }));
  });
  expect(width()).toBe(480);
  expect(localStorage.getItem("hermes-go.workspace.widthRem")).toBe("30");
  act(() => button("恢复默认栏宽").click());
  expect(width()).toBe(300);
});
it("layout choice cancels until Save and single-column mode leaves the chat mounted", () => {
  mount();
  const chat = button("切换会话栏");
  act(() => button("大屏布局").click());
  act(() => button("始终单栏").click());
  act(() => button("关闭").click());
  expect(host.querySelector('.conversation-workspace')?.getAttribute("data-split")).toBe("true");
  act(() => button("大屏布局").click());
  act(() => button("始终单栏").click());
  act(() => button("保存").click());
  expect(host.querySelector('.conversation-workspace')?.getAttribute("data-split")).toBe("false");
  expect(button("切换会话栏")).toBe(chat);
  expect(localStorage.getItem("hermes-go.workspace.mode")).toBe("single");
});
