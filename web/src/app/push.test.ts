import { expect, it, afterEach, vi } from "vitest";
import { readPushTarget, pushEnvironment } from "./push";
const channel = "12345678-1234-4234-a234-123456789abc";
afterEach(() => vi.unstubAllGlobals());
it("notification links select only validated account/Mac/profile/session targets", () => {
  const q = new URLSearchParams({
    push: channel,
    account: "a1",
    device: "mac1",
    session: "session1",
    profile: "中文身份",
  });
  expect(readPushTarget(q.toString())).toEqual({
    channelId: channel,
    accountId: "a1",
    deviceId: "mac1",
    sessionId: "session1",
    profile: "中文身份",
  });
  for (const [key, value] of [
    ["session", "../delete"],
    ["device", "https://evil.invalid"],
    ["profile", "../../"],
    ["push", "bad"],
    ["account", ""],
  ]) {
    const bad = new URLSearchParams(q);
    bad.set(key!, value!);
    expect(readPushTarget(bad.toString())).toBeNull();
  }
});
it("ordinary iPhone Safari guides installation before any permission request", () => {
  vi.stubGlobal("navigator", {
    userAgent: "iPhone",
    platform: "iPhone",
    maxTouchPoints: 5,
  });
  vi.stubGlobal("matchMedia", () => ({ matches: false }));
  expect(pushEnvironment()).toBe("install");
});

it("an in-flight old-account config cannot rebind the worker after logout", async () => {
  const messages: unknown[] = [];
  class Channel {
    port1: any = { onmessage: null, close: () => {} };
    port2 = {
      postMessage: (data: unknown) => this.port1.onmessage?.({ data }),
    };
  }
  vi.stubGlobal("MessageChannel", Channel);
  vi.stubGlobal("navigator", {
    serviceWorker: {
      getRegistration: async () => ({
        active: {
          postMessage: (message: unknown, ports: any[]) => {
            messages.push(message);
            ports[0].postMessage({ ok: true });
          },
        },
      }),
    },
  });
  const { bindPushWorker, resetPushWorker } = await import("./push");
  let finish!: (value: unknown) => void;
  const client = {
    request: () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  } as any;
  const pending = bindPushWorker(client);
  await resetPushWorker();
  finish({ publicKey: "test", registration: { channelId: channel } });
  await pending;
  expect(messages).toEqual([{ type: "push-bind", channelId: null }]);
});
