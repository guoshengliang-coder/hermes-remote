import { render } from "preact";
import { useState } from "preact/hooks";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import type { SessionListItem } from "../hermes/types";
import { SessionActionSheet } from "./SessionActions";
import { SessionRow } from "./SessionRow";

// History navigation is covered by overlayHistory.test.ts. Here the real row and action sheet
// receive a retargeted compatibility click; the DOM runner cannot generate iOS's native gesture.
vi.mock("../app/useBackClose", () => ({ useBackClose: () => {}, BackClose: () => null }));

let host: HTMLDivElement;
beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers();
  host = document.createElement("div");
  document.body.append(host);
});
afterEach(() => {
  act(() => render(null, host));
  // End any pending gesture guard without exporting a test-only reset API.
  document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  host.remove();
  vi.useRealTimers();
});

function pointer(target: EventTarget, type: string, options: PointerEventInit = {}) {
  const event = new PointerEvent(type, {
    bubbles: true, cancelable: true, pointerType: "touch", pointerId: 1,
    isPrimary: true, button: 0, clientX: 50, clientY: 600, ...options,
  });
  act(() => { target.dispatchEvent(event); });
  return event;
}
function click(target: EventTarget) {
  const event = new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 });
  act(() => { target.dispatchEvent(event); });
  return event;
}

function mount(kind: "chat" | "bot" | "archived" = "chat", options: { removeRow?: boolean; holdable?: boolean } = {}) {
  const open = vi.fn(), close = vi.fn(), held = vi.fn(), pin = vi.fn();
  const update = vi.fn(async () => ({}));
  const session: SessionListItem = { id: "s1", title: "A conversation", source: kind === "bot" ? "telegram" : "web", archived: kind === "archived" };
  const context = {
    t: (zh: string) => zh, language: "zh", device: { deviceId: "mac-a" },
    client: { updateSession: update, deviceApi: vi.fn(async () => ({ sessions: [session] })) },
    features: new Set(["session-manage", "session-delete", "workspace-move"]),
    sessions: [session], isPinned: () => false, togglePin: pin, flash: vi.fn(), setSessions: vi.fn(),
  } as unknown as AppContextValue;
  function Fixture() {
    const [shown, setShown] = useState(false);
    return <AppContext.Provider value={context}>
      {!(shown && options.removeRow) ? <SessionRow session={session} now={Date.now()} archived={kind === "archived"}
        bot={kind === "bot" ? { statusLine: null } : undefined} onOpen={open}
        onLongPress={options.holdable === false ? undefined : () => { held(); setShown(true); }} /> : null}
      {shown ? <SessionActionSheet session={session} archived={kind === "archived"}
        onClose={() => { close(); setShown(false); }} /> : null}
    </AppContext.Provider>;
  }
  act(() => render(<Fixture />, host));
  const row = host.querySelector<HTMLButtonElement>(".session-row")!;
  const hold = () => {
    pointer(row, "pointerdown");
    act(() => { vi.advanceTimersByTime(500); });
    expect(held).toHaveBeenCalledOnce();
    expect(host.querySelector(".picker-sheet")).not.toBeNull();
  };
  return { row, hold, open, close, held, pin, update };
}

it.each(["chat", "bot", "archived"] as const)("a %s row's release cannot activate a newly covered menu item", (kind) => {
  const s = mount(kind); s.hold();
  pointer(s.row, "pointerup");
  const event = click(host.querySelector(".sheet-action")!);
  expect(event.defaultPrevented).toBe(true);
  expect(s.pin).not.toHaveBeenCalled(); expect(s.update).not.toHaveBeenCalled();
  expect(s.open).not.toHaveBeenCalled(); expect(s.close).not.toHaveBeenCalled();
  expect(host.querySelector(".picker-sheet")).not.toBeNull();
});

it.each([".session-row", ".sheet-scrim"])("the release click is swallowed on %s too", (selector) => {
  const s = mount(); s.hold(); pointer(s.row, "pointerup");
  expect(click(host.querySelector(selector)!).defaultPrevented).toBe(true);
  expect(s.open).not.toHaveBeenCalled(); expect(s.close).not.toHaveBeenCalled();
});

it("keeps blocking while held and after a slow release instead of using a fixed timeout", () => {
  const s = mount(); s.hold(); act(() => { vi.advanceTimersByTime(5_000); });
  expect(click(host.querySelector(".sheet-action")!).defaultPrevented).toBe(true);
  pointer(s.row, "pointerup"); act(() => { vi.advanceTimersByTime(5_000); });
  expect(click(host.querySelector(".sheet-action")!).defaultPrevented).toBe(true);
  expect(s.pin).not.toHaveBeenCalled();
});

it("survives the source row unmounting while the sheet stays open", () => {
  const s = mount("chat", { removeRow: true }); s.hold();
  expect(host.querySelector(".session-row")).toBeNull();
  pointer(document, "pointerup");
  expect(click(host.querySelector(".sheet-action")!).defaultPrevented).toBe(true);
  expect(s.pin).not.toHaveBeenCalled();
});

