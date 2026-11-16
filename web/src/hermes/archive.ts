import { GatewayHttpError } from "../api/gateway";
import { appError, type AppError } from "../errors";
import { HermesSocketError } from "./client";
import type { JsonObject } from "./types";

export type ArchiveMode = "legacy" | "authority" | "unknown";

/** Only a completed handshake classifies a backend; never infer it from a version or a 404. */
export function archiveMode(payload: unknown): ArchiveMode {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return "unknown";
  const value = (payload as Record<string, unknown>).session_authority;
  return value === true ? "authority" : value === undefined || value === false ? "legacy" : "unknown";
}

export interface ArchiveOperations {
  mode: ArchiveMode;
  sessionId: string;
  archived: boolean;
  rpc: () => Promise<JsonObject>;
  rest: () => Promise<unknown>;
  /** An uncached read of the original device/profile; absence is not proof of success. */
  read: () => Promise<boolean | null>;
}

export function archiveFailure(error: unknown): AppError {
  if (error instanceof GatewayHttpError && error.status >= 400 && error.status < 500 && error.status !== 408 ||
      error instanceof HermesSocketError && error.kind === "rpc" && error.code !== undefined &&
      (error.code >= 4000 && error.code < 5000 || [-32600, -32601, -32602].includes(error.code))) {
    return appError("HR-SESS-026", error instanceof Error ? error.message : String(error));
  }
  return appError("HR-SESS-025", error instanceof Error ? error.message : String(error));
}

/** Never replay an ambiguous write. Only -32601 proves the legacy RPC did not execute. */
export async function archiveWithBackend(ops: ArchiveOperations): Promise<void> {
  if (ops.mode === "unknown") throw appError("HR-COMPAT-004");
  try {
    if (ops.mode === "authority") { await ops.rest(); return; }
    let result: JsonObject;
    try { result = await ops.rpc(); }
    catch (error) {
      if (error instanceof HermesSocketError && error.kind === "rpc" && error.code === -32601) {
        await ops.rest(); return;
      }
      throw error;
    }
    if (result.archived !== ops.archived || result.session_key !== ops.sessionId) {
      throw new Error("archive acknowledgement did not identify the requested state");
    }
  } catch (error) {
    const failure = archiveFailure(error);
    if (failure.code === "HR-SESS-026") throw failure;
    try { if (await ops.read() === ops.archived) return; } catch { /* keep the unknown outcome */ }
    throw failure;
  }
}

export function sessionMutationFailure(error: unknown): AppError {
  const archived = archiveFailure(error);
  return appError(archived.code === "HR-SESS-026" ? "HR-SESS-028" : "HR-SESS-027",
    error instanceof Error ? error.message : String(error));
}
