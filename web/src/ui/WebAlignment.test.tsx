import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { AccountDrawer } from "./AccountDrawer";
import { Composer } from "./Composer";
import { SessionRow } from "./SessionRow";

const hosts: HTMLElement[] = [];
const context = { t: (zh: string) => zh, language: "zh", device: null, features: new Set() } as unknown as AppContextValue;
afterEach(() => { for (const host of hosts.splice(0)) { render(null, host); host.remove(); } });

function mount(node: preact.ComponentChildren): HTMLElement {
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  act(() => render(<AppContext.Provider value={context}>{node}</AppContext.Provider>, host));
  return host;
}

it("keeps time in search results but reserves only the indicator slot in the main list", () => {
  const session = { id: "s1", title: "A", last_active: 1_600_000_000 };
  const host = mount(<SessionRow session={session} now={1_600_060_000_000} onOpen={() => {}} />);
  expect(host.querySelector(".row-time")).toBeNull();
  expect(host.querySelector(".row-side")?.classList.contains("with-time")).toBe(false);
  act(() => render(<AppContext.Provider value={context}>
    <SessionRow session={session} now={1_600_060_000_000} showTime onOpen={() => {}} />
  </AppContext.Provider>, host));
  expect(host.querySelector(".row-time")?.textContent).toBeTruthy();
  expect(host.querySelector(".row-side")?.classList.contains("with-time")).toBe(true);
});

it("moves the model control inside the composer only while focused", () => {
  const host = mount(<Composer t={(zh) => zh} language="zh" generating={false} disabled={false}
    onSend={() => {}} onInterrupt={() => {}} chip={{ label: "model", onClick: () => {} }} />);
  expect(host.querySelector(".model-chip")).toBeNull();
  expect(host.querySelector(".composer-add")).not.toBeNull();
  const area = host.querySelector("textarea")!;
  act(() => area.focus());
  expect(host.querySelector(".composer.expanded .model-chip")).not.toBeNull();
  expect(host.querySelector(".composer-actions .send-button")).not.toBeNull();
  expect(host.querySelector(".composer-disclaimer")?.textContent).toBe("内容由 AI 生成");
});

it("keeps theme changes pending until Save and cancels them without changing the setting", () => {
  const chosen: string[] = [];
  const value = {
    ...context,
    themeMode: "system",
    languagePreference: "system",
    setThemeMode: (mode: string) => chosen.push(`theme:${mode}`),
    setLanguagePreference: (choice: string) => chosen.push(`language:${choice}`),
  } as AppContextValue;
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  act(() => render(<AppContext.Provider value={value}><AccountDrawer onClose={() => {}} /></AppContext.Provider>, host));
  const button = (label: string) => [...host.querySelectorAll("button")].find((node) => node.getAttribute("aria-label") === label || node.textContent === label)!;
  act(() => button("主题").click());
  act(() => button("黑曜石深色").click());
  expect(chosen).toEqual([]);
  act(() => button("关闭").click());
  expect(host.querySelector('[aria-label="外观与主题"]')).toBeNull();
  act(() => button("主题").click());
  expect(host.querySelector('[aria-label="跟随系统"]')?.getAttribute("aria-checked")).toBe("true");
  act(() => button("黑曜石深色").click());
  act(() => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); });
  expect(chosen).toEqual([]);
  act(() => button("主题").click());
  act(() => host.querySelector<HTMLElement>(".drawer-theme-scrim")!.click());
  expect(host.querySelector('[aria-label="外观与主题"]')).toBeNull();
  act(() => button("主题").click());
  act(() => button("温润浅色").click());
  act(() => button("保存").click());
  expect(chosen).toEqual(["theme:light"]);
});

it("keeps shared settings on the card page and commits a sheet choice only on Save", () => {
  const chosen: string[] = [];
  const value = {
    ...context,
    themeMode: "system",
    languagePreference: "system",
    setThemeMode: (mode: string) => chosen.push(`theme:${mode}`),
    setLanguagePreference: (choice: string) => chosen.push(`language:${choice}`),
    fontSize: "standard",
    setFontSize: (size: string) => chosen.push(`font:${size}`),
  } as AppContextValue;
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  act(() => render(<AppContext.Provider value={value}><AccountDrawer onClose={() => {}} /></AppContext.Provider>, host));
  const button = (label: string) => [...host.querySelectorAll("button")].find((node) => node.getAttribute("aria-label") === label || node.textContent === label)!;
  const sheet = (label: string) => host.querySelector(`[role="dialog"][aria-label="${label}"]`);
  const option = (label: string) => host.querySelector(`[role="dialog"] [role="radio"][aria-label="${label}"]`);
  // The gear and the settings layer are gone: the drawer stays one card page.
  expect(host.querySelector('.account-drawer')?.getAttribute("aria-label")).toBe("Hermes GO");
  expect(host.querySelector('button[aria-label="设置"]')).toBeNull();
  expect(host.querySelector('button[aria-label="返回"]')).toBeNull();
  // Language: picking only moves the pending item; 当前使用 still marks the applied one.
  act(() => button("语言").click());
  expect(sheet("语言")).not.toBeNull();
  act(() => button("English").click());
  expect(chosen).toEqual([]);
  expect(option("English")?.getAttribute("aria-checked")).toBe("true");
  expect(option("跟随系统")?.getAttribute("aria-checked")).toBe("false");
  expect(option("跟随系统")?.textContent).toContain("当前使用");
  act(() => button("保存").click());
  expect(chosen).toEqual(["language:en"]);
  expect(sheet("语言")).toBeNull();
  // Font size: Escape cancels the pending choice without writing, and reopening resets it.
  act(() => button("字体大小").click());
  act(() => button("特大").click());
  expect(chosen).toEqual(["language:en"]);
  act(() => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); });
  expect(chosen).toEqual(["language:en"]);
  expect(sheet("字体大小")).toBeNull();
  act(() => button("字体大小").click());
  expect(option("标准")?.getAttribute("aria-checked")).toBe("true");
  act(() => button("特大").click());
  act(() => button("保存").click());
  expect(chosen).toEqual(["language:en", "font:xlarge"]);
  expect(sheet("字体大小")).toBeNull();
  expect(host.querySelector('.account-drawer')?.getAttribute("aria-label")).toBe("Hermes GO");
});
