import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { AccountDrawer } from "./AccountDrawer";
import { readPushStatus } from "../app/pushStatus";
import { GatewayHttpError } from "../api/gateway";
import { resetOverlays } from "../app/overlayHistory";
const hosts: HTMLElement[] = [];
const request = vi.fn(); const subscription = vi.fn(); const registration = vi.fn();
beforeEach(() => {
  vi.resetAllMocks(); resetOverlays();
  request.mockResolvedValue({ registration: null }); subscription.mockResolvedValue(null);
  registration.mockResolvedValue({ pushManager: { getSubscription: subscription } });
  vi.stubGlobal("navigator", { userAgent: "Android Chrome", platform: "Linux", maxTouchPoints: 5,
    serviceWorker: { getRegistration: registration }, vibrate: () => true });
  vi.stubGlobal("isSecureContext", true); vi.stubGlobal("PushManager", class {});
  vi.stubGlobal("Notification", { permission: "default", requestPermission: vi.fn() });
  vi.stubGlobal("matchMedia", () => ({ matches: false }));
});
afterEach(() => { hosts.splice(0).forEach(host => { act(() => render(null, host)); host.remove(); });
  vi.unstubAllGlobals(); resetOverlays(); });
async function mount(en = false) {
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const app = { client: { request }, account: { id: "fixture" }, language: en ? "en" : "zh",
    t: (zh: string, english: string) => en ? english : zh, features: new Set(), device: null, voiceFeedback: "on" } as unknown as AppContextValue;
  await act(async () => render(<AppContext.Provider value={app}><AccountDrawer onClose={() => {}} /></AppContext.Provider>, host));
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)); });
  const row = host.querySelector<HTMLButtonElement>(`[aria-label="${en ? "Notifications" : "消息通知"}"]`);
  return { host, row };
}
it.each(["insecure", "worker", "push", "notification"])("hides unsupported notification environment (%s)", async missing => {
  if (missing === "insecure") vi.stubGlobal("isSecureContext", false);
  if (missing === "worker") vi.stubGlobal("navigator", { userAgent: "Android", platform: "Linux", maxTouchPoints: 5 });
  // Feature detection uses property presence, so remove missing browser APIs rather than set undefined.
  if (missing === "push") Reflect.deleteProperty(window, "PushManager");
  if (missing === "notification") Reflect.deleteProperty(window, "Notification");
  const m = await mount(); expect(m.row).toBeNull(); expect(request).not.toHaveBeenCalled();
});
it("iOS retains a right-side installation value and opens guidance without permission", async () => {
  vi.stubGlobal("navigator", { userAgent: "iPhone", platform: "iPhone", maxTouchPoints: 5 });
  const m = await mount(); expect(m.row).not.toBeNull();
  expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe("需添加到主屏幕");
  expect(m.row?.lastElementChild?.tagName.toLowerCase()).toBe("svg");
  await act(async () => m.row!.click());
  expect(m.host.querySelector('.drawer-theme-sheet')?.textContent).toContain("添加到主屏幕");
  expect(request).not.toHaveBeenCalled(); expect(Notification.requestPermission).not.toHaveBeenCalled();
});
it.each([false, true])("shows On only with both server registration and local subscription (English %s)", async en => {
  request.mockResolvedValue({ registration: { channelId: "fixture" } }); subscription.mockResolvedValue({});
  const m = await mount(en); expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe(en ? "On" : "开启");
  expect(m.row?.children.length).toBe(4); expect(m.row?.lastElementChild?.tagName.toLowerCase()).toBe("svg");
  expect(Notification.requestPermission).not.toHaveBeenCalled(); expect(request.mock.calls.map(c => c[0])).toEqual(["GET"]);
});
it("no local worker means Off even with a server registration", async () => {
  request.mockResolvedValue({ registration: { channelId: "fixture" } }); registration.mockResolvedValue(undefined);
  const m = await mount(); expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe("关闭");
});
it("permission denial retains the entry and an honest status", async () => {
  vi.stubGlobal("Notification", { permission: "denied" }); const m = await mount();
  expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe("权限未开启");
});
it("server unavailable retains the entry rather than treating the device as unsupported", async () => {
  request.mockRejectedValue(new GatewayHttpError(404, {}, "/v2/web/push-subscription")); const m = await mount();
  expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe("服务未开启");
});
it("read failure keeps a coded retry instead of claiming Off", async () => {
  request.mockRejectedValue(new Error("private raw cause")); const m = await mount();
  expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe("读取失败");
  expect(m.host.querySelector('[role="alert"]')?.textContent).toContain("HR-WEB-012");
  expect(m.host.textContent).not.toContain("private raw cause");
});
it("a state event supersedes an older in-flight query", async () => {
  let finish!: (v: unknown) => void;
  request.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  const m = await mount(); expect(m.row?.textContent).toContain("读取中");
  request.mockResolvedValue({ registration: { channelId: "new" } }); subscription.mockResolvedValue({});
  await act(async () => { window.dispatchEvent(new CustomEvent("hermes-push-state", { detail: true })); await new Promise(resolve => setTimeout(resolve, 0)); });
  expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe("开启");
  await act(async () => finish({ registration: null }));
  expect(m.row?.querySelector('.drawer-shortcut-value')?.textContent).toBe("开启");
});
it("read-only status does not create a worker or subscription", async () => {
  expect(await readPushStatus({ request } as never)).toBe("off");
  expect(registration).toHaveBeenCalledWith("/app/"); expect(Notification.requestPermission).not.toHaveBeenCalled();
});
