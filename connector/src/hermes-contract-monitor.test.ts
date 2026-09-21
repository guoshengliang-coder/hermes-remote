import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { HermesContractMonitor, type HermesContractProbe } from "./hermes-contract-monitor.js";
import type { OpenApiFetchResult } from "./hermes-contract.js";

const complete = readFileSync(
  new URL("../fixtures/hermes-openapi/hermes-0.21.3-complete.json", import.meta.url),
  "utf8",
);
const missingRequired = readFileSync(
  new URL("../fixtures/hermes-openapi/hermes-missing-required.json", import.meta.url),
  "utf8",
);

class FakeProbe implements HermesContractProbe {
  version: string | undefined = "0.21.3";
  statusReachable = true;
  openapi: OpenApiFetchResult | Error = { kind: "ok", body: complete };
  openApiCalls = 0;
  statusCalls = 0;
  gate?: Promise<void>;

  async fetchOpenApi(): Promise<OpenApiFetchResult> {
    this.openApiCalls += 1;
    if (this.gate) await this.gate;
    if (this.openapi instanceof Error) throw this.openapi;
    return this.openapi;
  }

  async fetchStatusVersion(): Promise<string | undefined> {
    this.statusCalls += 1;
    if (!this.statusReachable) throw new Error("connect ECONNREFUSED 127.0.0.1:9119?token=abc123");
    return this.version;
  }
}

function clock(start = Date.parse("2026-09-21T12:00:00.000Z")) {
  let now = start;
  return { now: () => new Date(now), advance: (ms: number) => { now += ms; } };
}

test("the schema is fetched once per Hermes version, not once per request", async () => {
  const probe = new FakeProbe();
  const monitor = new HermesContractMonitor(probe);

  await monitor.refresh("startup");
  for (let index = 0; index < 5; index += 1) await monitor.ensureFresh("app_request");

  assert.equal(probe.openApiCalls, 1);
  assert.equal(monitor.current()?.status, "compatible");
});

test("a new version from /api/status re-runs the check", async () => {
  const probe = new FakeProbe();
  const monitor = new HermesContractMonitor(probe);
  await monitor.refresh("startup");

  probe.version = "0.22.0";
  probe.openapi = { kind: "ok", body: missingRequired };
  const report = await monitor.ensureFresh("app_request");

  assert.equal(probe.openApiCalls, 2);
  assert.equal(report.status, "breaking");
  assert.equal(report.hermesVersion, "0.22.0");
});

test("a reconnect re-checks even when the version did not move", async () => {
  // `hermes update` can move the code without bumping __version__; the restart is the signal.
  const probe = new FakeProbe();
  const monitor = new HermesContractMonitor(probe);
  await monitor.refresh("startup");
  probe.openapi = { kind: "ok", body: missingRequired };

  const report = await monitor.refresh("hermes_connected");

  assert.equal(probe.openApiCalls, 2);
  assert.equal(report.status, "breaking");
});

test("concurrent triggers share one check", async () => {
  const probe = new FakeProbe();
  let release!: () => void;
  probe.gate = new Promise((resolve) => { release = resolve; });
  const monitor = new HermesContractMonitor(probe);

  const pending = [
    monitor.refresh("startup"),
    monitor.refresh("hermes_connected"),
    monitor.ensureFresh("app_request"),
  ];
  await new Promise((resolve) => setImmediate(resolve));
  release();
  const reports = await Promise.all(pending);

  assert.equal(probe.openApiCalls, 1);
  assert.ok(reports.every((report) => report === reports[0]));
});

test("an unknown result is retried once it is stale, not on every request", async () => {
  const time = clock();
  const probe = new FakeProbe();
  probe.openapi = new Error("socket hang up");
  const monitor = new HermesContractMonitor(probe, { now: time.now, unknownRetryMs: 60_000 });

  assert.equal((await monitor.refresh("startup")).status, "unknown");
  probe.openapi = { kind: "ok", body: complete };
  time.advance(30_000);
  assert.equal((await monitor.ensureFresh("app_request")).status, "unknown");
  assert.equal(probe.openApiCalls, 1);

  time.advance(30_000);
  assert.equal((await monitor.ensureFresh("app_request")).status, "compatible");
  assert.equal(probe.openApiCalls, 2);
});

test("an unreachable Hermes keeps the last result instead of guessing", async () => {
  const probe = new FakeProbe();
  const monitor = new HermesContractMonitor(probe);
  await monitor.refresh("startup");

  probe.statusReachable = false;
  const report = await monitor.ensureFresh("app_request");

  assert.equal(report.status, "compatible");
  assert.equal(probe.openApiCalls, 1);
});

test("a version seen by the preflight triggers a check only when it differs", async () => {
  const probe = new FakeProbe();
  const monitor = new HermesContractMonitor(probe);
  await monitor.refresh("startup");

  monitor.observeVersion("0.21.3", "preflight");
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(probe.openApiCalls, 1);

  probe.version = "0.21.4";
  monitor.observeVersion("0.21.4", "preflight");
  await new Promise((resolve) => setImmediate(resolve));
  await monitor.ensureFresh("app_request");
  assert.equal(probe.openApiCalls, 2);
  assert.equal(monitor.current()?.hermesVersion, "0.21.4");
});

test("a result is logged when it changes, and never carries the probe's error text", async () => {
  const lines: Array<{ level: string; kind: string; fields: Record<string, unknown> }> = [];
  const probe = new FakeProbe();
  const monitor = new HermesContractMonitor(probe, {
    log: (level, kind, fields) => lines.push({ level, kind, fields }),
  });

  await monitor.refresh("startup");
  await monitor.refresh("hermes_connected");
  assert.equal(lines.length, 1);
  assert.equal(lines[0].level, "info");
  assert.equal(lines[0].fields.status, "compatible");

  probe.openapi = { kind: "ok", body: missingRequired };
  await monitor.refresh("hermes_connected");
  assert.equal(lines.length, 2);
  assert.equal(lines[1].level, "error");
  assert.equal(lines[1].fields.code, "HR-COMPAT-001");
  assert.equal(lines[1].fields.missing, "GET /api/sessions/{id}/messages");

  probe.statusReachable = false;
  probe.openapi = new Error("connect ECONNREFUSED 127.0.0.1:9119?token=abc123");
  const report = await monitor.refresh("hermes_connected");
  assert.equal(report.status, "unknown");
  assert.doesNotMatch(JSON.stringify(lines), /abc123|ECONNREFUSED/);
  assert.doesNotMatch(JSON.stringify(report), /abc123|ECONNREFUSED/);
});
