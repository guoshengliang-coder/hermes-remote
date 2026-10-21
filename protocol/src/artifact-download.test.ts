import assert from "node:assert/strict";
import test from "node:test";
import { artifactFailure, artifactRange } from "./index.js";

test("artifact ranges preserve exact offsets, clamp the end and reject ambiguous input", () => {
  assert.deepEqual(artifactRange(undefined, 0), { start: 0, end: -1 });
  assert.deepEqual(artifactRange(undefined, 100), { start: 0, end: 99 });
  assert.deepEqual(artifactRange("bytes=20-", 100), { start: 20, end: 99 });
  assert.deepEqual(artifactRange("bytes=20-999", 100), { start: 20, end: 99 });
  for (const range of ["bytes=-10", "bytes=1-2,4-5", "bytes=100-", "bytes=3-2", "bytes=9007199254740992-", "bytes=a-"]) {
    assert.throws(() => artifactRange(range, 100));
  }
});

test("download errors serialize stable bilingual summaries without file paths or credentials", () => {
  for (const code of ["HR-FILE-011", "HR-FILE-012", "HR-FILE-013", "HR-FILE-014", "HR-FILE-015"] as const) {
    const result = JSON.parse(JSON.stringify(artifactFailure(code))).error;
    assert.equal(result.code, code);
    assert.match(result.summaryZh, /[\u4e00-\u9fff]/);
    assert.match(result.summaryEn, /[a-z]/i);
    assert.equal(result.retryable, ["HR-FILE-013", "HR-FILE-015"].includes(code));
    assert.doesNotMatch(JSON.stringify(result), /Bearer|\/Users\/|token=/);
  }
});
