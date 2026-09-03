import assert from "node:assert/strict";
import type { IncomingMessage } from "node:http";
import test from "node:test";
import {
  nonNegativeIntegerQuery,
  parseEventIds,
  positiveIntegerQuery,
  selectRequestHeaders,
  selectResponseHeaders,
} from "./http-utils.js";
import { rawDataToBuffer, safeCloseCode } from "./websocket-utils.js";

test("HTTP utilities preserve the Relay header allowlists", () => {
  const request = {
    headers: {
      accept: "application/json",
      "content-type": "application/json",
      authorization: "must-not-forward",
      cookie: "must-not-forward",
    },
  } as IncomingMessage;
  assert.deepEqual(selectRequestHeaders(request), {
    accept: "application/json",
    "content-type": "application/json",
  });
  assert.deepEqual(selectResponseHeaders({
    "content-type": "text/plain",
    "content-length": "4",
    "content-disposition": "attachment",
    "cache-control": "private",
    "set-cookie": "must-not-forward",
  }), {
    "content-type": "text/plain",
    "content-length": "4",
    "content-disposition": "attachment",
    "cache-control": "private",
  });
});

test("mobile event helpers retain their bounds and pagination rules", () => {
  assert.deepEqual(parseEventIds(Buffer.from('{"event_ids":["a","b"]}')), ["a", "b"]);
  assert.throws(() => parseEventIds(Buffer.from('{"event_ids":[""]}')), /invalid_event_id/);
  assert.throws(() => parseEventIds(Buffer.from("{}")), /invalid_event_ids/);

  const url = new URL("https://relay.example/api/mobile/events?after=4&limit=20");
  assert.equal(nonNegativeIntegerQuery(url, "after", 0), 4);
  assert.equal(positiveIntegerQuery(url, "limit", 100, 500), 20);
  assert.equal(positiveIntegerQuery(new URL("https://relay.example/?limit=0"), "limit", 100, 500), undefined);
});

test("WebSocket helpers preserve close-code sanitization and binary conversion", () => {
  assert.equal(safeCloseCode(1000), 1000);
  assert.equal(safeCloseCode(4403), 4403);
  assert.equal(safeCloseCode(1005), 1011);
  assert.equal(safeCloseCode(9999), 1011);
  assert.equal(rawDataToBuffer(Buffer.from("frame")).toString(), "frame");
  assert.equal(rawDataToBuffer([Buffer.from("a"), Buffer.from("b")]).toString(), "ab");
});
