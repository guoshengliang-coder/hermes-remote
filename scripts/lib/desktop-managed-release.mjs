import {
  constants as fsConstants,
  chmod,
  copyFile,
  lstat,
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  realpath,
  rm,
  writeFile,
} from "node:fs/promises";
import { createHash, createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
import { createReadStream } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { tmpdir } from "node:os";

const MAX_CONFIG_BYTES = 128 * 1024;
const MAX_ENVELOPE_BYTES = 256 * 1024;
const MAX_PAYLOAD_BYTES = 128 * 1024;
const MAX_ARTIFACT_BYTES = 2 * 1024 * 1024 * 1024;
const MAX_ARCHIVE_LISTING_BYTES = 16 * 1024 * 1024;
const MAX_ARCHIVE_ENTRIES = 65_536;
const ALLOWED_ARCHITECTURES = new Set(["arm64", "x86_64", "universal"]);
const COMPONENTS = ["hermes_server", "connector"];
const COMPONENT_KINDS_V2 = new Set([
  "python_runtime", "node_runtime", "hermes_core", "connector", "browser_automation",
  "speech_runtime", "document_tools",
]);
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

export async function packageDesktopComponentReleaseV2({ configPath, outputDirectory }) {
  const createdFiles = [];
  try {
    const config = await loadComponentPublisherConfigV2(configPath);
    const output = await requireSafeDirectory(outputDirectory, true);
    const signingKey = await loadSigningKey(config.signing.privateKeyFile);
    const publicKey = rawEd25519PublicKey(signingKey);
    const prefix = normalizeArtifactPathPrefix(config.artifactPathPrefix);
    const components = [];
    for (const input of config.components) {
      const fileName = componentArtifactFileNameV2(input.kind, input.version, input.architecture);
      const source = await requireSafeRegularFile(input.source, MAX_ARTIFACT_BYTES);
      await verifySafeArchive(source, input.entrypoint);
      const destination = path.join(output, fileName);
      await requireAbsent(destination);
      await copyFile(source, destination, fsConstants.COPYFILE_EXCL);
      createdFiles.push(destination);
      const copied = await requireSafeRegularFile(destination, MAX_ARTIFACT_BYTES);
      const observedContentSHA256 = await desktopComponentArchiveContentIdentity({
        archivePath: copied, entrypoint: input.entrypoint,
      });
      if (observedContentSHA256 !== input.contentSHA256) fail("component_content_identity_invalid");
      components.push({
        kind: input.kind,
        version: input.version,
        architecture: input.architecture,
        installPhase: input.installPhase,
        requiredForBootstrap: input.requiredForBootstrap,
        ...(input.onDemandTrigger === undefined ? {} : { onDemandTrigger: input.onDemandTrigger }),
        reuseContract: input.reuseContract,
        ...(input.compatibilityIdentifier === undefined
          ? {} : { compatibilityIdentifier: input.compatibilityIdentifier }),
        fileName,
        entrypoint: input.entrypoint,
        downloadURL: artifactURL(config.artifactOrigin, prefix, fileName),
        sizeBytes: (await lstat(copied)).size,
        sha256: await sha256File(copied),
        contentSHA256: input.contentSHA256,
        dependencies: input.dependencies,
      });
    }
    const payloadObject = {
      schemaVersion: 2,
      releaseVersion: config.releaseVersion,
      channel: config.channel,
      platform: "macos",
      architecture: config.architecture,
      minimumMacOS: config.minimumMacOS,
      createdAt: config.createdAt,
      expiresAt: config.expiresAt,
      components,
    };
    validateComponentPayloadV2(payloadObject, {
      expectedOrigin: config.artifactOrigin,
      expectedChannel: config.channel,
      expectedArchitecture: config.architecture,
      now: new Date(),
    });
    const payload = Buffer.from(JSON.stringify(payloadObject));
    if (payload.length > MAX_PAYLOAD_BYTES) fail("component_manifest_payload_too_large");
    const signature = sign(null, payload, signingKey);
    if (signature.length !== 64 || !verify(null, payload, createPublicKey(signingKey), signature)) {
      fail("component_manifest_signature_self_check_failed");
    }
    const envelopeData = Buffer.from(`${JSON.stringify({
      algorithm: "Ed25519",
      keyId: config.signing.keyId,
      payload: canonicalBase64URL(payload),
      signature: canonicalBase64URL(signature),
    })}\n`);
    if (envelopeData.length > MAX_ENVELOPE_BYTES) fail("component_manifest_envelope_too_large");
    const manifestPath = path.join(
      output, `Hermes-Desktop-Components-${config.releaseVersion}-${config.architecture}.manifest.json`,
    );
    await requireAbsent(manifestPath);
    await writeFile(manifestPath, envelopeData, { flag: "wx", mode: 0o644 });
    createdFiles.push(manifestPath);
    const publicKeyBase64URL = canonicalBase64URL(publicKey);
    await verifyDesktopComponentReleaseV2({
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
      components: components.map((component) => ({
        kind: component.kind,
        path: path.join(output, component.fileName),
        sizeBytes: component.sizeBytes,
        sha256: component.sha256,
        contentSHA256: component.contentSHA256,
      })),
    };
  } catch (error) {
    for (const file of createdFiles.reverse()) await rm(file, { force: true }).catch(() => {});
    if (error instanceof DesktopManagedReleaseError) throw error;
    fail("unexpected_component_packaging_failure");
  }
}

export async function verifyDesktopComponentReleaseV2({
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
      key: Buffer.concat([SPKI_ED25519_PREFIX, publicKeyBytes]), format: "der", type: "spki",
    });
    const manifest = await requireSafeRegularFile(manifestPath, MAX_ENVELOPE_BYTES);
    const envelope = parseJSON(await readFile(manifest), "component_manifest_envelope_invalid");
    requireExactKeys(
      envelope, ["algorithm", "keyId", "payload", "signature"],
      "component_manifest_envelope_fields_invalid",
    );
    if (envelope.algorithm !== "Ed25519" || envelope.keyId !== expectedKeyId) {
      fail("component_manifest_signing_identity_invalid");
    }
    const payload = decodeCanonicalBase64URL(envelope.payload, "component_manifest_payload_encoding_invalid");
    const signature = decodeCanonicalBase64URL(
      envelope.signature, "component_manifest_signature_encoding_invalid",
    );
    if (payload.length > MAX_PAYLOAD_BYTES || signature.length !== 64) {
      fail("component_manifest_envelope_invalid");
    }
    if (!verify(null, payload, key, signature)) fail("component_manifest_signature_invalid");
    const payloadObject = parseJSON(payload, "component_manifest_payload_invalid");
    validateComponentPayloadV2(payloadObject, {
      expectedOrigin, expectedChannel, expectedArchitecture, now,
    });
    const artifactRoot = await requireSafeDirectory(artifactDirectory, false);
    for (const component of payloadObject.components) {
      const local = await requireSafeRegularFile(
        path.join(artifactRoot, component.fileName), MAX_ARTIFACT_BYTES,
      );
      const info = await lstat(local);
      if (info.size !== component.sizeBytes || await sha256File(local) !== component.sha256) {
        fail("component_artifact_integrity_invalid");
      }
      await verifySafeArchive(local, component.entrypoint);
      if (await desktopComponentArchiveContentIdentity({
        archivePath: local, entrypoint: component.entrypoint,
      }) !== component.contentSHA256) fail("component_content_identity_invalid");
    }
    return payloadObject;
  } catch (error) {
    if (error instanceof DesktopManagedReleaseError) throw error;
    fail("unexpected_component_verification_failure");
  }
}

