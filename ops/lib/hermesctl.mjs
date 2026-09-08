import { createHash, randomUUID } from "node:crypto";
import { hostname } from "node:os";
import { chmod, lstat, open, readFile, readlink, unlink } from "node:fs/promises";
import path from "node:path";
import {
  assertRegularFile,
  deploymentDigest,
  manifestIdentity,
  runtimeImageIds,
  sha256File,
} from "./config.mjs";
import { createOpsError, OpsError, redactOpsValue } from "./errors.mjs";
import {
  assertNoSymlinkAncestors,
  atomicWrite,
  createCommandRunner,
  ensureManagedDirectory,
  installCurrentSymlink,
  renderGatewayEnvironment,
  renderNginxConfig,
  renderSystemdUnit,
} from "./system.mjs";

const REQUIRED_COMMANDS = Object.freeze(["docker", "systemctl", "nginx", "curl", "ss"]);
const STAGES = Object.freeze([
  "initialized",
  "artifact_loaded",
  "filesystem_ready",
  "configuration_installed",
  "services_started",
  "smoke_passed",
  "complete",
]);

export const BOOTSTRAP_STAGES = STAGES;

export async function preflight(config, manifest, options = {}) {
  const runner = options.runner ?? createCommandRunner();
  const platform = options.platform ?? process.platform;
  const architecture = options.architecture ?? process.arch;
  const checks = [];

  await verifyArchiveAtUse(manifest);

  if (platform !== "linux" || architecture !== "x64") {
    throw new OpsError("config", `unsupported_host=${platform}/${architecture}`, "preflight_host");
  }
  checks.push(check("host", "pass", "linux_amd64"));

  for (const commandName of REQUIRED_COMMANDS) {
    const found = runner.run("which", [commandName], { allowFailure: true });
    if (found.status !== 0) throw new OpsError("config", `missing_command=${commandName}`, "preflight_dependencies");
  }
  checks.push(check("dependencies", "pass", "required_commands_available"));

  const systemd = runner.run("systemctl", ["is-system-running"], { allowFailure: true });
  const systemdState = systemd.stdout.trim();
  if (!new Set(["running", "degraded", "starting"]).has(systemdState)) {
    throw new OpsError("config", "systemd_not_running", "preflight_systemd");
  }
  checks.push(check("systemd", "pass", systemdState));

  const docker = runner.run("docker", ["info", "--format", "{{.OSType}}/{{.Architecture}}"], { allowFailure: true });
  if (docker.status !== 0 || !new Set(["linux/amd64", "linux/x86_64"]).has(docker.stdout.trim())) {
    throw new OpsError("config", "docker_linux_amd64_unavailable", "preflight_docker");
  }
  checks.push(check("docker", "pass", "linux_amd64"));

  const material = await inspectInputMaterial(config);
  checks.push(check("secrets", "pass", "three_distinct_private_tokens"));
  checks.push(check("tls", "pass", "certificate_and_private_key_valid"));

  const image = inspectLoadedImage(runner, manifest);
  checks.push(check("image", image.loaded ? "pass" : "pending", image.loaded ? "identity_verified" : "archive_verified_not_loaded"));

  for (const root of [config.paths.installRoot, config.paths.configRoot, config.paths.stateRoot]) {
    await inspectOptionalManagedRoot(root);
  }
  checks.push(check("managed_paths", "pass", "absent_or_safe_directories"));

  await assertCurrentCompatible(config, manifest);
  await assertJournalCompatible(config, manifest, material.fingerprint);
  const releaseName = `${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`;
  await inspectOptionalManagedFile(
    path.join(config.paths.installRoot, "releases", releaseName, "bundle.manifest.json"),
    `${JSON.stringify(stripArchivePath(manifest), null, 2)}\n`,
  );
  const secretRoot = path.join(config.paths.configRoot, "secrets");
  const tlsRoot = path.join(config.paths.configRoot, "tls");
  await inspectOptionalManagedFile(path.join(secretRoot, "app-token"), `${material.app}\n`);
  await inspectOptionalManagedFile(path.join(secretRoot, "connector-token"), `${material.connector}\n`);
  await inspectOptionalManagedFile(path.join(secretRoot, "internal-status-token"), `${material.internal}\n`);
  await inspectOptionalManagedFile(path.join(tlsRoot, "fullchain.pem"), material.certificate);
  await inspectOptionalManagedFile(path.join(tlsRoot, "privkey.pem"), material.privateKey);
  await inspectOptionalManagedFile(
    path.join(config.paths.configRoot, "gateway.env"),
    renderGatewayEnvironment(config),
  );
  await inspectOptionalManagedFile(
    path.join(config.paths.systemdUnitDirectory, `${config.service.name}.service`),
    renderSystemdUnit(config, manifest, image.loaded ? image.imageId : manifest.imageId),
  );
  await inspectOptionalManagedFile(config.nginx.configFile, renderNginxConfig(config));
  const serviceUnit = `${config.service.name}.service`;
  const serviceActive = runner.run("systemctl", ["is-active", "--quiet", serviceUnit], { allowFailure: true }).status === 0;
  const listeners = runner.run("ss", ["-ltnH", "sport", "=", `:${config.service.gatewayPort}`], { allowFailure: true });
  if (listeners.status !== 0) throw new OpsError("config", "listen_socket_inspection_failed", "preflight_port");
  if (listeners.stdout.trim() && !serviceActive) {
    throw new OpsError("config", "gateway_port_already_in_use", "preflight_port");
  }
  checks.push(check("gateway_port", "pass", serviceActive ? "managed_service_active" : "available"));

  return {
    ok: true,
    command: "preflight",
    environment: config.environment,
    serverVersion: manifest.serverVersion,
    sourceCommit: manifest.sourceCommit,
    imageId: manifest.imageId,
    checks,
  };
}

