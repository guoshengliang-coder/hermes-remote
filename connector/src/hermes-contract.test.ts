import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  CONTRACT_CODES,
  HERMES_REST_CONTRACT,
  MINIMUM_HERMES_VERSION,
  evaluateHermesContract,
  isVersionBelow,
  normalizePath,
  type OpenApiFetchResult,
} from "./hermes-contract.js";

const at = new Date("2026-09-21T12:00:00.000Z");

/** Trimmed from the live 0.21.3 (`17b5df02`) `openapi.json`: paths and methods only. */
function fixtureBody(name: string): string {
  return readFileSync(new URL(`../fixtures/hermes-openapi/${name}`, import.meta.url), "utf8");
}

function fixture(name: string): OpenApiFetchResult {
  return { kind: "ok", body: fixtureBody(name) };
}

function repoFile(path: string): string {
  return readFileSync(new URL(`../../${path}`, import.meta.url), "utf8");
}

test("the complete 0.21.3 schema serves every path the app depends on", () => {
  const report = evaluateHermesContract({
    openapi: fixture("hermes-0.21.3-complete.json"),
    hermesVersion: "0.21.3",
    checkedAt: at,
  });

  assert.equal(report.status, "compatible");
  assert.equal(report.code, undefined);
  assert.deepEqual(report.missing, []);
  assert.equal(report.checkedPaths, HERMES_REST_CONTRACT.length);
  assert.equal(report.hermesVersion, "0.21.3");
  assert.equal(report.versionBelowMinimum, false);
});

test("a missing history route is breaking and says which route and why", () => {
  const report = evaluateHermesContract({
    openapi: fixture("hermes-missing-required.json"),
    hermesVersion: "0.21.3",
    checkedAt: at,
  });

  assert.equal(report.status, "breaking");
  assert.equal(report.code, "HR-COMPAT-001");
  assert.equal(report.retryable, false);
  assert.deepEqual(report.missing, [{
    method: "GET",
    path: "/api/sessions/{id}/messages",
    tier: "required",
    feature: "history",
  }]);
});

test("a missing cron route or method only degrades, and names the features", () => {
  const report = evaluateHermesContract({
    openapi: fixture("hermes-missing-optional.json"),
    hermesVersion: "0.21.3",
    checkedAt: at,
  });

  assert.equal(report.status, "degraded");
  assert.equal(report.code, "HR-COMPAT-002");
  assert.deepEqual(
    report.missing.map((entry) => `${entry.method} ${entry.path} ${entry.tier} ${entry.feature}`),
    [
      "POST /api/cron/jobs/{id}/trigger optional cron",
      // The path is still there with GET; the app's PUT is what went missing.
      "PUT /api/skills/toggle optional skills",
    ],
  );
});

test("required findings are listed before optional ones", () => {
  const body = JSON.parse(fixtureBody("hermes-0.21.3-complete.json"));
  delete body.paths["/api/cron/jobs"];
  delete body.paths["/api/sessions"];
  const report = evaluateHermesContract({
    openapi: { kind: "ok", body: JSON.stringify(body) },
    checkedAt: at,
  });

  assert.equal(report.status, "breaking");
  assert.deepEqual(report.missing.map((entry) => entry.tier), ["required", "optional", "optional"]);
});

test("a check that could not look is unknown, never breaking", () => {
  const cases: Array<[OpenApiFetchResult, string]> = [
    [{ kind: "unreachable" }, "openapi_unreachable"],
    [{ kind: "http_status", status: 404 }, "openapi_http_status"],
    [{ kind: "too_large" }, "openapi_too_large"],
    [{ kind: "ok", body: "<!doctype html><title>Hermes</title>" }, "openapi_invalid"],
    [{ kind: "ok", body: '{"detail":"Not Found"}' }, "openapi_invalid"],
    [{ kind: "ok", body: '{"openapi":"3.1.0","paths":[]}' }, "openapi_invalid"],
    [{ kind: "ok", body: '{"openapi":"3.1.0","paths":{"/metrics":{"get":{}}}}' }, "openapi_unrecognized"],
  ];
  for (const [openapi, reason] of cases) {
    const report = evaluateHermesContract({ openapi, hermesVersion: "0.21.3", checkedAt: at });
    assert.equal(report.status, "unknown", reason);
    assert.equal(report.reason, reason);
    assert.equal(report.code, undefined, reason);
    assert.deepEqual(report.missing, [], reason);
  }
  const notFound = evaluateHermesContract({ openapi: { kind: "http_status", status: 404 }, checkedAt: at });
  assert.equal(notFound.httpStatus, 404);
});

test("an older Hermes than the contract was verified on degrades with its own code", () => {
  const report = evaluateHermesContract({
    openapi: fixture("hermes-0.21.3-complete.json"),
    hermesVersion: "0.20.9",
    checkedAt: at,
  });

  assert.equal(report.status, "degraded");
  assert.equal(report.code, "HR-COMPAT-003");
  assert.equal(report.versionBelowMinimum, true);
  assert.equal(report.minimumHermesVersion, MINIMUM_HERMES_VERSION);
});

