import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { test } from "node:test";
import type { SessionLifecycleEvent } from "@hermes-remote/protocol";
import { AccountHttpController } from "./account/account-http-controller.js";
import type { AccountControlService } from "./account/account-control-service.js";
import type { AccountService } from "./account/account-service.js";
import type { AccountPrincipal } from "./account/model.js";
import { PushFanout, shouldPush, wakeHint } from "./account/push/push-fanout.js";
import {
  FcmPushProvider,
  fcmMessage,
  parseFcmServiceAccount,
  type PushProvider,
  type PushSendResult,
  type PushWakeHint,
} from "./account/push/push-provider.js";
import type { PushRegistrationStore, PushTarget } from "./account/push/push-registration-store.js";

const PHONE_ID = "b5791214-1583-4737-a809-b3f2f03b3c61";

function lifecycle(event: SessionLifecycleEvent["event"]): SessionLifecycleEvent {
  return {
    type: "session.lifecycle",
    version: 1,
    eventId: `evt-${event}`,
    deviceId: "hermes-device",
    profile: "default",
    runtimeSessionId: "runtime-1",
    storedSessionId: "stored-1",
    event,
    state: event === "run.waiting" ? "waiting" : "idle",
    occurredAt: "2026-09-22T08:00:00.000Z",
    title: "Quarterly salary review for Alice",
  };
}

test("only waiting, completed and failed lifecycle events wake phones", () => {
  assert.equal(shouldPush("run.waiting"), true);
  assert.equal(shouldPush("run.completed"), true);
  assert.equal(shouldPush("run.interrupted"), true);
  assert.equal(shouldPush("run.unknown"), true);
  assert.equal(shouldPush("run.started"), false);
  assert.equal(shouldPush("run.resumed"), false);
});

test("the FCM message is a high-priority data-only hint without the session title", () => {
  const body = fcmMessage("token-1", wakeHint(lifecycle("run.completed")));
  const serialized = JSON.stringify(body);
  assert.equal(serialized.includes("Quarterly salary"), false);
  assert.equal(serialized.includes("title"), false);
  const message = body.message as {
    token: string;
    notification?: unknown;
    data: Record<string, string>;
    android: { priority: string; notification?: unknown };
  };
  assert.equal(message.token, "token-1");
  assert.equal(message.notification, undefined);
  assert.equal(message.android.notification, undefined);
  assert.equal(message.android.priority, "HIGH");
  assert.deepEqual(message.data, {
    type: "hermes.lifecycle",
    eventId: "evt-run.completed",
    event: "run.completed",
    state: "idle",
    deviceId: "hermes-device",
    storedSessionId: "stored-1",
    runtimeSessionId: "runtime-1",
    occurredAt: "2026-09-22T08:00:00.000Z",
    profile: "default",
  });
  for (const value of Object.values(message.data)) assert.equal(typeof value, "string");
});

test("FcmPushProvider maps FCM responses to sent, token_invalid and failed", async () => {
  const cases: Array<[() => Promise<Response>, PushSendResult]> = [
    [async () => new Response("{}", { status: 200 }), "sent"],
    [async () => new Response("{}", { status: 404 }), "token_invalid"],
    [async () => Response.json(
      { error: { details: [{ errorCode: "UNREGISTERED" }] } },
      { status: 400 },
    ), "token_invalid"],
    [async () => Response.json(
      { error: { details: [{ errorCode: "INVALID_ARGUMENT" }] } },
      { status: 400 },
    ), "failed"],
    [async () => new Response("", { status: 503 }), "failed"],
    [async () => { throw new Error("network down"); }, "failed"],
  ];
  for (const [respond, expected] of cases) {
    const requests: Array<{ url: string; init: RequestInit }> = [];
    const provider = new FcmPushProvider(
      "hermes-go-test",
      { getAccessToken: async () => "access-token" },
      (async (url: string, init: RequestInit) => {
        requests.push({ url, init });
        return respond();
      }) as unknown as typeof fetch,
    );
    assert.equal(await provider.send("token-1", wakeHint(lifecycle("run.waiting"))), expected);
    assert.equal(requests[0]?.url, "https://fcm.googleapis.com/v1/projects/hermes-go-test/messages:send");
    assert.equal((requests[0]?.init.headers as Record<string, string>).authorization, "Bearer access-token");
  }
});

