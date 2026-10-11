import { constants, closeSync, fstatSync, fsyncSync, lstatSync, openSync, readFileSync,
  realpathSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { createHash, randomUUID } from "node:crypto";
import { isAbsolute, join, normalize } from "node:path";

export const MAINTENANCE_CODE = "HR-HERMESUPDATE-005";
export const MAINTENANCE_ZH = "这台 Mac 的 Hermes 正在维护，暂不接受新操作。请在 Mac 上查看进度或恢复操作。";
export const MAINTENANCE_EN = "Hermes on this Mac is undergoing maintenance and cannot accept new operations. Check progress or recovery on the Mac.";

export function maintenanceError(correlationId?: string) {
  return { code: MAINTENANCE_CODE, message: MAINTENANCE_ZH, messageEn: MAINTENANCE_EN,
    retryable: false, recoveryAction: "details", ...(correlationId ? { correlationId } : {}) };
}

interface Request { schema: 1; mode: "pause" | "release"; runID: string; sourceIdentity: string }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Local owner-only mailbox. A pause never expires or clears merely because its file disappeared.
 * Existing operations keep their admission until completion, including connecting/closing sockets.
 * A zero count proves only Connector admission is drained, never that Hermes/cron/CLI is idle. */
export class HermesMaintenanceAdmission {
  readonly instanceID = randomUUID();
  private active = 0;
  private request?: Request;
  private digest?: string;
  private fault = false;
  private paused = false;

  constructor(private readonly directory?: string) { this.refresh(); }

  acquire(): (() => void) | undefined {
    this.refresh();
    if (this.paused || this.fault) return undefined;
    this.active++;
    try { this.publish(); } catch { this.active--; this.fault = true; this.paused = true; return undefined; }
    let finished = false;
    return () => {
      if (finished) return;
      finished = true; this.active--;
      this.refresh();
    };
  }

  get blocked(): boolean { this.refresh(); return this.paused || this.fault; }

  refresh(): void {
    if (!this.directory) return;
    try {
      this.validateDirectory();
      let bytes: Buffer;
      try { bytes = this.privateRead(join(this.directory, "request.json")); }
      catch (error) {
        if (isMissing(error) && !this.request && !this.fault) { this.publish(); return; }
        throw error;
      }
      const request: unknown = JSON.parse(bytes.toString("utf8"));
      // All accepted keys and values are ASCII and require no JSON escapes. Reject duplicate or
      // escaped keys instead of letting JSON.parse silently select the last occurrence.
      if (!validRequest(request) || bytes.includes(92)
        || [...bytes.toString("utf8").matchAll(/"(?:schema|mode|runID|sourceIdentity)"\s*:/g)].length !== 4) throw new Error("invalid_request");
      const digest = createHash("sha256").update(bytes).digest("hex");
      if (this.request && (request.runID !== this.request.runID || request.sourceIdentity !== this.request.sourceIdentity)) {
        // A later run is admitted only after this process observed an explicit release.
        if (this.paused || this.fault || request.mode !== "pause") throw new Error("custody_changed");
      }
      if (request.mode === "release") {
        if (this.fault || (this.request && request.runID !== this.request.runID)) throw new Error("unsafe_release");
        this.paused = false;
      } else this.paused = true;
      this.request = request; this.digest = digest;
      this.publish();
    } catch {
      this.fault = true; this.paused = true;
      // No success ACK for unsafe data or an unsafe mailbox. Primary responses contain only the
      // registered code; neither request bytes nor filesystem exceptions cross the log boundary.
    }
  }

  private validateDirectory(): void {
    const path = this.directory!;
    const info = lstatSync(path);
    if (!isAbsolute(path) || normalize(path) !== path || realpathSync(path) !== path || path === "/"
      || !info.isDirectory() || info.uid !== process.getuid?.() || (info.mode & 0o077) !== 0) throw new Error("unsafe_directory");
  }

  private privateRead(path: string): Buffer {
    const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const info = fstatSync(fd);
      if (!info.isFile() || info.uid !== process.getuid?.() || info.nlink !== 1 || (info.mode & 0o077) !== 0
        || info.size < 1 || info.size > 4096) throw new Error("unsafe_record");
      const bytes = readFileSync(fd);
      if (bytes.length > 4096) throw new Error("unsafe_record");
      return bytes;
    } finally { closeSync(fd); }
  }

  private publish(): void {
    if (!this.directory || this.fault) return;
    this.validateDirectory();
    const destination = join(this.directory, "ack.json");
    try { this.privateRead(destination); } catch (error) { if (!isMissing(error)) throw error; }
    const temporary = join(this.directory, `.${randomUUID()}.tmp`);
    const fd = openSync(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try {
      writeFileSync(fd, JSON.stringify({ schema: 1, pid: process.pid, instanceID: this.instanceID,
        runID: this.request?.runID ?? null, sourceIdentity: this.request?.sourceIdentity ?? null,
        requestSHA256: this.digest ?? null, paused: this.paused, activeOperations: this.active,
        observedAt: Date.now() }));
      fsyncSync(fd); renameSync(temporary, destination);
      const parent = openSync(this.directory, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW);
      try { fsyncSync(parent); } finally { closeSync(parent); }
    } finally { closeSync(fd); try { unlinkSync(temporary); } catch (error) { if (!isMissing(error)) throw error; } }
  }
}

function validRequest(value: unknown): value is Request {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  return Object.keys(v).sort().join(",") === "mode,runID,schema,sourceIdentity" && v.schema === 1
    && (v.mode === "pause" || v.mode === "release") && typeof v.runID === "string" && uuid.test(v.runID)
    && typeof v.sourceIdentity === "string" && /^[0-9a-f]{64}$/.test(v.sourceIdentity);
}

function isMissing(error: unknown): boolean { return !!error && typeof error === "object" && "code" in error && error.code === "ENOENT"; }

/** Connector-owned JSON-RPC refusal, in the implementation-defined reserved range. */
export const MAINTENANCE_RPC_CODE = -32002;
const drainingMethods = new Set(["session.interrupt", "approval.respond", "clarify.respond", "clarify.lock", "request.answer"]);

/** Pause new work on an already admitted channel without losing server events or answers that
 * finish an existing turn. Unreadable frames/notifications are dropped; the connection remains
 * counted as busy until actually closed, so they can never authorize an upgrade. */
export function maintenanceFrameDecision(bytes: Buffer, binary: boolean): { forward: boolean; replacement: string | null } {
  const refuse = { forward: false, replacement: null };
  if (binary) return refuse;
  let value: unknown;
  try { value = JSON.parse(bytes.toString("utf8")); } catch { return refuse; }
  if (!value || typeof value !== "object" || Array.isArray(value)) return refuse;
  const v = value as Record<string, unknown>;
  const keys = topLevelKeys(bytes.toString("utf8"));
  if (v.jsonrpc !== "2.0" || new Set(keys).size !== keys.length) return refuse;
  if (typeof v.method === "string" && drainingMethods.has(v.method)) return { forward: true, replacement: null };
  const id = v.id;
  if (!(typeof id === "string" || (typeof id === "number" && Number.isSafeInteger(id)))) return refuse;
  if (!Object.hasOwn(v, "method") && (Object.hasOwn(v, "result") || Object.hasOwn(v, "error"))) {
    return { forward: true, replacement: null };
  }
  return { forward: false, replacement: JSON.stringify({ jsonrpc: "2.0", id,
    error: { code: MAINTENANCE_RPC_CODE, message: MAINTENANCE_CODE, data: maintenanceError() } }) };
}

/** JSON has already been parsed successfully. Count only envelope keys, respecting nested data
 * and escaped strings; request.answer legitimately carries another id inside params. */
function topLevelKeys(text: string): string[] {
  const keys: string[] = [];
  let depth = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {
      const start = i++;
      for (; i < text.length; i++) {
        if (text[i] === "\\") i++;
        else if (text[i] === '"') break;
      }
      if (depth === 1 && /^\s*:/.test(text.slice(i + 1))) keys.push(JSON.parse(text.slice(start, i + 1)) as string);
    } else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") depth--;
  }
  return keys;
}
