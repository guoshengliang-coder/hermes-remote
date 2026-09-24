import { createHash, createPublicKey, verify } from "node:crypto";

const PUBLIC_KEY_PREFIX = Buffer.from("302a300506032b6570032100", "hex");
const MAX_INDEX_BYTES = 4096;
const MAX_MANIFEST_BYTES = 256 * 1024;

export async function verifyDesktopReleaseKeyContinuity({
  origin, keyId, publicKey, fetchImpl = fetch,
}) {
  const parsedOrigin = new URL(origin);
  if (parsedOrigin.protocol !== "https:" || parsedOrigin.pathname !== "/"
      || parsedOrigin.search || parsedOrigin.hash || origin !== parsedOrigin.origin) {
    throw new Error("desktop_origin_invalid");
  }
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(keyId)) throw new Error("desktop_key_id_invalid");
  const publicBytes = decodeCanonical(publicKey, 32);
  const key = createPublicKey({
    key: Buffer.concat([PUBLIC_KEY_PREFIX, publicBytes]), format: "der", type: "spki",
  });
  const observed = [];
  for (const kind of ["releases", "components"]) {
    const indexURL = `${origin}/desktop/${kind}/index.json`;
    const index = JSON.parse((await readBounded(fetchImpl, indexURL, MAX_INDEX_BYTES)).toString("utf8"));
    if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(index.releaseVersion)
        || !Number.isSafeInteger(index.manifestSizeBytes)
        || index.manifestSizeBytes < 1 || index.manifestSizeBytes > MAX_MANIFEST_BYTES
        || !/^[a-f0-9]{64}$/.test(index.manifestSHA256)
        || !["arm64", "x86_64", "universal"].includes(index.architecture)
        || !/^[A-Za-z0-9._-]{1,32}$/.test(index.channel)) {
      throw new Error("desktop_index_invalid");
    }
    const manifestURL = new URL(index.manifestURL);
    if (manifestURL.origin !== origin || manifestURL.search || manifestURL.hash
        || !manifestURL.pathname.startsWith(`/desktop/${kind}/${index.releaseVersion}/`)) {
      throw new Error("desktop_manifest_url_invalid");
    }
    const manifestData = await readBounded(fetchImpl, manifestURL.href, MAX_MANIFEST_BYTES);
    if (manifestData.length !== index.manifestSizeBytes
        || createHash("sha256").update(manifestData).digest("hex") !== index.manifestSHA256) {
      throw new Error("desktop_manifest_index_mismatch");
    }
    const envelope = JSON.parse(manifestData.toString("utf8"));
    if (envelope.algorithm !== "Ed25519" || envelope.keyId !== keyId) {
      throw new Error("desktop_signing_key_not_current");
    }
    const payload = decodeCanonical(envelope.payload, 128 * 1024);
    const signature = decodeCanonical(envelope.signature, 64);
    if (signature.length !== 64 || !verify(null, payload, key, signature)) {
      throw new Error("desktop_manifest_signature_invalid");
    }
    const signed = JSON.parse(payload.toString("utf8"));
    if (signed.releaseVersion !== index.releaseVersion || signed.channel !== index.channel
        || signed.architecture !== index.architecture) {
      throw new Error("desktop_signed_index_identity_mismatch");
    }
    observed.push({ kind, releaseVersion: index.releaseVersion, manifestSHA256: index.manifestSHA256 });
  }
  if (observed[0].releaseVersion !== observed[1].releaseVersion) {
    throw new Error("desktop_indexes_out_of_sync");
  }
  return observed;
}

async function readBounded(fetchImpl, url, limit) {
  const response = await fetchImpl(url, { redirect: "error", cache: "no-store" });
  if (!response.ok || !response.body) throw new Error("desktop_fetch_failed");
  const chunks = [];
  let length = 0;
  for await (const chunk of response.body) {
    length += chunk.length;
    if (length > limit) throw new Error("desktop_response_too_large");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function decodeCanonical(value, maxLength) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error("desktop_base64url_invalid");
  }
  const decoded = Buffer.from(value, "base64url");
  if (decoded.length > maxLength || decoded.toString("base64url") !== value) {
    throw new Error("desktop_base64url_invalid");
  }
  return decoded;
}
