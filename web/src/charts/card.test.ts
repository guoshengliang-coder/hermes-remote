// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { mountChart } from "./card";
import { decorateBlocks, renderMarkdownFragment } from "../markdown/render";
import { engine, restore, savePreference, storageKey } from "./model";
const disposers: (() => void)[] = [];
beforeEach(() => localStorage.clear());
afterEach(() => {
  disposers.splice(0).forEach((fn) => fn());
  document.body.replaceChildren();
  vi.useRealTimers();
});
const raw =
  "| 日期 | 人数 |\n| --- | --- |\n| 2026-09-01 | 10 |\n| 2026-09-02 | 20 |";
function mount() {
  const host = document.createElement("div"),
    fragment = renderMarkdownFragment(raw);
  decorateBlocks(fragment, {
    table: "表格",
    copyCode: "复制代码",
    copyTable: "复制表格",
    fullTable: "全屏",
  });
  host.append(fragment);
  document.body.append(host);
  const prepare = vi.fn(),
    flash = vi.fn(),
    table = host.querySelector("table")!,
    context = {
      scope: [
        "gateway",
        "account",
        "mac",
        "profile",
        "session",
        "message",
        "table",
      ],
      prompt: "",
      source: "来源：报表",
      prepare,
    };
  disposers.push(mountChart(table, context, "zh", "light", flash));
  return { host, table, prepare, flash, context };
}
it("uses the same renderer when switching and preserves the original full table", () => {
  const h = mount(),
    buttons = h.host.querySelectorAll<HTMLButtonElement>(
      ".table-view-toggle button",
    );
  expect(h.host.querySelector("iframe")).toBeNull();
  buttons[1]!.click();
  const frame = h.host.querySelector("iframe");
  expect(frame?.getAttribute("sandbox")).toBe("allow-scripts");
  buttons[0]!.click();
  expect(h.host.querySelector("iframe")).toBe(frame);
  expect(h.host.querySelector(".table-scroll")?.hasAttribute("hidden")).toBe(
    false,
  );
  expect(h.table.textContent).toContain("20");
  buttons[1]!.click();
  expect(h.host.querySelector("iframe")).toBe(frame);
});
it("only accepts messages from its own iframe and nonce; prepares a draft without sending", () => {
  savePreference("chart");
  const h = mount(),
    frame = h.host.querySelector("iframe")!,
    post = vi.spyOn(frame.contentWindow!, "postMessage");
  window.dispatchEvent(
    new MessageEvent("message", {
      source: frame.contentWindow,
      data: { protocol: 1, type: "ready" },
    }),
  );
  const init = post.mock.calls[0]?.[0] as { nonce: string; model: unknown };
  expect(init.model).toBeDefined();
  expect(post.mock.calls[0]?.[1]).toBe("*"); // Required for the opaque sandbox.
  const query = {
    protocol: 1,
    type: "query",
    nonce: init.nonce,
    range: "2026-10",
  };
  window.dispatchEvent(
    new MessageEvent("message", { source: window, data: query }),
  );
  window.dispatchEvent(
    new MessageEvent("message", {
      source: frame.contentWindow,
      data: { ...query, nonce: "other" },
    }),
  );
  expect(h.prepare).not.toHaveBeenCalled();
  window.dispatchEvent(
    new MessageEvent("message", { source: frame.contentWindow, data: query }),
  );
  expect(h.prepare).toHaveBeenCalledOnce();
  expect(h.prepare.mock.calls[0]?.[0]).toContain("来源：报表");
  expect(h.prepare.mock.calls[0]?.[0]).toContain("2026-10");
  const rows = engine.parseMarkdown(raw),
    model = engine.analyze(rows),
    state = { ...model.defaults, view: "chart", from: "2026-09-02" };
  window.dispatchEvent(
    new MessageEvent("message", {
      source: frame.contentWindow,
      data: { protocol: 1, type: "state", nonce: init.nonce, state },
    }),
  );
  expect(restore(storageKey(h.context.scope, rows))).toMatchObject({
    from: "2026-09-02",
  });
});
it("falls back to the original table with a registered error and a working retry", () => {
  vi.useFakeTimers();
  savePreference("chart");
  const h = mount();
  vi.advanceTimersByTime(10001);
  expect(h.host.querySelector(".table-scroll")?.hasAttribute("hidden")).toBe(
    false,
  );
  expect(h.host.textContent).toContain("HR-CHART-001");
  h.host.querySelector<HTMLButtonElement>(".chart-notice button")!.click();
  expect(
    h.host.querySelector(".table-chart-host")?.hasAttribute("hidden"),
  ).toBe(false);
});
