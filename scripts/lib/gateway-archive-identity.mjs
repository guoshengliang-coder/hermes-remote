import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const IMAGE_REFERENCE = /^hermes-remote-gateway:[A-Za-z0-9._-]+$/;

export function inspectGatewayArchiveIdentity(archivePath, expectedImageReference, expectedConfigImageId, {
  extractEntry = defaultExtractEntry,
} = {}) {
  if (typeof archivePath !== "string" || !archivePath.startsWith("/")
      || !IMAGE_REFERENCE.test(expectedImageReference)
      || !DIGEST.test(expectedConfigImageId)) {
    throw new Error("gateway_archive_identity_arguments_invalid");
  }

  const dockerManifest = parseJson(extractEntry(archivePath, "manifest.json"), "docker_manifest");
  if (!Array.isArray(dockerManifest) || dockerManifest.length !== 1) {
    throw new Error("docker_manifest_entries_invalid");
  }
  const dockerEntry = dockerManifest[0];
  const expectedConfigPath = digestPath(expectedConfigImageId);
  if (!dockerEntry || dockerEntry.Config !== expectedConfigPath
      || !Array.isArray(dockerEntry.RepoTags)
      || dockerEntry.RepoTags.length !== 1
      || dockerEntry.RepoTags[0] !== expectedImageReference) {
    throw new Error("docker_manifest_identity_mismatch");
  }

  const index = parseJson(extractEntry(archivePath, "index.json"), "oci_index");
  if (index?.schemaVersion !== 2
      || index.mediaType !== "application/vnd.oci.image.index.v1+json"
      || !Array.isArray(index.manifests)
      || index.manifests.length !== 1) {
    throw new Error("oci_index_invalid");
  }
  const descriptor = index.manifests[0];
  if (descriptor?.mediaType !== "application/vnd.oci.image.manifest.v1+json"
      || !DIGEST.test(descriptor.digest)
      || !Number.isSafeInteger(descriptor.size)
      || descriptor.size < 2
      || descriptor.size > 1024 * 1024) {
    throw new Error("oci_descriptor_invalid");
  }

  const ociManifestBytes = extractEntry(archivePath, digestPath(descriptor.digest));
  if (sha256(ociManifestBytes) !== descriptor.digest.slice("sha256:".length)
      || ociManifestBytes.length !== descriptor.size) {
    throw new Error("oci_descriptor_content_mismatch");
  }
  const ociManifest = parseJson(ociManifestBytes, "oci_manifest");
  if (ociManifest?.schemaVersion !== 2
      || ociManifest.mediaType !== "application/vnd.oci.image.manifest.v1+json"
      || ociManifest.config?.mediaType !== "application/vnd.oci.image.config.v1+json"
      || ociManifest.config.digest !== expectedConfigImageId
      || !Number.isSafeInteger(ociManifest.config.size)
      || ociManifest.config.size < 2
      || ociManifest.config.size > 1024 * 1024
      || !Array.isArray(ociManifest.layers)
      || ociManifest.layers.length === 0
      || ociManifest.layers.some((layer) => !DIGEST.test(layer?.digest))) {
    throw new Error("oci_manifest_identity_mismatch");
  }

  const configBytes = extractEntry(archivePath, expectedConfigPath);
  if (sha256(configBytes) !== expectedConfigImageId.slice("sha256:".length)
      || configBytes.length !== ociManifest.config.size) {
    throw new Error("oci_config_content_mismatch");
  }
  const config = parseJson(configBytes, "oci_config");
  if (config?.architecture !== "amd64" || config.os !== "linux") {
    throw new Error("oci_config_platform_invalid");
  }

  return {
    configImageId: expectedConfigImageId,
    containerdImageId: descriptor.digest,
    architecture: "amd64",
  };
}

function defaultExtractEntry(archivePath, entryPath) {
  const result = spawnSync("tar", ["-xOf", archivePath, entryPath], {
    encoding: null,
    maxBuffer: 2 * 1024 * 1024,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.status !== 0 || !Buffer.isBuffer(result.stdout) || result.stdout.length < 2) {
    throw new Error(`gateway_archive_entry_invalid=${entryPath}`);
  }
  return result.stdout;
}

function digestPath(digest) {
  return `blobs/sha256/${digest.slice("sha256:".length)}`;
}

function parseJson(value, label) {
  try {
    return JSON.parse(Buffer.from(value).toString("utf8"));
  } catch {
    throw new Error(`${label}_json_invalid`);
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
