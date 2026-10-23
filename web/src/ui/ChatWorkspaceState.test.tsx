import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { initialInbox } from "../app/inbox";
import { clearConversationMemory } from "../app/conversationMemory";
import { WorkspaceContext } from "../app/workspace";
import { resetOverlays } from "../app/overlayHistory";
import type { ChatSessionOptions } from "../chat/session";
import { ChatPage } from "./ChatPage";
import { useState } from "preact/hooks";
import { StartupContext } from "../startup/readiness";
import { appError } from "../errors";

const fixture = vi.hoisted(() => ({ connections: [] as { id: string | null; options: ChatSessionOptions; dispose: ReturnType<typeof vi.fn> }[], deferStart: false, deferPaint: false, variedHeights: false, paints: [] as (() => void)[] }));
vi.mock("./Markdown", async importOriginal => {
  const original = await importOriginal<typeof import("./Markdown")>();
  function Deferred({ source }: { source: string }) {
    const [busy, setBusy] = useState(true);
    fixture.paints.push(() => setBusy(false));
    return <div class="markdown" aria-busy={busy}>{busy ? "" : source}</div>;
  }
  return { ...original, Markdown: (props: Parameters<typeof original.Markdown>[0]) => fixture.deferPaint ? <Deferred {...props}/> : <original.Markdown {...props}/> };
});
vi.mock("../chat/session", () => ({ ChatSession: class {
  storedSessionId: string | null;
  dispose = vi.fn();
  loadFullHistory = vi.fn(async () => [
    { id: 1, role: "user", content: "生成报告" },
    { id: 2, role: "assistant", content: "MEDIA:/tmp/early.md" },
    { id: 3, role: "user", content: "继续" },
    { id: 4, role: "assistant", content: "新的回答" },
  ]);
  constructor(private options: ChatSessionOptions) { this.storedSessionId = options.storedSessionId; fixture.connections.push({ id: this.storedSessionId, options, dispose: this.dispose }); }
  start() {
    if (fixture.deferStart) return;
    const base = this.storedSessionId === "a" ? 0 : 100;
    this.options.dispatch({ type: "history", rows: Array.from({ length: 24 }, (_, i) => ({ id: base + i + 1, role: i % 2 ? "assistant" : "user", content: `${this.storedSessionId} 文字 ${i}`, timestamp: i + 1 })) });
    this.options.dispatch({ type: "connection", state: "ready" });
  }
} }));
let host: HTMLElement;
const app = { t: (zh: string) => zh, language: "zh", account: { id: "account" }, device: { deviceId: "mac" }, client: {}, features: new Set(),
  sessions: [{ id: "a", title: "A" }, { id: "b", title: "B" }], inbox: initialInbox, markSeen: () => {}, reportLiveQuestion: () => {},
  isPinned: () => false, chatSearchSeed: null, setChatSearchSeed: () => {} } as unknown as AppContextValue;
