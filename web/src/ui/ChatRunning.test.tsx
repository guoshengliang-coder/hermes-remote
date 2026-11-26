import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { initialInbox } from "../app/inbox";
import { clearConversationMemory } from "../app/conversationMemory";
import { resetOverlays } from "../app/overlayHistory";
import type { ChatSessionOptions } from "../chat/session";
import { ChatPage } from "./ChatPage";

const fixture = vi.hoisted(() => ({ connections: [] as ChatSessionOptions[], probes: [] as number[], pending: undefined as Promise<void> | undefined, detach: vi.fn(), dispose: vi.fn() }));
vi.mock("../chat/session", () => ({ ChatSession: class {
  storedSessionId: string | null;
  constructor(private options: ChatSessionOptions) { this.storedSessionId = options.storedSessionId; fixture.connections.push(options); }
  get pendingSendCompletion() { return fixture.pending; }
  detachPage() { fixture.detach(); }
  dispose() { fixture.dispose(); }
  start() {
    this.options.dispatch({ type: "history", rows: [{ id: 1, role: "user", content: "question", timestamp: 1 }] });
    this.options.dispatch({ type: "connection", state: "ready" });
  }
  reconcileLifecycle(entry: { sequence: number }) { fixture.probes.push(entry.sequence); }
  interrupt() { this.options.dispatch({ type: "interrupted" }); }
} }));
let host: HTMLElement;
let app: AppContextValue;
const draw = async (id: string | null = "a") => act(async () => render(<AppContext.Provider value={app}><ChatPage sessionId={id}/></AppContext.Provider>, host));
beforeEach(() => {
  fixture.connections.length = 0; fixture.probes.length = 0;
  fixture.pending = undefined; fixture.detach.mockClear(); fixture.dispose.mockClear();
  localStorage.clear(); clearConversationMemory();
  host = document.createElement("div"); document.body.append(host);
  app = { t: (zh: string) => zh, language: "zh", account: { id: "account" }, device: { deviceId: "mac" }, client: {}, features: new Set(),
    sessions: [{ id: "a", title: "A", profile: "default" }, { id: "b", title: "B", profile: "default" }], inbox: initialInbox, markSeen: () => {}, reportLiveQuestion: () => {},
    isPinned: () => false, chatSearchSeed: null, setChatSearchSeed: () => {} } as unknown as AppContextValue;
});
afterEach(() => { act(() => render(null, host)); host.remove(); clearConversationMemory(); resetOverlays(); });
it("HG-228 shows dots and Stop before the first token, keeps one status during streaming and clears both on completion", async () => {
  await draw();
  await act(async () => fixture.connections[0]!.dispatch({ type: "running", running: true }));
  expect(host.querySelectorAll(".loading-dots")).toHaveLength(1);
  expect(host.querySelector('[aria-label="停止"]')).not.toBeNull();
  expect(host.querySelectorAll(".turn-assistant")).toHaveLength(0); // status is not an empty transcript turn
  await act(async () => fixture.connections[0]!.dispatch({ type: "event", event: { type: "message.delta", sessionId: "a", payload: { text: "answer" } } }));
  expect(host.querySelectorAll(".loading-dots")).toHaveLength(1);
  expect(host.querySelectorAll(".turn-assistant")).toHaveLength(1);
  await act(async () => fixture.connections[0]!.dispatch({ type: "event", event: { type: "message.complete", sessionId: "a", payload: { text: "answer" } } }));
  expect(host.querySelector(".loading-dots")).toBeNull();
  expect(host.querySelector('[aria-label="停止"]')).toBeNull();
});
it("HG-228 consumes lifecycle invalidations once per entry and routes them to the selected conversation", async () => {
  const entry = { storedSessionId: "a", event: "run.started", sequence: 12, occurredAtMs: 1, title: null };
  app = { ...app, inbox: { ...initialInbox, latest: { [JSON.stringify(["default","a"])]: entry } } };
  await draw(); expect(fixture.probes).toEqual([12]);
  await draw(); expect(fixture.probes).toEqual([12]);
  app = { ...app, inbox: { ...initialInbox, latest: { [JSON.stringify(["default","a"])]: { ...entry, event: "run.completed", sequence: 13 } } } };
  await draw(); expect(fixture.probes).toEqual([12, 13]);
  await draw("b"); expect(fixture.probes).toEqual([12, 13]);
  expect(host.querySelector(".loading-dots")).toBeNull();
});
it("HG-228 Stop removes the waiting status and late output cannot revive it", async () => {
  await draw();
  await act(async () => fixture.connections[0]!.dispatch({ type: "running", running: true }));
  await act(async () => host.querySelector<HTMLButtonElement>('[aria-label="停止"]')!.click());
  await act(async () => fixture.connections[0]!.dispatch({ type: "event", event: { type: "message.delta", sessionId: "a", payload: { text: "late" } } }));
  expect(host.querySelector(".loading-dots")).toBeNull();
  expect(host.querySelector('[aria-label="停止"]')).toBeNull();
  expect(host.textContent).not.toContain("late");
});