export async function loadComponentPublisherConfigV2(configPath) {
  const file = await requireSafeRegularFile(configPath, MAX_CONFIG_BYTES);
  const config = parseJSON(await readFile(file), "component_publisher_config_invalid");
  requireExactKeys(config, [
    "schemaVersion", "releaseVersion", "channel", "architecture", "minimumMacOS",
    "createdAt", "expiresAt", "artifactOrigin", "artifactPathPrefix", "signing", "components",
  ], "component_publisher_config_fields_invalid");
  if (config.schemaVersion !== 2 || !validSemanticVersion(config.releaseVersion)
      || !validIdentifier(config.channel, 32) || !ALLOWED_ARCHITECTURES.has(config.architecture)
      || !validMacOSVersion(config.minimumMacOS)) fail("component_publisher_release_identity_invalid");
  validateDates(config.createdAt, config.expiresAt, new Date());
  requireHTTPSOrigin(config.artifactOrigin);
  normalizeArtifactPathPrefix(config.artifactPathPrefix);
  requireExactKeys(
    config.signing, ["keyId", "privateKeyFile"], "component_publisher_signing_config_invalid",
  );
  if (!validIdentifier(config.signing.keyId, 64) || !path.isAbsolute(config.signing.privateKeyFile)) {
    fail("component_publisher_signing_config_invalid");
  }
  if (!Array.isArray(config.components) || config.components.length < 2
      || config.components.length > COMPONENT_KINDS_V2.size) fail("component_publisher_components_invalid");
  const normalized = config.components.map(validateComponentPublisherInputV2);
  validateComponentGraphV2(normalized);
  const byKind = new Map(normalized.map((component) => [component.kind, component]));
  if (normalized.length !== 2
      || byKind.get("node_runtime")?.installPhase !== "bootstrap"
      || byKind.get("connector")?.installPhase !== "bootstrap"
      || byKind.get("node_runtime").dependencies.length !== 0
      || byKind.get("connector").dependencies.length !== 1
      || byKind.get("connector").dependencies[0].kind !== "node_runtime"
      || byKind.get("connector").dependencies[0].contentSHA256
        !== byKind.get("node_runtime").contentSHA256) {
    fail("component_publisher_components_invalid");
  }
  return { ...config, components: normalized };
}

