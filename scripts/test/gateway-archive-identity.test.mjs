import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { inspectGatewayArchiveIdentity } from "../lib/gateway-archive-identity.mjs";

test("Gateway archive identity binds the Docker config ID to the OCI descriptor ID", () => {
  const fixture = archiveFixture();
  const identity = inspectGatewayArchiveIdentity(
    "/safe/gateway.tar",
    fixture.imageReference,
    fixture.configImageId,
    { extractEntry: fixture.extractEntry },
  );
  assert.deepEqual(identity, {
    configImageId: fixture.configImageId,
    containerdImageId: fixture.containerdImageId,
    architecture: "amd64",
  });
});

test("Gateway archive identity rejects an unbound or altered OCI descriptor", () => {
  const fixture = archiveFixture();
  assert.throws(() => inspectGatewayArchiveIdentity(
    "/safe/gateway.tar",
    fixture.imageReference,
    `sha256:${"f".repeat(64)}`,
    { extractEntry: fixture.extractEntry },
  ), /docker_manifest_identity_mismatch/);

  const tampered = new Map(fixture.entries);
  tampered.set(digestPath(fixture.containerdImageId), Buffer.from("{}"));
  assert.throws(() => inspectGatewayArchiveIdentity(
    "/safe/gateway.tar",
    fixture.imageReference,
    fixture.configImageId,
    { extractEntry: (_archive, entry) => required(tampered, entry) },
  ), /oci_descriptor_content_mismatch/);
});

function archiveFixture() {
  const imageReference = "hermes-remote-gateway:0.4.0-abcdef123456";
  const configBytes = jsonBytes({ architecture: "amd64", os: "linux", config: {}, rootfs: { type: "layers", diff_ids: [] } });
  const configImageId = digest(configBytes);
  const ociManifestBytes = jsonBytes({
    schemaVersion: 2,
    mediaType: "application/vnd.oci.image.manifest.v1+json",
    config: {
      mediaType: "application/vnd.oci.image.config.v1+json",
      digest: configImageId,
      size: configBytes.length,
    },
    layers: [{ mediaType: "application/vnd.oci.image.layer.v1.tar", digest: `sha256:${"a".repeat(64)}`, size: 512 }],
  });
  const containerdImageId = digest(ociManifestBytes);
  const entries = new Map([
    ["manifest.json", jsonBytes([{ Config: digestPath(configImageId), RepoTags: [imageReference], Layers: [] }])],
    ["index.json", jsonBytes({
      schemaVersion: 2,
      mediaType: "application/vnd.oci.image.index.v1+json",
      manifests: [{
        mediaType: "application/vnd.oci.image.manifest.v1+json",
        digest: containerdImageId,
        size: ociManifestBytes.length,
      }],
    })],
    [digestPath(containerdImageId), ociManifestBytes],
    [digestPath(configImageId), configBytes],
  ]);
  return {
    imageReference,
    configImageId,
    containerdImageId,
    entries,
    extractEntry: (_archive, entry) => required(entries, entry),
  };
}

function required(entries, entry) {
  const value = entries.get(entry);
  if (!value) throw new Error(`missing=${entry}`);
  return value;
}

function jsonBytes(value) {
  return Buffer.from(JSON.stringify(value));
}

function digest(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function digestPath(value) {
  return `blobs/sha256/${value.slice("sha256:".length)}`;
}
