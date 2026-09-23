import assert from "node:assert/strict";
import test from "node:test";
import { CONTRACT_REPORT_PATH, type HermesContractReport } from "./hermes-contract.js";
import { DEFAULT_MODEL_PATH, contractReportResponse, defaultModelResponse, tunnelHttpRoute } from "./tunnel-routes.js";

const report: HermesContractReport = {
  schema: 1,
  status: "compatible",
  retryable: false,
  hermesVersion: "0.21.3",
  minimumHermesVersion: "0.21.0",
  versionBelowMinimum: false,
  missing: [],
  checkedPaths: 41,
  checkedAt: "2026-09-21T12:00:00.000Z",
};

test("the contract report is answered by the Connector, with or without a query", () => {
  assert.equal(tunnelHttpRoute(CONTRACT_REPORT_PATH), "contract");
  assert.equal(tunnelHttpRoute(`${CONTRACT_REPORT_PATH}?profile=work`), "contract");
  assert.equal(tunnelHttpRoute(`${CONTRACT_REPORT_PATH}#x`), "contract");
});

test("look-alike paths are forwarded to Hermes, not answered locally", () => {
  assert.equal(tunnelHttpRoute(`${CONTRACT_REPORT_PATH}x`), "hermes");
  assert.equal(tunnelHttpRoute(`${CONTRACT_REPORT_PATH}/extra`), "hermes");
  assert.equal(tunnelHttpRoute("/api/hermes-remote/other"), "hermes");
  assert.equal(tunnelHttpRoute("/api/status"), "hermes");
  assert.equal(tunnelHttpRoute("/api/sessions?q=/api/hermes-remote/contract"), "hermes");
  assert.equal(tunnelHttpRoute(DEFAULT_MODEL_PATH), "default-model");
  assert.equal(tunnelHttpRoute(`${DEFAULT_MODEL_PATH}?profile=work`), "default-model");
  assert.equal(tunnelHttpRoute(`${DEFAULT_MODEL_PATH}/extra`), "hermes");
});

test("file routes keep their existing local handler", () => {
  assert.equal(tunnelHttpRoute("/api/files?path=%2Ftmp%2Fa"), "files");
  assert.equal(tunnelHttpRoute("/api/files/upload?name=a.png"), "files");
});

test("GET serves the cached report and is never cached downstream", async () => {
  let asked = 0;
  const response = await contractReportResponse("get", async () => { asked += 1; return report; });

  assert.equal(response.status, 200);
  assert.deepEqual(response.body, { ...report });
  assert.equal(response.headers["cache-control"], "no-store");
  assert.equal(asked, 1);
});

test("any other method is 405 and does not touch Hermes", async () => {
  for (const method of ["POST", "PUT", "DELETE", "PATCH"]) {
    let asked = 0;
    const response = await contractReportResponse(method, async () => { asked += 1; return report; });
    assert.equal(response.status, 405, method);
    assert.equal(response.headers.allow, "GET");
    assert.equal(asked, 0, method);
  }
});

test("default-model read strips all upstream metadata and scopes the selected profile", async () => {
  const paths: string[] = [];
  const response = await defaultModelResponse("GET", `${DEFAULT_MODEL_PATH}?profile=%E5%B7%A5%E4%BD%9C`, false, async (path) => {
    paths.push(path);
    return new Response(JSON.stringify({ model: "gpt-6-sol", provider: "openai", secret: "CANARY-TOKEN", home: "/Users/private" }));
  });
  assert.deepEqual(paths, ["/api/model/info?profile=%E5%B7%A5%E4%BD%9C"]);
  assert.deepEqual(response.body, { model: "gpt-6-sol", provider: "openai" });
  assert.equal(JSON.stringify(response).includes("CANARY-TOKEN"), false);
  assert.equal(JSON.stringify(response).includes("/Users/private"), false);
  assert.equal(response.headers["cache-control"], "private, no-store");
});

test("default-model read refuses extra authority before touching Hermes", async () => {
  let calls = 0;
  const source = async () => { calls += 1; return new Response("{}"); };
  for (const [method, path, body] of [
    ["POST", DEFAULT_MODEL_PATH, false],
    ["GET", `${DEFAULT_MODEL_PATH}?profile=a&profile=b`, false],
    ["GET", `${DEFAULT_MODEL_PATH}?include_keys=1`, false],
    ["GET", `${DEFAULT_MODEL_PATH}?profile=a%2Fb`, false],
    ["GET", `${DEFAULT_MODEL_PATH}%2Fconfig`, false],
    ["GET", DEFAULT_MODEL_PATH, true],
  ] as const) {
    const response = await defaultModelResponse(method, path, body, source);
    assert.notEqual(response.status, 200, `${method} ${path}`);
  }
  assert.equal(calls, 0);
});

test("default-model read fails closed on missing, malformed, oversized or failed upstream metadata", async () => {
  for (const source of [
    new Response(JSON.stringify({ model: "gpt-6-sol" })),
    new Response(JSON.stringify({ model: "gpt-6-sol", provider: "" })),
    new Response(JSON.stringify({ model: "gpt-6-sol", provider: "openai", x: "x".repeat(20_000) })),
    new Response("not json"),
    new Response("{}", { status: 503 }),
  ]) {
    const response = await defaultModelResponse("HEAD", DEFAULT_MODEL_PATH, false, async () => source);
    assert.equal(response.status, 502);
    assert.deepEqual(response.body, { error: { code: "HR-WEB-009", message: "Default model unavailable", retryable: true } });
  }
});
