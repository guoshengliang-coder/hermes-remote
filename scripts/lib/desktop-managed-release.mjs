import {
  constants as fsConstants,
  copyFile,
  lstat,
  mkdir,
  readFile,
  realpath,
  rm,
  writeFile,
} from "node:fs/promises";
import { createHash, createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
import { createReadStream } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";

const MAX_CONFIG_BYTES = 128 * 1024;
const MAX_ENVELOPE_BYTES = 256 * 1024;
const MAX_PAYLOAD_BYTES = 128 * 1024;
const MAX_ARTIFACT_BYTES = 2 * 1024 * 1024 * 1024;
const MAX_ARCHIVE_LISTING_BYTES = 16 * 1024 * 1024;
const MAX_ARCHIVE_ENTRIES = 65_536;
const MAX_MANIFEST_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000;
const ALLOWED_ARCHITECTURES = new Set(["arm64", "x86_64", "universal"]);
const COMPONENTS = ["hermes_server", "connector"];
const SPKI_ED25519_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

export class DesktopManagedReleaseError extends Error {
  constructor(cause) {
    super(cause);
    this.name = "DesktopManagedReleaseError";
    this.technicalCause = cause;
  }
}

export async function packageDesktopManagedRelease({ configPath, outputDirectory }) {
  const createdFiles = [];
  try {
    const config = await loadPublisherConfig(configPath);
    const output = await requireSafeDirectory(outputDirectory, true);
    const signingKey = await loadSigningKey(config.signing.privateKeyFile);
    const publicKey = rawEd25519PublicKey(signingKey);
    const publicKeyBase64URL = canonicalBase64URL(publicKey);
    const artifactPathPrefix = normalizeArtifactPathPrefix(config.artifactPathPrefix);
    const artifacts = [];

    for (const component of COMPONENTS) {
      const input = config.artifacts.find((artifact) => artifact.component === component);
      const expectedName = artifactFileName(component, input.version, config.architecture);
      const source = await requireSafeRegularFile(input.source, MAX_ARTIFACT_BYTES);
      await verifySafeArchive(source, input.entrypoint);
      const destination = path.join(output, expectedName);
      await requireAbsent(destination);
      await copyFile(source, destination, fsConstants.COPYFILE_EXCL);
      createdFiles.push(destination);
      const copied = await requireSafeRegularFile(destination, MAX_ARTIFACT_BYTES);
      const sizeBytes = (await lstat(copied)).size;
      const sha256 = await sha256File(copied);
      const downloadURL = artifactURL(config.artifactOrigin, artifactPathPrefix, expectedName);
      artifacts.push({
        component,
        version: input.version,
        fileName: expectedName,
        entrypoint: input.entrypoint,
        downloadURL,
        sizeBytes,
        sha256,
      });
    }

    const payloadObject = {
      schemaVersion: 1,
      releaseVersion: config.releaseVersion,
      channel: config.channel,
      platform: "macos",
      architecture: config.architecture,
      minimumMacOS: config.minimumMacOS,
      createdAt: config.createdAt,
      expiresAt: config.expiresAt,
      artifacts,
    };
    validatePayload(payloadObject, {
      expectedOrigin: config.artifactOrigin,
      expectedChannel: config.channel,
      expectedArchitecture: config.architecture,
      now: new Date(),
    });
    const payload = Buffer.from(JSON.stringify(payloadObject));
    if (payload.length > MAX_PAYLOAD_BYTES) fail("manifest_payload_too_large");
    const signature = sign(null, payload, signingKey);
    if (signature.length !== 64 || !verify(null, payload, createPublicKey(signingKey), signature)) {
      fail("manifest_signature_self_check_failed");
    }
    const envelope = {
      algorithm: "Ed25519",
      keyId: config.signing.keyId,
      payload: canonicalBase64URL(payload),
      signature: canonicalBase64URL(signature),
    };
    const envelopeData = Buffer.from(`${JSON.stringify(envelope)}\n`);
    if (envelopeData.length > MAX_ENVELOPE_BYTES) fail("manifest_envelope_too_large");
    const manifestFile = `Hermes-Desktop-${config.releaseVersion}-${config.architecture}.manifest.json`;
    const manifestPath = path.join(output, manifestFile);
    await requireAbsent(manifestPath);
    await writeFile(manifestPath, envelopeData, { flag: "wx", mode: 0o644 });
    createdFiles.push(manifestPath);

    await verifyDesktopManagedRelease({
      manifestPath,
      artifactDirectory: output,
      expectedKeyId: config.signing.keyId,
      publicKey: publicKeyBase64URL,
      expectedOrigin: config.artifactOrigin,
      expectedChannel: config.channel,
      expectedArchitecture: config.architecture,
      now: new Date(),
    });

    return {
      manifestPath,
      manifestSha256: await sha256File(manifestPath),
      keyId: config.signing.keyId,
      publicKey: publicKeyBase64URL,
      releaseVersion: config.releaseVersion,
      artifacts: artifacts.map((artifact) => ({
        component: artifact.component,
        path: path.join(output, artifact.fileName),
        sizeBytes: artifact.sizeBytes,
        sha256: artifact.sha256,
      })),
    };
  } catch (error) {
    for (const file of createdFiles.reverse()) await rm(file, { force: true }).catch(() => {});
    if (error instanceof DesktopManagedReleaseError) throw error;
    fail("unexpected_packaging_failure");
  }
}

export async function verifyDesktopManagedRelease({
  manifestPath,
  artifactDirectory,
  expectedKeyId,
  publicKey,
  expectedOrigin,
  expectedChannel,
  expectedArchitecture,
  now = new Date(),
}) {
  try {
    if (!validIdentifier(expectedKeyId, 64)) fail("verification_key_id_invalid");
    const publicKeyBytes = decodeCanonicalBase64URL(publicKey, "verification_public_key_invalid");
    if (publicKeyBytes.length !== 32) fail("verification_public_key_invalid");
    const key = createPublicKey({
      key: Buffer.concat([SPKI_ED25519_PREFIX, publicKeyBytes]),
      format: "der",
      type: "spki",
    });
    const manifest = await requireSafeRegularFile(manifestPath, MAX_ENVELOPE_BYTES);
    const envelopeData = await readFile(manifest);
    const envelope = parseJSON(envelopeData, "manifest_envelope_invalid");
    requireExactKeys(envelope, ["algorithm", "keyId", "payload", "signature"], "manifest_envelope_fields_invalid");
    if (envelope.algorithm !== "Ed25519" || envelope.keyId !== expectedKeyId) {
      fail("manifest_signing_identity_invalid");
    }
    const payload = decodeCanonicalBase64URL(envelope.payload, "manifest_payload_encoding_invalid");
    const signature = decodeCanonicalBase64URL(envelope.signature, "manifest_signature_encoding_invalid");
    if (payload.length > MAX_PAYLOAD_BYTES || signature.length !== 64) fail("manifest_envelope_invalid");
    if (!verify(null, payload, key, signature)) fail("manifest_signature_invalid");
    const payloadObject = parseJSON(payload, "manifest_payload_invalid");
    validatePayload(payloadObject, {
      expectedOrigin,
      expectedChannel,
      expectedArchitecture,
      now,
    });

    const artifactRoot = await requireSafeDirectory(artifactDirectory, false);
    for (const artifact of payloadObject.artifacts) {
      const local = await requireSafeRegularFile(path.join(artifactRoot, artifact.fileName), MAX_ARTIFACT_BYTES);
      const info = await lstat(local);
      if (info.size !== artifact.sizeBytes || await sha256File(local) !== artifact.sha256) {
        fail("artifact_integrity_invalid");
      }
      await verifySafeArchive(local, artifact.entrypoint);
    }
    return payloadObject;
  } catch (error) {
    if (error instanceof DesktopManagedReleaseError) throw error;
    fail("unexpected_verification_failure");
  }
}

export async function loadPublisherConfig(configPath) {
  const file = await requireSafeRegularFile(configPath, MAX_CONFIG_BYTES);
  const config = parseJSON(await readFile(file), "publisher_config_invalid");
  requireExactKeys(config, [
    "schemaVersion", "releaseVersion", "channel", "architecture", "minimumMacOS",
    "createdAt", "expiresAt", "artifactOrigin", "artifactPathPrefix", "signing", "artifacts",
  ], "publisher_config_fields_invalid");
  if (config.schemaVersion !== 1 || !validSemanticVersion(config.releaseVersion)
      || !validIdentifier(config.channel, 32) || !ALLOWED_ARCHITECTURES.has(config.architecture)
      || !validMacOSVersion(config.minimumMacOS)) {
    fail("publisher_release_identity_invalid");
  }
  validateDates(config.createdAt, config.expiresAt, new Date());
  requireHTTPSOrigin(config.artifactOrigin);
  normalizeArtifactPathPrefix(config.artifactPathPrefix);
  if (!isPlainObject(config.signing)) fail("publisher_signing_config_invalid");
  requireExactKeys(config.signing, ["keyId", "privateKeyFile"], "publisher_signing_config_invalid");
  if (!validIdentifier(config.signing.keyId, 64) || !path.isAbsolute(config.signing.privateKeyFile)) {
    fail("publisher_signing_config_invalid");
  }
  if (!Array.isArray(config.artifacts) || config.artifacts.length !== COMPONENTS.length) {
    fail("publisher_artifacts_invalid");
  }
  const components = new Set();
  for (const artifact of config.artifacts) {
    if (!isPlainObject(artifact)) fail("publisher_artifacts_invalid");
    requireExactKeys(artifact, ["component", "version", "source", "entrypoint"], "publisher_artifacts_invalid");
    if (!COMPONENTS.includes(artifact.component) || components.has(artifact.component)
        || !validSemanticVersion(artifact.version) || !path.isAbsolute(artifact.source)
        || !validRelativePath(artifact.entrypoint)) {
      fail("publisher_artifacts_invalid");
    }
    components.add(artifact.component);
  }
  return config;
}

async function loadSigningKey(value) {
  const file = await requireSafeRegularFile(value, 16 * 1024);
  const info = await lstat(file);
  if ((info.mode & 0o077) !== 0 || (typeof process.getuid === "function" && info.uid !== process.getuid())) {
    fail("signing_key_permissions_invalid");
  }
  let key;
  try {
    key = createPrivateKey(await readFile(file));
  } catch {
    fail("signing_key_invalid");
  }
  if (key.type !== "private" || key.asymmetricKeyType !== "ed25519") fail("signing_key_invalid");
  return key;
}

function rawEd25519PublicKey(privateKey) {
  const der = createPublicKey(privateKey).export({ format: "der", type: "spki" });
  if (der.length !== SPKI_ED25519_PREFIX.length + 32
      || !der.subarray(0, SPKI_ED25519_PREFIX.length).equals(SPKI_ED25519_PREFIX)) {
    fail("signing_public_key_invalid");
  }
  return der.subarray(SPKI_ED25519_PREFIX.length);
}

function validatePayload(payload, { expectedOrigin, expectedChannel, expectedArchitecture, now }) {
  if (!isPlainObject(payload)) fail("manifest_payload_invalid");
  requireExactKeys(payload, [
    "schemaVersion", "releaseVersion", "channel", "platform", "architecture",
    "minimumMacOS", "createdAt", "expiresAt", "artifacts",
  ], "manifest_payload_fields_invalid");
  requireHTTPSOrigin(expectedOrigin);
  if (payload.schemaVersion !== 1 || payload.platform !== "macos"
      || payload.channel !== expectedChannel
      || (payload.architecture !== expectedArchitecture && payload.architecture !== "universal")
      || !validIdentifier(payload.channel, 32) || !ALLOWED_ARCHITECTURES.has(payload.architecture)
      || !validSemanticVersion(payload.releaseVersion) || !validMacOSVersion(payload.minimumMacOS)) {
    fail("manifest_release_identity_invalid");
  }
  validateDates(payload.createdAt, payload.expiresAt, now);
  if (!Array.isArray(payload.artifacts) || payload.artifacts.length !== COMPONENTS.length) {
    fail("manifest_artifacts_invalid");
  }
  const components = new Set();
  for (const artifact of payload.artifacts) {
    if (!isPlainObject(artifact)) fail("manifest_artifacts_invalid");
    requireExactKeys(artifact, [
      "component", "version", "fileName", "entrypoint", "downloadURL", "sizeBytes", "sha256",
    ], "manifest_artifact_fields_invalid");
    if (!COMPONENTS.includes(artifact.component) || components.has(artifact.component)
        || !validSemanticVersion(artifact.version)
        || artifact.fileName !== artifactFileName(artifact.component, artifact.version, payload.architecture)
        || !validRelativePath(artifact.entrypoint)
        || !Number.isSafeInteger(artifact.sizeBytes) || artifact.sizeBytes < 1
        || artifact.sizeBytes > MAX_ARTIFACT_BYTES || !/^[0-9a-f]{64}$/.test(artifact.sha256)) {
      fail("manifest_artifacts_invalid");
    }
    validateArtifactURL(artifact.downloadURL, expectedOrigin, artifact.fileName);
    components.add(artifact.component);
  }
  if (components.size !== COMPONENTS.length) fail("manifest_artifacts_invalid");
}

function validateDates(createdAt, expiresAt, now) {
  const created = parseCanonicalDate(createdAt);
  const expires = parseCanonicalDate(expiresAt);
  if (!created || !expires || !(now instanceof Date) || Number.isNaN(now.valueOf())
      || created > new Date(now.valueOf() + 5 * 60 * 1000) || expires <= now
      || expires.valueOf() - created.valueOf() > MAX_MANIFEST_LIFETIME_MS) {
    fail("manifest_lifetime_invalid");
  }
}

function parseCanonicalDate(value) {
  if (typeof value !== "string"
      || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) return null;
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return null;
  const canonical = date.toISOString();
  return canonical === value || canonical.replace(".000Z", "Z") === value ? date : null;
}

function requireHTTPSOrigin(value) {
  let url;
  try { url = new URL(value); } catch { fail("artifact_origin_invalid"); }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash
      || url.pathname !== "/" || url.origin !== value.replace(/\/$/, "")) {
    fail("artifact_origin_invalid");
  }
  return url.origin;
}

