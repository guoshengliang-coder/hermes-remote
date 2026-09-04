import assert from "node:assert/strict";
import { once } from "node:events";
import { access } from "node:fs/promises";
import test from "node:test";
import { WebSocket } from "ws";
import {
  startProductionSmokeRuntime,
  withProductionSmokeRuntime,
} from "../../ops/lib/production-smoke-runtime.mjs";

test("R5-D production smoke runtime is loopback-only, isolated, and functional", async (t) => {
  let runtime;
  try {
    runtime = await startProductionSmokeRuntime({
      connectorEntry: process.execPath,
      baseEnvironment: {
        PATH: process.env.PATH,
        NODE_EXTRA_CA_CERTS: "/one-time/ca.pem",
        HERMES_SESSION_TOKEN: "must-not-leak",
        UNRELATED_SECRET: "must-not-leak",
      },
    });
  } catch (error) {
    if (/EACCES|EPERM/.test(error?.technicalCause ?? "")) {
      t.skip("local sandbox does not permit loopback listeners");
      return;
    }
    throw error;
  }
  t.after(() => runtime?.close());

  assert.match(runtime.origin, /^http:\/\/127\.0\.0\.1:\d+$/);
  assert.equal(runtime.environment.HERMES_BASE_URL, runtime.origin);
  assert.equal(runtime.environment.HERMES_MODE, "live");
  assert.equal(runtime.environment.SESSION_OBSERVER_ENABLED, "0");
  assert.equal(runtime.environment.NODE_EXTRA_CA_CERTS, "/one-time/ca.pem");
  assert.equal("HERMES_SESSION_TOKEN" in runtime.environment, false);
  assert.equal("UNRELATED_SECRET" in runtime.environment, false);
  assert.match(runtime.environment.HERMES_BASIC_AUTH_USERNAME, /^[A-Za-z0-9_-]{32,}$/);
  assert.match(runtime.environment.HERMES_BASIC_AUTH_PASSWORD, /^[A-Za-z0-9_-]{32,}$/);
  assert.notEqual(runtime.environment.HERMES_BASIC_AUTH_USERNAME, runtime.environment.HERMES_BASIC_AUTH_PASSWORD);

  const login = await fetch(`${runtime.origin}/auth/password-login`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      provider: "basic",
      username: runtime.environment.HERMES_BASIC_AUTH_USERNAME,
      password: runtime.environment.HERMES_BASIC_AUTH_PASSWORD,
    }),
  });
  assert.equal(login.status, 200);
  const cookie = login.headers.get("set-cookie")?.split(";", 1)[0];
  assert.ok(cookie);
  const status = await fetch(`${runtime.origin}/api/status`, { headers: { cookie } });
  assert.deepEqual(await status.json(), { status: "ok", version: "mock-hermes" });
  const ticketResponse = await fetch(`${runtime.origin}/api/auth/ws-ticket`, {
    method: "POST",
    headers: { cookie },
  });
  const ticket = (await ticketResponse.json()).ticket;
  assert.match(ticket, /^[0-9a-f-]{36}$/);

  const socket = new WebSocket(`${runtime.origin.replace("http:", "ws:")}/api/ws?ticket=${ticket}`);
  const [ready] = await once(socket, "message");
  assert.equal(JSON.parse(ready.toString()).params.type, "gateway.ready");
  socket.send(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "session.active_list", params: {} }));
  const [response] = await once(socket, "message");
  assert.deepEqual(JSON.parse(response.toString()).result, { sessions: [] });
  socket.close();
  await once(socket, "close");

  const root = runtime.root;
  const origin = runtime.origin;
  await runtime.close();
  await assert.rejects(() => access(root), { code: "ENOENT" });
  await assert.rejects(() => fetch(`${origin}/api/status`, { signal: AbortSignal.timeout(250) }));
});

test("R5-D production smoke runtime removes private state after a failed operation", async (t) => {
  const primaryFailure = new Error("deliberate-operation-failure");
  let root;
  let origin;
  let invoked = false;
  let child;
  try {
    await withProductionSmokeRuntime(async (runtime) => {
      invoked = true;
      root = runtime.root;
      origin = runtime.origin;
      child = runtime.spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"]);
      throw primaryFailure;
    }, { connectorEntry: process.execPath });
    assert.fail("failed smoke operation unexpectedly succeeded");
  } catch (error) {
    if (!invoked && /EACCES|EPERM/.test(error?.technicalCause ?? "")) {
      t.skip("local sandbox does not permit loopback listeners");
      return;
    }
    assert.equal(error, primaryFailure);
  }
  if (child.exitCode === null && child.signalCode === null) await once(child, "exit");
  assert.equal(child.signalCode, "SIGKILL");
  await assert.rejects(() => access(root), { code: "ENOENT" });
  await assert.rejects(() => fetch(`${origin}/api/status`, { signal: AbortSignal.timeout(250) }));
});
