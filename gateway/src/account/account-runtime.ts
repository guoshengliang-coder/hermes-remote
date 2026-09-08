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
import { EmailOtpSecurity } from "./email-otp.js";
import { EmailOtpService } from "./email-otp-service.js";
import { PostgresAccountRepository } from "./postgres-account-repository.js";
import { PostgresAccountControlRepository } from "./postgres-account-control-repository.js";
import { PostgresEmailOtpStore } from "./postgres-email-otp-store.js";
import {
  PostgresEmailDeliveryMetrics,
  type AccountEmailDeliveryMetrics,
} from "./postgres-email-delivery-metrics.js";
import { ResendEmailSender } from "./resend-email-sender.js";
import { ResendWebhookController } from "./resend-webhook-controller.js";
import { PostgresEmailDeliveryEventStore } from "./postgres-email-delivery-event-store.js";
import { AccountSharingService } from "./account-sharing-service.js";
import { PostgresAccountSharingRepository } from "./postgres-account-sharing-repository.js";
import {
  AccountRetentionScheduler,
  PostgresAccountRetentionStore,
  type AccountRetentionMetrics,
} from "./account-retention.js";
import type { DeviceAccessRevocationListener } from "./account-sharing-model.js";
import {
  PostgresAccountAccessRevocationSubscriber,
  type AccountAccessRevocationListener,
} from "./postgres-access-revocation-bus.js";
import { WebSessionSecurity } from "./web-session-security.js";
import { TokenCodec } from "./token-codec.js";
import type { AccountPrincipal } from "./model.js";
import type { AccountDevice, BindingProofMaterial, BindingState } from "./account-control-model.js";
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
  resolveDevice(principal: AccountPrincipal, deviceId?: string): Promise<AccountDevice>;
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
  subscribeAccessRevocations?(listener: AccountAccessRevocationListener): () => void;
}

