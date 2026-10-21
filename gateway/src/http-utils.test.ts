import assert from "node:assert/strict";
import test from "node:test";
import { selectResponseHeaders } from "./http-utils.js";

test("file metadata forwards length and limit without exposing unrelated headers", () => {
  assert.deepEqual(selectResponseHeaders({
    "content-length": "132120576", "x-hermes-max-file-bytes": "104857600",
    "cache-control": "no-store", "set-cookie": "secret", "authorization": "secret",
  }), { "content-length": "132120576", "cache-control": "no-store", "x-hermes-max-file-bytes": "104857600" });
});
