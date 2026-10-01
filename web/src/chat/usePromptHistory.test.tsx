import { render } from "preact";
import { act } from "preact/test-utils";
import { useReducer } from "preact/hooks";
import { afterEach, expect, it, vi } from "vitest";
import { initialChatState, reduceChat, type ChatState } from "./model";
import { usePromptHistory } from "./usePromptHistory";
import { turnGroups } from "./turns";
import type { MessageRow } from "../hermes/types";

const rows = (from: number, to: number): MessageRow[] => Array.from({ length: to - from + 1 }, (_, i) => ({ id: from + i, role: (from + i) % 2 ? "user" : "assistant", content: `m${from + i}` }));
const tail = () => reduceChat(initialChatState, { type: "history", rows: rows(101, 200), hasOlder: true });
let host: HTMLDivElement | null = null;
let latest: ReturnType<typeof usePromptHistory>;
let chat: ChatState;
function Probe({ open, stored = true, initial, load }: { open: boolean; stored?: boolean; initial: ChatState; load: () => Promise<MessageRow[]> }) {
  const [state, dispatch] = useReducer(reduceChat, initial); chat = state;
  latest = usePromptHistory(open, stored, state, load, (all, epoch) => dispatch({ type: "older-loaded", rows: all, hasMore: false, epoch }));
  return null;
}
function mount(open: boolean, initial: ChatState, load: () => Promise<MessageRow[]>, stored = true) {
  if (!host) { host = document.createElement("div"); document.body.append(host); }
  act(() => render(<Probe open={open} initial={initial} load={load} stored={stored} />, host!));
}
const settle = () => act(async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); });
afterEach(() => { if (host) { act(() => render(null, host!)); host.remove(); host = null; } });

it("opening a long history obtains a full prompt count and jump targets while preserving local turns", async () => {
  const state = reduceChat(tail(), { type: "user-sent", key: "local", text: "in progress", nowMs: 1 });
  const load = vi.fn(async () => rows(1, 200));
  mount(false, state, load); expect(load).not.toHaveBeenCalled();
  mount(true, state, load); expect(latest.loading).toBe(true);
  await settle();
  expect(turnGroups(chat.items)).toHaveLength(101);
  expect(chat.items.some((item) => item.key === "h-1")).toBe(true);
  expect(chat.items.some((item) => item.key === "local")).toBe(true);
  expect(chat.older.hasMore).toBe(false);
  expect(latest.loading).toBe(false);
  mount(false, state, load); mount(true, state, load);
  expect(load).toHaveBeenCalledOnce();
});
it("failed full history is recoverable and never changes the loaded conversation", async () => {
  const load = vi.fn<() => Promise<MessageRow[]>>().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(rows(1, 200));
  const state = tail(); mount(true, state, load); await settle();
  expect(latest.error).toMatchObject({ code: "HR-SYNC-001", retryable: true });
  expect(latest.loading).toBe(false); expect(chat.historyRows).toHaveLength(100);
  act(() => latest.retry()); await settle();
  expect(chat.historyRows).toHaveLength(200); expect(latest.error).toBeNull();
});
it("closing while loading ignores a late result", async () => {
  let resolve!: (all: MessageRow[]) => void;
  const load = () => new Promise<MessageRow[]>((r) => { resolve = r; });
  const state = tail(); mount(true, state, load); mount(false, state, load);
  resolve(rows(1, 200)); await settle();
  expect(chat.historyRows).toHaveLength(100);
  expect(latest.loading).toBe(false);
});
it("new and fully loaded chats do not fetch or show a waiting state", () => {
  const load = vi.fn(async () => []);
  mount(true, initialChatState, load, false);
  expect(latest.loading).toBe(false); expect(load).not.toHaveBeenCalled();
});