export async function bootstrapStaging(config, manifest, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 90_000 });
  const fetchImpl = options.fetchImpl ?? fetch;
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const getUid = options.getUid ?? (() => process.getuid?.());
  const confirmation = options.confirmation;
  const now = options.now ?? (() => new Date());
  const runId = options.runId ?? randomUUID();
  const ownership = options.ownership ?? {
    host: { uid: 0, gid: 0 },
    container: { uid: 1000, gid: 1000 },
    secret: { uid: 0, gid: 1000 },
  };

  if (confirmation !== "staging" || config.environment !== "staging") {
    throw new OpsError("config", "staging_confirmation_required", "bootstrap_authorize");
  }
  if (getUid() !== 0) throw new OpsError("config", "bootstrap_requires_root", "bootstrap_authorize");

  const startedAt = now().toISOString();
  const releaseName = `${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`;
  const paths = bootstrapPaths(config, releaseName);
  let lock;
  let stage = "preflight";

  await preflight(config, manifest, options);
  const material = await inspectInputMaterial(config);
  const digest = deploymentDigest(config, manifest, material.fingerprint);
  await prepareBootstrapDirectories(config, paths, ownership);
  lock = await acquireBootstrapLock(paths.lock, runId);

  try {
    await appendOpsAudit(paths.audit, {
      runId,
      operator: config.operator,
      environment: config.environment,
      serverVersion: manifest.serverVersion,
      sourceCommit: manifest.sourceCommit,
      imageId: manifest.imageId,
      startedAt,
      finishedAt: null,
      stage: "initialized",
      result: "started",
      errorCode: null,
    });
    const journal = await readOrCreateJournal(paths.journal, digest, manifest, startedAt, runId, ownership.host);
    await assertCurrentCompatible(config, manifest);

    stage = "artifact_loaded";
    if (!inspectLoadedImage(runner, manifest).loaded) {
      await verifyArchiveAtUse(manifest);
      const loaded = runner.run("docker", ["load", "--input", manifest.archivePath], {
        allowFailure: true,
        timeout: 120_000,
      });
      if (loaded.status !== 0) throw new OpsError("artifact", "bundle_image_load_failed", "artifact_image_load");
    }
    const runtimeImage = verifyLoadedImage(runner, manifest);
    await updateJournal(paths.journal, journal, stage, now, ownership.host);

    stage = "filesystem_ready";
    await atomicWrite(paths.releaseManifest, `${JSON.stringify(stripArchivePath(manifest), null, 2)}\n`, 0o644, ownership.host);
    await updateJournal(paths.journal, journal, stage, now, ownership.host);

    stage = "configuration_installed";
    await installConfiguration(config, manifest, paths, ownership, runtimeImage.imageId);
    await updateJournal(paths.journal, journal, stage, now, ownership.host);

    stage = "services_started";
    runner.run("systemctl", ["daemon-reload"]);
    runner.run("nginx", ["-t"]);
    runner.run("systemctl", ["enable", "--now", `${config.service.name}.service`], { timeout: 90_000 });
    runner.run("systemctl", ["enable", "--now", "nginx.service"], { timeout: 90_000 });
    runner.run("systemctl", ["reload", "nginx.service"], { timeout: 30_000 });
    await updateJournal(paths.journal, journal, stage, now, ownership.host);

    stage = "smoke_passed";
    await waitForGateway(config, fetchImpl, sleep);
    smokePublicRoute(runner, config);
    await updateJournal(paths.journal, journal, stage, now, ownership.host);

    stage = "complete";
    await installCurrentSymlink(config.paths.installRoot, releaseName);
    await updateJournal(paths.journal, journal, stage, now, ownership.host);

    const finishedAt = now().toISOString();
    await appendOpsAudit(paths.audit, {
      runId,
      operator: config.operator,
      environment: config.environment,
      serverVersion: manifest.serverVersion,
      sourceCommit: manifest.sourceCommit,
      imageId: manifest.imageId,
      startedAt,
      finishedAt,
      stage,
      result: "success",
      errorCode: null,
    });
    return {
      ok: true,
      command: "bootstrap",
      environment: config.environment,
      runId,
      serverVersion: manifest.serverVersion,
      sourceCommit: manifest.sourceCommit,
      imageId: manifest.imageId,
      stage,
      resumedFrom: journal.initialStage,
    };
  } catch (error) {
    await appendOpsAudit(paths.audit, {
      runId,
      operator: config.operator,
      environment: config.environment,
      serverVersion: manifest.serverVersion,
      sourceCommit: manifest.sourceCommit,
      imageId: manifest.imageId,
      startedAt,
      finishedAt: now().toISOString(),
      stage,
      result: "failed",
      errorCode: error instanceof OpsError
        ? createOpsError(error.kind, error.technicalCause, error.stage).code
        : "HR-OPS-003",
    }).catch(() => {});
    if (error instanceof OpsError) throw error;
    throw new OpsError("bootstrap", error instanceof Error ? error.message : error, `bootstrap_${stage}`);
  } finally {
    await releaseBootstrapLock(lock).catch(() => {});
  }
}

