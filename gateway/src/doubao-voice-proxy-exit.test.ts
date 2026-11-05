import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

test("real voice socket tests exit naturally without the WebSocket close timeout", async () => {
  const env = { ...process.env };
  delete env.NODE_TEST_CONTEXT;
  // Run directly so the timeout owns the only fixture process. A test-runner grandchild could
  // otherwise outlive a killed runner. The generous budget detects ws's leaked 30-second timer,
  // not minor scheduler differences; no force-exit flag is used to make the test pass.
  const { stdout } = await promisify(execFile)(process.execPath, [
    fileURLToPath(new URL("./doubao-voice-proxy.test.js", import.meta.url)),
  ], { env, timeout: 5_000, maxBuffer: 1024 * 1024 });
  assert.match(stdout, /voice proxy authenticates upstream/);
  assert.match(stdout, /voice revocation closes only the matching account session/);
  assert.match(stdout, /speech sockets recheck session liveness/);
  assert.match(stdout, /^# fail 0$/m);
  assert.match(stdout, /^# cancelled 0$/m);
});
