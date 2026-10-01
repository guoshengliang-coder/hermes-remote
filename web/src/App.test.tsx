import { render } from "preact";
import { useState, useEffect } from "preact/hooks";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { App } from "./App";
import { useApp } from "./app/store";
import { navigate, clearPageSnapshots } from "./app/router";
import { resetOverlays } from "./app/overlayHistory";
import { rememberPushTarget, clearPushTarget } from "./app/push";
const fixture = vi.hoisted(() => ({
  mounts: 0,
  client: {
    capabilities: vi.fn(),
    webSession: vi.fn(),
    devices: vi.fn(),
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
  SessionList: () => {
    const app = useApp();
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
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  resetOverlays();
  clearPageSnapshots();
  fixture.mounts = 0;
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
  host.remove();
  resetOverlays();
  clearPageSnapshots();
  clearPushTarget();
  vi.clearAllMocks();
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