it("HG-261 keeps the role below the title without a directory, including reconnects", async () => {
  app = { ...app, profileCatalog: { default: "芯芯" } };
  await draw();
  const title = host.querySelector(".chat-title h1")!;
  const role = host.querySelector(".chat-title .role-owner-name")!;
  expect(role).not.toBeNull();
  expect(title.parentElement!.contains(role)).toBe(false);
  expect(title.parentElement!.nextElementSibling!.contains(role)).toBe(true);
  await act(async () => fixture.connections[0]!.dispatch({ type: "connection", state: "reconnecting" }));
  expect(host.querySelector(".chat-title h1")!.parentElement!.contains(host.querySelector(".role-owner-name"))).toBe(false);
  expect(host.querySelector(".chat-title .role-owner-name")?.textContent).toBe("芯芯");
  expect(host.querySelector(".chat-status")?.textContent).toContain("恢复");
  expect(host.querySelector(".chat-status")?.getAttribute("title")).toBe(host.querySelector(".chat-status")?.textContent);
});

it("HG-261 retains the project next to the role beneath the title", async () => {
  app = { ...app, profileCatalog: { default: "芯芯" }, sessions: [{ id: "a", title: "A", profile: "default", cwd: "/qa/project" }] };
  await draw();
  const role = host.querySelector(".chat-title .role-owner-name")!;
  expect(role.parentElement!.querySelector(".chat-workspace-name")?.textContent).toBe("project");
  expect(host.querySelector("h1")!.parentElement!.contains(role)).toBe(false);
});


it("HG-268 leaves a pending route send attached to its connection until it settles", async () => {
  await draw();
  let settle!: () => void;
  fixture.pending = new Promise(resolve => { settle = resolve; });
  await act(async () => render(null, host));
  expect(fixture.detach).toHaveBeenCalledTimes(1);
  expect(fixture.dispose).not.toHaveBeenCalled();

  await draw();
  expect(fixture.connections).toHaveLength(1);
  fixture.pending = undefined;
  await act(async () => settle());
  expect(fixture.connections).toHaveLength(2);
});


it("HG-268 reopening New waits for its unbound send before creating another connection", async () => {
  app = { ...app, profileCatalog: { default: "Default" } };
  await draw(null);
  let settle!: () => void;
  fixture.pending = new Promise(resolve => { settle = resolve; });
  await act(async () => render(null, host));
  await draw(null);
  expect(fixture.connections).toHaveLength(1);
  expect(host.querySelector<HTMLButtonElement>('button[aria-label="发送"]')?.disabled ?? true).toBe(true);
  fixture.pending = undefined;
  await act(async () => settle());
  expect(fixture.connections).toHaveLength(2);
});
