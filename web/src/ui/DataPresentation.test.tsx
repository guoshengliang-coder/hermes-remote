// @vitest-environment jsdom
import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { AccountDrawer } from "./AccountDrawer";
import { TableFullscreen } from "./TableFullscreen";
import { Markdown } from "./Markdown";
import { setTableFullscreen } from "../charts/card";
import { resetOverlays } from "../app/overlayHistory";
const host = document.createElement("div");
afterEach(() => {
  act(() => render(null, host));
  document.body.replaceChildren();
  localStorage.clear();
  resetOverlays();
});
const app = {
  t: (zh: string) => zh,
  features: new Set(),
  device: null,
  language: "zh",
  voiceFeedback: "off",
  flash: vi.fn(),
} as unknown as AppContextValue;
it("removes the global presentation choice even with a saved legacy preference", () => {
  localStorage.setItem("hermes-go.table-charts.preference", "chart");
  document.body.append(host);
  act(() =>
    render(
      <AppContext.Provider value={app}>
        <AccountDrawer onClose={() => {}} />
      </AppContext.Provider>,
      host,
    ),
  );
  expect(host.textContent).not.toContain("数据展示方式");
});
it("fullscreen restores the live card, scroll position and exact node rather than reparsing a table", () => {
  const scroll = document.createElement("div"),
    card = document.createElement("div"),
    table = document.createElement("table");
  card.className = "table-card";
  card.append(table);
  scroll.append(card);
  scroll.scrollTop = 70;
  document.body.append(scroll, host);
  act(() =>
    render(
      <AppContext.Provider value={app}>
        <TableFullscreen table={table} onClose={() => {}} />
      </AppContext.Provider>,
      host,
    ),
  );
  expect(host.querySelector("table")).toBe(table);
  scroll.scrollTop = 0;
  act(() => render(null, host));
  expect(scroll.querySelector(".table-card")).toBe(card);
  expect(scroll.scrollTop).toBe(70);
});

it("adds charts only after streaming finishes and replaces state when the conversation changes", () => {
  document.body.append(host);
  const raw =
    "| 日期 | 人数 |\n| --- | --- |\n| 2026-09-01 | 10 |\n| 2026-09-02 | 20 |";
  const show = (scope: string[], streaming: boolean) =>
    act(() =>
      render(
        <AppContext.Provider value={app}>
          <Markdown
            source={raw}
            streaming={streaming}
            chartContext={{
              scope,
              prompt: "",
              source: "离线表",
              prepare: () => {},
            }}
          />
        </AppContext.Provider>,
        host,
      ),
    );
  show(["session-a"], true);
  expect(host.querySelector(".table-view-toggle")).toBeNull();
  show(["session-a"], false);
  const first = host.querySelector(".table-view-toggle");
  expect(first).not.toBeNull();
  expect((first as HTMLElement).hidden).toBe(true);
  act(() => setTableFullscreen(host.querySelector("table")!, true));
  expect(host.querySelector("iframe")).not.toBeNull();
  show(["session-b"], false);
  expect(host.querySelector(".table-view-toggle")).not.toBe(first);
  expect(host.querySelector("iframe")).toBeNull();
});
