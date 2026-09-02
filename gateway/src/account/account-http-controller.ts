import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { isIP } from "node:net";
import { AccountService } from "./account-service.js";
import { AccountControlService } from "./account-control-service.js";
import {
  AccountModeError,
  accountErrors,
  type AccountPlatform,
  type ReauthenticationScope,
} from "./model.js";

const MAX_ACCOUNT_BODY_BYTES = 32 * 1024;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface AccountCapabilities {
  version: 1;
  accountAuth: {
    enabled: boolean;
    providers: ["google"];
    android: boolean;
    macos: boolean;
  };
  binding: {
    enabled: boolean;
    replacement: boolean;
    maxActiveConnectorsPerAccount: 1;
  };
  legacy: {
    appTokenAccepted: boolean;
    connectorTokenAccepted: boolean;
  };
}

export class AccountHttpController {
  private readonly exchangeLimiter = new FixedWindowLimiter(10, 60_000);
  private readonly refreshLimiter = new FixedWindowLimiter(60, 60_000);

  constructor(
    private readonly enabled: boolean,
    private readonly service?: AccountService,
    private readonly options: {
      trustLoopbackProxy?: boolean;
      controlEnabled?: boolean;
      controlService?: AccountControlService;
    } = {},
  ) {}

  async handle(request: IncomingMessage, response: ServerResponse, url: URL): Promise<void> {
    const correlationId = randomUUID();
    try {
      if (url.pathname === "/v2/capabilities" && request.method === "GET") {
        sendJson(response, 200, capabilities(this.enabled, Boolean(this.options.controlEnabled)), {
          "cache-control": "public, max-age=60",
        });
        return;
      }
      if (!this.enabled || !this.service) throw accountErrors.featureDisabled();

      if (url.pathname === "/v2/installations" && request.method === "GET") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, { items: await control.listInstallations(principal) });
        return;
      }

