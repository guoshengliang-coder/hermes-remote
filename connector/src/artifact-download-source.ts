import { createHash, randomBytes } from "node:crypto";
import type { Stats } from "node:fs";
import { Agent as HttpAgent, request as httpRequest, type ClientRequest } from "node:http";
import { Agent as HttpsAgent, request as httpsRequest } from "node:https";
import { once } from "node:events";
import { setTimeout as delay } from "node:timers/promises";
import {
  ARTIFACT_DEFAULT_MAX_BYTES, ARTIFACT_ID, ARTIFACT_QUICK_OPEN_BYTES,
  ARTIFACT_RETURN_PATH, TRANSFER_ID, artifactFailure, artifactRange,
  ArtifactRangeError, type ArtifactDownloadDescriptor, type ArtifactErrorCode,
  type TunnelHttpRequest,
} from "@hermes-remote/protocol";
import { ArtifactFileError, openArtifactFile } from "./artifact-file.js";
import { TransferBudget } from "./transfer-budget.js";
import type { LinkState } from "@hermes-remote/protocol";

interface Reference { path: string; etag: string; expires: number }
export function artifactVersion(stat: Stats): string {
  return `"${createHash("sha256").update(JSON.stringify([
    stat.dev, stat.ino, stat.size, stat.mtimeMs, stat.ctimeMs,
  ])).digest("hex")}"`;
}

/** No listening port. Each response dials the configured Gateway over a separate binary HTTP stream. */
export class ArtifactDownloadSource {
  private readonly references = new Map<string, Reference>();
  private readonly origin: string;
  readonly enabled: boolean;
  private readonly active = new Map<string, string>();
  private readonly httpAgent = new HttpAgent({ keepAlive: true, maxSockets: 4, maxFreeSockets: 4, timeout: 30_000 });
  private readonly httpsAgent = new HttpsAgent({ keepAlive: true, maxSockets: 4, maxFreeSockets: 4, timeout: 30_000 });
  private nextSendAt = 0;
  readonly budget: TransferBudget;
  setLinkState(lane: string, state: LinkState): void { this.budget.state(lane, state); }

  constructor(
    private readonly filesRoot: string,
    gatewayUrl: string,
    private readonly contentType: (path: string) => string,
    readonly maxBytes = ARTIFACT_DEFAULT_MAX_BYTES,
    private readonly bytesPerSecond = 4 * 1024 * 1024,
    private readonly now: () => number = Date.now,
  ) {
    this.budget = new TransferBudget(bytesPerSecond);
    const url = new URL(gatewayUrl);
    if (url.username || url.password || !["ws:", "wss:"].includes(url.protocol)) throw new Error("invalid_gateway_origin");
    url.protocol = url.protocol === "wss:" ? "https:" : "http:";
    this.enabled = url.protocol === "https:" || ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
    this.origin = url.origin;
  }

  clear(): void {
    this.references.clear();
    this.httpAgent.destroy(); this.httpsAgent.destroy();
  }

  async prepare(path: string): Promise<ArtifactDownloadDescriptor> {
    if (!this.enabled) throw new ArtifactFileError(501, "HR-FILE-012");
    const opened = await openArtifactFile(path, { filesRoot: this.filesRoot });
    try {
      if (!Number.isSafeInteger(opened.metadata.size) || opened.metadata.size > this.maxBytes) {
        throw new ArtifactFileError(413, "HR-FILE-004");
      }
      for (const [key, entry] of this.references) if (entry.expires <= this.now()) this.references.delete(key);
      if (this.references.size >= 4096) this.references.delete(this.references.keys().next().value!);
      const artifactId = randomBytes(32).toString("hex");
      const etag = artifactVersion(opened.metadata);
      this.references.set(artifactId, { path, etag, expires: this.now() + 10 * 60_000 });
      return { artifactId, etag, sizeBytes: opened.metadata.size,
        contentType: this.contentType(opened.canonicalPath), maxBytes: this.maxBytes,
        quickOpenBytes: ARTIFACT_QUICK_OPEN_BYTES, maxConcurrentRanges: 4, maxRangeBytes: 1024 * 1024 };
    } finally { await opened.handle.close(); }
  }