export async function getStatus(config, manifest, options = {}) {
  const runner = options.runner ?? createCommandRunner();
  const fetchImpl = options.fetchImpl ?? fetch;
  const serviceUnit = `${config.service.name}.service`;
  const serviceActive = runner.run("systemctl", ["is-active", "--quiet", serviceUnit], { allowFailure: true }).status === 0;
  const nginxActive = runner.run("systemctl", ["is-active", "--quiet", "nginx.service"], { allowFailure: true }).status === 0;
  const nginxValid = runner.run("nginx", ["-t"], { allowFailure: true }).status === 0;
  const container = runner.run(
    "docker",
    ["container", "inspect", "--format", "{{.State.Status}}|{{.Image}}", config.service.containerName],
    { allowFailure: true },
  );
  const [containerState = "absent", containerImage = ""] = container.status === 0
    ? container.stdout.trim().split("|")
    : ["absent", ""];
  const health = await probeGateway(fetchImpl, config.service.gatewayPort, "/healthz", "alive");
  const readiness = await probeGateway(fetchImpl, config.service.gatewayPort, "/readyz", "ready");
  const imageMatches = runtimeImageIds(manifest).includes(containerImage);
  const ok = serviceActive && nginxActive && nginxValid && containerState === "running" && imageMatches && health.ok && readiness.ok;

  return {
    ok,
    command: "status",
    environment: config.environment,
    serverVersion: manifest.serverVersion,
    sourceCommit: manifest.sourceCommit,
    layers: {
      systemd: { gateway: serviceActive ? "active" : "inactive", nginx: nginxActive ? "active" : "inactive" },
      nginx: { configuration: nginxValid ? "valid" : "invalid" },
      container: { state: containerState, imageIdentity: imageMatches ? "match" : "mismatch" },
      gateway: { liveness: health.status, readiness: readiness.status },
    },
    error: ok ? null : createOpsError("status", "managed_components_degraded", "status_collect"),
  };
}

