import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { PushNotifications } from "./PushNotifications";
import { resetOverlays } from "../app/overlayHistory";
const h = vi.hoisted(() => ({ environment: vi.fn(), read: vi.fn(), worker: vi.fn(), disable: vi.fn(),
  message: vi.fn(), announce: vi.fn(), reset: vi.fn(), request: vi.fn(), permission: vi.fn(), epoch: 0 }));
vi.mock("../app/pushStatus", () => ({ readPushStatus: h.read }));
vi.mock("../app/push", () => ({ pushEnvironment: h.environment, pushWorker: h.worker,
  currentPushEpoch: () => h.epoch, announcePushState: h.announce, resetPushWorker: h.reset,
  disablePush: h.disable, messagePushWorker: h.message, PUSH_PATH: "/v2/web/push-subscription" }));
const hosts: HTMLElement[] = [];
beforeEach(() => {
  vi.resetAllMocks(); resetOverlays(); h.epoch = 0;
  h.environment.mockReturnValue("supported"); h.read.mockResolvedValue("off");
  h.request.mockResolvedValue({ publicKey: "AQID" });
  h.worker.mockResolvedValue({ pushManager: { getSubscription: async () => ({ toJSON: () => ({ endpoint: "fixture" }) }) } });
  h.reset.mockResolvedValue(undefined); h.message.mockResolvedValue({}); h.disable.mockResolvedValue(undefined);
  vi.stubGlobal("crypto", { randomUUID: () => "12345678-1234-4234-a234-123456789abc" });
  h.permission.mockResolvedValue("granted");
  vi.stubGlobal("Notification", { permission: "default", requestPermission: h.permission });
});
afterEach(() => { for (const host of hosts.splice(0)) { act(() => render(null, host)); host.remove(); }
  resetOverlays(); vi.unstubAllGlobals(); });
