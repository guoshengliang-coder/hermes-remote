import { readFile } from "node:fs/promises";

const FILE_NAME = /^[A-Za-z0-9._-]+$/;

// The publisher verifies the signed envelope before calling this helper. The file list must come
// from its signed payload, not from unsigned fields on the envelope.
export function desktopPublishFiles(envelope) {
  if (typeof envelope?.payload !== "string"
      || !/^[A-Za-z0-9_-]+$/.test(envelope.payload)) {
    throw new Error("invalid desktop release payload");
  }
  const payload = JSON.parse(Buffer.from(envelope.payload, "base64url").toString("utf8"));
  const entries = payload.artifacts ?? payload.components;
  if (!Array.isArray(entries) || entries.length === 0) {
    throw new Error("desktop release has no artifacts");
  }
  const names = entries.map((entry) => entry?.fileName);
  if (names.some((name) => typeof name !== "string" || !FILE_NAME.test(name))
      || new Set(names).size !== names.length) {
    throw new Error("invalid desktop release artifact name");
  }
  return names;
}

export async function desktopPublishFilesFromFile(path) {
  return desktopPublishFiles(JSON.parse(await readFile(path, "utf8")));
}