export async function createDoctorBundle(config, manifest, outputPath, options = {}) {
  const runner = options.runner ?? createCommandRunner();
  const now = options.now ?? (() => new Date());
  let createdOutput = false;
  try {
    validateDoctorOutput(outputPath);
    await assertNoSymlinkAncestors(path.dirname(outputPath));
    const parent = await lstat(path.dirname(outputPath));
    if (parent.isSymbolicLink() || !parent.isDirectory()) throw new Error("doctor_parent_unsafe");

    const status = await getStatus(config, manifest, options);
    const report = buildDoctorReport(config, manifest, status, runner, now);
    const content = Buffer.from(`${JSON.stringify(report, null, 2)}\n`, "utf8");
    if (content.length > 64 * 1024) throw new Error("doctor_bundle_too_large");
    const handle = await open(outputPath, "wx", 0o600);
    createdOutput = true;
    try {
      await handle.writeFile(content);
      await handle.sync();
    } finally {
      await handle.close();
    }
    await chmod(outputPath, 0o600);
    const sha256 = await sha256File(outputPath);
    return {
      ok: true,
      command: "doctor",
      diagnosticId: report.diagnosticId,
      bytes: content.length,
      sha256,
      outputCreated: true,
      serviceReady: status.ok,
    };
  } catch (error) {
    if (createdOutput) await unlink(outputPath).catch(() => {});
    if (error instanceof OpsError && error.kind === "doctor") throw error;
    throw new OpsError("doctor", error instanceof Error ? error.message : error, "doctor_write");
  }
}

export function buildDoctorReport(config, manifest, status, runner, now = () => new Date()) {
  return {
    schemaVersion: 1,
    diagnosticId: randomUUID(),
    createdAt: now().toISOString(),
    collectionPolicy: {
      allowlistedFieldsOnly: true,
      journalIncluded: false,
      requestBodiesIncluded: false,
      environmentFilesIncluded: false,
      secretFilesIncluded: false,
      sourcePathsIncluded: false,
    },
    target: {
      environment: config.environment,
      service: config.service.name,
      serverName: config.nginx.serverName,
      gatewayPort: config.service.gatewayPort,
      edgePort: config.nginx.listenPort,
      accountAuthEnabled: false,
      accountBindingEnabled: false,
    },
    artifact: {
      serverVersion: manifest.serverVersion,
      sourceCommit: manifest.sourceCommit,
      imageId: manifest.imageId,
      architecture: manifest.architecture,
      archiveSha256: manifest.archiveSha256,
    },
    runtime: status,
    tools: {
      docker: safeVersion(runner, "docker", ["--version"]),
      nginx: safeVersion(runner, "nginx", ["-v"]),
      systemd: safeVersion(runner, "systemctl", ["--version"]),
    },
  };
}

export function inspectLoadedImage(runner, manifest) {
  const result = runner.run(
    "docker",
    ["image", "inspect", "--format", "{{.Id}}|{{.Architecture}}", manifest.imageReference],
    { allowFailure: true },
  );
  if (result.status !== 0) return { loaded: false };
  const [imageId, architecture] = result.stdout.trim().split("|");
  if (!runtimeImageIds(manifest).includes(imageId) || architecture !== "amd64") {
    throw new OpsError("artifact", "loaded_image_identity_mismatch", "artifact_image_inspect");
  }
  return { loaded: true, imageId, architecture };
}

export function verifyLoadedImage(runner, manifest) {
  const image = inspectLoadedImage(runner, manifest);
  if (!image.loaded) throw new OpsError("artifact", "bundle_image_not_loaded", "artifact_image_load");
  return image;
}

