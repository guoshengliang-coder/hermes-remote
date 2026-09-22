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

// ---- Web batch 4: methods admitted in one shape only ------------------------------------------

const forwarded = (method: string, params: unknown): boolean =>
  screenBrowserFrame(frame({ jsonrpc: "2.0", id: 9, method, params }), false).forward;

test("slash.exec only switches the current session's model", () => {
  const ok = { session_id: "live-1", command: "/model claude-opus-5 --provider anthropic --session" };
  assert.equal(forwarded("slash.exec", ok), true);
  assert.equal(forwarded("slash.exec", { ...ok, command: "/model openrouter/anthropic/claude-3.5 --provider openrouter --session", profile: "work" }), true);
  assert.equal(forwarded("slash.exec", { ...ok, command: "/model qwen2.5:7b --provider ollama --session" }), true);
  for (const command of [
    "/model claude-opus-5 --provider anthropic", // global switch
    "/model claude-opus-5 --session", // no provider
    "/model x --provider p --session; /reset",
    "/model x --provider p --session\n/reset",
    "/model x --provider p --session /reset",
    "/model x　--provider p --session", // ideographic space
    "/model \"x y\" --provider p --session",
    "/reset",
    "/config set model x",
    " /model x --provider p --session",
  ]) {
    assert.equal(forwarded("slash.exec", { ...ok, command }), false, command);
  }
  assert.equal(forwarded("slash.exec", { command: ok.command }), false, "session_id is required");
  assert.equal(forwarded("slash.exec", { ...ok, extra: 1 }), false, "no extra keys");
  assert.equal(forwarded("slash.exec", "not an object"), false);
});

test("config.get / config.set only touch the current session's reasoning effort", () => {
  assert.equal(forwarded("config.get", { key: "reasoning", session_id: "live-1" }), true);
  for (const value of ["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]) {
    assert.equal(forwarded("config.set", { key: "reasoning", session_id: "live-1", value }), true, value);
  }
  assert.equal(forwarded("config.set", { key: "reasoning", session_id: "live-1", value: "turbo" }), false);
  assert.equal(forwarded("config.set", { key: "reasoning", session_id: "live-1", value: "high", scope: "global" }), false);
  assert.equal(forwarded("config.set", { key: "reasoning", session_id: "live-1", value: "high", confirm_expensive_model: true }), false);
  assert.equal(forwarded("config.set", { key: "model", session_id: "live-1", value: "x" }), false);
  assert.equal(forwarded("config.set", { key: "reasoning", value: "high" }), false, "session_id is required");
  assert.equal(forwarded("config.get", { key: "api_key", session_id: "live-1" }), false);
  assert.equal(forwarded("config.get", { key: "reasoning", session_id: "live-1", cwd: "/" }), false);
});

test("workspace move, process list and session access are admitted with their own keys only", () => {
  assert.equal(forwarded("session.workspace.move", { session_key: "20260922_101500_ab12cd", cwd: "/Users/me/proj", profile: "工作" }), true);
  assert.equal(forwarded("session.workspace.move", { session_key: "s", cwd: "relative/dir" }), false);
  assert.equal(forwarded("session.workspace.move", { session_key: "s", cwd: "/a\nb" }), false);
  assert.equal(forwarded("session.workspace.move", { session_key: "s", cwd: `/${"a".repeat(1100)}` }), false);
  assert.equal(forwarded("session.workspace.move", { session_key: "../x", cwd: "/a" }), false);
  assert.equal(forwarded("session.workspace.move", { session_key: "s", cwd: "/a", create: true }), false);
  assert.equal(forwarded("process.list", { session_id: "live-1" }), true);
  assert.equal(forwarded("process.list", { session_id: "live-1", kill: "all" }), false);
  assert.equal(forwarded("session.access", { session_id: "s", profile: "work", live_session_id: "live-1" }), true);
  assert.equal(forwarded("session.access", { session_id: "s", acquire: true }), false);
  assert.equal(forwarded("session.access", { session_id: "s", profile: "a/b" }), false);
});
