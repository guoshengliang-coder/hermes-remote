import { createHash } from "node:crypto";
import { compatibilityCommit, compatibilityErrors, compatibilityVersion } from "./hermes-compatibility-model.js";
import type { OfficialCandidate } from "./hermes-compatibility-store.js";

const ROOT = "https://api.github.com/repos/NousResearch/hermes-agent";
/** Fixed origin, bounded payload/time, no credentials, no redirects or administrator-provided URL. */
export async function discoverOfficialHermes(fetcher: typeof fetch = fetch): Promise<OfficialCandidate> {
  const signal = AbortSignal.timeout(30_000);
  async function json(path: string): Promise<Record<string, unknown>> {
    const response = await fetcher(ROOT + path, { redirect: "error", signal,
      headers: { accept: "application/vnd.github+json", "user-agent": "hermes-go-compatibility" } });
    if (!response.ok || !response.body) throw compatibilityErrors.unavailable();
    const reader = response.body.getReader(), chunks: Uint8Array[] = [];
    let size = 0;
    try {
      while (true) {
        const next = await reader.read(); if (next.done) break;
        size += next.value.byteLength;
        if (size > 1024 * 1024) throw compatibilityErrors.unavailable();
        chunks.push(next.value);
      }
    } finally { await reader.cancel().catch(() => {}); }
    const value: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!value || typeof value !== "object" || Array.isArray(value)) throw compatibilityErrors.unavailable();
    return value as Record<string, unknown>;
  }
  function ref(value: unknown): { type: "tag" | "commit"; sha: string } {
    if (!value || typeof value !== "object") throw compatibilityErrors.unavailable();
    const v = value as Record<string, unknown>;
    if (v.type !== "tag" && v.type !== "commit") throw compatibilityErrors.unavailable();
    return { type: v.type, sha: compatibilityCommit(v.sha) };
  }
  try {
    const release = await json("/releases/latest");
    if (release.draft !== false || release.prerelease !== false || typeof release.tag_name !== "string"
        || !release.tag_name.startsWith("v")) throw compatibilityErrors.unavailable();
    const version = compatibilityVersion(release.tag_name.slice(1)), tag = `v${version}`;
    let object = ref((await json(`/git/ref/tags/${tag}`)).object);
    const tagObject = object.sha, seen = new Set<string>();
    for (let depth = 0; object.type === "tag"; depth++) {
      if (depth >= 4 || seen.has(object.sha)) throw compatibilityErrors.unavailable();
      seen.add(object.sha);
      object = ref((await json(`/git/tags/${object.sha}`)).object);
    }
    const targetCommit = object.sha;
    const installer = await json(`/contents/scripts/install.sh?ref=${targetCommit}`);
    if (installer.type !== "file" || installer.encoding !== "base64" || typeof installer.content !== "string"
        || !Number.isSafeInteger(installer.size) || (installer.size as number) < 1 || (installer.size as number) > 512 * 1024) throw compatibilityErrors.unavailable();
    const encoded = installer.content.replace(/\n/g, ""), bytes = Buffer.from(encoded,"base64");
    if (bytes.toString("base64") !== encoded || bytes.length !== installer.size) throw compatibilityErrors.unavailable();
    return { version,tag,tagObject,targetCommit,installerSha256:createHash("sha256").update(bytes).digest("hex") };
  } catch { throw compatibilityErrors.unavailable(); }
}