test("FcmPushProvider treats an access-token failure as a failed send", async () => {
  const provider = new FcmPushProvider(
    "hermes-go-test",
    { getAccessToken: async () => { throw new Error("bad key"); } },
    (async () => { throw new Error("must not be called"); }) as unknown as typeof fetch,
  );
  assert.equal(await provider.send("token-1", wakeHint(lifecycle("run.waiting"))), "failed");
});

test("service-account parsing accepts a real-shaped key and rejects anything else", () => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const pem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  const parsed = parseFcmServiceAccount(JSON.stringify({
    type: "service_account",
    project_id: "hermes-go-test",
    client_email: "push@hermes-go-test.iam.gserviceaccount.com",
    private_key: pem,
  }));
  assert.equal(parsed.projectId, "hermes-go-test");
  assert.throws(() => parseFcmServiceAccount("not json"));
  assert.throws(() => parseFcmServiceAccount(JSON.stringify({ type: "authorized_user" })));
  assert.throws(() => parseFcmServiceAccount(JSON.stringify({
    type: "service_account",
    project_id: "Bad Project",
    client_email: "x@y",
    private_key: pem,
  })));
});

class MemoryStore implements PushRegistrationStore {
  targets: PushTarget[] = [];
  removed: Array<[string, string]> = [];
  failList = false;

  async upsert(_account: string, installationId: string, provider: "fcm", token: string) {
    this.targets = [...this.targets.filter((t) => t.installationId !== installationId), {
      installationId,
      provider,
      token,
    }];
  }

  async remove(_account: string, installationId: string) {
    this.targets = this.targets.filter((t) => t.installationId !== installationId);
  }

  async listTargets(): Promise<PushTarget[]> {
    if (this.failList) throw new Error("db down");
    return this.targets;
  }

  async removeToken(installationId: string, token: string) {
    this.removed.push([installationId, token]);
  }
}

class RecordingProvider implements PushProvider {
  readonly name = "fcm" as const;
  sent: Array<[string, PushWakeHint]> = [];
  constructor(private readonly results: Record<string, PushSendResult>) {}

  async send(token: string, hint: PushWakeHint): Promise<PushSendResult> {
    this.sent.push([token, hint]);
    return this.results[token] ?? "sent";
  }
}

test("PushFanout sends each registration once, drops dead tokens and never throws", async () => {
  const store = new MemoryStore();
  store.targets = [
    { installationId: "phone-a", provider: "fcm", token: "live" },
    { installationId: "phone-b", provider: "fcm", token: "dead" },
    { installationId: "phone-c", provider: "fcm", token: "flaky" },
  ];
  const provider = new RecordingProvider({ dead: "token_invalid", flaky: "failed" });
  const failures: string[] = [];
  const fanout = new PushFanout(store, [provider], (message) => failures.push(message));

  await fanout.notify("account-1", lifecycle("run.started"));
  assert.equal(provider.sent.length, 0);

  await fanout.notify("account-1", lifecycle("run.completed"));
  assert.deepEqual(provider.sent.map(([token]) => token).sort(), ["dead", "flaky", "live"]);
  assert.deepEqual(store.removed, [["phone-b", "dead"]]);
  assert.deepEqual(fanout.snapshot(), { sent: 1, failed: 1, tokensDropped: 1 });
  assert.equal(failures.length, 1);
  assert.equal(failures.some((message) => message.includes("flaky")), false);

  store.failList = true;
  await fanout.notify("account-1", lifecycle("run.waiting"));
  assert.equal(fanout.snapshot().failed, 2);
});

