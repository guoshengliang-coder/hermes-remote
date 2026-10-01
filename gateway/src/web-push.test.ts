import assert from "node:assert/strict";
import { test } from "node:test";
import webpush from "web-push";
import type { IncomingMessage, ServerResponse } from "node:http";
import { AccountHttpController } from "./account/account-http-controller.js";
import type { AccountService } from "./account/account-service.js";
import type { AccountPrincipal } from "./account/model.js";
import {
  WebSessionSecurity,
  WEB_COOKIE_NAMES,
} from "./account/web-session-security.js";
import {
  parseWebSubscription,
  WebPushFanout,
  type WebPushStore,
  type WebPushTarget,
} from "./account/push/web-push.js";
import type { SessionLifecycleEvent } from "@hermes-remote/protocol";
const keys = webpush.generateVAPIDKeys();
const subscription = {
  endpoint: "https://web.push.apple.com/token",
  keys: {
    p256dh: keys.publicKey,
    auth: Buffer.alloc(16, 1).toString("base64url"),
  },
};
const channel = "12345678-1234-4234-a234-123456789abc";
const event: SessionLifecycleEvent = {
  type: "session.lifecycle",
  version: 1,
  eventId: "event1",
  event: "run.completed",
  state: "idle",
  deviceId: "mac1",
  storedSessionId: "s1",
  runtimeSessionId: "r1",
  profile: "work",
  occurredAt: new Date().toISOString(),
  title: "private conversation",
};
test("Web Push validates key shapes and rejects arbitrary network destinations", () => {
  assert.deepEqual(parseWebSubscription(subscription), subscription);
  for (const endpoint of [
    "http://web.push.apple.com/x",
    "https://127.0.0.1/x",
    "https://web.push.apple.com.evil.invalid/x",
    "https://user:password@web.push.apple.com/x",
    "https://web.push.apple.com:8080/x",
    "https://evil.invalid/x",
  ])
    assert.throws(() => parseWebSubscription({ ...subscription, endpoint }));
  assert.throws(() =>
    parseWebSubscription({
      ...subscription,
      keys: { ...subscription.keys, auth: "invalid" },
    }),
  );
});
const p = {
  account: { id: "account1" },
  installation: { id: "install1", kind: "browser", platform: "web" },
  sessionId: "session1",
} as AccountPrincipal;
function memory() {
  const target: WebPushTarget = {
    accountId: p.account.id,
    installationId: p.installation.id,
    channelId: channel,
    language: "zh",
    subscription,
  };
  let current: WebPushTarget | null = null;
  const dropped: WebPushTarget[] = [];
  const store: WebPushStore = {
    get: async () => (current ? { channelId: current.channelId } : null),
    put: async () => {
      current = target;
    },
    remove: async () => {
      current = null;
    },
    targets: async () => [target],
    drop: async (t) => {
      dropped.push(t);
    },
  };
  return { store, dropped };
}
test("encrypted Web Push hints carry routing but no conversation title, with bounded delivery", async () => {
  const { store } = memory();
  const sent: any[] = [];
  const fanout = new WebPushFanout(
    store,
    keys.publicKey,
    keys.privateKey,
    "mailto:push@example.invalid",
    async (s, payload, options) => {
      sent.push({ s, payload, options });
    },
  );
  await fanout.notify(event);
  await fanout.notify({ ...event, event: "run.started", state: "working" });
  assert.equal(sent.length, 1);
  const payload = JSON.parse(sent[0].payload);
  assert.equal(payload.title, undefined);
  assert.equal(payload.accountId, "account1");
  assert.equal(payload.profile, "work");
  assert.equal(payload.channelId, channel);
  assert.equal(sent[0].options.timeout, 10000);
  assert.equal(sent[0].options.TTL, 600);
});
test("410/404 remove only the failed channel; transient failures preserve subscriptions and never fail ingestion", async () => {
  for (const statusCode of [410, 404, 503]) {
    const { store, dropped } = memory();
    const fanout = new WebPushFanout(
      store,
      keys.publicKey,
      keys.privateKey,
      "mailto:push@example.invalid",
      async () => {
        throw { statusCode };
      },
    );
    await fanout.notify(event);
    assert.equal(dropped.length, statusCode === 503 ? 0 : 1);
  }
});
class Reply {
  status = 0;
  body = "";
  writeHead(status: number) {
    this.status = status;
    return this;
  }
  end(data?: unknown) {
    if (data) this.body += String(data);
  }
}
async function call(
  method: string,
  options: {
    origin?: string;
    csrf?: string;
    kind?: string;
    enabled?: boolean;
    body?: unknown;
  } = {},
) {
  const { store } = memory();
  const principal = options.kind
    ? { ...p, installation: { ...p.installation, kind: options.kind } }
    : p;
  const security = new WebSessionSecurity("https://test.invalid");
  const controller = new AccountHttpController(
    true,
    { authenticate: async () => principal } as unknown as AccountService,
    {
      webSessionEnabled: true,
      webDeviceAccessEnabled: true,
      webSessionSecurity: security,
      ...(options.enabled === false
        ? {}
        : { webPush: { store, publicKey: keys.publicKey } }),
    },
  );
  const csrf = `hgc_${"c".repeat(43)}`;
  const body = JSON.stringify(
    options.body ?? {
      accountId: p.account.id,
      channelId: channel,
      subscription,
      language: "zh",
    },
  );
  const req = {
    method,
    headers: {
      origin: options.origin ?? "https://test.invalid",
      "sec-fetch-site": "same-origin",
      "x-hermes-csrf": options.csrf ?? csrf,
      cookie: `${WEB_COOKIE_NAMES.access}=hga_${"a".repeat(43)}; ${WEB_COOKIE_NAMES.csrf}=${csrf}`,
      "content-type": "application/json",
    },
    socket: { remoteAddress: "127.0.0.1" },
    async *[Symbol.asyncIterator]() {
      yield Buffer.from(body);
    },
  } as unknown as IncomingMessage;
  const reply = new Reply();
  await controller.handle(
    req,
    reply as unknown as ServerResponse,
    new URL("https://test.invalid/v2/web/push-subscription"),
  );
  return reply;
}
test("browser subscription mutations require exact Origin, CSRF and browser principal", async () => {
  assert.equal((await call("PUT")).status, 204);
  assert.equal((await call("DELETE")).status, 204);
  assert.equal((await call("GET")).status, 200);
  assert.equal(
    (await call("PUT", { origin: "https://evil.invalid" })).status,
    403,
  );
  assert.equal((await call("PUT", { csrf: "invalid" })).status, 403);
  assert.equal((await call("PUT", { kind: "phone" })).status, 403);
  assert.equal(
    (
      await call("PUT", {
        body: {
          accountId: "old-account",
          channelId: channel,
          subscription,
          language: "zh",
        },
      })
    ).status,
    403,
  );
  assert.equal((await call("PUT", { enabled: false })).status, 404);
  const bad = await call("PUT", {
    body: {
      accountId: p.account.id,
      channelId: channel,
      subscription: { ...subscription, endpoint: "https://127.0.0.1/private" },
      language: "zh",
    },
  });
  assert.equal(bad.status, 400);
  assert.match(bad.body, /HR-/);
  assert.doesNotMatch(bad.body, /127\.0\.0\.1|p256dh/);
});
