import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { COMPRESSION_HEADERS, projectHermesDisplay } from "./hermes-display.js";
test("shared display samples keep the same body and note classification", () => {
  const samples = JSON.parse(readFileSync(new URL("../../protocol/fixtures/hermes-display-v1.json", import.meta.url), "utf8"));
  for (const sample of samples) {
    const projection = projectHermesDisplay(sample.row);
    assert.equal(projection.body ?? sample.row.content, sample.body, sample.name);
    assert.equal(projection.kind === "compression" || projection.kind === "future_notice",
      sample.note !== null, sample.name);
  }
});
test("GO projection keeps mixed user text and original upstream rows", () => {
  for (const header of COMPRESSION_HEADERS) {
    const row = { role: "user", content: "真实问题\n\n" + header + "\nreload()" };
    assert.deepEqual(projectHermesDisplay(row), { schema: 1, kind: null, body: "真实问题" });
    assert.ok(row.content.includes("reload()"));
    assert.equal(projectHermesDisplay({ role: "user", content: header }).kind, "compression");
  }
});
test("structured metadata wins and assistant quotations are never cut", () => {
  const content = COMPRESSION_HEADERS[1];
  assert.deepEqual(projectHermesDisplay({ role: "user", content, display_kind: "future_kind" }), { schema: 1, kind: "future_kind" });
  assert.deepEqual(projectHermesDisplay({ role: "assistant", content }), { schema: 1, kind: null });
});
test("upstream display content prevents stale raw compaction projection and empty-row resurrection", () => {
  for (const display_content of ["current ask", ""]) {
    const row = { role: "user", content: COMPRESSION_HEADERS[1], display_content };
    assert.deepEqual(projectHermesDisplay(row), { schema: 1, kind: null });
    assert.equal(row.content, COMPRESSION_HEADERS[1]);
    assert.equal(row.display_content, display_content);
  }
});
