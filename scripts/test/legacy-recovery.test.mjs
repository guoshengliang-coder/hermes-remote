import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { createHash } from "node:crypto";
import {
  access, lstat, mkdir, mkdtemp, open, readFile, realpath, rm, symlink, writeFile,
} from "node:fs/promises";
import net from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { captureLegacyRecovery, verifyLegacyRecovery } from "../../ops/lib/legacy-recovery.mjs";
import {
  loadLegacyArchiveManifest, loadLegacyCaptureConfig, loadLegacyRestoreConfig,
} from "../../ops/lib/legacy-recovery-config.mjs";
import { loadProductionEvidence } from "../../ops/lib/production-config.mjs";
import { OPS_ERROR_DEFINITIONS, createOpsError } from "../../ops/lib/errors.mjs";

const execFileAsync = promisify(execFile);

test("legacy recovery captures with authenticated encryption and proves an off-host isolated start", async (t) => {
  const fixture = await createFixture(t);
  await writeFile(path.join(fixture.runtime, "._index.mjs"), "AppleDouble metadata must not enter the archive");
  const capture = await captureLegacyRecovery(fixture.captureConfig, {
    confirmation: "production:prod-host",
    hostname: "prod-host",
    platform: "linux",
    runner: healthyRunner(),
    now: () => new Date("2026-09-04T01:02:03.000Z"),
  });
  assert.equal(capture.ok, true);
  assert.equal(capture.encryption, "openssl-cms-auth-enveloped-aes-256-gcm");
  assert.equal(capture.subject.identityDigest, fixture.identityDigest);
  const manifest = await loadLegacyArchiveManifest(fixture.captureConfig.manifestFile);
  assert.equal(manifest.archiveSha256, capture.archiveSha256);
  assert.equal(manifest.entries.some((entry) => entry.type === "symlink"), true);
  assert.equal(manifest.entries.some((entry) => path.basename(entry.path).startsWith("._")), false);

  const restored = await verifyLegacyRecovery(fixture.restoreConfig, {
    confirmation: "isolated:prod-host",
    hostname: "restore-host",
    runner: healthyRunner(),
    now: () => new Date("2026-09-04T01:04:05.000Z"),
    serviceVerifier: async (config, archiveManifest) => {
      const restoredEntrypoint = path.join(config.restoreRoot, archiveManifest.service.entrypoint.slice(1));
      assert.equal((await readFile(restoredEntrypoint, "utf8")).includes("createServer"), true);
    },
  });
  assert.equal(restored.ok, true);
  assert.deepEqual(restored.verifiedChecks, ["archive_hash", "files_restored", "service_start"]);
  await assert.rejects(() => access(fixture.restoreConfig.restoreRoot));
  const evidence = await loadProductionEvidence(
    fixture.restoreConfig.evidenceFile,
    "hermes-go-legacy-recovery-v1",
  );
  assert.equal(evidence.subject.identityDigest, fixture.identityDigest);
  assert.equal((await lstat(fixture.restoreConfig.evidenceFile)).mode & 0o777, 0o600);
});

test("legacy recovery rejects archive tampering and same-host verification", async (t) => {
  const fixture = await createFixture(t);
  await captureLegacyRecovery(fixture.captureConfig, {
    confirmation: "production:prod-host",
    hostname: "prod-host",
    platform: "linux",
    runner: healthyRunner(),
  });
  await assert.rejects(() => verifyLegacyRecovery(fixture.restoreConfig, {
    confirmation: "isolated:prod-host",
    hostname: "prod-host",
    runner: healthyRunner(),
  }), isRecoveryError("legacy_restore_must_be_off_host"));

  const originalManifest = JSON.parse(await readFile(fixture.restoreConfig.manifestFile, "utf8"));
  await writeJson(fixture.restoreConfig.manifestFile, {
    ...originalManifest,
    subject: { identityDigest: "f".repeat(64) },
  });
  await assert.rejects(
    () => loadLegacyArchiveManifest(fixture.restoreConfig.manifestFile),
    isRecoveryError("legacy_archive_identity_digest_mismatch"),
  );
  await writeJson(fixture.restoreConfig.manifestFile, originalManifest);

  const handle = await open(fixture.restoreConfig.archiveFile, "r+");
  try {
    const byte = Buffer.alloc(1);
    await handle.read(byte, 0, 1, 8);
    byte[0] ^= 0xff;
    await handle.write(byte, 0, 1, 8);
  } finally {
    await handle.close();
  }
  await assert.rejects(() => verifyLegacyRecovery(fixture.restoreConfig, {
    confirmation: "isolated:prod-host",
    hostname: "restore-host",
    runner: healthyRunner(),
  }), isRecoveryError("legacy_restore_archive_hash_mismatch"));
  await assert.rejects(() => access(fixture.restoreConfig.evidenceFile));
});

