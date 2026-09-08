import assert from "node:assert/strict";
import type { IncomingMessage, ServerResponse } from "node:http";
import { test } from "node:test";
import { GatewayHttpRouter } from "./gateway-http-router.js";

test("Gateway routes the exact Resend callback before the account client surface", async () => {
  const calls: string[] = [];
  const router = new GatewayHttpRouter({
    serverRelease: { handle: async () => false },
    resendWebhook: { handle: async (_request: IncomingMessage, response: ServerResponse) => {
      calls.push("webhook");
      response.end();
    } },
    accountController: { handle: async () => { calls.push("account"); } },
  } as never);
  const response = { end: () => {} } as unknown as ServerResponse;
  await router.handle({
    method: "POST",
    url: "/v2/webhooks/resend",
    headers: { host: "localhost" },
  } as IncomingMessage, response);
  assert.deepEqual(calls, ["webhook"]);
});
