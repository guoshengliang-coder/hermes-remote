import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const dist = resolve("dist");
const manifest = JSON.parse(await readFile(resolve(dist, "release-manifest.json"), "utf8"));
if (manifest.manifestVersion !== 2 || typeof manifest.serverVersion !== "string") {
  throw new Error("Gateway release manifest is invalid");
}
if (process.env.REQUIRE_RELEASE_CLEAN === "1" && manifest.sourceDirty !== false) {
  throw new Error("Gateway release was built from a dirty or unverifiable source tree");
}
for (const [relative, expected] of Object.entries(manifest.files ?? {})) {
  const actual = createHash("sha256").update(await readFile(resolve(dist, relative))).digest("hex");
  if (actual !== expected) throw new Error(`Gateway release file failed verification: ${relative}`);
}
console.log(`GATEWAY_RELEASE_OK version=${manifest.serverVersion} commit=${manifest.sourceCommit} dirty=${manifest.sourceDirty} files=${Object.keys(manifest.files).length}`);