export async function inspectInputMaterial(config) {
  const app = await readPrivateToken(config.secrets.appTokenSource, "app_token");
  const connector = await readPrivateToken(config.secrets.connectorTokenSource, "connector_token");
  const internal = await readPrivateToken(config.secrets.internalStatusTokenSource, "internal_status_token");
  const fingerprints = [app, connector, internal].map((value) => createHash("sha256").update(value).digest("hex"));
  if (new Set(fingerprints).size !== fingerprints.length) {
    throw new OpsError("config", "gateway_tokens_must_be_distinct", "preflight_secrets");
  }
  const certificate = await readCertificate(config.nginx.certificateSource);
  const privateKey = await readPrivateKey(config.nginx.privateKeySource);
  const databaseUrl = config.database
    ? await readPrivateDatabaseUrl(config.database.urlSource)
    : null;
  const databaseFingerprint = databaseUrl ? sha256(databaseUrl) : null;
  const materialFingerprints = [...fingerprints, sha256(certificate), sha256(privateKey)];
  if (databaseFingerprint) materialFingerprints.push(databaseFingerprint);
  const fingerprint = createHash("sha256")
    .update(materialFingerprints.join(":"), "utf8")
    .digest("hex");
  return { app, connector, internal, certificate, privateKey, databaseUrl, fingerprint };
}

export async function verifyArchiveAtUse(manifest) {
  if (!manifest.archivePath) throw new OpsError("artifact", "bundle_archive_path_missing", "artifact_archive_verify");
  try {
    await assertRegularFile(manifest.archivePath, "artifact_archive");
  } catch (error) {
    throw new OpsError("artifact", error instanceof Error ? error.technicalCause || error.message : error, "artifact_archive_verify");
  }
  const actual = await sha256File(manifest.archivePath);
  if (actual !== manifest.archiveSha256) {
    throw new OpsError("artifact", "archive_sha256_mismatch", "artifact_archive_verify");
  }
}

async function installConfiguration(config, manifest, paths, ownership, runtimeImageId) {
  const material = await inspectInputMaterial(config);
  await atomicWrite(paths.appToken, `${material.app}\n`, 0o440, ownership.secret);
  await atomicWrite(paths.connectorToken, `${material.connector}\n`, 0o440, ownership.secret);
  await atomicWrite(paths.internalStatusToken, `${material.internal}\n`, 0o440, ownership.secret);
  await atomicWrite(paths.certificate, await readFile(config.nginx.certificateSource), 0o644, ownership.host);
  await atomicWrite(paths.privateKey, await readFile(config.nginx.privateKeySource), 0o600, ownership.host);
  await atomicWrite(paths.environment, renderGatewayEnvironment(config), 0o600, ownership.host);
  await atomicWrite(paths.unit, renderSystemdUnit(config, manifest, runtimeImageId), 0o644, ownership.host);
  await atomicWrite(config.nginx.configFile, renderNginxConfig(config), 0o644, ownership.host);
}

async function readPrivateToken(filePath, label) {
  const info = await assertRegularFile(filePath, label);
  if ((info.mode & 0o077) !== 0 || info.size < 32 || info.size > 4097) {
    throw new OpsError("config", `${label}_permissions_or_size_invalid`, "preflight_secrets");
  }
  const raw = await readFile(filePath, "utf8");
  const value = raw.endsWith("\n") ? raw.slice(0, -1) : raw;
  if (!/^[^\s]{32,4096}$/.test(value)) throw new OpsError("config", `${label}_format_invalid`, "preflight_secrets");
  return value;
}

async function readPrivateDatabaseUrl(filePath) {
  const info = await assertRegularFile(filePath, "account_database_url");
  if ((info.mode & 0o077) !== 0 || info.size < 16 || info.size > 4097) {
    throw new OpsError("config", "account_database_url_permissions_or_size_invalid", "preflight_database");
  }
  const raw = await readFile(filePath, "utf8");
  const value = raw.endsWith("\n") ? raw.slice(0, -1) : raw;
  if (/\s/.test(value)) {
    throw new OpsError("config", "account_database_url_format_invalid", "preflight_database");
  }
  try {
    const parsed = new URL(value);
    if (!new Set(["postgres:", "postgresql:"]).has(parsed.protocol)
        || !parsed.hostname || !parsed.username || !parsed.password
        || parsed.pathname.length < 2 || parsed.hash) {
      throw new Error("invalid");
    }
  } catch {
    throw new OpsError("config", "account_database_url_format_invalid", "preflight_database");
  }
  return value;
}

