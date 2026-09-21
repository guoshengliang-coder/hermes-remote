import assert from "node:assert/strict";
import test from "node:test";
import { mockRunOptions } from "../dev/mock-run-options.mjs";

test("mock run prefixes pin the clarify form or skip the questions", () => {
  assert.deepEqual(mockRunOptions("!clarify-single 发布"), { form: 0, quick: false, suffix: "" });
  assert.deepEqual(mockRunOptions("!clarify-multi"), { form: 1, quick: false, suffix: "" });
  assert.deepEqual(mockRunOptions("  !clarify-batch go"), { form: 2, quick: false, suffix: "" });
  assert.deepEqual(mockRunOptions("!quick hello"), { form: undefined, quick: true, suffix: "" });
});

test("!media appends a MEDIA tag for an absolute path and ignores relative ones", () => {
  const media = mockRunOptions("!media /tmp/hr-files/report.png");
  assert.equal(media.quick, true);
  assert.match(media.suffix, /\nMEDIA:\/tmp\/hr-files\/report\.png\n$/);
  assert.deepEqual(mockRunOptions("!media report.png"), { form: undefined, quick: false, suffix: "" });
});

test("ordinary prompts keep the historical rotation", () => {
  for (const text of ["hello", "", "!fail now", "!slow", "clarify-batch"]) {
    assert.deepEqual(mockRunOptions(text), { form: undefined, quick: false, suffix: "" }, text);
  }
});