function normalizeArtifactPathPrefix(value) {
  if (typeof value !== "string" || !value.startsWith("/") || value.endsWith("/")
      || value.includes("\\") || /[?#\u0000-\u001f\u007f]/.test(value)) {
    fail("artifact_path_prefix_invalid");
  }
  const parts = value.slice(1).split("/");
  if (!parts.length || parts.some((part) => !part || part === "." || part === ".."
      || !/^[A-Za-z0-9._-]+$/.test(part))) fail("artifact_path_prefix_invalid");
  return `/${parts.join("/")}`;
}

function artifactURL(origin, prefix, fileName) {
  const normalizedOrigin = requireHTTPSOrigin(origin);
  return `${normalizedOrigin}${prefix}/${fileName}`;
}

function validateArtifactURL(value, origin, fileName) {
  let url;
  try { url = new URL(value); } catch { fail("artifact_url_invalid"); }
  const encodedPath = value.slice(url.origin.length).toLowerCase();
  if (url.protocol !== "https:" || url.origin !== requireHTTPSOrigin(origin)
      || url.username || url.password || url.search || url.hash
      || path.posix.basename(url.pathname) !== fileName
      || /\/(?:\.\.|%2e%2e)(?:\/|$)/.test(encodedPath)
      || encodedPath.includes("%2f") || encodedPath.includes("%5c")) {
    fail("artifact_url_invalid");
  }
}

async function requireSafeDirectory(value, create) {
  if (typeof value !== "string" || !path.isAbsolute(value) || value === "/") fail("output_directory_invalid");
  if (create) await mkdir(value, { recursive: true, mode: 0o755 }).catch(() => fail("output_directory_invalid"));
  let info;
  try { info = await lstat(value); } catch { fail("output_directory_invalid"); }
  if (!info.isDirectory() || info.isSymbolicLink()) fail("output_directory_invalid");
  const resolved = await realpath(value).catch(() => fail("output_directory_invalid"));
  if (resolved === "/") fail("output_directory_invalid");
  return resolved;
}

async function requireSafeRegularFile(value, maximumBytes) {
  if (typeof value !== "string" || !path.isAbsolute(value)) fail("input_file_invalid");
  let info;
  try { info = await lstat(value); } catch { fail("input_file_invalid"); }
  if (!info.isFile() || info.isSymbolicLink() || info.size < 1 || info.size > maximumBytes) {
    fail("input_file_invalid");
  }
  const resolved = await realpath(value).catch(() => fail("input_file_invalid"));
  if (resolved !== value) fail("input_file_invalid");
  return resolved;
}

async function requireAbsent(value) {
  try {
    await lstat(value);
    fail("output_target_exists");
  } catch (error) {
    if (error instanceof DesktopManagedReleaseError) throw error;
    if (error?.code !== "ENOENT") fail("output_target_invalid");
  }
}

async function verifySafeArchive(archive, entrypoint) {
  const namesResult = spawnSync("/usr/bin/tar", ["-tzf", archive], {
    encoding: "utf8", maxBuffer: MAX_ARCHIVE_LISTING_BYTES, timeout: 30_000,
    stdio: ["ignore", "pipe", "ignore"],
  });
  const typesResult = spawnSync("/usr/bin/tar", ["-tvzf", archive], {
    encoding: "utf8", maxBuffer: MAX_ARCHIVE_LISTING_BYTES, timeout: 30_000,
    stdio: ["ignore", "pipe", "ignore"],
  });
  if (namesResult.error || typesResult.error || namesResult.status !== 0 || typesResult.status !== 0) {
    fail("artifact_archive_listing_failed");
  }
  const names = namesResult.stdout.split(/\r?\n/).filter(Boolean);
  const typeLines = typesResult.stdout.split(/\r?\n/).filter(Boolean);
  if (!names.length || names.length > MAX_ARCHIVE_ENTRIES || names.length !== typeLines.length) {
    fail("artifact_archive_unsafe");
  }
  const normalized = names.map(normalizeArchiveMember);
  if (new Set(normalized).size !== normalized.length
      || typeLines.some((line) => line[0] !== "-" && line[0] !== "d")) {
    fail("artifact_archive_unsafe");
  }
  const entrypointIndex = normalized.indexOf(entrypoint);
  if (entrypointIndex < 0 || typeLines[entrypointIndex][0] !== "-") fail("artifact_entrypoint_missing");
}

function normalizeArchiveMember(value) {
  if (!value || Buffer.byteLength(value) > 512 || value.startsWith("/") || value.includes("\\")
      || /[\u0000-\u001f\u007f]/.test(value)) fail("artifact_archive_unsafe");
  let normalized = value.endsWith("/") ? value.slice(0, -1) : value;
  while (normalized.startsWith("./")) normalized = normalized.slice(2);
  if (normalized === ".") return normalized;
  const parts = normalized.split("/");
  if (!parts.length || parts.some((part) => !part || part === "." || part === "..")) {
    fail("artifact_archive_unsafe");
  }
  return normalized;
}

function artifactFileName(component, version, architecture) {
  const prefix = component === "connector" ? "Hermes-Connector" : "Hermes-Server";
  return `${prefix}-${version}-${architecture}.tar.gz`;
}

function validIdentifier(value, maximum) {
  return typeof value === "string" && Buffer.byteLength(value) >= 1
    && Buffer.byteLength(value) <= maximum && /^[A-Za-z0-9._-]+$/.test(value);
}

function validSemanticVersion(value) {
  return typeof value === "string" && /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value);
}