async function readCertificate(filePath) {
  const info = await assertRegularFile(filePath, "tls_certificate");
  if (info.size < 64 || info.size > 1024 * 1024) throw new OpsError("config", "tls_certificate_size_invalid", "preflight_tls");
  const value = await readFile(filePath, "utf8");
  if (!value.includes("-----BEGIN CERTIFICATE-----") || value.includes("PRIVATE KEY-----")) {
    throw new OpsError("config", "tls_certificate_format_invalid", "preflight_tls");
  }
  return value;
}

async function readPrivateKey(filePath) {
  const info = await assertRegularFile(filePath, "tls_private_key");
  if ((info.mode & 0o077) !== 0 || info.size < 64 || info.size > 128 * 1024) {
    throw new OpsError("config", "tls_private_key_permissions_or_size_invalid", "preflight_tls");
  }
  const value = await readFile(filePath, "utf8");
  if (!/-----BEGIN [^-\r\n]*PRIVATE KEY-----/.test(value)) {
    throw new OpsError("config", "tls_private_key_format_invalid", "preflight_tls");
  }
  return value;
}

async function inspectOptionalManagedRoot(root) {
  try {
    const info = await lstat(root);
    if (info.isSymbolicLink() || !info.isDirectory()) throw new OpsError("config", "managed_root_unsafe", "preflight_paths");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
}

async function inspectOptionalManagedFile(filePath, expectedContent) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile()) {
      throw new OpsError("bootstrap", "managed_file_unsafe", "preflight_managed_files");
    }
    const current = await readFile(filePath, "utf8");
    if (current !== expectedContent) {
      throw new OpsError("bootstrap", "existing_managed_file_conflict", "preflight_managed_files");
    }
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
}

async function assertJournalCompatible(config, manifest, inputMaterialFingerprint) {
  const journalPath = path.join(config.paths.stateRoot, "ops", "bootstrap-state.json");
  try {
    const info = await lstat(journalPath);
    if (info.isSymbolicLink() || !info.isFile() || (info.mode & 0o077) !== 0) {
      throw new OpsError("bootstrap", "bootstrap_journal_unsafe", "preflight_journal");
    }
    const journal = JSON.parse(await readFile(journalPath, "utf8"));
    const expectedDigest = deploymentDigest(config, manifest, inputMaterialFingerprint);
    if (journal.schemaVersion !== 1 || journal.deploymentDigest !== expectedDigest || !STAGES.includes(journal.stage)) {
      throw new OpsError("bootstrap", "bootstrap_journal_conflict", "preflight_journal");
    }
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw new OpsError("bootstrap", "bootstrap_journal_invalid", "preflight_journal");
  }
}

