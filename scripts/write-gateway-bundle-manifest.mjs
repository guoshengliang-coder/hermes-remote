import { open, unlink } from "node:fs/promises";
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
] = process.argv.slice(2);

if (!manifestPath || process.argv.length !== 11) {
  throw new Error("usage: write-gateway-bundle-manifest <manifest> <archive> <sha256> <image> <id> <arch> <version> <commit> <created-at>");
}

const manifest = validateManifest({
  schemaVersion: 1,
  kind: "hermes-go-gateway-oci",
  serverVersion,
  sourceCommit,
  imageReference,
  imageId,
  architecture,
  archiveFile,
  archiveSha256,
  createdAt,
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
