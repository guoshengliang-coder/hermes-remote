import { readFileSync } from "node:fs";
import { Pool } from "pg";
import { AccountHttpController } from "./account-http-controller.js";
import { AccountService } from "./account-service.js";
import { AccountControlService } from "./account-control-service.js";
import {
  ConnectorProofCoordinator,
  type ConnectorChallenge,
} from "./connector-proof-coordinator.js";
import { GoogleIdentityVerifier } from "./google-identity-verifier.js";
import { PostgresAccountRepository } from "./postgres-account-repository.js";
import { PostgresAccountControlRepository } from "./postgres-account-control-repository.js";
import { TokenCodec } from "./token-codec.js";
import type { AccountPrincipal } from "./model.js";
import type { BindingProofMaterial, BindingState } from "./account-control-model.js";
import type { SessionLifecycleEvent } from "@hermes-remote/protocol";
import type { LifecycleEventPage } from "../lifecycle-event-store.js";
import {
  loadServerReleaseManifest,
  type GatewayReadiness,
  type ServerReleaseManifest,
} from "../server-release.js";

export interface AccountGatewayControl {
  authenticate(authorization: string | undefined): Promise<AccountPrincipal>;
  getBinding(principal: AccountPrincipal): Promise<BindingState>;
  issueConnectorChallenge(input: {
    bindingId: string;
    generation: number;
    publicKeyFingerprint: string;
  }): Promise<ConnectorChallenge>;
  authenticateConnector(input: {
    bindingId: string;
    generation: number;
    publicKeyFingerprint: string;
    connectionNonce: string;
    signature: string;
  }): Promise<BindingProofMaterial>;
  recordConnectorHealth(
    material: BindingProofMaterial,
    health: {
      hermesReachable: boolean;
      hermesVersion?: string;
      gatewayLatencyMs: number;
      endToEndHealthy: boolean;
    },
  ): Promise<boolean>;
  recordConnectorDisconnected(material: BindingProofMaterial): Promise<boolean>;
  ingestLifecycleEvent(
    material: BindingProofMaterial,
    event: SessionLifecycleEvent,
  ): Promise<"stored" | "duplicate" | "binding_invalid" | "event_id_conflict">;
  listLifecycleEvents(
    principal: AccountPrincipal,
    after: number,
    limit: number,
  ): Promise<LifecycleEventPage>;
  markLifecycleEvents(
    principal: AccountPrincipal,
    eventIds: string[],
    field: "delivered" | "read",
  ): Promise<number>;
}

export interface AccountRuntime {
  controller: AccountHttpController;
  gatewayControl?: AccountGatewayControl;
  accountAuthEnabled: boolean;
  bindingEnabled: boolean;
  readiness(): Promise<GatewayReadiness>;
  close(): Promise<void>;
}

export function createAccountRuntime(
  environment: NodeJS.ProcessEnv,
  release: ServerReleaseManifest = loadServerReleaseManifest(),
): AccountRuntime {
  const enabled = booleanFlag(environment, "ACCOUNT_AUTH_ENABLED", false);
  if (!enabled) {
    return {
      controller: new AccountHttpController(false, undefined, { serverRelease: release }),
      accountAuthEnabled: false,
      bindingEnabled: false,
      readiness: async () => ({
        ready: true,
        checks: {
          config: "ok",
          database: "disabled",
          migrations: "not_required",
          postgresql: "not_required",
        },
      }),
      close: async () => {},
    };
  }

  const databaseUrl = requireSecret(environment, "ACCOUNT_DATABASE_URL", 8);
  const hashKey = requireSecret(environment, "ACCOUNT_TOKEN_HASH_KEY", 32);
  const androidAudience = requireSecret(environment, "ACCOUNT_GOOGLE_ANDROID_CLIENT_ID", 8);
  const macosAudience = requireSecret(environment, "ACCOUNT_GOOGLE_MACOS_CLIENT_ID", 8);
  const databaseSsl = booleanFlag(environment, "ACCOUNT_DATABASE_SSL", false);
  const trustLoopbackProxy = booleanFlag(environment, "ACCOUNT_TRUST_LOOPBACK_PROXY", false);
  const controlEnabled = booleanFlag(environment, "ACCOUNT_BINDING_ENABLED", false);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: positiveInteger(environment, "ACCOUNT_DATABASE_POOL_SIZE", 10, 100),
    connectionTimeoutMillis: positiveInteger(
      environment,
      "ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS",
      3_000,
      30_000,
    ),
    ssl: databaseSsl ? { rejectUnauthorized: true } : undefined,
  });
  const repository = new PostgresAccountRepository(pool);
  const service = new AccountService(
    new GoogleIdentityVerifier({ android: androidAudience, macos: macosAudience }),
    repository,
    new TokenCodec(hashKey),
  );
  const controlRepository = new PostgresAccountControlRepository(
    pool,
    positiveInteger(environment, "MAX_ACCOUNT_LIFECYCLE_EVENTS", 10_000, 1_000_000),
  );
  const controlService = new AccountControlService(controlRepository, new TokenCodec(hashKey));
  const proofCoordinator = controlEnabled
    ? new ConnectorProofCoordinator(
        controlRepository,
        requireOrigin(environment, "ACCOUNT_GATEWAY_ORIGIN"),
        () => new Date(),
        positiveInteger(environment, "ACCOUNT_MAX_PENDING_CONNECTOR_PROOFS", 256, 4096),
      )
    : undefined;
  return {
    controller: new AccountHttpController(true, service, {
      trustLoopbackProxy,
      controlEnabled,
      controlService,
      serverRelease: release,
    }),
    accountAuthEnabled: true,
    bindingEnabled: controlEnabled,
    ...(proofCoordinator ? {
      gatewayControl: {
        authenticate: (authorization) => service.authenticate(authorization),
        getBinding: (principal) => controlService.getBinding(principal),
        issueConnectorChallenge: (input) => proofCoordinator.issue(input),
        authenticateConnector: (input) => proofCoordinator.authenticate(input),
        recordConnectorHealth: (material, health) => controlRepository.recordBindingHealth(
          material.id,
          material.generation,
          health,
        ),
        recordConnectorDisconnected: (material) => controlRepository.recordBindingDisconnected(
          material.id,
          material.generation,
          material.publicKeyFingerprint,
        ),
        ingestLifecycleEvent: async (material, event) => (
          await controlRepository.ingestAccountLifecycleEvent(material, event)
        ).status,
        listLifecycleEvents: (principal, after, limit) => controlService.listLifecycleEvents(
          principal,
          after,
          limit,
        ),
        markLifecycleEvents: (principal, eventIds, field) => controlService.markLifecycleEvents(
          principal,
          eventIds,
          field,
        ),
      },
    } : {}),
    readiness: () => checkDatabaseReadiness(pool, release),
    close: () => service.close(),
  };
}

