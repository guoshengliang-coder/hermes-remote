import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { appError } from "../errors";
import { SessionActionSheet } from "./SessionActions";

let host: HTMLDivElement | undefined;
afterEach(() => {
  if (!host) return;
  act(() => render(null, host!));
  host.remove();
  host = undefined;
});

function fixture(entry: "archive" | "menu" = "archive") {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const work = new Promise<void>((done, fail) => { resolve = done; reject = fail; });
  const write = vi.fn(() => work);
  const onChanged = vi.fn();
  const onClose = vi.fn();
  const flash = vi.fn();
  const session = { id: "s1", title: "Original", profile: "work" };
  const context = {
    t: (_zh: string, en: string) => en,
    language: "en", device: { deviceId: "mac-a" }, features: new Set(["session-manage"]),
    client: { updateSession: write, deviceApi: async () => ({ sessions: [] }), onSignedOut: () => () => {} },
    flash, isPinned: () => false, setSessions: vi.fn(), sessions: [session],
  } as unknown as AppContextValue;
  const node = document.createElement("div");
  host = node;
  document.body.append(node);
  const open = (deviceId = "mac-a") => act(() => render(
    <AppContext.Provider value={{ ...context, device: { ...context.device!, deviceId } }}>
      <SessionActionSheet session={session} start={entry === "archive" ? "archive" : undefined} onClose={onClose} onChanged={onChanged} />
    </AppContext.Provider>,
    node,
  ));
  open();
  const click = (selector: string) => act(() => node.querySelector<HTMLElement>(selector)!.click());
  return { node, open, click, write, resolve, reject, onChanged, onClose, flash };
}

it("reports the pending action immediately and holds the sheet until a single acknowledgement", async () => {
  const ui = fixture();
  const confirm = ui.node.querySelector<HTMLButtonElement>(".primary-button")!;
  act(() => { confirm.click(); confirm.click(); });
  expect(ui.write).toHaveBeenCalledTimes(1);
  expect(ui.node.querySelector('[role="status"]')?.textContent).toBe("Archiving…");
  expect(confirm.disabled).toBe(true);
  ui.click(".sheet-scrim");
  act(() => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); });
  expect(ui.onClose).not.toHaveBeenCalled();
  await act(async () => ui.resolve());
  expect(ui.onChanged).toHaveBeenCalledWith("archived");
  expect(ui.onClose).toHaveBeenCalledOnce();
});

it("keeps the draft title and exposes read-only recovery when rename is unconfirmed", async () => {
  const ui = fixture("menu");
  act(() => [...ui.node.querySelectorAll<HTMLButtonElement>("button")].find((e) => e.textContent === "Rename")!.click());
  const input = ui.node.querySelector("input")!;
  act(() => { input.value = "Draft name"; input.dispatchEvent(new Event("input", { bubbles: true })); });
  ui.click(".primary-button");
  await act(async () => ui.reject(appError("HR-SESS-027")));
  expect(ui.node.querySelector("input")?.value).toBe("Draft name");
  expect(ui.node.textContent).toContain("Refresh state");
  expect(ui.node.querySelector<HTMLButtonElement>(".primary-button")?.disabled).toBe(true);
  expect(ui.onClose).not.toHaveBeenCalled();
});

it("ignores an older Mac's acknowledgement when the route changes", async () => {
  const ui = fixture();
  ui.click(".primary-button");
  ui.open("mac-b");
  await act(async () => ui.resolve());
  expect(ui.onChanged).not.toHaveBeenCalled();
  expect(ui.flash).not.toHaveBeenCalled();
  expect(ui.node.querySelector(".primary-button")?.textContent).toBe("Archive");
});