it("allows the next independent tap on the menu immediately", () => {
  const s = mount(); s.hold(); pointer(s.row, "pointerup");
  const action = host.querySelector(".sheet-action")!;
  pointer(action, "pointerdown", { pointerId: 2 }); pointer(action, "pointerup", { pointerId: 2 }); click(action);
  expect(s.pin).toHaveBeenCalledOnce(); expect(s.close).toHaveBeenCalledOnce();
});

it("allows a new tap on the scrim even when the long press generated no click", () => {
  const s = mount(); s.hold(); pointer(s.row, "pointerup");
  const scrim = host.querySelector(".sheet-scrim")!;
  pointer(scrim, "pointerdown"); pointer(scrim, "pointerup"); click(scrim);
  expect(s.close).toHaveBeenCalledOnce(); expect(s.pin).not.toHaveBeenCalled();
});

it("preserves keyboard and assistive activation after a long press", () => {
  const s = mount(); s.hold(); pointer(s.row, "pointerup");
  act(() => host.querySelector<HTMLButtonElement>(".sheet-action")!.click());
  expect(s.pin).toHaveBeenCalledOnce(); expect(s.close).toHaveBeenCalledOnce();
  act(() => { s.row.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true })); });
  act(() => s.row.click());
  expect(s.open).toHaveBeenCalledOnce();
});

it("blocks a touch PointerEvent click even with a zero click count", () => {
  const s = mount(); s.hold(); pointer(s.row, "pointerup");
  expect(pointer(host.querySelector(".sheet-action")!, "click", { detail: 0 }).defaultPrevented).toBe(true);
  expect(s.pin).not.toHaveBeenCalled();
});

it("a second finger does not release the opening gesture's click guard", () => {
  const s = mount(); s.hold();
  const action = host.querySelector(".sheet-action")!;
  pointer(action, "pointerdown", { pointerId: 2, isPrimary: false });
  pointer(s.row, "pointerup");
  expect(click(action).defaultPrevented).toBe(true);
  expect(s.pin).not.toHaveBeenCalled();
});

it("a browser context menu following the hold does not open the sheet twice", () => {
  const s = mount(); s.hold();
  act(() => { s.row.dispatchEvent(new MouseEvent("contextmenu", { bubbles: true, cancelable: true })); });
  expect(s.held).toHaveBeenCalledOnce();
  pointer(s.row, "pointerup");
  expect(click(host.querySelector(".sheet-action")!).defaultPrevented).toBe(true);
});

it("a fresh right click can open a menu after an earlier long press", () => {
  const s = mount(); s.hold(); pointer(s.row, "pointerup");
  pointer(s.row, "pointerdown", { pointerType: "mouse", button: 2 });
  act(() => { s.row.dispatchEvent(new MouseEvent("contextmenu", { bubbles: true, cancelable: true, button: 2 })); });
  expect(s.held).toHaveBeenCalledTimes(2);
});

it("a normal short tap opens the conversation", () => {
  const s = mount(); pointer(s.row, "pointerdown"); act(() => { vi.advanceTimersByTime(499); });
  pointer(s.row, "pointerup"); click(s.row); act(() => { vi.advanceTimersByTime(1_000); });
  expect(s.open).toHaveBeenCalledOnce(); expect(s.held).not.toHaveBeenCalled();
});

it.each(["pointercancel", "pointerleave", "move"])("%s cancels a pending hold", (type) => {
  const s = mount(); pointer(s.row, "pointerdown");
  pointer(s.row, type === "move" ? "pointermove" : type, { clientY: type === "move" ? 611 : 600 });
  act(() => { vi.advanceTimersByTime(1_000); }); expect(s.held).not.toHaveBeenCalled();
});

it("unmount cancels a pending timer", () => {
  const s = mount(); pointer(s.row, "pointerdown"); act(() => render(null, host));
  act(() => { vi.advanceTimersByTime(1_000); }); expect(s.held).not.toHaveBeenCalled();
});

it("right click opens the menu and the next mouse click can choose an action", () => {
  const s = mount(); pointer(s.row, "pointerdown", { pointerType: "mouse", button: 2 });
  const menu = new MouseEvent("contextmenu", { bubbles: true, cancelable: true, button: 2 });
  act(() => { s.row.dispatchEvent(menu); }); expect(menu.defaultPrevented).toBe(true);
  expect(s.held).toHaveBeenCalledOnce();
  const action = host.querySelector(".sheet-action")!;
  pointer(action, "pointerdown", { pointerType: "mouse" }); pointer(action, "pointerup", { pointerType: "mouse" }); click(action);
  expect(s.pin).toHaveBeenCalledOnce();
});

it("rows without actions retain ordinary clicks", () => {
  const s = mount("chat", { holdable: false }); pointer(s.row, "pointerdown");
  act(() => { vi.advanceTimersByTime(1_000); }); pointer(s.row, "pointerup"); click(s.row);
  expect(s.open).toHaveBeenCalledOnce(); expect(s.held).not.toHaveBeenCalled();
});
