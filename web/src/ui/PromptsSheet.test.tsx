import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import type { ChatItem } from "../chat/model";
import { appError } from "../errors";
import { PromptsSheet } from "./ChatSheets";
import { resetOverlays } from "../app/overlayHistory";

const item = (key: string, text: string, extra: Partial<ChatItem> = {}): ChatItem => ({ key, role: "user", text, attachments: [], images: [], reasoning: "", tools: [], streaming: false, timestampMs: null, ...extra });
const hosts: HTMLElement[] = [];
afterEach(() => { hosts.forEach((host) => { act(() => render(null, host)); host.remove(); }); hosts.length = 0; resetOverlays(); vi.restoreAllMocks(); });
function mount(items: ChatItem[], extra: Partial<Parameters<typeof PromptsSheet>[0]> = {}, language = "zh") {
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const t = (zh: string, en: string) => language === "en" ? en : zh;
  const props = { items, onJump: vi.fn(), onLatest: vi.fn(), onClose: vi.fn(), ...extra };
  act(() => render(<AppContext.Provider value={{ t, language } as AppContextValue}><PromptsSheet {...props} /></AppContext.Provider>, host));
  return { host, props };
}
it("counts all real prompts including files, with Latest in the header and current row accessible", () => {
  const { host, props } = mount([
    item("start", "hello", { role: "assistant" }), item("one", "First\nsecond line", { timestampMs: 1700000000000 }),
    item("note", "ignore", { note: { glyph: "", zh: "", en: "", expandable: false } }),
    item("two", "", { localFiles: ["report.pdf"] }), item("three", "", { images: [{ url: "https://example.test/p.png" }] }),
  ], { currentKey: "two" });
  expect(host.querySelector(".prompt-count")!.textContent).toBe("3 条");
  expect(host.querySelectorAll(".prompt-row")).toHaveLength(4);
  const current = host.querySelector<HTMLButtonElement>('[aria-current="location"]')!;
  expect(current.textContent).toContain("文件：report.pdf");
  expect(current.getAttribute("aria-label")).toContain("当前位置");
  expect(host.querySelector(".prompt-time")!.parentElement!.className).toBe("prompt-content");
  const latest = host.querySelector<HTMLButtonElement>('[aria-label="回到最新"]')!;
  expect(latest.closest(".picker-head")).not.toBeNull();
  act(() => { latest.click(); current.click(); });
  expect(props.onLatest).toHaveBeenCalledOnce(); expect(props.onJump).toHaveBeenCalledWith("two");
  expect(host.querySelector(".prompt-index.start")!.textContent).toBe("");
});
it.each(["zh", "en"])("uses bot wording consistently in %s", (language) => {
  const { host } = mount([item("one", "hi")], { bot: true }, language);
  expect(host.querySelector('[role="dialog"]')!.getAttribute("aria-label")).toBe(language === "en" ? "Their prompts" : "对方的提问");
});
it("shows a loading state or retryable error rather than a false total", () => {
  const loading = mount([item("one", "hi")], { loading: true });
  expect(loading.host.querySelector(".prompt-count")!.textContent).toBe("…");
  expect(loading.host.querySelector('[role="status"]')).not.toBeNull();
  const retry = vi.fn();
  const failed = mount([item("one", "hi")], { error: appError("HR-SYNC-001"), onRetry: retry });
  expect(failed.host.querySelector(".prompt-count")!.textContent).toBe("…");
  const button = [...failed.host.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === "重试")!;
  act(() => button.click()); expect(retry).toHaveBeenCalledOnce();
});
it("opens with two rows above the current prompt", () => {
  vi.spyOn(HTMLElement.prototype, "offsetTop", "get").mockImplementation(function(this: HTMLElement) {
    return this.classList.contains("prompt-row") ? Number(this.querySelector(".prompt-index")?.textContent) * 60 : 0;
  });
  const { host } = mount(Array.from({ length: 20 }, (_, i) => item(`u${i}`, `Prompt ${i}`)), { currentKey: "u10" });
  expect(host.querySelector(".picker-list")!.scrollTop).toBe(9 * 60);
});
it("keeps an empty conversation count at zero", () => {
  const { host } = mount([]);
  expect(host.querySelector(".prompt-count")!.textContent).toBe("0 条");
  expect(host.textContent).toContain("还没有提问");
});
