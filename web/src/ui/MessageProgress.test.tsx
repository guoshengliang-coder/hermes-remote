import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import type { ChatItem } from "../chat/model";
import { MessageView } from "./Message";
import { resetOverlays } from "../app/overlayHistory";

let host: HTMLDivElement;
let item: ChatItem;
const fullHistoryRow = vi.fn();
const context = { t: (zh: string) => zh, language: "zh", client: { fullHistoryRow }, device: { deviceId: "mac" } } as unknown as AppContextValue;
async function draw(changes: Partial<ChatItem> = {}) {
  item = { ...item, ...changes };
  await act(async () => render(<AppContext.Provider value={context}><MessageView item={item} /></AppContext.Provider>, host));
}
const click = async (selector: string) => act(async () => host.querySelector<HTMLElement>(selector)!.click());
beforeEach(() => {
  host = document.createElement("div"); document.body.append(host);
  item = { key: "turn", role: "assistant", text: "", attachments: [], images: [], reasoning: "first thought", tools: [], streaming: true, timestampMs: null, raw: "" };
  fullHistoryRow.mockReset();
  vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} });
});
afterEach(() => { act(() => render(null, host)); host.remove(); resetOverlays(); vi.unstubAllGlobals(); });

it("starts live reasoning folded through deltas, prose and completion; history reopens folded", async () => {
  await draw();
  expect(host.querySelector(".reasoning-body")).toBeNull();
  await draw({ reasoning: "later thought", text: "answer beginning" });
  expect(host.querySelector(".reasoning-body")).toBeNull();
  await draw({ streaming: false });
  expect(host.querySelector(".reasoning-body")).toBeNull();
  await click(".reasoning-head");
  expect(host.querySelector(".reasoning-body")?.textContent).toBe("later thought");
  act(() => render(null, host));
  await draw();
  expect(host.querySelector(".reasoning-body")).toBeNull();
});
it("manual full expansion survives deltas and completion; manual hide stays hidden", async () => {
  await draw(); await click(".reasoning-head");
  expect(host.querySelector(".reasoning-body")?.textContent).toBe("first thought");
  await draw({ reasoning: "updated thought", streaming: false });
  expect(host.querySelector(".reasoning-body")?.textContent).toBe("updated thought");
  await click(".reasoning-head");
  await draw({ reasoning: "next thought", streaming: true });
  expect(host.querySelector(".reasoning-body")).toBeNull();
});
it("can expand and hide live reasoning without later deltas reopening it", async () => {
  await draw(); await click(".reasoning-head"); await click(".reasoning-head");
  await draw({ reasoning: "later" });
  expect(host.querySelector(".reasoning-body")).toBeNull();
});
it("keeps the newest single tool and allows all rows to be inspected, including failures", async () => {
  const tools = ["one", "two", "three"].map((name, i) => ({ id: name, name, done: true, output: "result", ...(i === 0 ? { exitCode: 1 } : {}) }));
  await draw({ tools });
  expect([...host.querySelectorAll(".tool-timeline-row .tool-card-name")].map(n => n.textContent)).toEqual(["three"]);
  expect(host.querySelector(".tool-timeline-summary")?.textContent).toContain("1 次失败");
  await click(".tool-timeline-summary");
  await draw({ tools: [...tools, { id: "four", name: "four", done: false, output: "" }] });
  expect(host.querySelectorAll(".tool-timeline-row")).toHaveLength(4);
  await click(".tool-timeline-summary");
  expect(host.querySelector(".tool-timeline-row .tool-card-name")?.textContent).toBe("four");
  await draw({ streaming: false });
  expect(host.querySelectorAll(".tool-timeline-row")).toHaveLength(0);
});
it("shows the full recent status without character truncation and opens a stable selectable snapshot", async () => {
  const line = "先核实所有源文件，再逐一检查工具返回的详细结果，最后整理成可复核的完整报告。😀".repeat(5);
  await draw({ reasoning: `old line\n${line}` });
  expect(host.querySelector(".running-text")?.textContent).toBe(line);
  await click(".running-text");
  expect(host.querySelector('[role="dialog"] .running-details')?.textContent).toBe(line);
  await draw({ reasoning: "new status" });
  expect(host.querySelector(".running-details")?.textContent).toBe(line);
  await click('[aria-label="关闭"]');
  expect(host.querySelector('[role="dialog"]')).toBeNull();
  expect(host.querySelector(".running-text")?.textContent).toBe("new status");
});
it("hides status text as soon as visible prose starts while keeping the live indicator", async () => {
  await draw({ reasoning: "latest reasoning", tools: [{ id: "t", name: "terminal", done: false, command: "run command", output: "" }] });
  expect(host.querySelector(".running-text")?.textContent).toContain("run command");
  await draw({ text: "final answer begins" });
  expect(host.querySelector(".running-text")).toBeNull();
  expect(host.querySelector(".running-line .loading-dots")).not.toBeNull();
  expect(host.querySelector<HTMLElement>(".turn-footer")?.style.minHeight).toBe("");
  await draw({ reasoning: "late reasoning", text: "final answer continues" });
  expect(host.querySelector(".running-text")).toBeNull();
  await draw({ streaming: false });
  expect(host.querySelector(".running-line")).toBeNull();
  expect(host.querySelector(".message-actions")).not.toBeNull();
});
it("does not treat whitespace or structured tool payload as visible final prose", async () => {
  await draw({ text: "  \n  " });
  expect(host.querySelector(".running-text")).not.toBeNull();
  await draw({ text: '{"command":"pwd","exit_code":0,"output":"/workspace"}' });
  expect(host.querySelector(".tool-card")).not.toBeNull();
  expect(host.querySelector(".running-text")).not.toBeNull();
  await draw({ text: '{"command":"unfinished' });
  expect(host.querySelector(".running-text")).not.toBeNull();
});
it("loads historical reasoning only after manual expansion, without fetching it while folded", async () => {
  const source = { sessionId: "s", rowId: 7, offset: 0, profile: null };
  fullHistoryRow.mockResolvedValue({ messages: [{ id: 7, reasoning_content: "full historical reasoning" }] });
  await draw({ streaming: false, reasoningParts: [{ text: "first thought", source }] });
  expect(fullHistoryRow).not.toHaveBeenCalled();
  await click(".reasoning-head");
  expect(fullHistoryRow).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(host.querySelector(".reasoning-body")?.textContent).toBe("full historical reasoning"));
});