function principal(kind: "phone" | "desktop"): AccountPrincipal {
  return {
    account: { id: "account-1" },
    installation: {
      id: PHONE_ID,
      kind,
      platform: kind === "phone" ? "android" : "macos",
      displayName: "Phone",
    },
    sessionId: "session-1",
    refreshFamilyId: "family-1",
  } as AccountPrincipal;
}

function controller(store: MemoryStore | undefined, kind: "phone" | "desktop" = "phone") {
  return new AccountHttpController(true, {
    authenticate: async () => principal(kind),
  } as unknown as AccountService, {
    controlEnabled: true,
    controlService: {} as AccountControlService,
    ...(store ? { pushRegistration: { store, providers: ["fcm" as const] } } : {}),
  });
}

async function call(
  target: AccountHttpController,
  method: string,
  path: string,
  body?: unknown,
): Promise<MemoryResponse> {
  const response = new MemoryResponse();
  const payload = body === undefined ? "" : JSON.stringify(body);
  await target.handle({
    method,
    headers: {
      authorization: "Bearer access",
      ...(body === undefined ? {} : { "content-type": "application/json" }),
    },
    socket: { remoteAddress: "127.0.0.1" },
    async *[Symbol.asyncIterator]() {
      if (payload.length > 0) yield Buffer.from(payload);
    },
  } as unknown as IncomingMessage, response as unknown as ServerResponse, new URL(`http://localhost${path}`));
  return response;
}

test("capabilities advertise push only when a provider is configured", async () => {
  const withPush = await call(controller(new MemoryStore()), "GET", "/v2/capabilities");
  assert.deepEqual((withPush.json() as { push?: unknown }).push, { providers: ["fcm"] });
  const withoutPush = await call(controller(undefined), "GET", "/v2/capabilities");
  assert.equal((withoutPush.json() as { push?: unknown }).push, undefined);
});

test("push registration routes upsert and remove the current Android installation only", async () => {
  const store = new MemoryStore();
  const target = controller(store);
  const path = "/v2/installations/current/push-registration";

  assert.equal((await call(target, "PUT", path, { provider: "fcm", token: "abc:DEF_12-3" })).status, 204);
  assert.deepEqual(store.targets, [{ installationId: PHONE_ID, provider: "fcm", token: "abc:DEF_12-3" }]);
  assert.equal((await call(target, "PUT", path, { provider: "fcm", token: "rotated" })).status, 204);
  assert.deepEqual(store.targets.map((t) => t.token), ["rotated"]);

  const badProvider = await call(target, "PUT", path, { provider: "apns", token: "x" });
  assert.equal(badProvider.status, 400);
  assert.equal((badProvider.json() as { error: { code: string } }).error.code, "HR-ACCOUNT-004");
  assert.equal((await call(target, "PUT", path, { provider: "fcm", token: "" })).status, 400);
  assert.equal((await call(target, "PUT", path, { provider: "fcm", token: "has space" })).status, 400);

  assert.equal((await call(target, "DELETE", path)).status, 204);
  assert.deepEqual(store.targets, []);
  assert.equal((await call(target, "DELETE", path)).status, 204);

  const desktop = await call(controller(new MemoryStore(), "desktop"), "PUT", path, { provider: "fcm", token: "t" });
  assert.equal(desktop.status, 400);

  const unconfigured = await call(controller(undefined), "PUT", path, { provider: "fcm", token: "t" });
  assert.equal(unconfigured.status, 404);
  assert.equal((unconfigured.json() as { error: { code: string } }).error.code, "HR-ACCOUNT-006");
});

class MemoryResponse {
  status = 0;
  body = "";
  writableEnded = false;

  writeHead(status: number): this {
    this.status = status;
    return this;
  }

  setHeader(): this {
    return this;
  }

  end(value?: string): this {
    this.body = value ?? "";
    this.writableEnded = true;
    return this;
  }

  json(): unknown {
    return JSON.parse(this.body);
  }
}
