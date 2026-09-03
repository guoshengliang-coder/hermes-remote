import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { constants } from "node:fs";
import test from "node:test";

test("Gateway release contract stays aligned with package and protocol versions", async () => {
  const gatewayPackage = JSON.parse(await readFile("gateway/package.json", "utf8"));
  const contract = JSON.parse(await readFile("gateway/release-contract.json", "utf8"));
  const protocolSource = await readFile("protocol/src/index.ts", "utf8");
  assert.equal(gatewayPackage.version, "0.2.0");
  assert.match(protocolSource, new RegExp(`PROTOCOL_VERSION = ${contract.protocolVersions.legacy}`));
  assert.match(
    protocolSource,
    new RegExp(`ACCOUNT_CONNECTOR_PROTOCOL_VERSION = ${contract.protocolVersions.accountConnector}`),
  );
  assert.equal(contract.databaseSchemaVersion, 7);
  await access(
    `gateway/migrations/${String(contract.databaseSchemaVersion).padStart(3, "0")}_gateway_schema_state.sql`,
    constants.R_OK,
  );

  assert.deepEqual(contract.supportedPostgresqlMajors, [18]);
  const ci = await readFile(".github/workflows/ci.yml", "utf8");
  assert.match(
    ci,
    new RegExp(
      `image: postgres:${contract.supportedPostgresqlMajors[0]}-alpine@sha256:[0-9a-f]{64}`,
    ),
  );
});

test("Gateway image build context is allowlisted and release packaging fails closed", async () => {
  const dockerignore = await readFile(".dockerignore", "utf8");
  assert.equal(dockerignore.startsWith("**\n"), true);
  assert.equal(dockerignore.includes("!.env"), false);
  assert.equal(dockerignore.includes("!environment.md"), false);

  const dockerfile = await readFile("deploy/Dockerfile.gateway", "utf8");
  for (const required of [
    "HERMES_BUILD_COMMIT",
    "HERMES_REQUIRE_RELEASE_CLEAN",
    "release:verify",
    "/readyz",
  ]) {
    assert.equal(dockerfile.includes(required), true, `${required} missing from Gateway image contract`);
  }

  const packageScript = await readFile("scripts/package-gateway-image.sh", "utf8");
  const cleanGate = packageScript.indexOf("git status --porcelain");
  const imageBuild = packageScript.indexOf("docker build");
  assert(cleanGate >= 0 && imageBuild > cleanGate);
  assert.equal(packageScript.includes("HERMES_BUILD_DIRTY=0"), true);
  assert.equal(packageScript.includes("HERMES_REQUIRE_RELEASE_CLEAN=1"), true);
});
