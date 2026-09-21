import assert from "node:assert/strict";
import test from "node:test";
import { InFlightHttpRequests, ResponseChunkWaiters } from "./http-request-lifecycle.js";

test("HTTP cancellation is idempotent and aborts an outstanding chunk acknowledgement", async () => {
  const requests = new InFlightHttpRequests();
  const waiters = new ResponseChunkWaiters();
  const controller = requests.begin("request-1");
  const waiting = waiters.wait("request-1", 0, 5_000, controller.signal);

  assert.equal(requests.cancel("request-1", "client_aborted"), true);
  assert.equal(requests.cancel("request-1", "client_aborted"), false);
  await assert.rejects(waiting, /client_aborted/);
  assert.equal(waiters.size, 0);
  requests.finish("request-1", controller);
  assert.equal(requests.size, 0);
});

test("closing the control socket aborts every in-flight HTTP request", () => {
  const requests = new InFlightHttpRequests();
  const first = requests.begin("request-1");
  const second = requests.begin("request-2");

  assert.equal(requests.abortAll("control_socket_closed"), 2);
  assert.equal(first.signal.aborted, true);
  assert.equal(second.signal.aborted, true);
  assert.equal(requests.size, 0);
});

test("an already-cancelled request never leaves a chunk waiter behind", async () => {
  const waiters = new ResponseChunkWaiters();
  const controller = new AbortController();
  controller.abort(new Error("gateway_timeout"));

  await assert.rejects(waiters.wait("request-1", 0, 5_000, controller.signal), /gateway_timeout/);
  assert.equal(waiters.size, 0);
});