test("legacy recovery default verifier starts the restored service and checks the token contract", async (t) => {
  const port = await availablePort();
  if (port === undefined) {
    t.skip("local sandbox does not permit loopback listeners");
    return;
  }
  const fixture = await createFixture(t);
  fixture.restoreConfig.listenPort = port;
  await captureLegacyRecovery(fixture.captureConfig, {
    confirmation: "production:prod-host",
    hostname: "prod-host",
    platform: "linux",
    runner: healthyRunner(),
  });
  const result = await verifyLegacyRecovery(fixture.restoreConfig, {
    confirmation: "isolated:prod-host",
    hostname: "restore-host",
    runner: healthyRunner(),
  });
  assert.equal(result.ok, true);
});

test("legacy capture rejects escaping symlinks and removes partial outputs", async (t) => {
  const fixture = await createFixture(t);
  await symlink("/etc/passwd", path.join(fixture.runtime, "escape"));
  await assert.rejects(() => captureLegacyRecovery(fixture.captureConfig, {
    confirmation: "production:prod-host",
    hostname: "prod-host",
    platform: "linux",
    runner: healthyRunner(),
  }), isRecoveryError("legacy_capture_symlink_unsafe"));
  await assert.rejects(() => access(fixture.captureConfig.archiveFile));
  await assert.rejects(() => access(fixture.captureConfig.manifestFile));
});

test("legacy recovery parsers reject unknown fields and unsafe topology", async (t) => {
  const fixture = await createFixture(t);
  const captureExample = JSON.parse(await readFile("ops/legacy.capture.example.json", "utf8"));
  const restoreExample = JSON.parse(await readFile("ops/legacy.restore.example.json", "utf8"));
  const captureSchema = JSON.parse(await readFile("ops/hermesctl-legacy-capture-config.schema.json", "utf8"));
  const restoreSchema = JSON.parse(await readFile("ops/hermesctl-legacy-restore-config.schema.json", "utf8"));
  assert.equal(captureExample.environment, "production");
  assert.equal(restoreExample.environment, "isolated-restore");
  assert.equal(captureSchema.additionalProperties, false);
  assert.equal(restoreSchema.additionalProperties, false);
  assert.equal(captureSchema.properties.roots.minItems, 5);
  assert.equal(captureSchema.properties.roots.maxItems, 16);

  const repeatedRolePath = path.join(fixture.base, "capture-repeated-role.json");
  await writeJson(repeatedRolePath, {
    ...fixture.captureConfig,
    roots: [
      ...fixture.captureConfig.roots,
      { role: "configuration", path: path.join(fixture.base, "second-config-file") },
    ],
  });
  assert.equal((await loadLegacyCaptureConfig(repeatedRolePath)).roots.length, 6);

  const capturePath = path.join(fixture.base, "capture.json");
  await writeJson(capturePath, { ...fixture.captureConfig, unexpected: true });
  await assert.rejects(() => loadLegacyCaptureConfig(capturePath), isOpsCode("HR-OPS-011"));

  const restorePath = path.join(fixture.base, "restore.json");
  await writeJson(restorePath, { ...fixture.restoreConfig, environment: "production" });
  await assert.rejects(() => loadLegacyRestoreConfig(restorePath), isOpsCode("HR-OPS-011"));
});

