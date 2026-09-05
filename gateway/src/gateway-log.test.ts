import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createGatewayLogger,
  formatGatewayLogLine,
  parseGatewayLogLevel,
  sanitizeLogFields,
} from "./gateway-log.js";

test("log level defaults to info and rejects unknown values", () => {
  assert.equal(parseGatewayLogLevel(undefined), "info");
  assert.equal(parseGatewayLogLevel(""), "info");
  assert.equal(parseGatewayLogLevel(" DEBUG "), "debug");
  assert.equal(parseGatewayLogLevel("off"), "off");
  assert.throws(() => parseGatewayLogLevel("verbose"), /GATEWAY_LOG_LEVEL/);
});

test("a line is one JSON object with ts, level, kind and the fields", () => {
  const line = formatGatewayLogLine("info", "app.tunnel.open", { device: "mac-mini", tunnels: 1 }, new Date("2026-09-05T02:31:08.000Z"));
  assert.deepEqual(JSON.parse(line), {
    ts: "2026-09-05T02:31:08.000Z",
    level: "info",
    kind: "app.tunnel.open",
    device: "mac-mini",
    tunnels: 1,
  });
  assert.equal(line.includes("\n"), false);
});

test("credential-shaped keys never reach a line and strings are bounded", () => {
  const safe = sanitizeLogFields({
    appToken: "secret-value",
    authorization: "Bearer x",
    cookie: "session=abc",
    wsTicket: "t",
    device: "mac-mini",
    reason: "x".repeat(500),
    error: new Error("boom"),
    nested: { a: 1 },
    missing: undefined,
  });
  assert.deepEqual(Object.keys(safe).sort(), ["device", "error", "nested", "reason"]);
  assert.equal((safe.reason as string).length, 201);
  assert.equal(safe.error, "boom");
  assert.equal(safe.nested, "{\"a\":1}");
});

test("levels gate what is written", () => {
  const lines: string[] = [];
  const info = createGatewayLogger("info", (line) => lines.push(line), () => new Date(0));
  info.debug("app.control.open", {});
  info.info("connector.online", { device: "mac-mini" });
  info.error("failure", { message: "x" });
  assert.deepEqual(lines.map((l) => JSON.parse(l).kind), ["connector.online", "failure"]);
  assert.equal(info.enabled("debug"), false);
  assert.equal(info.enabled("info"), true);

  const off: string[] = [];
  const silent = createGatewayLogger("off", (line) => off.push(line));
  silent.error("failure", {});
  assert.deepEqual(off, []);
});
