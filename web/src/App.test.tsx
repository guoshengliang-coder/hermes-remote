import { render } from "preact";
import { useState, useEffect } from "preact/hooks";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { App } from "./App";
import { useApp } from "./app/store";
import { navigate, clearPageSnapshots } from "./app/router";
import { resetOverlays } from "./app/overlayHistory";
import { rememberPushTarget, clearPushTarget } from "./app/push";
import { useStartupPage } from "./startup/readiness";
import { installStartup } from "./startup/fixture";
import { GatewayNetworkError } from "./api/gateway";
const fixture = vi.hoisted(() => ({
  mounts: 0,
  pageReady: true,
  pageSignals: {} as Record<string, () => void>,
  client: {
    capabilities: vi.fn(),
    webSession: vi.fn(),
    devices: vi.fn(),
    resume: vi.fn(),
    requestEmailChallenge: vi.fn(),
    exchangeEmail: vi.fn(),
    onSignedOut: () => () => {},
    lifecycleEvents: async () => ({
      events: [],
      nextCursor: 0,
      hasMore: false,
    }),
    ackEvents: async () => {},
  },
}));
vi.mock("./api/gateway", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api/gateway")>()),
  GatewayClient: vi.fn(function () {
    return fixture.client;
  }),
}));
vi.mock("./ui/SessionList", () => ({
  cancelSessionLoads: vi.fn(),
  SessionList: () => {
    const app = useApp();
    const [ready, setReady] = useState(fixture.pageReady);
    fixture.pageSignals.list = () => setReady(true);
    useStartupPage("list", ready);
    return (
      <div data-list={app.device?.deviceId}>
        <button onClick={app.chooseDevice}>换 Mac</button>
      </div>
    );
  },
}));
vi.mock("./ui/ChatPage", () => ({
  ChatPage: ({
    sessionId,
    profileHint,
  }: {
    sessionId: string | null;
    profileHint?: string | null;
  }) => {
    const app = useApp();
    const [draft, setDraft] = useState("");
    const [ready, setReady] = useState(fixture.pageReady);
    fixture.pageSignals.chat = () => setReady(true);
    useStartupPage(sessionId === null ? "new" : "chat", ready);
    useEffect(() => {
      fixture.mounts++;
    }, []);
    return (
      <div
        data-chat={app.device?.deviceId}
        data-session={sessionId}
        data-profile={profileHint}
      >
        <input
          aria-label="草稿"
          value={draft}
          onInput={(e) => setDraft(e.currentTarget.value)}
        />
        <button onClick={app.chooseDevice}>换 Mac</button>
      </div>
    );
  },
}));
const device = (deviceId: string, isDefault = false) => ({
  id: deviceId,
  generation: 1,
  deviceId,
  desktopDisplayName: deviceId,
  connector: { online: deviceId === "mac-a" },
  access: "owner",
  isDefault,
});
let host: HTMLElement;
let shell: ReturnType<typeof installStartup> | null = null;
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  resetOverlays();
  clearPageSnapshots();
  fixture.mounts = 0;
  fixture.pageReady = true;
  fixture.pageSignals = {};
  fixture.client.resume.mockResolvedValue(false);
  history.replaceState({}, "", "/app/s/session-a");
  fixture.client.capabilities.mockResolvedValue({
    accountAuth: { webDeviceAccess: true },
  });
  fixture.client.webSession.mockResolvedValue({
    session: {
      authenticated: true,
      account: { id: "account-a", displayName: "Fixture" },
    },
  });
  fixture.client.devices.mockResolvedValue({
    items: [device("mac-a", true), device("mac-b")],
  });
  host = document.createElement("div");
  document.body.append(host);
});
afterEach(() => {
  act(() => render(null, host));
  shell?.cleanup(); shell = null;
  host.remove();
  resetOverlays();
  clearPageSnapshots();
  clearPushTarget();
  vi.clearAllMocks();
  vi.useRealTimers();
});
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 25));
  });
}
async function mount() {
  await act(async () => {
    render(<App />, host);
  });
  await vi.waitFor(async () => {
    await settle();
    expect(
      host.querySelector("[data-chat],[data-list],.device-list"),
      host.textContent ?? "",
    ).not.toBeNull();
  });
}
async function click(text: string) {
  await act(async () => {
    Array.from(host.querySelectorAll("button"))
      .find((b) => b.textContent?.includes(text))!
      .click();
  });
  await settle();
}
it("return from Mac switching retains the original conversation and draft", async () => {
  await mount();
  const input = host.querySelector<HTMLInputElement>("input")!;
  await act(async () => {
    input.value = "保留草稿";
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await click("换 Mac");
  expect(host.querySelector('[role="dialog"]')).not.toBeNull();
  await act(async () => {
    host
      .querySelector<HTMLButtonElement>(
        '[aria-label="返回"],[aria-label="Back"]',
      )!
      .click();
  });
  await settle();
  expect(host.querySelector("[data-chat]")?.getAttribute("data-chat")).toBe(
    "mac-a",
  );
  expect(host.querySelector<HTMLInputElement>("input")!.value).toBe("保留草稿");
  expect(location.pathname).toBe("/app/s/session-a");
  expect(fixture.mounts).toBe(1);
});
it("an unfolded 700px window shows list and chat, and folding retains the mounted draft", async () => {
  const resize = async (width: number) => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: width });
    await act(async () => { window.dispatchEvent(new Event("resize")); });
    await settle();
  };
  await resize(700);
  await mount();
  expect(host.querySelector('.conversation-workspace[data-split="true"]')).not.toBeNull();
  expect(host.querySelector('[data-list="mac-a"]')).not.toBeNull();
  const input = host.querySelector<HTMLInputElement>("input")!;
  await act(async () => {
    input.value = "折叠保留草稿";
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await resize(400);
  expect(host.querySelector('.conversation-workspace[data-split="false"]')).not.toBeNull();
  await resize(700);
  expect(host.querySelector<HTMLInputElement>("input")).toBe(input);
  expect(input.value).toBe("折叠保留草稿");
  expect(fixture.mounts).toBe(1);
  await resize(1024);
});
it("selecting another Mac opens its list instead of resuming the old Mac's session", async () => {
  await mount();
  await click("换 Mac");
  await click("mac-b");
  expect(host.querySelector("[data-list]")?.getAttribute("data-list")).toBe(
    "mac-b",
  );
  expect(location.pathname).toBe("/app/");
});
it("a new conversation adopting its id retains the mounted composer", async () => {
  history.replaceState({}, "", "/app/new");
  await mount();
  const input = host.querySelector<HTMLInputElement>("input")!;
  await act(async () => {
    input.value = "保留新会话草稿";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    navigate({ name: "chat", sessionId: "new-stored-id" }, { replace: true });
  });
  await settle();
  expect(fixture.mounts).toBe(1);
  expect(host.querySelector<HTMLInputElement>("input")!.value).toBe(
    "保留新会话草稿",
  );
});
it("a late picker refresh cannot erase a Mac chosen before it finishes", async () => {
  await mount();
  let finish!: (value: unknown) => void;
  fixture.client.devices.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  await click("换 Mac");
  await click("mac-b");
  await act(async () => {
    finish({ items: [device("mac-b")] });
  });
  await settle();
  expect(host.querySelector("[data-list]")?.getAttribute("data-list")).toBe(
    "mac-b",
  );
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});
it("an authorized notification selects its actual Mac, profile and session", async () => {
  history.replaceState(
    {},
    "",
    "/app/?push=12345678-1234-4234-a234-123456789abc&account=account-a&device=mac-b&session=notify-session&profile=work",
  );
  rememberPushTarget();
  await mount();
  const chat = host.querySelector("[data-chat]")!;
  expect(chat.getAttribute("data-chat")).toBe("mac-b");
  expect(chat.getAttribute("data-session")).toBe("notify-session");
  expect(chat.getAttribute("data-profile")).toBe("work");
});
it("a notification from another account cannot select its Mac or session", async () => {
  history.replaceState(
    {},
    "",
    "/app/?push=12345678-1234-4234-a234-123456789abc&account=old-account&device=mac-b&session=notify-session&profile=work",
  );
  rememberPushTarget();
  await mount();
  expect(host.querySelector("[data-list]")?.getAttribute("data-list")).toBe(
    "mac-a",
  );
  expect(host.querySelector("[data-chat]")).toBeNull();
});

it("first selection without an active Mac has no bypass back action", async () => {
  fixture.client.devices.mockResolvedValue({
    items: [
      { ...device("mac-a"), connector: { online: false } },
      { ...device("mac-b"), connector: { online: false } },
    ],
  });
  await mount();
  expect(host.querySelector(".device-list")).not.toBeNull();
  expect(
    host.querySelector('[aria-label="返回"],[aria-label="Back"]'),
  ).toBeNull();
  expect(fixture.mounts).toBe(0);
});
it("a removed original Mac cannot be restored by backing out of the refreshed picker", async () => {
  await mount();
  fixture.client.devices.mockResolvedValue({ items: [device("mac-b")] });
  await click("换 Mac");
  expect(host.querySelector(".device-list")).not.toBeNull();
  expect(
    host.querySelector('[aria-label="返回"],[aria-label="Back"]'),
  ).toBeNull();
  expect(host.querySelector("[data-chat]")).toBeNull();
});

it("cold chat waits for its content; a ready split sidebar does not release the gate", async () => {
  fixture.pageReady = false;
  shell = installStartup(host);
  await mount();
  expect(shell.controller.pending).toBe(true);
  if (fixture.pageSignals.list) act(() => fixture.pageSignals.list!());
  expect(shell.controller.pending).toBe(true);
  await act(async () => fixture.pageSignals.chat!());
  expect(document.documentElement.dataset.startup).toBe('done');
  expect(location.pathname).toBe('/app/s/session-a');
  expect(fixture.mounts).toBe(1);
});
it("cold list waits for its first response and keeps the same mounted page on entry", async () => {
  history.replaceState({}, '', '/app/');
  fixture.pageReady = false;
  shell = installStartup(host);
  await mount();
  const list = host.querySelector('[data-list]');
  expect(shell.controller.pending).toBe(true);
  await act(async () => fixture.pageSignals.list!());
  expect(document.documentElement.dataset.startup).toBe('done');
  expect(host.querySelector('[data-list]')).toBe(list);
});
it("signed-out cold start enters login and preserves its intended chat after sign-in", async () => {
  fixture.client.webSession.mockResolvedValueOnce({ session: { authenticated: false } });
  shell = installStartup(host);
  await act(async () => render(<App />, host));
  await settle();
  expect(location.pathname).toBe('/app/login');
  expect(shell.controller.pending).toBe(false);
  expect(host.querySelector('input[type="email"]')).not.toBeNull();
  fixture.client.requestEmailChallenge.mockResolvedValue({ challenge: { challengeId: 'fixture-challenge', resendAfter: new Date(Date.now() + 60000).toISOString() } });
  fixture.client.exchangeEmail.mockResolvedValue({ account: { id: 'account-a', displayName: 'Fixture' } });
  await act(async () => {
    const email = host.querySelector<HTMLInputElement>('input[type="email"]')!;
    email.value = 'fixture@example.invalid'; email.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  await settle();
  await act(async () => {
    const code = host.querySelector<HTMLInputElement>('.code-input')!;
    code.value = '123456'; code.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await settle();
  expect(location.pathname).toBe('/app/s/session-a');
  expect(host.querySelector('[data-chat]')).not.toBeNull();
  expect(document.documentElement.dataset.startup).toBe('done');
});
it("initial Mac choice is usable without waiting for nonexistent chat data", async () => {
  fixture.client.devices.mockResolvedValue({ items: [] });
  shell = installStartup(host);
  await mount();
  expect(shell.controller.pending).toBe(false);
  expect(host.querySelector('.device-list')).not.toBeNull();
});
it("initial account route enters as soon as the authenticated account is available", async () => {
  history.replaceState({}, '', '/app/account');
  shell = installStartup(host);
  await act(async () => render(<App />, host));
  await settle();
  expect(shell.controller.pending).toBe(false);
  expect(location.pathname).toBe('/app/account');
});
it("startup network failure preserves its code and retries the boot without reloading", async () => {
  fixture.client.capabilities.mockRejectedValueOnce(new GatewayNetworkError(new Error('unreachable'), '/v2/capabilities'));
  shell = installStartup(host);
  await act(async () => render(<App />, host));
  await settle();
  expect(document.documentElement.dataset.startup).toBe('failed');
  expect(shell.gate.querySelector('#startup-code')!.textContent).toBe('HR-WEB-003');
  await act(async () => shell!.gate.querySelector<HTMLButtonElement>('#startup-retry')!.click());
  await settle();
  expect(document.documentElement.dataset.startup).toBe('done');
  expect(host.querySelector('[data-chat]')).not.toBeNull();
});
it("timeout invalidates a delayed boot response and provides a document reload for recovery", async () => {
  vi.useFakeTimers();
  let finish!: (value: unknown) => void;
  fixture.client.capabilities.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  shell = installStartup(host);
  await act(async () => render(<App />, host));
  await act(async () => { await vi.advanceTimersByTimeAsync(15050); });
  expect(shell.gate.querySelector('#startup-code')!.textContent).toBe('HR-WEB-013');
  await act(async () => finish({ accountAuth: { webDeviceAccess: false } }));
  expect(document.documentElement.dataset.startup).toBe('failed');
  expect(host.querySelector('.notice-page')).toBeNull();
  const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
  await act(async () => shell!.gate.querySelector<HTMLButtonElement>('#startup-retry')!.click());
  expect(reload).toHaveBeenCalledOnce();
  reload.mockRestore();
});
it("returning to a tab or navigating keeps the page and draft without another brand gate", async () => {
  shell = installStartup(host);
  await mount();
  const input = host.querySelector<HTMLInputElement>('input')!;
  act(() => { input.value = '原有草稿'; input.dispatchEvent(new Event('input', { bubbles: true })); });
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('online'));
    navigate({ name: 'chat', sessionId: 'session-b' });
  });
  expect(document.documentElement.dataset.startup).toBe('done');
  expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('原有草稿');
  expect(fixture.mounts).toBe(1);
});