async function assertCurrentCompatible(config, manifest) {
  const current = path.join(config.paths.installRoot, "current");
  try {
    const info = await lstat(current);
    if (!info.isSymbolicLink()) throw new OpsError("bootstrap", "current_release_not_symlink", "preflight_current");
    const target = await readlink(current);
    const expected = path.join("releases", `${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`);
    if (target !== expected) throw new OpsError("bootstrap", "existing_release_requires_r4_deploy", "preflight_current");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
}

function bootstrapPaths(config, releaseName) {
  const opsRoot = path.join(config.paths.stateRoot, "ops");
  return {
    releaseDir: path.join(config.paths.installRoot, "releases", releaseName),
    releaseManifest: path.join(config.paths.installRoot, "releases", releaseName, "bundle.manifest.json"),
    secretsDir: path.join(config.paths.configRoot, "secrets"),
    tlsDir: path.join(config.paths.configRoot, "tls"),
    appToken: path.join(config.paths.configRoot, "secrets", "app-token"),
    connectorToken: path.join(config.paths.configRoot, "secrets", "connector-token"),
    internalStatusToken: path.join(config.paths.configRoot, "secrets", "internal-status-token"),
    certificate: path.join(config.paths.configRoot, "tls", "fullchain.pem"),
    privateKey: path.join(config.paths.configRoot, "tls", "privkey.pem"),
    environment: path.join(config.paths.configRoot, "gateway.env"),
    unit: path.join(config.paths.systemdUnitDirectory, `${config.service.name}.service`),
    journal: path.join(opsRoot, "bootstrap-state.json"),
    audit: path.join(opsRoot, "operations.jsonl"),
    lock: path.join(opsRoot, "bootstrap.lock"),
    opsRoot,
  };
}

async function prepareBootstrapDirectories(config, paths, ownership) {
  await ensureManagedDirectory(config.paths.installRoot, 0o755, ownership.host);
  await ensureManagedDirectory(path.join(config.paths.installRoot, "releases"), 0o755, ownership.host);
  await ensureManagedDirectory(paths.releaseDir, 0o755, ownership.host);
  await ensureManagedDirectory(config.paths.configRoot, 0o750, ownership.host);
  await ensureManagedDirectory(paths.secretsDir, 0o750, ownership.secret);
  await ensureManagedDirectory(paths.tlsDir, 0o750, ownership.host);
  await ensureManagedDirectory(config.paths.stateRoot, 0o750, ownership.host);
  await ensureManagedDirectory(path.join(config.paths.stateRoot, "gateway"), 0o700, ownership.container);
  await ensureManagedDirectory(paths.opsRoot, 0o700, ownership.host);
}

async function readOrCreateJournal(filePath, digest, manifest, startedAt, runId, owner) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile() || (info.mode & 0o077) !== 0) {
      throw new OpsError("bootstrap", "bootstrap_journal_unsafe", "bootstrap_resume");
    }
    const journal = JSON.parse(await readFile(filePath, "utf8"));
    if (journal.schemaVersion !== 1 || journal.deploymentDigest !== digest || !STAGES.includes(journal.stage)) {
      throw new OpsError("bootstrap", "bootstrap_journal_conflict", "bootstrap_resume");
    }
    return { ...journal, initialStage: journal.stage, runId };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw new OpsError("bootstrap", "bootstrap_journal_invalid", "bootstrap_resume");
  }
  const journal = {
    schemaVersion: 1,
    deploymentDigest: digest,
    serverVersion: manifest.serverVersion,
    sourceCommit: manifest.sourceCommit,
    stage: "initialized",
    startedAt,
    updatedAt: startedAt,
    runId,
    initialStage: "none",
  };
  await atomicWrite(filePath, `${JSON.stringify(stripInternalJournal(journal), null, 2)}\n`, 0o600, owner);
  return journal;
}

async function updateJournal(filePath, journal, stage, now, owner) {
  journal.stage = stage;
  journal.updatedAt = now().toISOString();
  await atomicWrite(filePath, `${JSON.stringify(stripInternalJournal(journal), null, 2)}\n`, 0o600, owner);
}

