import { GatewayHttpError, GatewayNetworkError } from "../api/gateway";
import { HermesSocketError } from "../hermes/client";
import {
  appError,
  fromHttp,
  fromNetworkFailure,
  fromSocketError,
  type AppError,
  type HttpContext,
  type RpcContext,
} from "../errors";

// Any thrown value → the structured user-visible error (docs/ERROR_HANDLING.md).

export function toAppError(error: unknown, http: HttpContext = "generic", rpc: RpcContext = "generic"): AppError {
  if (isAppError(error)) return error;
  if (error instanceof GatewayHttpError) return fromHttp(error.status, error.body, http);
  if (error instanceof GatewayNetworkError) return fromNetworkFailure(error.cause);
  if (error instanceof HermesSocketError) return fromSocketError(error, rpc);
  const details = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  return appError("HR-UNKNOWN-001", details);
}

export function isAppError(value: unknown): value is AppError {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as AppError).code === "string" &&
    /^HR-/.test((value as AppError).code) &&
    typeof (value as AppError).zh === "string"
  );
}

/** Thrown to carry an already-structured error through a promise chain. */
export class AppErrorException extends Error {
  constructor(readonly appError: AppError) {
    super(appError.code);
    this.name = "AppErrorException";
  }
}

export function unwrap(error: unknown, http: HttpContext = "generic", rpc: RpcContext = "generic"): AppError {
  return error instanceof AppErrorException ? error.appError : toAppError(error, http, rpc);
}
