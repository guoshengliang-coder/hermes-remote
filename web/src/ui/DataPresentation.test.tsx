// @vitest-environment jsdom
import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { AccountDrawer } from "./AccountDrawer";
import { TableFullscreen } from "./TableFullscreen";
import { Markdown } from "./Markdown";
import { preference } from "../charts/model";
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
it("keeps presentation choices pending until Save and cancels on close", () => {
  document.body.append(host);
  act(() =>
    render(
      <AppContext.Provider value={app}>
        <AccountDrawer onClose={() => {}} />
      </AppContext.Provider>,
      host,
    ),
  );
  const buttons = () =>
    Array.from(host.querySelectorAll<HTMLButtonElement>("button"));
  const click = (text: string) =>
    act(() =>
      buttons()
        .find((b) => b.textContent === text || b.textContent?.startsWith(text))!
        .click(),
    );
  click("数据展示方式");
  click("图表优先");
  expect(preference()).toBe("table");
  act(() =>
    host.querySelector<HTMLButtonElement>(".drawer-theme-close")!.click(),
  );
  expect(preference()).toBe("table");
  click("数据展示方式");
  click("自动选择");
  click("保存");
  expect(preference()).toBe("auto");
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
  act(() => first!.querySelectorAll<HTMLButtonElement>("button")[1]!.click());
  expect(host.querySelector("iframe")).not.toBeNull();
  show(["session-b"], false);
  expect(host.querySelector(".table-view-toggle")).not.toBe(first);
  expect(host.querySelector("iframe")).toBeNull();
});