function validMacOSVersion(value) {
  return typeof value === "string" && /^(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?$/.test(value);
}

function validRelativePath(value) {
  return typeof value === "string" && Buffer.byteLength(value) >= 1 && Buffer.byteLength(value) <= 256
    && !value.startsWith("/") && !value.endsWith("/") && !value.includes("\\")
    && !/[\u0000-\u001f\u007f]/.test(value)
    && value.split("/").every((part) => part && part !== "." && part !== "..");
}

function requireExactKeys(value, expected, cause) {
  if (!isPlainObject(value)) fail(cause);
  const actual = Object.keys(value).sort();
  const required = [...expected].sort();
  if (actual.length !== required.length || actual.some((key, index) => key !== required[index])) fail(cause);
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    && Object.getPrototypeOf(value) === Object.prototype;
}

function parseJSON(data, cause) {
  try {
    const value = JSON.parse(Buffer.from(data).toString("utf8"));
    if (!isPlainObject(value)) fail(cause);
    return value;
  } catch (error) {
    if (error instanceof DesktopManagedReleaseError) throw error;
    fail(cause);
  }
}

function canonicalBase64URL(value) {
  return Buffer.from(value).toString("base64url");
}

function decodeCanonicalBase64URL(value, cause) {
  if (typeof value !== "string" || !value || !/^[A-Za-z0-9_-]+$/.test(value)) fail(cause);
  let decoded;
  try { decoded = Buffer.from(value, "base64url"); } catch { fail(cause); }
  if (canonicalBase64URL(decoded) !== value) fail(cause);
  return decoded;
}

async function sha256File(value) {
  return new Promise((resolve, reject) => {
    const hash = createHash("sha256");
    const stream = createReadStream(value);
    stream.on("error", () => reject(new DesktopManagedReleaseError("artifact_read_failed")));
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

function fail(cause) {
  throw new DesktopManagedReleaseError(cause);
}
