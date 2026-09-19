import assert from "node:assert/strict";
import test from "node:test";
import { OVERSIZED_FRAME_RPC_CODE, decideOversizedFrame, readRpcId } from "./oversized-frame.js";

const LIMIT = 64;

function frameOf(bytes: number, head = ""): Buffer {
  return Buffer.concat([Buffer.from(head, "utf8"), Buffer.alloc(Math.max(0, bytes - head.length), 0x20)]);
}

test("a frame within the limit is forwarded untouched", () => {
  const decision = decideOversizedFrame(frameOf(LIMIT, '{"jsonrpc":"2.0","id":7,"result":{}}'), LIMIT);

  assert.equal(decision.forward, true);
  assert.equal(decision.replacement, null);
});

test("an oversized response is answered instead of killing the tunnel", () => {
  // The shape that took HG-65 down: one session.resume answer larger than the relay can carry.
  const decision = decideOversizedFrame(frameOf(LIMIT + 1, '{"jsonrpc":"2.0","id":42,"result":{'), LIMIT);

  assert.equal(decision.forward, false);
  assert.equal(decision.rpcId, 42);
  const replacement = JSON.parse(decision.replacement ?? "");
  assert.equal(replacement.id, 42);
  assert.equal(replacement.error.code, OVERSIZED_FRAME_RPC_CODE);
  // The size and the limit both appear, so the phone's diagnostics say which one was exceeded.
  assert.match(replacement.error.message, new RegExp(String(LIMIT + 1)));
  assert.match(replacement.error.message, new RegExp(String(LIMIT)));
});

test("the error code stays inside JSON-RPC's reserved implementation range", () => {
  // Upstream Hermes owns the positive 4xxx/5xxx codes; minting one there would collide with a code
  // it may define later, and we cannot version-negotiate with it.
  assert.ok(OVERSIZED_FRAME_RPC_CODE <= -32000 && OVERSIZED_FRAME_RPC_CODE >= -32099);
});

test("an oversized event carries no id, so it is dropped silently", () => {
  const decision = decideOversizedFrame(frameOf(LIMIT + 1, '{"jsonrpc":"2.0","method":"event","params":{'), LIMIT);

  assert.equal(decision.forward, false);
  assert.equal(decision.replacement, null);
  assert.equal(decision.rpcId, null);
});

test("the id is read from the envelope without parsing the whole frame", () => {
  // 40 MiB of padding: parsing this to learn one integer is the cost this path exists to avoid.
  const huge = frameOf(40 * 1024 * 1024, '{"jsonrpc":"2.0","id":1234,"result":{"messages":[');

  assert.equal(readRpcId(huge), 1234);
});

test("an id past the scanned prefix is not guessed at", () => {
  const buried = Buffer.concat([
    Buffer.from(`{"jsonrpc":"2.0","result":"${"x".repeat(8192)}"`, "utf8"),
    Buffer.from(',"id":9}', "utf8"),
  ]);

  // Better to drop it than to answer a request that was never made: a wrong id would resolve some
  // other in-flight call with an error it has nothing to do with.
  assert.equal(readRpcId(buried), null);
});

test("a string id is left alone rather than coerced", () => {
  assert.equal(readRpcId(Buffer.from('{"jsonrpc":"2.0","id":"abc","result":1}', "utf8")), null);
});

test("whitespace around the id does not hide it", () => {
  assert.equal(readRpcId(Buffer.from('{ "id" : 5 , "result" : 1 }', "utf8")), 5);
});
