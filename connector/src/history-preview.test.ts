import assert from "node:assert/strict";
import { test } from "node:test";
import { historyProjection } from "./history-preview.js";

const path = "/api/sessions/session-1/messages?order=latest&limit=100&offset=0";
const long = "payload ".repeat(10_000);

test("legacy requests are untouched and opt-in preview shrinks tool and reasoning fields", async () => {
  let requested = "";
  const upstream = async (p: string) => {
    requested = p;
    return Response.json({ messages: [
      { id: 1, role: "user", content: "hello" },
      { id: 2, role: "assistant", content: "answer", reasoning: long, reasoning_content: long },
      { id: 3, role: "tool", content: long, tool_call_id: "t" },
    ], pagination: { limit: 100, offset: 0, order: "latest", returned: 3 } });
  };
  assert.equal(await historyProjection("GET", path, upstream), null);
  const response = await historyProjection("GET", `${path}&hr_preview=1`, upstream);
  assert.equal(response?.status, 200);
  assert.equal(requested, path);
  const page = await response!.json() as { messages: Array<Record<string, any>> };
  assert.equal(page.messages[0]!.content, "hello");
  assert.equal(page.messages[1]!.content, "answer");
  assert.equal(page.messages[1]!.reasoning.length, 160);
  assert.deepEqual(page.messages[1]!.hr_preview, { fields: ["reasoning", "reasoning_content"], offset: 0, sessionId: "session-1", profile: null });
  assert.equal(page.messages[2]!.content.length, 160);
  assert.deepEqual(page.messages[2]!.hr_preview, { fields: ["content"], offset: 0, sessionId: "session-1", profile: null });
  assert.ok(JSON.stringify(page).length < long.length / 10);
});

test("full row lookup validates id across drifting latest-page offsets", async () => {
  const offsets: number[] = [];
  const response = await historyProjection("GET", `${path}&hr_full_message_id=42&hr_full_offset=0`, async (p) => {
    const offset = Number(new URL(p, "http://local").searchParams.get("offset"));
    offsets.push(offset);
    return Response.json({ messages: offset === 100 ? [{ id: 42, role: "tool", content: long }] : [] });
  });
  assert.equal(response?.status, 200);
  assert.deepEqual(offsets, [0, 100]);
  assert.equal(((await response!.json()) as any).messages[0].content, long);
});

test("malformed or oversized opt-in pages fail with a retryable structured code", async () => {
  const malformed = await historyProjection("GET", `${path}&hr_preview=1`, async () => new Response("not JSON"));
  assert.equal(malformed?.status, 502);
  assert.deepEqual((await malformed!.json() as any).error, {
    code: "HR-SYNC-005", message: "History preview unavailable", retryable: true,
  });
  const oversized = await historyProjection("GET", `${path}&hr_preview=1`, async () =>
    new Response("", { headers: { "content-length": String(9 * 1024 * 1024) } }));
  assert.equal(oversized?.status, 502);
});

test("projection is limited to exact messages route and rejects unsafe row selectors", async () => {
  let called = 0;
  const upstream = async () => { called++; return Response.json({ messages: [] }); };
  assert.equal(await historyProjection("GET", "/api/sessions/s1/search?hr_preview=1", upstream), null);
  assert.equal(await historyProjection("POST", `${path}&hr_preview=1`, upstream), null);
  assert.equal((await historyProjection("GET", `${path}&hr_full_message_id=0&hr_full_offset=0`, upstream))?.status, 400);
  assert.equal((await historyProjection("GET", `${path}&hr_preview=1&hr_full_message_id=3`, upstream))?.status, 400);
  assert.equal((await historyProjection("GET", "/api/sessions/s%2Fother/messages?hr_preview=1", upstream))?.status, 400);
  assert.equal(called, 0);
});

test("a moved row that is absent from the bounded lookup returns the same retryable code", async () => {
  const result = await historyProjection("GET", `${path}&hr_full_message_id=42&hr_full_offset=100`, async () =>
    Response.json({ messages: [{ id: 7, role: "tool", content: "other" }] }));
  assert.equal(result?.status, 404);
  assert.equal(((await result!.json()) as any).error.code, "HR-SYNC-005");
});