test("legacy recovery failure is localized, retryable, registered, and redacted", async () => {
  const definition = OPS_ERROR_DEFINITIONS.recovery;
  assert.equal(definition.code, "HR-OPS-011");
  assert.match(definition.summaryZh, /旧 Gateway/);
  assert.match(definition.summaryEn, /Legacy Gateway/);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "inspect_legacy_recovery_stage_and_retry");
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  assert.match(registry, /`HR-OPS-011`/);
  const error = createOpsError("recovery", "token=secret-value password=bad", "legacy_restore_test");
  assert.equal(JSON.stringify(error).includes("secret-value"), false);
  assert.equal(JSON.stringify(error).includes("password=bad"), false);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "legacy-recovery-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const runtime = path.join(base, "source", "runtime");
  const configuration = path.join(base, "source", "config", "gateway.env");
  const lifecycle = path.join(base, "source", "state", "lifecycle.json");
  const nginx = path.join(base, "source", "nginx", "gateway.conf");
  const systemd = path.join(base, "source", "systemd", "gateway.service");
  const recovery = path.join(base, "recovery");
  await mkdir(runtime, { recursive: true });
  await mkdir(path.dirname(configuration), { recursive: true });
  await mkdir(path.dirname(lifecycle), { recursive: true });
  await mkdir(path.dirname(nginx), { recursive: true });
  await mkdir(path.dirname(systemd), { recursive: true });
  await mkdir(recovery, { recursive: true, mode: 0o700 });
  const entrypoint = path.join(runtime, "index.mjs");
  const packageFile = path.join(runtime, "package.json");
  await writeFile(entrypoint, `
import http from "node:http";
const token = process.env.APP_TOKEN;
const server = http.createServer((request, response) => {
  if (request.url === "/health") { response.writeHead(200); response.end("ok"); return; }
  if (request.url === "/api/status") {
    if (request.headers["x-hermes-session-token"] !== token) { response.writeHead(401); response.end(); return; }
    response.writeHead(503); response.end("connector offline"); return;
  }
  response.writeHead(404); response.end();
});
server.listen(Number(process.env.PORT), process.env.HOST);
process.on("SIGTERM", () => server.close(() => process.exit(0)));
`);
  await writeFile(packageFile, '{"name":"legacy-fixture","version":"0.1.0","type":"module"}\n');
  await writeFile(configuration, [
    "APP_TOKEN=test-app-token",
    "HOST=0.0.0.0",
    "PORT=8444",
    "TLS_CERT_FILE=/production-only/tls/fullchain.pem",
    "TLS_KEY_FILE=/production-only/tls/privkey.pem",
    "",
  ].join("\n"));
  await writeFile(lifecycle, '{"events":[],"nextSequence":1}\n', { mode: 0o600 });
  await writeFile(nginx, "location / { proxy_pass http://127.0.0.1:8444; }\n");
  await writeFile(systemd, "[Service]\nExecStart=/usr/bin/node index.mjs\n");
  await symlink("index.mjs", path.join(runtime, "current.mjs"));
  const identityFiles = [
    { path: packageFile, sha256: await sha256(packageFile) },
    { path: entrypoint, sha256: await sha256(entrypoint) },
  ];
  const identityDigest = createHash("sha256")
    .update(JSON.stringify([...identityFiles].sort((left, right) => left.path.localeCompare(right.path))))
    .digest("hex");
  const recipientPrivateKey = path.join(recovery, "recipient-key.pem");
  const recipientCertificate = path.join(recovery, "recipient-cert.pem");
  await execFileAsync("openssl", [
    "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
    "-subj", "/CN=Hermes-R5-Recovery-Test", "-keyout", recipientPrivateKey, "-out", recipientCertificate,
  ]);
  await chmodPrivate(recipientPrivateKey);
  const archiveFile = path.join(recovery, "legacy.cms");
  const manifestFile = path.join(recovery, "legacy.manifest.json");
  const evidenceFile = path.join(recovery, "legacy.evidence.json");
  const captureConfig = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    sourceHostname: "prod-host",
    service: {
      name: "hermes-remote-gateway",
      workingDirectory: runtime,
      entrypoint,
      environmentFile: configuration,
      healthPath: "/health",
      authenticatedPath: "/api/status",
    },
    recipientCertificate,
    archiveFile,
    manifestFile,
    maximumTotalBytes: 16 * 1024 * 1024,
    roots: [
      { role: "runtime", path: runtime },
      { role: "configuration", path: configuration },
      { role: "lifecycle", path: lifecycle },
      { role: "nginx", path: nginx },
      { role: "systemd", path: systemd },
    ],
    identityFiles,
  };
  const restoreConfig = {
    schemaVersion: 1,
    environment: "isolated-restore",
    operator: "test-operator",
    expectedSourceHostname: "prod-host",
    archiveFile,
    manifestFile,
    recipientCertificate,
    recipientPrivateKey,
    restoreRoot: path.join(base, "restore-root"),
    listenPort: 18787,
    evidenceFile,
  };
  return { base, runtime, captureConfig, restoreConfig, identityDigest };
}

function healthyRunner() {
  return {
    run(command) {
      if (command === "which" || command === "systemctl" || command === "openssl") return { status: 0, stdout: "", stderr: "" };
      throw new Error(`unexpected command ${command}`);
    },
  };
}

function isRecoveryError(cause) {
  return (error) => error?.kind === "recovery" && error.technicalCause.includes(cause);
}

function isOpsCode(code) {
  return (error) => createOpsError(error?.kind, error?.technicalCause, error?.stage).code === code;
}

async function sha256(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex");
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
}

async function chmodPrivate(filePath) {
  const { chmod } = await import("node:fs/promises");
  await chmod(filePath, 0o600);
}

function availablePort() {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once("error", () => resolve(undefined));
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      server.close((error) => resolve(error ? undefined : address.port));
    });
  });
}
