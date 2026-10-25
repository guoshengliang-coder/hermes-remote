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

test("local model fixture serves bounded synthetic replies and restores configuration on failure", () => {
  const script = fileURLToPath(new URL("./hermes-compatibility-model-fixture.test.py", import.meta.url));
  const result = spawnSync("python3", ["-B", script], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});

test("relay fixture pins source builds, separates credentials and stops only its children", () => {
  const script = fileURLToPath(new URL("./hermes-compatibility-relay-fixture.test.py", import.meta.url));
  const result = spawnSync("python3", ["-B", script], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});

test("launchd fixture uses isolated labels and ports and preserves the private token-file contract", () => {
  const script = fileURLToPath(new URL("./hermes-compatibility-launchagent.test.py", import.meta.url));
  const result = spawnSync("python3", ["-B", script], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});

test('account fixture refuses daily/non-private targets and verifies fresh PostgreSQL when configured',()=>{
  const script=fileURLToPath(new URL('./hermes-compatibility-account-fixture.test.py',import.meta.url));
  const result=spawnSync('python3',['-B',script],{encoding:'utf8'});
  assert.equal(result.status,0,result.stderr||result.error?.message);
});