test("a missing route outranks the version finding", () => {
  const breaking = evaluateHermesContract({
    openapi: fixture("hermes-missing-required.json"),
    hermesVersion: "0.1.0",
    checkedAt: at,
  });
  const degraded = evaluateHermesContract({
    openapi: fixture("hermes-missing-optional.json"),
    hermesVersion: "0.1.0",
    checkedAt: at,
  });

  assert.equal(breaking.code, "HR-COMPAT-001");
  assert.equal(degraded.code, "HR-COMPAT-002");
  assert.equal(degraded.versionBelowMinimum, true);
});

test("version comparison only claims what it can read", () => {
  assert.equal(isVersionBelow("0.20.9", "0.21.0"), true);
  assert.equal(isVersionBelow("0.21.0", "0.21.0"), false);
  assert.equal(isVersionBelow("0.21.3", "0.21.0"), false);
  assert.equal(isVersionBelow("1.0", "0.21.0"), false);
  assert.equal(isVersionBelow("v0.9.0", "0.21.0"), true);
  assert.equal(isVersionBelow("dev", "0.21.0"), false);
  assert.equal(isVersionBelow("", "0.21.0"), false);
});

test("parameter names are not part of the contract", () => {
  assert.equal(normalizePath("/api/cron/jobs/{job_id}/runs"), normalizePath("/api/cron/jobs/{id}/runs"));
  assert.notEqual(normalizePath("/api/profiles/active"), normalizePath("/api/profiles/{name}"));
});

test("the report serializes to a stable payload without absent optional keys", () => {
  const report = evaluateHermesContract({
    openapi: fixture("hermes-0.21.3-complete.json"),
    hermesVersion: "0.21.3",
    checkedAt: at,
  });
  const wire = JSON.parse(JSON.stringify(report));

  assert.deepEqual(Object.keys(wire).sort(), [
    "checkedAt", "checkedPaths", "hermesVersion", "minimumHermesVersion", "missing", "retryable",
    "schema", "status", "versionBelowMinimum",
  ]);
  assert.equal(wire.schema, 1);
  assert.equal(wire.checkedAt, "2026-09-21T12:00:00.000Z");
});

test("upstream text never reaches the report", () => {
  // An unreadable version string is dropped, not relayed; the unknown reason is a fixed token.
  const report = evaluateHermesContract({
    openapi: { kind: "ok", body: "Traceback token=abc123 /Users/someone/.hermes" },
    hermesVersion: "0.21.3\u0000token=abc123",
    checkedAt: at,
  });
  const wire = JSON.stringify(report);

  assert.equal(report.hermesVersion, undefined);
  assert.doesNotMatch(wire, /abc123|Traceback|\/Users\//);
});

test("the contract list has no duplicates and every path is upstream's /api/", () => {
  const keys = HERMES_REST_CONTRACT.map((entry) => `${entry.method} ${normalizePath(entry.path)}`);
  assert.equal(new Set(keys).size, keys.length);
  for (const entry of HERMES_REST_CONTRACT) {
    assert.match(entry.path, /^\/api\//);
    // Served by the Connector itself or not describable by OpenAPI: never checked upstream.
    assert.doesNotMatch(entry.path, /^\/api\/(files|ws|mobile|hermes-remote)(\/|$)/);
  }
  assert.ok(HERMES_REST_CONTRACT.some((entry) => entry.tier === "required"));
});

test("docs/HERMES_CONTRACT.md §2 is the same list as the code", () => {
  const document = repoFile("docs/HERMES_CONTRACT.md");
  const section = document.slice(document.indexOf("### 2. REST paths"), document.indexOf("### 3."));
  const rows = [...section.matchAll(/^\| `(GET|POST|PUT|PATCH|DELETE)` \| `([^`]+)` \| (required|optional) \| (\w+) \|$/gm)]
    .map((match) => `${match[1]} ${match[2]} ${match[3]} ${match[4]}`);
  const code = HERMES_REST_CONTRACT.map((entry) => `${entry.method} ${entry.path} ${entry.tier} ${entry.feature}`);

  assert.deepEqual(rows, code, "update the §2 table and HERMES_REST_CONTRACT together");
});

test("every HR-COMPAT code is registered with Chinese and English copy and is not retryable", () => {
  const registry = repoFile("docs/ERROR_HANDLING.md");
  for (const code of Object.values(CONTRACT_CODES)) {
    const row = registry.split("\n").find((line) => line.startsWith(`| \`${code}\` |`));
    assert.ok(row, `${code} is not registered in docs/ERROR_HANDLING.md`);
    const cells = row.split(" | ");
    assert.match(cells[2], /[一-鿿]/, `${code} needs a Chinese explanation`);
    assert.match(cells[3], /[A-Za-z]/, `${code} needs an English explanation`);
    assert.match(cells[4], /^No\b/, `${code} must not be retryable`);
  }
});