export interface AccountRuntime {
  controller: AccountHttpController;
  resendWebhook?: ResendWebhookController;
  gatewayControl?: AccountGatewayControl;
  accountAuthEnabled: boolean;
  googleAuthEnabled: boolean;
  emailOtpEnabled: boolean;
  resendWebhookEnabled: boolean;
  identityManagementEnabled: boolean;
  accountDeletionEnabled: boolean;
  webAccountCenterEnabled: boolean;
  webSessionEnabled: boolean;
  multiDeviceEnabled: boolean;
  sharingEnabled: boolean;
  bindingEnabled: boolean;
  emailDeliveryMetrics?(): Promise<AccountEmailDeliveryMetrics>;
  retentionMetrics?(): AccountRetentionMetrics;
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
      controller: new AccountHttpController(false, undefined, {
        googleAuthEnabled: false,
        serverRelease: release,
      }),
      accountAuthEnabled: false,
      googleAuthEnabled: false,
      emailOtpEnabled: false,
      resendWebhookEnabled: false,
      identityManagementEnabled: false,
      accountDeletionEnabled: false,
      webAccountCenterEnabled: false,
      webSessionEnabled: false,
      multiDeviceEnabled: false,
      sharingEnabled: false,
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
  const tokens = new TokenCodec(hashKey);
  const googleAuthEnabled = booleanFlag(environment, "ACCOUNT_GOOGLE_AUTH_ENABLED", false);
  const androidAudience = googleAuthEnabled
    ? requireSecret(environment, "ACCOUNT_GOOGLE_ANDROID_CLIENT_ID", 8)
    : undefined;
  const macosAudience = googleAuthEnabled
    ? requireSecret(environment, "ACCOUNT_GOOGLE_MACOS_CLIENT_ID", 8)
    : undefined;
  const databaseSsl = booleanFlag(environment, "ACCOUNT_DATABASE_SSL", false);
  const trustLoopbackProxy = booleanFlag(environment, "ACCOUNT_TRUST_LOOPBACK_PROXY", false);
  const controlEnabled = booleanFlag(environment, "ACCOUNT_BINDING_ENABLED", false);
  const multiDeviceEnabled = booleanFlag(environment, "ACCOUNT_MULTI_DEVICE_ENABLED", false);
  const emailOtpEnabled = booleanFlag(environment, "ACCOUNT_EMAIL_OTP_ENABLED", false);
  const resendWebhookEnabled = booleanFlag(
    environment,
    "ACCOUNT_RESEND_WEBHOOK_ENABLED",
    false,
  );
  const identityManagementEnabled = booleanFlag(
    environment,
    "ACCOUNT_IDENTITY_MANAGEMENT_ENABLED",
    false,
  );
  const accountDeletionEnabled = booleanFlag(environment, "ACCOUNT_DELETION_ENABLED", false);
  const webAccountCenterEnabled = booleanFlag(
    environment,
    "ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED",
    false,
  );
  const webSessionEnabled = booleanFlag(environment, "ACCOUNT_WEB_SESSION_ENABLED", false);
  const sharingEnabled = booleanFlag(environment, "ACCOUNT_DEVICE_SHARING_ENABLED", false);
  const desktopManagedInstallEnabled = booleanFlag(
    environment,
    "ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED",
    false,
  );
  if (webAccountCenterEnabled && !identityManagementEnabled) {
    throw new Error(
      "ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED requires ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1",
    );
  }
  if (accountDeletionEnabled
      && (!identityManagementEnabled || (!emailOtpEnabled && !googleAuthEnabled))) {
    throw new Error(
      "ACCOUNT_DELETION_ENABLED requires ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1 "
      + "and at least one enabled identity provider",
    );
  }
  if (webSessionEnabled && (!webAccountCenterEnabled || !emailOtpEnabled)) {
    throw new Error(
      "ACCOUNT_WEB_SESSION_ENABLED requires ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED=1 "
      + "and ACCOUNT_EMAIL_OTP_ENABLED=1",
    );
  }
  if (multiDeviceEnabled && !controlEnabled) {
    throw new Error("ACCOUNT_MULTI_DEVICE_ENABLED requires ACCOUNT_BINDING_ENABLED=1");
  }
  if (desktopManagedInstallEnabled && !controlEnabled) {
    throw new Error(
      "ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED requires ACCOUNT_BINDING_ENABLED=1",
    );
  }
  if (sharingEnabled && (!controlEnabled || !multiDeviceEnabled || !identityManagementEnabled)) {
    throw new Error(
      "ACCOUNT_DEVICE_SHARING_ENABLED requires ACCOUNT_BINDING_ENABLED=1, "
      + "ACCOUNT_MULTI_DEVICE_ENABLED=1, and ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1",
    );
  }
  if ((emailOtpEnabled || sharingEnabled) && !resendWebhookEnabled) {
    throw new Error(
      "ACCOUNT_EMAIL_OTP_ENABLED and ACCOUNT_DEVICE_SHARING_ENABLED require "
      + "ACCOUNT_RESEND_WEBHOOK_ENABLED=1",
    );
  }
  const maxOwnedDevices = multiDeviceEnabled ? 3 : 1;
  const databaseConnection = {
    connectionString: databaseUrl,
    connectionTimeoutMillis: positiveInteger(
      environment,
      "ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS",
      3_000,
      30_000,
    ),
    ssl: databaseSsl ? { rejectUnauthorized: true } : undefined,
  };
  const pool = new Pool({
    ...databaseConnection,
    max: positiveInteger(environment, "ACCOUNT_DATABASE_POOL_SIZE", 10, 100),
  });
  const accessRevocationPool = new Pool({ ...databaseConnection, max: 1 });
  const repository = new PostgresAccountRepository(pool, tokens);
  const emailDeliveryMetrics = new PostgresEmailDeliveryMetrics(pool);
  const retention = new AccountRetentionScheduler(
    new PostgresAccountRetentionStore(pool, {
      lifecycleRetentionMilliseconds: positiveInteger(
        environment,
        "ACCOUNT_LIFECYCLE_RETENTION_DAYS",
        30,
        3_650,
      ) * 24 * 60 * 60 * 1_000,
      auditRetentionMilliseconds: positiveInteger(
        environment,
        "ACCOUNT_AUDIT_RETENTION_DAYS",
        180,
        3_650,
      ) * 24 * 60 * 60 * 1_000,
    }),
    { reportFailure: () => console.error("HR-OPS-019 account retention sweep failed") },
  );
  const accessRevocations = new PostgresAccountAccessRevocationSubscriber(accessRevocationPool);
  const webAudience = googleAuthEnabled && webSessionEnabled
    ? requireSecret(environment, "ACCOUNT_GOOGLE_WEB_CLIENT_ID", 8)
    : undefined;
  const service = new AccountService(
    googleAuthEnabled
      ? new GoogleIdentityVerifier({
          android: androidAudience!,
          macos: macosAudience!,
          ...(webAudience ? { web: webAudience } : {}),
        })
      : undefined,
    repository,
    tokens,
  );
  const transactionalEmailSender = emailOtpEnabled || sharingEnabled
    ? new ResendEmailSender(
        requireSecret(environment, "ACCOUNT_RESEND_API_KEY", 11),
        requireSecret(environment, "ACCOUNT_EMAIL_FROM", 3),
        `hermes-go-gateway/${release.serverVersion}`,
      )
    : undefined;
  const resendWebhook = resendWebhookEnabled
    ? new ResendWebhookController(
        requireSecret(environment, "ACCOUNT_RESEND_WEBHOOK_SECRET", 22),
        new PostgresEmailDeliveryEventStore(pool),
      )
    : undefined;
  const emailOtpService = emailOtpEnabled
    ? new EmailOtpService(
        new PostgresEmailOtpStore(pool),
        transactionalEmailSender!,
        new EmailOtpSecurity(
          requireSecret(environment, "ACCOUNT_EMAIL_OTP_HASH_KEY", 32),
          requireHttpsOrigin(environment, "ACCOUNT_EMAIL_OTP_ISSUER"),
        ),
      )
    : undefined;
  const controlRepository = new PostgresAccountControlRepository(
    pool,
    positiveInteger(environment, "MAX_ACCOUNT_LIFECYCLE_EVENTS", 10_000, 1_000_000),
    maxOwnedDevices,
  );
  const sharingRepository = sharingEnabled
    ? new PostgresAccountSharingRepository(pool)
    : undefined;
  const sharingService = sharingRepository && transactionalEmailSender
    ? new AccountSharingService(
        sharingRepository,
        tokens,
        transactionalEmailSender,
        (principal) => service.listExternalIdentities(principal),
        requireHttpsOrigin(environment, "ACCOUNT_SHARING_ACCOUNT_CENTER_ORIGIN"),
      )
    : undefined;
  const controlService = new AccountControlService(
    controlRepository,
    tokens,
    () => new Date(),
    maxOwnedDevices,
    sharingRepository,
  );
  const proofCoordinator = controlEnabled
    ? new ConnectorProofCoordinator(
        controlRepository,
        requireOrigin(environment, "ACCOUNT_GATEWAY_ORIGIN"),
        () => new Date(),
        positiveInteger(environment, "ACCOUNT_MAX_PENDING_CONNECTOR_PROOFS", 256, 4096),
      )
    : undefined;
  retention.start();
  return {
    controller: new AccountHttpController(true, service, {
      trustLoopbackProxy,
      controlEnabled,
      controlService,
      emailOtpEnabled,
      googleAuthEnabled,
      emailOtpService,
      identityManagementEnabled,
      accountDeletionEnabled,
      webAccountCenterEnabled,
      webSessionEnabled,
      ...(webSessionEnabled ? {
        googleWebClientId: webAudience,
        webSessionSecurity: new WebSessionSecurity(
          requireHttpsOrigin(environment, "ACCOUNT_WEB_ORIGIN"),
        ),
      } : {}),
      multiDeviceEnabled,
      sharingEnabled,
      desktopManagedInstallEnabled,
      sharingService,
      serverRelease: release,
    }),
    ...(resendWebhook ? { resendWebhook } : {}),
    accountAuthEnabled: true,
    googleAuthEnabled,
    emailOtpEnabled,
    resendWebhookEnabled,
    identityManagementEnabled,
    accountDeletionEnabled,
    webAccountCenterEnabled,
    webSessionEnabled,
    multiDeviceEnabled,
    sharingEnabled,
    bindingEnabled: controlEnabled,
    emailDeliveryMetrics: () => emailDeliveryMetrics.snapshot(),
    retentionMetrics: () => retention.snapshot(),
    ...(proofCoordinator ? {
      gatewayControl: {
        authenticate: (authorization) => service.authenticate(authorization),
        getBinding: (principal) => controlService.getBinding(principal),
        resolveDevice: (principal, deviceId) => controlService.resolveDevice(principal, deviceId),
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
        subscribeAccessRevocations: (listener: AccountAccessRevocationListener) => {
          const unsubscribeDatabase = accessRevocations.subscribe(listener);
          const unsubscribeLocal = sharingService?.subscribeRevocations(
            (access: Parameters<DeviceAccessRevocationListener>[0]) => listener({
              kind: "binding",
              accountId: access.granteeAccountId,
              bindingId: access.bindingId,
            }),
          );
          return () => {
            unsubscribeLocal?.();
            unsubscribeDatabase();
          };
        },
      },
    } : {}),
    readiness: () => checkDatabaseReadiness(pool, release),
    close: async () => {
      await retention.close();
      await accessRevocations.close();
      await accessRevocationPool.end();
      await service.close();
    },
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

function requireHttpsOrigin(environment: NodeJS.ProcessEnv, name: string): string {
  const raw = environment[name];
  if (!raw) throw new Error(`${name} must be configured when its feature is enabled`);
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`${name} must be an absolute HTTPS origin`);
  }
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/"
      || url.search || url.hash || url.origin !== raw) {
    throw new Error(`${name} must be an exact HTTPS origin without credentials or a path`);
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