      if (url.pathname === "/v2/installations/current" && request.method === "DELETE") {
        const control = this.requireControl();
        await control.revokeCurrentPhoneInstallation(
          firstHeader(request, "authorization"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      const installationMatch = /^\/v2\/installations\/([0-9a-f-]{36})$/i.exec(url.pathname);
      if (installationMatch && request.method === "DELETE") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        await control.revokePhoneInstallation(
          principal,
          uuid(installationMatch[1], "installationId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (url.pathname === "/v2/connector-binding" && request.method === "GET") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, await control.getBinding(principal));
        return;
      }

      if (url.pathname === "/v2/connector-binding" && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const binding = await control.createPendingBinding(principal, {
          desktopInstallationId: uuid(body.desktopInstallationId, "desktopInstallationId"),
          displayName: boundedDisplayString(body.displayName, "displayName", 128),
          connectorPublicKey: boundedString(body.connectorPublicKey, "connectorPublicKey", 43, 43),
          keyAlgorithm: boundedString(body.keyAlgorithm, "keyAlgorithm", 1, 32),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 201, binding);
        return;
      }

      if (url.pathname === "/v2/connector-binding/confirm" && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const binding = await control.confirmPendingBinding(principal, {
          bindingId: uuid(body.bindingId, "bindingId"),
          generation: boundedInteger(body.generation, "generation", 1, 2_147_483_647),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { state: "bound", binding });
        return;
      }

      if (url.pathname === "/v2/connector-binding/replacement-requests"
          && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const replacement = await control.createReplacementRequest(principal, {
          desktopInstallationId: uuid(body.desktopInstallationId, "desktopInstallationId"),
          displayName: boundedDisplayString(body.displayName, "displayName", 128),
          connectorPublicKey: boundedString(body.connectorPublicKey, "connectorPublicKey", 43, 43),
          keyAlgorithm: boundedString(body.keyAlgorithm, "keyAlgorithm", 1, 32),
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 201, replacement);
        return;
      }

      const replacementConfirmation = /^\/v2\/connector-binding\/replacement-requests\/([0-9a-f-]{36})\/confirm$/i
        .exec(url.pathname);
      if (replacementConfirmation && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const binding = await control.confirmReplacementRequest(principal, {
          requestId: uuid(replacementConfirmation[1], "requestId"),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { state: "bound", binding });
        return;
      }

      if (url.pathname === "/v2/connector-binding" && request.method === "DELETE") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        await control.unbindConnector(principal, {
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (url.pathname === "/v2/auth/google/exchange" && request.method === "POST") {
        this.exchangeLimiter.requireAllowance(this.sourceKey(request));
        const body = await readJsonObject(request);
        const platform = accountPlatform(body.platform);
        const result = await this.service.exchangeGoogleProof({
          platform,
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          clientInstallationId: uuid(body.clientInstallationId, "clientInstallationId"),
          displayName: boundedDisplayString(body.displayName, "displayName", 128),
          appVersion: boundedDisplayString(body.appVersion, "appVersion", 64),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, result);
        return;
      }

      if (url.pathname === "/v2/auth/refresh" && request.method === "POST") {
        this.refreshLimiter.requireAllowance(this.sourceKey(request));
        const body = await readJsonObject(request);
        const result = await this.service.refresh({
          refreshToken: boundedString(body.refreshToken, "refreshToken", 1, 256),
          clientInstallationId: uuid(body.clientInstallationId, "clientInstallationId"),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { session: result });
        return;
      }

      if (url.pathname === "/v2/account" && request.method === "GET") {
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, {
          account: principal.account,
          installation: principal.installation,
          session: { authenticated: true, recentReauthentication: false },
        });
        return;
      }

      if (url.pathname === "/v2/auth/reauth/google" && request.method === "POST") {
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const result = await this.service.reauthenticateGoogle(principal, {
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          scope: reauthenticationScope(body.scope),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, result);
        return;
      }

      if (url.pathname === "/v2/auth/revoke-all" && request.method === "POST") {
        const body = await readJsonObject(request);
        await this.service.revokeAllSessions(
          firstHeader(request, "authorization"),
          boundedString(body.grant, "grant", 1, 256),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (url.pathname === "/v2/auth/sign-out" && request.method === "POST") {
        await this.service.signOut(
          firstHeader(request, "authorization"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      throw new AccountModeError(404, "HR-ACCOUNT-004", "The account endpoint was not found.", false, "none");
    } catch (error) {
      const mapped = error instanceof AccountModeError ? error : accountErrors.unavailable();
      if (!(error instanceof AccountModeError)) {
        console.error("Account request failed", {
          correlationId,
          errorType: error instanceof Error ? error.name : "unknown",
        });
      }
      sendJson(response, mapped.status, {
        error: {
          code: mapped.code,
          message: mapped.message,
          retryable: mapped.retryable,
          recoveryAction: mapped.recoveryAction,
          correlationId,
        },
      }, mapped.code === "HR-AUTH-007" ? { "retry-after": "60" } : {});
    }
  }

  private sourceKey(request: IncomingMessage): string {
    const peer = request.socket.remoteAddress ?? "unknown";
    if (!this.options.trustLoopbackProxy || !isLoopback(peer)) return peer;
    const forwarded = firstHeader(request, "x-forwarded-for")?.split(",", 1)[0]?.trim();
    return forwarded && isIP(forwarded) !== 0 ? forwarded : peer;
  }

  private requireControl(): AccountControlService {
    if (!this.options.controlEnabled || !this.options.controlService) {
      throw accountErrors.bindingFeatureDisabled();
    }
    return this.options.controlService;
  }
}

function capabilities(enabled: boolean, controlEnabled: boolean): AccountCapabilities {
  return {
    version: 1,
    accountAuth: {
      enabled,
      providers: ["google"],
      android: true,
      macos: true,
    },
    binding: {
      enabled: enabled && controlEnabled,
      replacement: enabled && controlEnabled,
      maxActiveConnectorsPerAccount: 1,
    },
    legacy: {
      appTokenAccepted: true,
      connectorTokenAccepted: true,
    },
  };
}

async function readJsonObject(request: IncomingMessage): Promise<Record<string, unknown>> {
  const contentType = firstHeader(request, "content-type")?.split(";", 1)[0]?.trim().toLowerCase();
  if (contentType !== "application/json") {
    throw accountErrors.invalidRequest("Content-Type must be application/json.");
  }
  const chunks: Buffer[] = [];
  let size = 0;
  try {
    for await (const chunk of request) {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      size += bytes.length;
      if (size > MAX_ACCOUNT_BODY_BYTES) {
        throw accountErrors.invalidRequest("The account request body is too large.");
      }
      chunks.push(bytes);
    }
    const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!isRecord(parsed)) throw new Error("not_an_object");
    return parsed;
  } catch (error) {
    if (error instanceof AccountModeError) throw error;
    throw accountErrors.invalidRequest("The account request body must be valid JSON.");
  }
}

function accountPlatform(value: unknown): AccountPlatform {
  if (value === "android" || value === "macos") return value;
  throw accountErrors.invalidRequest("platform must be android or macos.");
}

function reauthenticationScope(value: unknown): ReauthenticationScope {
  if (value === "connector.replace" || value === "connector.unbind" || value === "account.revoke_all") {
    return value;
  }
  throw accountErrors.invalidRequest("scope is not a supported reauthentication operation.");
}

function boundedString(
  value: unknown,
  field: string,
  minimum: number,
  maximum: number,
): string {
  if (typeof value !== "string" || value.length < minimum || value.length > maximum) {
    throw accountErrors.invalidRequest(`${field} must contain ${minimum}-${maximum} characters.`);
  }
  return value;
}

function boundedDisplayString(value: unknown, field: string, maximum: number): string {
  const result = boundedString(value, field, 1, maximum);
  if (result.trim().length === 0 || /[\u0000-\u001f\u007f]/.test(result)) {
    throw accountErrors.invalidRequest(`${field} contains unsupported characters.`);
  }
  return result;
}

function uuid(value: unknown, field: string): string {
  const result = boundedString(value, field, 36, 36);
  if (!UUID_PATTERN.test(result)) throw accountErrors.invalidRequest(`${field} must be a UUID.`);
  return result.toLowerCase();
}

function boundedInteger(value: unknown, field: string, minimum: number, maximum: number): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw accountErrors.invalidRequest(`${field} must be an integer between ${minimum} and ${maximum}.`);
  }
  return value as number;
}

function firstHeader(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isLoopback(address: string): boolean {
  return address === "::1" || address.startsWith("127.") || address.startsWith("::ffff:127.");
}

function sendJson(
  response: ServerResponse,
  status: number,
  value: unknown,
  extraHeaders: Record<string, string> = {},
): void {
  if (response.writableEnded) return;
  response.writeHead(status, {
    "content-type": "application/json",
    "cache-control": "no-store",
    ...extraHeaders,
  });
  response.end(JSON.stringify(value));
}

class FixedWindowLimiter {
  private readonly entries = new Map<string, { count: number; resetAt: number }>();

  constructor(
    private readonly maximum: number,
    private readonly windowMs: number,
  ) {}

  requireAllowance(key: string): void {
    const now = Date.now();
    const current = this.entries.get(key);
    if (!current || current.resetAt <= now) {
      this.compact(now);
      if (!this.entries.has(key) && this.entries.size >= 10_000) {
        throw accountErrors.rateLimited();
      }
      this.entries.set(key, { count: 1, resetAt: now + this.windowMs });
      return;
    }
    current.count += 1;
    if (current.count > this.maximum) throw accountErrors.rateLimited();
  }

  private compact(now: number): void {
    for (const [key, entry] of this.entries) {
      if (entry.resetAt <= now) this.entries.delete(key);
    }
  }
}