export async function checkDatabaseReadiness(
  pool: Pool,
  release: ServerReleaseManifest,
): Promise<GatewayReadiness> {
  let postgresql: GatewayReadiness["checks"]["postgresql"] = "unknown";
  let migrations: GatewayReadiness["checks"]["migrations"] = "unknown";
  try {
    const versionResult = await pool.query<{ server_version_num: string }>(
      "SELECT current_setting('server_version_num') AS server_version_num",
    );
    const major = Math.floor(Number(versionResult.rows[0]?.server_version_num) / 10_000);
    postgresql = release.supportedPostgresqlMajors.includes(major)
      ? "supported"
      : "unsupported";
    try {
      const schemaResult = await pool.query<{ version: number }>(
        "SELECT version FROM gateway_schema_state WHERE singleton = true",
      );
      migrations = schemaResult.rows[0]?.version === release.databaseSchemaVersion
        ? "ok"
        : "mismatch";
    } catch {
      migrations = "mismatch";
    }
    const ready = postgresql === "supported" && migrations === "ok";
    return {
      ready,
      checks: { config: "ok", database: "ok", migrations, postgresql },
    };
  } catch {
    return {
      ready: false,
      checks: {
        config: "ok",
        database: "unavailable",
        migrations,
        postgresql,
      },
    };
  }
}

function requireOrigin(environment: NodeJS.ProcessEnv, name: string): string {
  const raw = environment[name];
  if (!raw) throw new Error(`${name} must be configured when ACCOUNT_BINDING_ENABLED=1`);
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`${name} must be an absolute http or https origin`);
  }
  if ((url.protocol !== "https:" && url.protocol !== "http:")
      || url.username || url.password || url.pathname !== "/" || url.search || url.hash
      || url.origin !== raw.replace(/\/$/, "")) {
    throw new Error(`${name} must be an exact http or https origin without credentials or a path`);
  }
  return url.origin;
}

function requireSecret(
  environment: NodeJS.ProcessEnv,
  name: string,
  minimumLength: number,
): string {
  const file = environment[`${name}_FILE`];
  const value = environment[name] ?? (file ? readFileSync(file, "utf8").trim() : undefined);
  if (!value || Buffer.byteLength(value, "utf8") < minimumLength) {
    throw new Error(`${name} must contain at least ${minimumLength} bytes`);
  }
  return value;
}

function booleanFlag(
  environment: NodeJS.ProcessEnv,
  name: string,
  fallback: boolean,
): boolean {
  const raw = environment[name];
  if (raw === undefined) return fallback;
  if (raw === "1") return true;
  if (raw === "0") return false;
  throw new Error(`${name} must be 0 or 1`);
}

function positiveInteger(
  environment: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
  maximum: number,
): number {
  const raw = environment[name];
  if (raw === undefined) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) {
    throw new Error(`${name} must be an integer between 1 and ${maximum}`);
  }
  return value;
}