async function mount(en = false) {
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const closed = vi.fn(() => render(null, host)); const before = history.state;
  const context = { client: { request: h.request }, language: en ? "en" : "zh",
    t: (zh: string, english: string) => en ? english : zh, account: { id: "fixture" } } as unknown as AppContextValue;
  await act(async () => render(<AppContext.Provider value={context}><PushNotifications onClose={closed} /></AppContext.Provider>, host));
  const button = (label: string) => {
    const buttons = [...host.querySelectorAll<HTMLButtonElement>("button")];
    return buttons.find(b => b.getAttribute("role") === "radio" && b.getAttribute("aria-label") === label)
      ?? buttons.find(b => b.getAttribute("aria-label") === label || b.textContent === label)!;
  };
  const click = async (label: string) => { await act(async () => { button(label).click(); await new Promise(resolve => setTimeout(resolve, 0)); }); };
  return { host, closed, before, button, click };
}
it("Safari installation guidance never requests permission or reads a subscription", async () => {
  h.environment.mockReturnValue("install"); const m = await mount();
  expect(m.host.textContent).toContain("添加到主屏幕"); expect(h.read).not.toHaveBeenCalled();
  expect(h.permission).not.toHaveBeenCalled(); expect(m.host.querySelector('[role="radio"]')).toBeNull();
});
it("selection stays pending and only Save synchronously requests permission", async () => {
  const m = await mount(); await m.click("开启");
  expect(h.permission).not.toHaveBeenCalled(); expect(h.request).not.toHaveBeenCalled();
  expect(m.host.querySelector('[role="radio"][aria-label="开启"]')?.getAttribute("aria-checked")).toBe("true");
  expect(m.host.querySelector('[role="radio"][aria-label="关闭"]')?.textContent).toContain("当前使用");
  act(() => { m.button("保存").click(); expect(h.permission).toHaveBeenCalledOnce(); expect(h.request).not.toHaveBeenCalled(); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)); });
  expect(h.request.mock.calls.map(c => c[0])).toEqual(["GET", "PUT"]);
  expect(h.announce).toHaveBeenCalledWith(true); expect(m.closed).toHaveBeenCalledOnce();
});
it.each(["close", "scrim", "escape", "back"])("cancels pending changes via %s", async action => {
  const m = await mount(); await m.click("开启");
  await act(async () => {
    if (action === "close") m.host.querySelector<HTMLButtonElement>(".drawer-theme-close")!.click();
    if (action === "scrim") m.host.querySelector<HTMLElement>(".drawer-theme-scrim")!.click();
    if (action === "escape") window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    if (action === "back") { history.replaceState(m.before, ""); window.dispatchEvent(new PopStateEvent("popstate")); }
  });
  expect(m.closed).toHaveBeenCalledOnce(); expect(h.permission).not.toHaveBeenCalled();
  expect(h.request).not.toHaveBeenCalled(); expect(h.disable).not.toHaveBeenCalled();
  const reopened = await mount();
  expect(reopened.host.querySelector('[role="radio"][aria-label="关闭"]')?.getAttribute("aria-checked")).toBe("true");
});
it("saving the unchanged state closes without permission or mutation", async () => {
  const m = await mount(); await m.click("保存"); expect(m.closed).toHaveBeenCalledOnce();
  expect(h.permission).not.toHaveBeenCalled(); expect(h.disable).not.toHaveBeenCalled();
});
it("only confirmed Off executes cleanup", async () => {
  h.read.mockResolvedValue("enabled"); const m = await mount(); await m.click("关闭");
  expect(h.disable).not.toHaveBeenCalled(); await m.click("保存");
  expect(h.disable).toHaveBeenCalledOnce(); expect(m.closed).toHaveBeenCalledOnce();
});
it.each(["default", "denied"])("permission %s never declares success", async result => {
  h.permission.mockResolvedValue(result); vi.stubGlobal("Notification", { permission: result, requestPermission: h.permission });
  const m = await mount(); await m.click("开启"); await m.click("保存");
  expect(m.closed).not.toHaveBeenCalled(); expect(h.request).not.toHaveBeenCalled(); expect(h.announce).not.toHaveBeenCalled();
  expect(m.host.textContent).toContain(result === "denied" ? "HR-PERM-002" : "消息通知未开启");
});
it.each([false, true])("denied state gives platform-specific recovery (iOS %s)", async ios => {
  h.read.mockResolvedValue("denied"); vi.stubGlobal("navigator", { userAgent: ios ? "iPhone" : "Android", platform: ios ? "iPhone" : "Linux", maxTouchPoints: 5 });
  const m = await mount(); expect(m.host.textContent).toContain("HR-PERM-002");
  expect(m.host.textContent).toContain(ios ? "系统设置 → 通知" : "浏览器的网站设置");
  h.read.mockResolvedValue("off"); await m.click("已修改设置，重新检查");
  expect(m.host.querySelector('[role="radio"]')).not.toBeNull(); expect(h.permission).not.toHaveBeenCalled();
});
it("server-off can be rechecked without permission", async () => {
  h.read.mockResolvedValue("unavailable"); const m = await mount();
  expect(m.host.textContent).toContain("服务器尚未开启"); expect(m.host.querySelector('[role="radio"]')).toBeNull();
  h.read.mockResolvedValue("off"); await m.click("重新检查");
  expect(m.host.querySelector('[role="radio"]')).not.toBeNull(); expect(h.permission).not.toHaveBeenCalled();
});
it("failed reads remain unconfirmed until retry succeeds", async () => {
  h.read.mockRejectedValueOnce(new Error("private raw cause")); const m = await mount();
  expect(m.host.querySelector('[role="alert"]')?.textContent).toContain("HR-WEB-012");
  expect(m.host.textContent).not.toContain("private raw cause"); expect(m.host.querySelector('[role="radio"]')).toBeNull();
  await m.click("重试"); expect(m.host.querySelector('[role="alert"]')).toBeNull();
  expect(m.host.querySelector('[role="radio"]')).not.toBeNull();
});
it.each([false, true])("permission exceptions are localized, redacted and retryable (English %s)", async en => {
  h.permission.mockImplementationOnce(() => { throw new Error("private raw cause"); });
  const m = await mount(en); await m.click(en ? "On" : "开启"); await m.click(en ? "Save" : "保存");
  expect(m.host.querySelector('[role="alert"]')?.textContent).toContain("HR-WEB-012");
  expect(m.host.textContent).toContain(en ? "Check your connection" : "检查网络");
  expect(m.host.textContent).not.toContain("private raw cause"); expect(m.closed).not.toHaveBeenCalled();
  await m.click(en ? "Retry" : "重试"); expect(h.permission).toHaveBeenCalledTimes(2); expect(m.closed).toHaveBeenCalledOnce();
});
it("registration failure stays open for retry", async () => {
  h.request.mockRejectedValueOnce(new Error("private provider endpoint")); const m = await mount();
  await m.click("开启"); await m.click("保存");
  expect(m.host.textContent).toContain("HR-WEB-012"); expect(m.host.textContent).not.toContain("private provider endpoint");
  expect(m.closed).not.toHaveBeenCalled(); await m.click("重试"); expect(m.closed).toHaveBeenCalledOnce();
});
it("failed Off does not close and Retry actually repeats cleanup", async () => {
  h.read.mockResolvedValue("enabled"); h.disable.mockRejectedValueOnce(new Error("cleanup failed"));
  const m = await mount(); await m.click("关闭"); await m.click("保存");
  expect(m.host.textContent).toContain("HR-WEB-012"); expect(m.host.textContent).toContain("尚未确认通知状态");
  expect(m.closed).not.toHaveBeenCalled(); await m.click("重试");
  expect(h.disable).toHaveBeenCalledTimes(2); expect(m.closed).toHaveBeenCalledOnce();
});
it("blocks repeated saves while OS permission is pending", async () => {
  let finish!: (v: string) => void; h.permission.mockReturnValue(new Promise<string>(resolve => { finish = resolve; }));
  const m = await mount(); await m.click("开启"); await m.click("保存");
  expect(m.host.querySelector<HTMLButtonElement>(".drawer-theme-save")?.disabled).toBe(true);
  expect([...m.host.querySelectorAll<HTMLButtonElement>('[role="radio"]')].every(b => b.disabled)).toBe(true);
  await m.click("保存中…"); expect(h.permission).toHaveBeenCalledOnce();
  await act(async () => { finish("granted"); await new Promise(resolve => setTimeout(resolve, 0)); }); expect(m.closed).toHaveBeenCalledOnce();
});
it("logout during permission cannot register the old account", async () => {
  let finish!: (v: string) => void; h.permission.mockReturnValue(new Promise<string>(resolve => { finish = resolve; }));
  const m = await mount(); await m.click("开启"); await m.click("保存"); h.epoch++;
  await act(async () => { finish("granted"); await new Promise(resolve => setTimeout(resolve, 0)); }); expect(h.request).not.toHaveBeenCalled();
  expect(h.announce).not.toHaveBeenCalled(); expect(m.closed).not.toHaveBeenCalled();
});
