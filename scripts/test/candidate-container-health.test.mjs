import assert from "node:assert/strict";
import test from "node:test";
import { verifyCandidateBase } from "../../ops/lib/deploy.mjs";

const config = {
  slots: {
    green: {
      gatewayPort: 18788,
      containerName: "hermes-go-gateway-green",
    },
  },
};
const manifest = {
  serverVersion: "0.4.10",
  sourceCommit: "a".repeat(40),
};

test("candidate base waits for Docker healthy after HTTP readiness", async () => {
  const dockerStatuses = ["starting", "healthy"];
  const sleeps = [];
  await verifyCandidateBase(config, manifest, "green", "internal-token", {
    fetchImpl: healthyGateway,
    runner: dockerRunner(dockerStatuses),
    sleep: async (milliseconds) => sleeps.push(milliseconds),
  });
  assert.deepEqual(dockerStatuses, []);
  assert.deepEqual(sleeps, [1_000]);
});

test("candidate base rejects Docker unhealthy even when HTTP is ready", async () => {
  await assert.rejects(
    () => verifyCandidateBase(config, manifest, "green", "internal-token", {
      fetchImpl: healthyGateway,
      runner: dockerRunner(["unhealthy"]),
      sleep: async () => {},
    }),
    (error) => error?.technicalCause === "candidate_container_unhealthy"
      && error?.stage === "candidate_smoke",
  );
});

test("candidate base rejects a container without a usable health status", async () => {
  await assert.rejects(
    () => verifyCandidateBase(config, manifest, "green", "internal-token", {
      fetchImpl: healthyGateway,
      runner: dockerRunner(Array(45).fill("absent")),
      sleep: async () => {},
    }),
    (error) => error?.technicalCause === "candidate_container_health_timeout=absent"
      && error?.stage === "candidate_smoke",
  );
});

async function healthyGateway(url) {
  const pathname = new URL(url).pathname;
  if (pathname === "/healthz") return jsonResponse({ status: "alive" });
  if (pathname === "/readyz") return jsonResponse({ status: "ready" });
  if (pathname === "/internal/version") return jsonResponse(manifest);
  assert.fail(`unexpected URL ${url}`);
}

function dockerRunner(statuses) {
  return {
    run(command, args) {
      assert.equal(command, "docker");
      assert.deepEqual(args.slice(0, 2), ["container", "inspect"]);
      assert.equal(args.at(-1), "hermes-go-gateway-green");
      return { status: 0, stdout: `${statuses.shift() ?? "absent"}\n`, stderr: "" };
    },
  };
}

function jsonResponse(value) {
  return new Response(JSON.stringify(value), {
    headers: { "content-type": "application/json" },
  });
}
