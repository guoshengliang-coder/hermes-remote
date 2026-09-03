import { open, readFile, unlink } from "node:fs/promises";
import path from "node:path";

const [
  manifestPath,
  archiveFile,
  archiveSha256,
  imageReference,
  imageId,
  architecture,
  serverVersion,
  sourceCommit,
  createdAt,
  releaseContractPath,
] = process.argv.slice(2);

if (!manifestPath || process.argv.length !== 12) {
  throw new Error("usage: write-gateway-bundle-manifest <manifest> <archive> <sha256> <image> <id> <arch> <version> <commit> <created-at> <release-contract>");
}

const releaseContract = validateReleaseContract(JSON.parse(await readFile(releaseContractPath, "utf8")));

const manifest = validateManifest({
  schemaVersion: 2,
  kind: "hermes-go-gateway-oci",
  serverVersion,
  sourceCommit,
  imageReference,
  imageId,
  architecture,
  archiveFile,
  archiveSha256,
  createdAt,
  releaseContract,
});

const handle = await open(manifestPath, "wx", 0o644);
try {
  await handle.writeFile(`${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  await handle.sync();
} catch (error) {
  await handle.close();
  await unlink(manifestPath).catch(() => {});
  throw error;
} finally {
  await handle.close().catch(() => {});
}

function validateManifest(value) {
  if (value.archiveFile !== path.basename(value.archiveFile)) throw new Error("archiveFile must be a basename");
  if (!/^Hermes-Gateway-[A-Za-z0-9._-]+-linux-amd64\.tar$/.test(value.archiveFile)) {
    throw new Error("invalid archiveFile");
  }
  if (!/^[0-9a-f]{64}$/.test(value.archiveSha256)) throw new Error("invalid archiveSha256");
  if (!/^sha256:[0-9a-f]{64}$/.test(value.imageId)) throw new Error("invalid imageId");
  if (value.architecture !== "amd64") throw new Error("invalid architecture");
  if (!/^\d+\.\d+\.\d+$/.test(value.serverVersion)) throw new Error("invalid serverVersion");
  if (!/^[0-9a-f]{40}$/.test(value.sourceCommit)) throw new Error("invalid sourceCommit");
  if (!/^hermes-remote-gateway:[A-Za-z0-9._-]+$/.test(value.imageReference)) {
    throw new Error("invalid imageReference");
  }
  if (!Number.isFinite(Date.parse(value.createdAt)) || !value.createdAt.endsWith("Z")) {
    throw new Error("invalid createdAt");
  }
  const expectedStem = `Hermes-Gateway-${value.serverVersion}-${value.sourceCommit.slice(0, 12)}-linux-amd64`;
  if (value.archiveFile !== `${expectedStem}.tar`) throw new Error("archive identity mismatch");
  if (value.imageReference !== `hermes-remote-gateway:${value.serverVersion}-${value.sourceCommit.slice(0, 12)}`) {
    throw new Error("image identity mismatch");
  }
  return value;
}

function validateReleaseContract(value) {
  exactKeys(value, [
    "manifestVersion",
    "configSchemaVersion",
    "databaseSchemaVersion",
    "supportedPostgresqlMajors",
    "protocolVersions",
    "minimumClients",
    "minimumSourceVersion",
    "maintenanceRequired",
    "rollbackSupported",
  ]);
  exactKeys(value.protocolVersions, ["legacy", "accountConnector"]);
  exactKeys(value.minimumClients, ["android", "desktop", "connector"]);
  for (const name of ["manifestVersion", "configSchemaVersion", "databaseSchemaVersion"]) {
    if (!Number.isSafeInteger(value[name]) || value[name] < 1) throw new Error(`invalid ${name}`);
  }
  if (!Array.isArray(value.supportedPostgresqlMajors)
      || value.supportedPostgresqlMajors.length === 0
      || !value.supportedPostgresqlMajors.every((entry) => Number.isSafeInteger(entry) && entry > 0)
      || new Set(value.supportedPostgresqlMajors).size !== value.supportedPostgresqlMajors.length) {
    throw new Error("invalid supportedPostgresqlMajors");
  }
  for (const name of ["legacy", "accountConnector"]) {
    if (!Number.isSafeInteger(value.protocolVersions[name]) || value.protocolVersions[name] < 1) {
      throw new Error(`invalid protocolVersions.${name}`);
    }
  }
  for (const name of ["android", "desktop", "connector"]) {
    if (!isVersion(value.minimumClients[name])) throw new Error(`invalid minimumClients.${name}`);
  }
  if (!isVersion(value.minimumSourceVersion)) throw new Error("invalid minimumSourceVersion");
  if (typeof value.maintenanceRequired !== "boolean" || typeof value.rollbackSupported !== "boolean") {
    throw new Error("invalid release policy flags");
  }
  return value;
}

function exactKeys(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("release contract must be an object");
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error("release contract fields invalid");
  }
}

function isVersion(value) {
  return typeof value === "string" && /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(value);
}
