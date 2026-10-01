import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { PushNotifications } from "./PushNotifications";
import { resetOverlays } from "../app/overlayHistory";
const helper = vi.hoisted(() => ({
  environment: vi.fn(),
  bind: vi.fn(),
  worker: vi.fn(),
}));
vi.mock("../app/push", () => ({
  pushEnvironment: helper.environment,
  bindPushWorker: helper.bind,
  pushWorker: helper.worker,
  currentPushEpoch: () => 0,
  announcePushState: vi.fn(),
  resetPushWorker: vi.fn(async () => {}),
  disablePush: vi.fn(),
  messagePushWorker: vi.fn(),
  PUSH_PATH: "/v2/web/push-subscription",
}));
const hosts: HTMLElement[] = [];
afterEach(() => {
  for (const h of hosts.splice(0)) {
    render(null, h);
    h.remove();
  }
  resetOverlays();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
async function mount() {
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  const context = {
    client: {},
    language: "zh",
    t: (zh: string) => zh,
    account: { id: "fixture" },
  } as unknown as AppContextValue;
  await act(async () => {
    render(
      <AppContext.Provider value={context}>
        <PushNotifications onClose={() => {}} />
      </AppContext.Provider>,
      host,
    );
  });
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  return host;
}
it("ordinary Safari gives installation guidance without requesting permission", async () => {
  helper.environment.mockReturnValue("install");
  const requestPermission = vi.fn();
  vi.stubGlobal("Notification", { requestPermission });
  const host = await mount();
  expect(host.textContent).toContain("添加到主屏幕");
  expect(requestPermission).not.toHaveBeenCalled();
  expect(helper.bind).not.toHaveBeenCalled();
});
it("supported environment waits for an explicit enable click to request permission", async () => {
  helper.environment.mockReturnValue("supported");
  helper.bind.mockResolvedValue({ registration: null });
  helper.worker.mockResolvedValue({
    pushManager: { getSubscription: async () => null },
  });
  const requestPermission = vi.fn(async () => "default");
  vi.stubGlobal("Notification", { permission: "default", requestPermission });
  const host = await mount();
  expect(host.textContent).toContain("消息通知未开启");
  expect(requestPermission).not.toHaveBeenCalled();
  await act(async () => {
    Array.from(host.querySelectorAll("button"))
      .find((b) => b.textContent === "开启消息通知")!
      .click();
  });
  expect(requestPermission).toHaveBeenCalledTimes(1);
});
it("denied permission provides settings recovery without another system request", async () => {
  helper.environment.mockReturnValue("supported");
  helper.bind.mockResolvedValue({ registration: null });
  helper.worker.mockResolvedValue({
    pushManager: { getSubscription: async () => null },
  });
  const requestPermission = vi.fn();
  vi.stubGlobal("Notification", { permission: "denied", requestPermission });
  const host = await mount();
  expect(host.textContent).toContain("通知权限已拒绝");
  expect(host.textContent).toContain("重新检查");
  expect(requestPermission).not.toHaveBeenCalled();
});

it("a successful retry clears the previous setup error", async () => {
  helper.environment.mockReturnValue("supported");
  helper.bind.mockRejectedValueOnce(new Error("query failed"));
  helper.worker.mockResolvedValue({
    pushManager: { getSubscription: async () => null },
  });
  vi.stubGlobal("Notification", {
    permission: "default",
    requestPermission: vi.fn(),
  });
  const host = await mount();
  expect(host.querySelector('[role="alert"]')).not.toBeNull();
  helper.bind.mockResolvedValue({ registration: null });
  await act(async () => {
    Array.from(host.querySelectorAll("button"))
      .find((b) => b.textContent?.includes("重试"))!
      .click();
  });
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  expect(host.querySelector('[role="alert"]')).toBeNull();
});
it("a synchronous permission failure is localized and recoverable", async () => {
  helper.environment.mockReturnValue("supported");
  helper.bind.mockResolvedValue({ registration: null });
  helper.worker.mockResolvedValue({
    pushManager: { getSubscription: async () => null },
  });
  vi.stubGlobal("Notification", {
    permission: "default",
    requestPermission: () => {
      throw new Error("private raw cause");
    },
  });
  const host = await mount();
  await act(async () => {
    Array.from(host.querySelectorAll("button"))
      .find((b) => b.textContent === "开启消息通知")!
      .click();
  });
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  expect(host.querySelector('[role="alert"]')?.textContent).toContain(
    "HR-WEB-012",
  );
  expect(host.querySelector('[role="alert"]')?.textContent).not.toContain(
    "private raw cause",
  );
});
