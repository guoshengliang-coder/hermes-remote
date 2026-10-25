import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

test("Hermes compatibility lab guards and real-file recovery (synthetic installer)", () => {
  const script = fileURLToPath(new URL("./hermes-compatibility-lab.test.py", import.meta.url));
  const result = spawnSync("python3", ["-B", script], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});

test("contract lab refuses daily HOME and restores synthetic paths after a failed probe", () => {
  const script = fileURLToPath(new URL("./hermes-compatibility-contract.test.py", import.meta.url));
  const result = spawnSync("python3", ["-B", script], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});
