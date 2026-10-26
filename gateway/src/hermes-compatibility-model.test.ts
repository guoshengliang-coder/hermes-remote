import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import {
  REQUIRED_COMPATIBILITY_CHECKS, compatibilityDigest, parseValidationIdentity,
  parseValidationReport, requireApprovableReport, compatibilityErrors,
  type ValidationIdentity, type ValidationReport,
} from "./account/hermes-compatibility-model.js";

const sha = "a".repeat(40), hash = "b".repeat(64);
function identity(): ValidationIdentity {
  return {
    schemaVersion: 1, maintenancePolicy: "go-controlled-window-v1", candidateId: randomUUID(), version: "0.22.5",
    sourceCommit: "c".repeat(40), targetCommit: sha, goCommit: "d".repeat(40),
    suiteSha256: hash, installerSha256: "e".repeat(64),
    environment: { os: "macos", arch: "arm64", osVersion: "26.0.1", installation: "legacy-venv-to-pm" },
    components: ["desktop", "connector", "gateway", "android", "web"].map((name) => ({
      name: name as ValidationIdentity["components"][number]["name"],
      version: "1.2.3", artifactSha256: hash,
    })),
  };
}
function report(input = identity()): ValidationReport {
  return { schemaVersion: 1, runId: randomUUID(), identity: input, startedAt: "2026-10-25T00:00:00Z",
    finishedAt: "2026-10-25T00:10:00Z", outcome: "passed",
    checks: REQUIRED_COMPATIBILITY_CHECKS.map((name) => ({ name, status: "passed", evidenceSha256: hash })),
    evidenceBundleSha256: hash };
}
const code = (expected: string) => (e: unknown) => !!e && typeof e === "object" && "code" in e && e.code === expected;

test("compatibility identity is exact, bounded and canonical; no ranges or shell parameters", () => {
  const source = identity();
  assert.deepEqual(parseValidationIdentity(source), source);
  for (const change of [
    { schemaVersion: true }, { sourceCommit: sha }, { targetCommit: "main" },
    { version: "latest" }, { version: "0.22.5\n" }, { version: "0.22.5-rc1" },
    { command: "private-token-must-not-echo" }, { goCommit: "unknown" },
    { components: [] }, { components: [...source.components, source.components[0]] },
    { environment: { ...source.environment, osVersion: "*" } },
    { environment: { ...source.environment, installation: "/private/home" } },
    { components: source.components.map(c => ({ ...c, version: ">=1.0.0" })) },
  ]) assert.throws(() => parseValidationIdentity({ ...source, ...change }), e =>
    code("HR-HERMESCOMPAT-002")(e) && !String(e).includes("private-token"));
});

test("report digest is stable across object order and binds every scope and evidence field", () => {
  const value = report();
  assert.deepEqual(parseValidationReport(value), value);
  assert.equal(compatibilityDigest(value), compatibilityDigest({ ...value, identity: { ...value.identity } }));
  assert.equal(compatibilityDigest({ a: 1, b: { x: 2, y: 3 } }), compatibilityDigest({ b: { y: 3, x: 2 }, a: 1 }));
  for (const change of [
    { targetCommit: "f".repeat(40) }, { goCommit: "f".repeat(40) },
    { suiteSha256: "f".repeat(64) }, { sourceCommit: "f".repeat(40) },
    { environment: { ...value.identity.environment, osVersion: "26.0.2" } },
    { components: value.identity.components.map(c => ({ ...c, artifactSha256: "f".repeat(64) })) },
  ]) assert.notEqual(compatibilityDigest(value), compatibilityDigest({ ...value, identity: { ...value.identity, ...change } }));
});

test("approval requires the exact run, identity, complete passing evidence and released artifacts", () => {
  const value = report();
  const input = { runId: value.runId, identity: value.identity, report: value, releasedComponents: value.identity.components };
  assert.equal(requireApprovableReport(input), compatibilityDigest(value));
  for (const change of [
    { runId: randomUUID() }, { identity: { ...value.identity, goCommit: "f".repeat(40) } },
    { releasedComponents: [] },
    { releasedComponents: value.identity.components.map(c => ({ ...c, version: "1.2.4" })) },
    { report: { ...value, outcome: "infrastructure_failed" } },
    { report: { ...value, checks: value.checks.slice(1) } },
    ...["failed", "infrastructure_failed", "not_run"].map(status => ({ report: { ...value,
      checks: value.checks.map((c, i) => i === 0 ? { ...c, status } : c) } })),
  ]) assert.throws(() => requireApprovableReport({ ...input, ...change } as typeof input), code("HR-HERMESCOMPAT-004"));
});

test("report parser rejects invented checks, secret/log fields, duplicate checks and impossible time", () => {
  const value = report();
  for (const change of [
    { log: "secret" }, { finishedAt: "2026-10-24T00:00:00Z" }, { startedAt: "yesterday" },
    { checks: [...value.checks, value.checks[0]] },
    { checks: [{ name: "admin_says_ok", status: "passed", evidenceSha256: hash }] },
    { checks: [{ ...value.checks[0], evidenceSha256: null }] },
    { checks: [{ ...value.checks[0], stdout: "secret" }] },
  ]) assert.throws(() => parseValidationReport({ ...value, ...change }), code("HR-HERMESCOMPAT-002"));
  // Incomplete/failed reports are retained for diagnosis, but never approved.
  assert.equal(parseValidationReport({ ...value, outcome: "failed", checks: [] }).outcome, "failed");
});

test("compatibility failures carry stable bilingual summaries and recovery behavior", () => {
  for (const key of Object.keys(compatibilityErrors) as Array<keyof typeof compatibilityErrors>) {
    const error = compatibilityErrors[key]();
    assert.match(error.code, /^HR-HERMESCOMPAT-00[1-6]$/);
    assert.match(error.message, /[\u4e00-\u9fff]/);
    assert.match(error.summaryEn, /[A-Za-z]/);
    assert.equal(error.retryable, key === "unavailable" || key === "publicationIncomplete");
    assert.notEqual(error.action, "none");
  }
});


test("maintenance policy must be explicit; historical reports cannot become B approvals", () => {
  const current = { ...identity(), maintenancePolicy: "go-controlled-window-v1" };
  const legacy = { ...current } as Record<string, unknown>;
  delete legacy.maintenancePolicy;
  assert.throws(() => parseValidationIdentity(legacy), code("HR-HERMESCOMPAT-002"));
  assert.deepEqual(parseValidationIdentity(current), current);
  assert.throws(() => parseValidationIdentity({ ...current, maintenancePolicy: "all-updaters" }), code("HR-HERMESCOMPAT-002"));
  assert.notEqual(compatibilityDigest(current), compatibilityDigest(legacy));
  const value = report();
  const oldChecks = value.checks.map(c => c.name === "maintenanceWindow" ? { ...c, name: "concurrentUpdate" } : c);
  assert.throws(() => parseValidationReport({ ...value, checks: oldChecks }), code("HR-HERMESCOMPAT-002"));
});