export async function desktopComponentArchiveContentIdentity({ archivePath, entrypoint }) {
  const archive = await requireSafeRegularFile(archivePath, MAX_ARTIFACT_BYTES);
  if (!validRelativePath(entrypoint)) fail("component_entrypoint_invalid");
  await verifySafeArchive(archive, entrypoint);
  const root = await mkdtemp(path.join(tmpdir(), "hermes-component-identity-"));
  try {
    const extracted = spawnSync("/usr/bin/tar", [
      "-xzf", archive, "-C", root, "--no-same-owner", "--no-same-permissions",
    ], { encoding: "utf8", timeout: 120_000, stdio: ["ignore", "ignore", "ignore"] });
    if (extracted.error || extracted.status !== 0) fail("component_archive_extraction_failed");
    const executable = path.join(root, ...entrypoint.split("/"));
    const executableInfo = await lstat(executable).catch(() => fail("component_entrypoint_invalid"));
    if (!executableInfo.isFile() || executableInfo.isSymbolicLink()) fail("component_entrypoint_invalid");
    await chmodExecutable(executable);
    return await hashComponentTree(root);
  } finally {
    await rm(root, { recursive: true, force: true }).catch(() => {});
  }
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

function validateComponentPublisherInputV2(component) {
  if (!isPlainObject(component)) fail("component_publisher_components_invalid");
  const expected = [
    "kind", "version", "architecture", "installPhase", "requiredForBootstrap", "reuseContract",
    "source", "entrypoint", "contentSHA256", "dependencies",
  ];
  if (Object.hasOwn(component, "onDemandTrigger")) expected.push("onDemandTrigger");
  if (Object.hasOwn(component, "compatibilityIdentifier")) expected.push("compatibilityIdentifier");
  requireExactKeys(component, expected, "component_publisher_component_fields_invalid");
  validateComponentIdentityV2(component, true);
  if (!path.isAbsolute(component.source)) fail("component_publisher_components_invalid");
  return component;
}

function validateComponentPayloadV2(payload, {
  expectedOrigin, expectedChannel, expectedArchitecture, now,
}) {
  if (!isPlainObject(payload)) fail("component_manifest_payload_invalid");
  requireExactKeys(payload, [
    "schemaVersion", "releaseVersion", "channel", "platform", "architecture",
    "minimumMacOS", "createdAt", "expiresAt", "components",
  ], "component_manifest_payload_fields_invalid");
  requireHTTPSOrigin(expectedOrigin);
  if (payload.schemaVersion !== 2 || payload.platform !== "macos"
      || payload.channel !== expectedChannel
      || (payload.architecture !== expectedArchitecture && payload.architecture !== "universal")
      || !validIdentifier(payload.channel, 32) || !ALLOWED_ARCHITECTURES.has(payload.architecture)
      || !validSemanticVersion(payload.releaseVersion) || !validMacOSVersion(payload.minimumMacOS)) {
    fail("component_manifest_release_identity_invalid");
  }
  validateDates(payload.createdAt, payload.expiresAt, now);
  if (!Array.isArray(payload.components) || payload.components.length < 2
      || payload.components.length > COMPONENT_KINDS_V2.size) fail("component_manifest_components_invalid");
  for (const component of payload.components) {
    if (!isPlainObject(component)) fail("component_manifest_components_invalid");
    const expected = [
      "kind", "version", "architecture", "installPhase", "requiredForBootstrap", "reuseContract",
      "fileName", "entrypoint", "downloadURL", "sizeBytes", "sha256", "contentSHA256", "dependencies",
    ];
    if (Object.hasOwn(component, "onDemandTrigger")) expected.push("onDemandTrigger");
    if (Object.hasOwn(component, "compatibilityIdentifier")) expected.push("compatibilityIdentifier");
    requireExactKeys(component, expected, "component_manifest_component_fields_invalid");
    validateComponentIdentityV2(component, false);
    if (component.fileName !== componentArtifactFileNameV2(
      component.kind, component.version, component.architecture,
    ) || !Number.isSafeInteger(component.sizeBytes) || component.sizeBytes < 1
        || component.sizeBytes > MAX_ARTIFACT_BYTES || !/^[0-9a-f]{64}$/.test(component.sha256)) {
      fail("component_manifest_components_invalid");
    }
    if (component.architecture !== payload.architecture && component.architecture !== "universal") {
      fail("component_manifest_components_invalid");
    }
    validateArtifactURL(component.downloadURL, expectedOrigin, component.fileName);
  }
  validateComponentGraphV2(payload.components);
}

function validateComponentIdentityV2(component, publisherInput) {
  if (!COMPONENT_KINDS_V2.has(component.kind) || !validSemanticVersion(component.version)
      || !ALLOWED_ARCHITECTURES.has(component.architecture) || !validRelativePath(component.entrypoint)
      || !/^[0-9a-f]{64}$/.test(component.contentSHA256)
      || !Array.isArray(component.dependencies)) fail("component_manifest_components_invalid");
  if (component.installPhase === "bootstrap") {
    if (component.requiredForBootstrap !== true || Object.hasOwn(component, "onDemandTrigger")) {
      fail("component_manifest_activation_invalid");
    }
  } else if (component.installPhase === "on_demand") {
    if (component.requiredForBootstrap !== false || !validIdentifier(component.onDemandTrigger, 96)) {
      fail("component_manifest_activation_invalid");
    }
  } else {
    fail("component_manifest_activation_invalid");
  }
  if (component.reuseContract === "exact_content") {
    if (Object.hasOwn(component, "compatibilityIdentifier")) fail("component_manifest_reuse_invalid");
  } else if (component.reuseContract === "verified_compatibility") {
    if (component.kind !== "browser_automation"
        || !validIdentifier(component.compatibilityIdentifier, 96)) fail("component_manifest_reuse_invalid");
  } else {
    fail("component_manifest_reuse_invalid");
  }
  const dependencyKinds = new Set();
  for (const dependency of component.dependencies) {
    requireExactKeys(
      dependency, ["kind", "contentSHA256"], "component_manifest_dependency_fields_invalid",
    );
    if (!COMPONENT_KINDS_V2.has(dependency.kind) || dependency.kind === component.kind
        || dependencyKinds.has(dependency.kind) || !/^[0-9a-f]{64}$/.test(dependency.contentSHA256)) {
      fail("component_manifest_dependencies_invalid");
    }
    dependencyKinds.add(dependency.kind);
  }
  if (publisherInput && Object.hasOwn(component, "fileName")) fail("component_publisher_components_invalid");
}

function validateComponentGraphV2(components) {
  const byKind = new Map();
  for (const component of components) {
    if (byKind.has(component.kind)) fail("component_manifest_components_invalid");
    byKind.set(component.kind, component);
  }
  if (byKind.get("connector")?.installPhase !== "bootstrap"
      || (byKind.get("node_runtime")?.installPhase !== "bootstrap"
        && byKind.get("hermes_core")?.installPhase !== "bootstrap")) {
    fail("component_manifest_components_invalid");
  }
  for (const component of components) {
    for (const dependency of component.dependencies) {
      const target = byKind.get(dependency.kind);
      if (target?.contentSHA256 !== dependency.contentSHA256
          || (component.installPhase === "bootstrap" && target.installPhase !== "bootstrap")) {
        fail("component_manifest_dependencies_invalid");
      }
    }
  }
  const visiting = new Set();
  const visited = new Set();
  const visit = (kind) => {
    if (visiting.has(kind)) fail("component_manifest_dependency_cycle");
    if (visited.has(kind)) return;
    visiting.add(kind);
    for (const dependency of byKind.get(kind).dependencies) visit(dependency.kind);
    visiting.delete(kind);
    visited.add(kind);
  };
  for (const kind of byKind.keys()) visit(kind);
}

function componentArtifactFileNameV2(kind, version, architecture) {
  return `Hermes-Component-${kind}-${version}-${architecture}.tar.gz`;
}

async function chmodExecutable(value) {
  try { await chmod(value, 0o700); }
  catch { fail("component_entrypoint_invalid"); }
}

async function hashComponentTree(root) {
  const entries = [];
  const collect = async (directory, relativeDirectory = "") => {
    let names;
    try { names = await readdir(directory); }
    catch { fail("component_content_tree_unsafe"); }
    for (const name of names) {
      const relative = relativeDirectory ? `${relativeDirectory}/${name}` : name;
      const absolute = path.join(directory, name);
      const info = await lstat(absolute).catch(() => fail("component_content_tree_unsafe"));
      if (info.isSymbolicLink() || (!info.isDirectory() && !info.isFile())) {
        fail("component_content_tree_unsafe");
      }
      entries.push({ relative, absolute, info });
      if (info.isDirectory()) await collect(absolute, relative);
    }
  };
  await collect(root);
  entries.sort((left, right) => left.relative < right.relative ? -1 : left.relative > right.relative ? 1 : 0);
  if (entries.length > MAX_ARCHIVE_ENTRIES) fail("component_content_tree_too_large");
  let fileBytes = 0;
  const digest = createHash("sha256");
  for (const entry of entries) {
    if (entry.info.isDirectory()) {
      digest.update(Buffer.from(`D\0${entry.relative}\0`));
      continue;
    }
    fileBytes += entry.info.size;
    if (!Number.isSafeInteger(fileBytes) || fileBytes > MAX_ARTIFACT_BYTES) {
      fail("component_content_tree_too_large");
    }
    const executable = (entry.info.mode & 0o111) === 0 ? "0" : "1";
    digest.update(Buffer.from(`F\0${entry.relative}\0${executable}\0${entry.info.size}\0`));
    await new Promise((resolve, reject) => {
      const stream = createReadStream(entry.absolute);
      stream.on("error", () => reject(new DesktopManagedReleaseError("component_content_tree_unsafe")));
      stream.on("data", (chunk) => digest.update(chunk));
      stream.on("end", resolve);
    });
    digest.update(Buffer.from([0]));
  }
  return digest.digest("hex");
}

function validateDates(createdAt, expiresAt, now) {
  const created = parseCanonicalDate(createdAt);
  const expires = parseCanonicalDate(expiresAt);
  if (!created || !expires || !(now instanceof Date) || Number.isNaN(now.valueOf())
      || expires <= created) {
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