export async function appendOpsAudit(filePath, record, { kind = "bootstrap", stage = "bootstrap_audit" } = {}) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile() || (info.mode & 0o077) !== 0) {
      throw new OpsError(kind, "audit_log_unsafe", stage);
    }
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
  const handle = await open(filePath, "a", 0o600);
  try {
    await handle.writeFile(`${JSON.stringify(record)}\n`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  await import("node:fs/promises").then(({ chmod }) => chmod(filePath, 0o600));
}

async function acquireBootstrapLock(filePath, runId, attempt = 0) {
  let handle;
  try {
    handle = await open(filePath, "wx", 0o600);
    const value = { schemaVersion: 1, runId, pid: process.pid, hostname: hostname() };
    try {
      await handle.writeFile(`${JSON.stringify(value)}\n`, "utf8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    return { filePath, runId };
  } catch (error) {
    if (handle) {
      await handle.close().catch(() => {});
      await unlink(filePath).catch(() => {});
    }
    if (error?.code !== "EEXIST" || attempt > 0) {
      throw new OpsError("bootstrap", "bootstrap_lock_unavailable", "bootstrap_lock");
    }
    let existing;
    let originalInfo;
    try {
      originalInfo = await lstat(filePath);
      if (originalInfo.isSymbolicLink() || !originalInfo.isFile() || (originalInfo.mode & 0o077) !== 0) {
        throw new Error("unsafe_lock");
      }
      existing = JSON.parse(await readFile(filePath, "utf8"));
    } catch {
      throw new OpsError("bootstrap", "bootstrap_lock_invalid", "bootstrap_lock");
    }
    if (existing.hostname !== hostname() || !Number.isSafeInteger(existing.pid) || existing.pid < 1) {
      throw new OpsError("bootstrap", "bootstrap_lock_owner_unknown", "bootstrap_lock");
    }
    try {
      process.kill(existing.pid, 0);
      throw new OpsError("bootstrap", "bootstrap_already_running", "bootstrap_lock");
    } catch (probeError) {
      if (probeError instanceof OpsError) throw probeError;
      if (probeError?.code !== "ESRCH") throw new OpsError("bootstrap", "bootstrap_lock_probe_failed", "bootstrap_lock");
    }
    const currentInfo = await lstat(filePath);
    if (currentInfo.dev !== originalInfo.dev || currentInfo.ino !== originalInfo.ino) {
      throw new OpsError("bootstrap", "bootstrap_lock_changed", "bootstrap_lock");
    }
    await unlink(filePath);
    return acquireBootstrapLock(filePath, runId, attempt + 1);
  }
}

async function releaseBootstrapLock(lock) {
  if (!lock) return;
  const existing = JSON.parse(await readFile(lock.filePath, "utf8"));
  if (existing.runId === lock.runId) await unlink(lock.filePath);
}

async function waitForGateway(config, fetchImpl, sleep) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const health = await probeGateway(fetchImpl, config.service.gatewayPort, "/healthz", "alive");
    const ready = await probeGateway(fetchImpl, config.service.gatewayPort, "/readyz", "ready");
    if (health.ok && ready.ok) return;
    await sleep(250);
  }
  throw new OpsError("bootstrap", "gateway_probe_timeout", "bootstrap_smoke");
}

function smokePublicRoute(runner, config) {
  const origin = `https://${config.nginx.serverName}:${config.nginx.listenPort}`;
  const result = runner.run("curl", [
    "--fail",
    "--silent",
    "--show-error",
    "--max-time",
    "5",
    "--resolve",
    `${config.nginx.serverName}:${config.nginx.listenPort}:127.0.0.1`,
    "--output",
    "/dev/null",
    `${origin}/relay-health`,
  ], { allowFailure: true });
  if (result.status !== 0) throw new OpsError("bootstrap", "public_route_smoke_failed", "bootstrap_smoke");
}

async function probeGateway(fetchImpl, port, endpoint, expectedStatus) {
  try {
    const response = await fetchImpl(`http://127.0.0.1:${port}${endpoint}`, {
      signal: AbortSignal.timeout(2_000),
    });
    if (!response.ok) return { ok: false, status: `http_${response.status}` };
    const body = await response.json();
    return body?.status === expectedStatus
      ? { ok: true, status: expectedStatus }
      : { ok: false, status: "contract_mismatch" };
  } catch {
    return { ok: false, status: "unreachable" };
  }
}

function buildDoctorToolValue(result) {
  if (result.status !== 0) return "unavailable";
  const line = `${result.stdout}\n${result.stderr}`.split(/\r?\n/).map((value) => value.trim()).find(Boolean);
  return redactOpsValue(line || "available").slice(0, 160);
}

function safeVersion(runner, command, args) {
  return buildDoctorToolValue(runner.run(command, args, { allowFailure: true }));
}

function validateDoctorOutput(outputPath) {
  if (typeof outputPath !== "string" || !path.isAbsolute(outputPath) || path.normalize(outputPath) !== outputPath) {
    throw new OpsError("doctor", "doctor_output_path_invalid", "doctor_validate");
  }
  if (!/^\/[A-Za-z0-9._/-]+\.json$/.test(outputPath) || outputPath.includes("//")) {
    throw new OpsError("doctor", "doctor_output_path_unsafe", "doctor_validate");
  }
}

function stripArchivePath(manifest) {
  return manifestIdentity(manifest);
}

function stripInternalJournal(journal) {
  const { initialStage: _initialStage, ...persisted } = journal;
  return persisted;
}

function check(id, status, detail) {
  return { id, status, detail };
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
