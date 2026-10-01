import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { IDBFactory } from "fake-indexeddb";
import { expect, it, vi } from "vitest";
const source = readFileSync("public/push-worker.js", "utf8");
const channel = "12345678-1234-4234-a234-123456789abc";
function worker() {
  const handlers: Record<string, (e: unknown) => void> = {};
  const notices: any[] = [];
  const nav = { setAppBadge: vi.fn(), clearAppBadge: vi.fn() };
  const navigate = vi.fn();
  const focus = vi.fn();
  const openWindow = vi.fn();
  const self = {
    addEventListener: (name: string, fn: any) => (handlers[name] = fn),
    navigator: nav,
    location: { origin: "https://test.invalid" },
    registration: {
      showNotification: vi.fn(async (title: string, options: any) =>
        notices.push({ title, ...options, close: vi.fn() }),
      ),
      getNotifications: async () => notices,
    },
    clients: {
      matchAll: async () => [
        { url: "https://test.invalid/app/", navigate, focus },
      ],
      openWindow,
    },
  };
  runInNewContext(source, {
    self,
    indexedDB: new IDBFactory(),
    URL,
    URLSearchParams,
    Date,
  });
  async function dispatch(name: string, fields: any) {
    const waits: Promise<any>[] = [];
    handlers[name]!({
      ...fields,
      waitUntil: (p: Promise<any>) => waits.push(p),
    });
    await Promise.all(waits);
  }
  return { self, notices, nav, navigate, focus, dispatch };
}
const row = {
  eventId: "event1",
  event: "run.completed",
  deviceId: "mac1",
  storedSessionId: "session1",
  accountId: "a1",
  profile: "default",
  occurredAt: new Date().toISOString(),
  channelId: channel,
  language: "zh",
};
it("deduplicates push events, updates badge, and clears only the viewed conversation", async () => {
  const w = worker();
  await w.dispatch("message", {
    data: { type: "push-bind", channelId: channel },
  });
  await w.dispatch("push", { data: { json: () => row } });
  await w.dispatch("push", { data: { json: () => row } });
  expect(w.notices).toHaveLength(1);
  expect(w.notices[0].body).toBe("回答已完成。");
  expect(w.nav.setAppBadge).toHaveBeenLastCalledWith(1);
  await w.dispatch("push", {
    data: {
      json: () => ({ ...row, eventId: "event2", storedSessionId: "session2" }),
    },
  });
  expect(w.nav.setAppBadge).toHaveBeenLastCalledWith(2);
  await w.dispatch("message", {
    data: { type: "push-read", deviceId: "mac1", sessionId: "session1" },
  });
  expect(w.nav.setAppBadge).toHaveBeenLastCalledWith(1);
  expect(w.notices[0].close).toHaveBeenCalled();
});
it("ignores queued old-account packets and notification clicks after sign-out", async () => {
  const w = worker();
  await w.dispatch("message", {
    data: { type: "push-bind", channelId: channel },
  });
  await w.dispatch("push", { data: { json: () => row } });
  await w.dispatch("message", { data: { type: "clear" } });
  await w.dispatch("push", {
    data: { json: () => ({ ...row, eventId: "late" }) },
  });
  await w.dispatch("notificationclick", { notification: w.notices[0] });
  expect(w.notices).toHaveLength(1);
  expect(w.navigate).not.toHaveBeenCalled();
  expect(w.nav.clearAppBadge).toHaveBeenCalled();
});
it("notification clicks retain account, Mac, profile and stored session for authenticated routing", async () => {
  const w = worker();
  await w.dispatch("message", {
    data: { type: "push-bind", channelId: channel },
  });
  await w.dispatch("push", { data: { json: () => row } });
  await w.dispatch("notificationclick", { notification: w.notices[0] });
  const url = new URL(w.navigate.mock.calls[0]![0], "https://test.invalid");
  expect(url.pathname).toBe("/app/");
  expect(url.searchParams.get("device")).toBe("mac1");
  expect(url.searchParams.get("account")).toBe("a1");
  expect(url.searchParams.get("profile")).toBe("default");
  expect(w.focus).toHaveBeenCalled();
});
it("foreground receipt suppresses duplicate system delivery of the same event", async () => {
  const w = worker();
  await w.dispatch("message", {
    data: { type: "push-bind", channelId: channel },
  });
  await w.dispatch("message", {
    data: {
      type: "push-foreground",
      accountId: "a1",
      events: [row],
      currentSessionId: null,
    },
  });
  await w.dispatch("push", { data: { json: () => row } });
  expect(w.notices).toHaveLength(0);
});

it("rejects malformed identifiers and external-path payloads without showing or opening them", async () => {
  const w = worker();
  await w.dispatch("message", {
    data: { type: "push-bind", channelId: channel },
  });
  for (const patch of [
    { accountId: undefined },
    { deviceId: undefined },
    { storedSessionId: undefined },
    { profile: "../../settings" },
    { deviceId: "https://evil.invalid" },
  ]) {
    await w.dispatch("push", { data: { json: () => ({ ...row, ...patch }) } });
  }
  expect(w.notices).toHaveLength(0);
});
it("switching channels discards old-account unread counts and queued packets", async () => {
  const w = worker();
  await w.dispatch("message", {
    data: { type: "push-bind", channelId: channel },
  });
  await w.dispatch("push", { data: { json: () => row } });
  await w.dispatch("message", {
    data: {
      type: "push-bind",
      channelId: "22345678-1234-4234-a234-123456789abc",
    },
  });
  await w.dispatch("push", {
    data: { json: () => ({ ...row, eventId: "late" }) },
  });
  expect(w.notices).toHaveLength(1);
  expect(w.nav.clearAppBadge).toHaveBeenCalled();
});

it("reading a session clears its profile without clearing the same id on another profile", async () => {
  const w = worker();
  await w.dispatch("message", {
    data: { type: "push-bind", channelId: channel },
  });
  await w.dispatch("push", { data: { json: () => row } });
  await w.dispatch("push", {
    data: { json: () => ({ ...row, eventId: "work-event", profile: "work" }) },
  });
  await w.dispatch("message", {
    data: {
      type: "push-read",
      deviceId: "mac1",
      sessionId: "session1",
      profile: "default",
    },
  });
  expect(w.nav.setAppBadge).toHaveBeenLastCalledWith(1);
  expect(w.notices[0].close).toHaveBeenCalled();
  expect(w.notices[1].close).not.toHaveBeenCalled();
});
