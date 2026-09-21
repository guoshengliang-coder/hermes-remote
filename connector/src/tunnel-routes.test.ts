import assert from "node:assert/strict";
import test from "node:test";
import { CONTRACT_REPORT_PATH, type HermesContractReport } from "./hermes-contract.js";
import { contractReportResponse, tunnelHttpRoute } from "./tunnel-routes.js";

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