let width: number;
const startupReport = vi.fn();
async function draw(id: string) {
  await act(async () => {
    render(<StartupContext.Provider value={{ target: "chat", report: startupReport }}><AppContext.Provider value={app}><WorkspaceContext.Provider value={{ mode: "auto", setMode: () => {}, eligible: true, split: width >= 624, width, listWidth: 300, selectedId: id, toggleList: () => {}, resetWidth: () => {} }}>
      <ChatPage sessionId={id}/>
    </WorkspaceContext.Provider></AppContext.Provider></StartupContext.Provider>, host);
  });
}
beforeEach(() => {
  width = 1000; fixture.connections.length = 0; fixture.deferPaint = false; fixture.variedHeights = false; fixture.paints = []; localStorage.clear(); clearConversationMemory();
  fixture.deferStart = false; startupReport.mockClear();
  host = document.createElement("div"); document.body.append(host);
  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(300);
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(function(this: HTMLElement) { return this.classList.contains("messages") ? fixture.variedHeights ? 7200 : 2400 : 0; });
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function(this: HTMLElement) {
    const port = this.closest<HTMLElement>(".messages");
    const turns = [...(port?.querySelectorAll(".turn") ?? [])];
    const index = turns.indexOf(this);
    const heightOf = (node: Element) => fixture.variedHeights && node.classList.contains("turn-assistant") ? node.querySelector('[aria-busy="true"]') ? 20 : 500 : 100;
    const height = heightOf(this);
    const top = index >= 0 ? turns.slice(0, index).reduce((sum, node) => sum + heightOf(node), 0) - (port?.scrollTop ?? 0) : 0;
    return { x: 0, y: top, top, bottom: top + height, left: 0, right: width, width, height, toJSON: () => {} };
  });
});
it("cold entry waits for real first history and Markdown paint, without requiring a socket to read it", async () => {
  fixture.deferStart = true; fixture.deferPaint = true;
  await draw("a");
  expect(startupReport).not.toHaveBeenCalled();
  await act(async () => fixture.connections[0]!.options.dispatch({ type: "history", rows: [{ id: 1, role: "assistant", content: "首屏历史", timestamp: 1 }] }));
  expect(host.querySelector('.markdown[aria-busy="true"]')).not.toBeNull();
  expect(startupReport).not.toHaveBeenCalled();
  await act(async () => fixture.paints.splice(0).forEach(paint => paint()));
  expect(startupReport).toHaveBeenCalledWith();
});
it("waits for delayed Markdown before applying an offset deep inside an answer", async () => {
  fixture.variedHeights = true;
  await draw("a");
  const port = host.querySelector<HTMLElement>(".messages")!;
  act(() => { port.dispatchEvent(new WheelEvent("wheel")); port.scrollTop = 2770; port.dispatchEvent(new Event("scroll")); });
  await draw("b");
  fixture.deferPaint = true;
  await draw("a");
  expect(host.querySelector('.markdown[aria-busy="true"]')).not.toBeNull();
  await act(async () => { fixture.paints.splice(0).forEach(paint => paint()); await new Promise(resolve => setTimeout(resolve, 20)); });
  expect(port.scrollTop).toBe(2770);
});
afterEach(() => { act(() => render(null, host)); host.remove(); clearConversationMemory(); resetOverlays(); vi.restoreAllMocks(); });
it("finds an artifact outside the loaded tail, navigates to its source and restores the artifact list", async () => {
  fixture.deferStart = true;
  await draw("a");
  await act(async () => fixture.connections[0]!.options.dispatch({ type: "history", hasOlder: true, rows: [
    { id: 3, role: "user", content: "继续" }, { id: 4, role: "assistant", content: "新的回答" },
  ] }));
  act(() => host.querySelector<HTMLButtonElement>('[aria-label="更多"]')!.click());
  await act(async () => [...host.querySelectorAll<HTMLButtonElement>("button")].find(node => node.textContent === "产物")!.click());
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
  expect(host.querySelector(".artifacts-list")?.textContent).toContain("early.md");
  const list = host.querySelector<HTMLElement>(".artifacts-list")!;
  act(() => { list.scrollTop = 160; list.dispatchEvent(new Event("scroll")); });
  // jsdom/happy-dom has no scrolling implementation, but records the exact target element.
  const scrollIntoView = vi.fn();
  host.querySelector<HTMLElement>('[data-key="h-2"]')!.scrollIntoView = scrollIntoView;
  await act(async () => { host.querySelector<HTMLButtonElement>(".artifact-details button")!.click(); await new Promise(resolve => setTimeout(resolve, 30)); });
  expect(scrollIntoView).toHaveBeenCalled();
  expect(host.querySelector('[data-key="h-2"]')?.classList.contains("landing")).toBe(true);
  act(() => host.querySelector<HTMLButtonElement>(".return-artifacts")!.click());
  expect(host.querySelector<HTMLElement>(".artifacts-list")?.scrollTop).toBe(160);
  await draw("b");
  expect(host.querySelector(".artifacts-list")).toBeNull();
  expect(host.querySelector(".return-artifacts")).toBeNull();
});
it("shows a failed first history read in artifacts instead of claiming there are no results", async () => {
  fixture.deferStart = true;
  await draw("a");
  await act(async () => fixture.connections[0]!.options.dispatch({ type: "history-failed", error: appError("HR-SYNC-001", "history failed") }));
  act(() => host.querySelector<HTMLButtonElement>('[aria-label="更多"]')!.click());
  await act(async () => [...host.querySelectorAll<HTMLButtonElement>("button")].find(node => node.textContent === "产物")!.click());
  expect(host.querySelector(".artifacts-list")?.textContent).toContain("HR-SYNC-001");
  expect(host.querySelector(".artifacts-list")?.textContent).not.toContain("暂无产物");
});
it("keeps the reading turn through width changes and restores it after another conversation", async () => {
  await draw("a");
  const port = host.querySelector<HTMLElement>(".messages")!;
  act(() => { port.dispatchEvent(new WheelEvent("wheel")); port.scrollTop = 850; port.dispatchEvent(new Event("scroll")); });
  width = 700; await draw("a"); expect(port.scrollTop).toBe(850);
  width = 400; await draw("a"); expect(port.scrollTop).toBe(850);
  expect(fixture.connections).toHaveLength(1);
  await draw("b");
  expect(fixture.connections[0]!.dispose).toHaveBeenCalledOnce();
  await draw("a");
  expect(host.querySelector<HTMLElement>(".messages")!.scrollTop).toBe(850);
  expect(fixture.connections).toHaveLength(3);
});
it("restores each conversation's search and rejects late events from the disposed connection", async () => {
  await draw("a");
  act(() => host.querySelector<HTMLButtonElement>('[aria-label="更多"]')!.click());
  act(() => [...host.querySelectorAll<HTMLButtonElement>("button")].find(node => node.textContent?.includes("搜索对话"))!.click());
  const query = host.querySelector<HTMLInputElement>('input[type="search"]')!;
  act(() => { query.value = "文字"; query.dispatchEvent(new Event("input", { bubbles: true })); });
  await draw("b");
  expect(host.querySelector('input[type="search"]')).toBeNull();
  act(() => fixture.connections[0]!.options.dispatch({ type: "notice", error: { code: "HR-SYNC-001" } as never }));
  expect(host.textContent).not.toContain("HR-SYNC-001");
  await draw("a");
  expect(host.querySelector<HTMLInputElement>('input[type="search"]')!.value).toBe("文字");
});