  async respond(request: TunnelHttpRequest, signal: AbortSignal): Promise<void> {
    const id = request.path.substring(request.path.lastIndexOf("/") + 1);
    const transferId = request.headers["x-hermes-file-return"];
    const ticket = request.headers["x-hermes-file-ticket"];
    if (!ARTIFACT_ID.test(id) || !TRANSFER_ID.test(transferId ?? "")
        || !/^[a-f0-9]{64}$/.test(ticket ?? "")) return;
    const sendFailure = (status: number, code: ArtifactErrorCode, headers: Record<string, string> = {}) => {
      const body = Buffer.from(JSON.stringify(artifactFailure(code)));
      return this.returnBody(transferId!, ticket!, status,
        { "content-type": "application/json", "content-length": String(body.length), ...headers },
        (async function* () { yield body; })(), signal);
    };
    // Several ranges of ONE opaque reference may overlap. Separate files/phones cannot
    // multiply the Mac's transfer budget or displace an in-progress file.
    if (this.active.size >= this.budget.concurrency || [...this.active.values()].some((value) => value !== id)) {
      await sendFailure(503, "HR-FILE-013"); return;
    }
    this.active.set(request.id, id);
    const release = () => { this.active.delete(request.id); };
    let streaming = false;
    try {
      const reference = this.references.get(id);
      if (!reference || reference.expires <= this.now()) { await sendFailure(410, "HR-FILE-015"); return; }
      const opened = await openArtifactFile(reference.path, { filesRoot: this.filesRoot });
      try {
        const { metadata, handle } = opened;
        if (metadata.size > this.maxBytes) { await sendFailure(413, "HR-FILE-004"); return; }
        if (artifactVersion(metadata) !== reference.etag || request.headers["if-match"] !== reference.etag) {
          await sendFailure(412, "HR-FILE-011"); return;
        }
        let range: { start: number; end: number };
        try { range = artifactRange(request.headers.range, metadata.size); }
        catch (error) {
          if (!(error instanceof ArtifactRangeError)) throw error;
          await sendFailure(416, "HR-FILE-006", { "content-range": `bytes */${metadata.size}` }); return;
        }
        const length = Math.max(0, range.end - range.start + 1);
        const headers: Record<string, string> = {
          "content-type": this.contentType(opened.canonicalPath), "content-length": String(length),
          etag: reference.etag, "accept-ranges": "bytes", "cache-control": "private, no-store",
        };
        if (request.headers.range) headers["content-range"] = `bytes ${range.start}-${range.end}/${metadata.size}`;
        const pace = this.bytesPerSecond;
        const budget = this.budget;
        const reserve = (bytes: number) => {
          const now = performance.now();
          this.nextSendAt = Math.max(now, this.nextSendAt) + bytes / pace * 1000;
          return this.nextSendAt - now;
        };
        async function* chunks() {
          for (let offset = range.start; offset <= range.end;) {
            signal.throwIfAborted();
            const buffer = Buffer.allocUnsafe(Math.min(64 * 1024, Math.max(1, Math.floor(pace / 4)), range.end - offset + 1));
            const { bytesRead } = await handle.read(buffer, 0, buffer.length, offset);
            if (!bytesRead) throw new Error("source_truncated");
            offset += bytesRead;
            // Hold the final block until the source generation is checked again. An aborted
            // response cannot be mistaken for a complete, correctly-sized file.
            if (offset > range.end && artifactVersion(await handle.stat()) !== reference!.etag) {
              throw new Error("source_changed");
            }
            // All concurrent ranges share this reservation clock. Four requests still
            // send at the configured aggregate rate, including across range boundaries.
            if (budget.enabled) await budget.take(bytesRead, signal);
            else { const wait = reserve(bytesRead); if (wait > 0) await delay(wait, undefined, { signal }); }
            yield buffer.subarray(0, bytesRead);
          }
        }
        streaming = true;
        await this.returnBody(transferId!, ticket!, request.headers.range ? 206 : 200, headers, chunks(), signal, release);
      } finally { await opened.handle.close(); }
    } catch (error) {
      if (signal.aborted || streaming) return;
      const status = error instanceof ArtifactFileError ? error.status
        : ["ENOENT", "ENOTDIR"].includes((error as NodeJS.ErrnoException).code ?? "") ? 404
        : ["EACCES", "EPERM"].includes((error as NodeJS.ErrnoException).code ?? "") ? 403 : 500;
      await sendFailure(status, status === 403 ? "HR-FILE-003" : status === 404 || status === 400 ? "HR-FILE-005" : "HR-FILE-006");
    } finally { release(); }
  }

  private async returnBody(
    id: string, ticket: string, status: number, headers: Record<string, string>,
    body: AsyncIterable<Buffer>, signal: AbortSignal, sent?: () => void,
  ): Promise<void> {
    signal.throwIfAborted();
    const url = new URL(`${ARTIFACT_RETURN_PATH}${id}`, this.origin);
    let out: ClientRequest;
    const finished = new Promise<void>((resolve, reject) => {
      out = (url.protocol === "https:" ? httpsRequest : httpRequest)(url, {
        method: "PUT", agent: url.protocol === "https:" ? this.httpsAgent : this.httpAgent, signal,
        headers: { ...headers, authorization: `Bearer ${ticket}`, "x-hermes-file-status": String(status) },
      }, (response) => {
        response.resume();
        response.on("end", () => response.statusCode === 204 ? resolve() : reject(new Error("file_return_rejected")));
        response.on("error", reject);
      });
      out.on("error", reject);
      out.setTimeout(this.budget.enabled ? 75_000 : 30_000, () => out.destroy(new Error("file_channel_idle")));
    });
    // Install a handler before writing: a fast refusal must not become an unhandled rejection.
    void finished.catch(() => undefined);
    try {
      for await (const chunk of body) {
        if (out!.destroyed) throw new Error("file_channel_closed");
        if (!out!.write(chunk)) await once(out!, "drain", { signal });
      }
      out!.end();
      sent?.();
      await finished;
    } catch (error) {
      out!.destroy();
      throw error;
    }
  }
}
