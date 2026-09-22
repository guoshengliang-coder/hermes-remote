import assert from "node:assert/strict";
import test from "node:test";
import { screenBrowserFrame } from "./account/web-rpc-filter.js";

const frame = (value: unknown): Buffer => Buffer.from(JSON.stringify(value));

test("browser tunnels forward the chat client's methods and answers to server requests", () => {
  for (const method of [
    "client.capabilities", "session.create", "session.resume", "prompt.submit", "session.interrupt",
    "image.attach", "file.attach", "request.answer", "clarify.lock", "approval.respond",
    "clarify.respond",
  ]) {
    assert.deepEqual(screenBrowserFrame(frame({ jsonrpc: "2.0", id: 1, method, params: {} }), false), {
      forward: true,
      replies: [],
    }, method);
  }
  // The client's refusal of a `sudo` server request carries no method.
  assert.equal(
    screenBrowserFrame(frame({ jsonrpc: "2.0", id: "srq-1", error: { code: -32601 } }), false).forward,
    true,
  );
});

test("administration methods are refused in-band with HR-WEB-001 and never forwarded", () => {
  for (const method of ["config.set", "slash.exec", "projects.create", "cron.create", "session.delete", "model.set"]) {
    const screen = screenBrowserFrame(frame({ jsonrpc: "2.0", id: 7, method, params: {} }), false);
    assert.equal(screen.forward, false, method);
    assert.equal(screen.violation, undefined);
    const reply = JSON.parse(screen.replies[0]!) as { id: number; error: { code: number; data: { code: string } } };
    assert.equal(reply.id, 7);
    assert.equal(reply.error.code, 4403);
    assert.equal(reply.error.data.code, "HR-WEB-001");
  }
  // A notification (no id) is dropped without a reply.
  assert.deepEqual(screenBrowserFrame(frame({ jsonrpc: "2.0", method: "config.set" }), false), {
    forward: false,
    replies: [],
  });
});

test("one refused frame in a multi-frame message drops the whole message", () => {
  const message = Buffer.from([
    JSON.stringify({ jsonrpc: "2.0", id: 1, method: "prompt.submit", params: {} }),
    JSON.stringify({ jsonrpc: "2.0", id: 2, method: "config.set", params: {} }),
  ].join("\n"));
  const screen = screenBrowserFrame(message, false);
  assert.equal(screen.forward, false);
  assert.equal(screen.replies.length, 1);
});

test("binary, non-JSON and non-object frames close the tunnel", () => {
  assert.ok(screenBrowserFrame(Buffer.from([1, 2, 3]), true).violation);
  assert.ok(screenBrowserFrame(Buffer.from("not json"), false).violation);
  assert.ok(screenBrowserFrame(Buffer.from("[1,2]"), false).violation);
  assert.ok(screenBrowserFrame(Buffer.from("null"), false).violation);
});
