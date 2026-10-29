import assert from "node:assert/strict";
import test from "node:test";
import { BusinessWire } from "./business-wire.js";

test("large wire frames are bounded and other request flows pass before reassembly completes", (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] });
  const sent: string[] = [];
  const sender = new BusinessWire((raw) => sent.push(raw), () => 0, () => assert.fail());
  const receiver = new BusinessWire(() => {}, () => 0, () => assert.fail());
  t.after(() => { sender.stop(); receiver.stop(); });
  const large = JSON.stringify({ type: "tunnel.ws.frame", id: "a", data: "a".repeat(256_000) });
  sender.send(large, "ws:a");
  t.mock.timers.tick(10); assert.ok(Buffer.byteLength(sent[0]!) < 48 * 1024);
  sender.send('{"type":"small"}', "b");
  sender.send('{"type":"after"}', "ws:a");
  t.mock.timers.tick(100);
  const received = sent.flatMap((raw) => { const result = receiver.receive(raw); return result ? [result] : []; });
  assert.deepEqual(received, ['{"type":"small"}', large, '{"type":"after"}']);
  assert.throws(() => receiver.receive(sent[0]!.replace('"sequence":0', '"sequence":2')), /fragment/);
});

test("stalled fragments expire and shutdown clears queues", (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] }); let now = 0, failed = 0;
  const wire = new BusinessWire(() => {}, () => 0, () => { failed++; }, () => now);
  wire.receive(JSON.stringify({ type: "transport.fragment", version: 1, id: "a".repeat(36), flow: "a", sequence: 0, final: false, data: Buffer.alloc(32 * 1024).toString("base64") }));
  now = 75_001; t.mock.timers.tick(10); assert.equal(failed, 1);
  assert.equal(wire.send("{}", "a"), false);
});

test("waiting for the shared bulk budget never holds a small request on another flow", async (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] });
  let release!: () => void; const sent: string[] = [];
  const wire = new BusinessWire((raw) => sent.push(raw), () => 0, () => assert.fail(), () => 0,
    async () => new Promise<void>((resolve) => { release = resolve; }));
  t.after(() => wire.stop());
  wire.send(JSON.stringify({ data: "a".repeat(64_000) }), "bulk"); t.mock.timers.tick(10);
  wire.send('{"type":"chat"}', "chat");
  assert.deepEqual(sent, ['{"type":"chat"}']);
  release(); await Promise.resolve(); await Promise.resolve();
});

test("cross-lane cancellation clears partial assembly and late fragments do not kill unrelated chat", (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] }); let now = 0;
  const sent: string[] = [];
  const sender = new BusinessWire((raw) => sent.push(raw), () => 0, () => assert.fail());
  const receiver = new BusinessWire(() => {}, () => 0, () => assert.fail(), () => now);
  t.after(() => { sender.stop(); receiver.stop(); });
  sender.send(JSON.stringify({ type: "response", requestId: "cancelled", data: "a".repeat(200_000) }), "cancelled");
  t.mock.timers.tick(20);
  receiver.receive(sent[0]!); receiver.cancel("cancelled"); sender.cancel("cancelled");
  assert.equal(receiver.receive(sent[1]!), undefined);
  now = 80_000; t.mock.timers.tick(10);
  assert.equal(receiver.receive('{"type":"chat"}'), '{"type":"chat"}');
});
