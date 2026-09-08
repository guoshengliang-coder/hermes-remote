import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, sign } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { AccountHttpController } from "./account/account-http-controller.js";
import { AccountControlService } from "./account/account-control-service.js";
import { AccountService } from "./account/account-service.js";
import type {
  AccountControlRepository,
  AccountDevice,
  AccountInstallationRevocationResult,
  ActiveBinding,
  BindingCandidate,
  BindingProofMaterial,
  BindingState,
  ConfirmBindingResult,
  ConfirmReplacementResult,
  CreateBindingResult,
  CreateReplacementResult,
  CurrentInstallationRevocationResult,
  ManagedInstallation,
  UnbindResult,
} from "./account/account-control-model.js";
import {
  ConnectorProofCoordinator,
  canonicalConnectorChallenge,
} from "./account/connector-proof-coordinator.js";
import type { AccountPrincipal, IdempotencyMaterial } from "./account/model.js";
import { accountErrors } from "./account/model.js";
import { TokenCodec } from "./account/token-codec.js";

const HASH_KEY = "control-test-key-that-is-more-than-thirty-two-bytes";
const DESKTOP_ID = "979d7035-9ba5-456f-979a-98ab28ae89ec";
const BINDING_ID = "10000000-0000-4000-8000-000000000001";

test("AccountControlService limits management and binding creation to the current Desktop", async () => {
  const repository = new FakeControlRepository();
  const service = new AccountControlService(repository, new TokenCodec(HASH_KEY));
  const phone = principal("phone");
  await assert.rejects(
    service.listInstallations(phone),
    (error: unknown) => errorCode(error) === "HR-ACCOUNT-007",
  );
  await assert.rejects(
    service.listInstallations(principal("browser")),
    (error: unknown) => errorCode(error) === "HR-ACCOUNT-007",
  );
  assert.deepEqual(await service.listLifecycleEvents(phone, 0, 20), {
    events: [],
    nextCursor: 0,
    hasMore: false,
  });
  await assert.rejects(
    service.listLifecycleEvents(principal("desktop"), 0, 20),
    (error: unknown) => errorCode(error) === "HR-ACCOUNT-004",
  );

  const publicKey = Buffer.alloc(32, 7).toString("base64url");
  const key = "68f80302-19f1-4609-bb47-66c2f17bcd17";
  const created = await service.createPendingBinding(principal("desktop"), {
    desktopInstallationId: DESKTOP_ID,
    displayName: "Mac mini",
    connectorPublicKey: publicKey,
    keyAlgorithm: "Ed25519",
    idempotencyKey: key,
  });
  assert.equal(created.id, BINDING_ID);
  assert.equal(repository.createIdempotency?.key, key);
  assert.equal(repository.createdPublicKey?.toString("base64url"), publicKey);

  await assert.rejects(
    service.createPendingBinding(principal("desktop"), {
      desktopInstallationId: "20000000-0000-4000-8000-000000000002",
      displayName: "Other Mac",
      connectorPublicKey: publicKey,
      keyAlgorithm: "Ed25519",
      idempotencyKey: "52f6edbc-4d28-45f7-b72c-95cdefc74bf3",
    }),
    (error: unknown) => errorCode(error) === "HR-ACCOUNT-007",
  );
});

test("ConnectorProofCoordinator verifies one short-lived Ed25519 challenge exactly once", async () => {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const publicDer = publicKey.export({ format: "der", type: "spki" });
  const rawPublicKey = publicDer.subarray(publicDer.byteLength - 32);
  const fingerprint = createHash("sha256").update(rawPublicKey).digest("hex");
  const repository = new FakeControlRepository();
  repository.proofMaterial = {
    id: BINDING_ID,
    accountId: "account-1",
    deviceId: `hermes-${BINDING_ID}`,
    generation: 1,
    publicKey: rawPublicKey,
    publicKeyFingerprint: fingerprint,
    status: "pending",
    expiresAt: new Date("2026-09-02T04:10:00.000Z"),
  };
  let now = new Date("2026-09-02T04:00:00.000Z");
  const coordinator = new ConnectorProofCoordinator(
    repository,
    "https://mrlgs.net",
    () => now,
  );
  const challenge = await coordinator.issue({
    bindingId: BINDING_ID,
    generation: 1,
    publicKeyFingerprint: fingerprint,
  });
  const signature = sign(
    null,
    canonicalConnectorChallenge("https://mrlgs.net", challenge),
    privateKey,
  ).toString("base64url");
  await coordinator.authenticate({
    bindingId: BINDING_ID,
    generation: 1,
    publicKeyFingerprint: fingerprint,
    connectionNonce: challenge.connectionNonce,
    signature,
  });
  assert.equal(repository.keyProofRecorded, true);

  await assert.rejects(
    coordinator.authenticate({
      bindingId: BINDING_ID,
      generation: 1,
      publicKeyFingerprint: fingerprint,
      connectionNonce: challenge.connectionNonce,
      signature,
    }),
    (error: unknown) => errorCode(error) === "HR-BIND-005",
  );

  const expired = await coordinator.issue({
    bindingId: BINDING_ID,
    generation: 1,
    publicKeyFingerprint: fingerprint,
  });
  now = new Date("2026-09-02T04:00:06.000Z");
  await assert.rejects(
    coordinator.authenticate({
      bindingId: BINDING_ID,
      generation: 1,
      publicKeyFingerprint: fingerprint,
      connectionNonce: expired.connectionNonce,
      signature: sign(
        null,
        canonicalConnectorChallenge("https://mrlgs.net", expired),
        privateKey,
      ).toString("base64url"),
    }),
    (error: unknown) => errorCode(error) === "HR-BIND-005",
  );
});

test("AccountControlService hashes scoped grants and permits current-phone retry after revocation", async () => {
  const repository = new FakeControlRepository();
  const codec = new TokenCodec(HASH_KEY);
  const service = new AccountControlService(repository, codec);
  const grant = codec.issueReauthenticationGrant();
  const replacement = await service.createReplacementRequest(principal("desktop"), {
    desktopInstallationId: DESKTOP_ID,
    displayName: "Replacement Mac",
    connectorPublicKey: Buffer.alloc(32, 8).toString("base64url"),
    keyAlgorithm: "Ed25519",
    grant,
    idempotencyKey: "441da5ac-6449-4626-b366-a927ef20fb9a",
  });
  assert.equal(replacement.state, "replacement_pending");
  assert.equal(repository.replacementGrantHash, codec.hashReauthenticationGrant(grant));

  await service.unbindConnector(principal("desktop"), {
    grant,
    idempotencyKey: "bf2b7b25-f613-4d09-b2a1-d8a51aa513af",
  });
  assert.equal(repository.unbindGrantHash, codec.hashReauthenticationGrant(grant));

  const accessToken = codec.issueAccessToken();
  await service.revokeCurrentPhoneInstallation(
    `Bearer ${accessToken}`,
    "d3e36747-b206-455d-98d1-f505a663570f",
  );
  assert.equal(repository.currentAccessTokenHash, codec.hashAccessToken(accessToken));
});

test("multi-device service requires explicit selection and preserves a chosen default", async () => {
  const repository = new FakeControlRepository();
  repository.devices = [
    accountDevice(),
    {
      ...accountDevice(),
      id: "20000000-0000-4000-8000-000000000002",
      deviceId: "hermes-20000000-0000-4000-8000-000000000002",
      generation: 2,
      desktopDisplayName: "Office Mac mini",
      isDefault: false,
    },
  ];
  const service = new AccountControlService(
    repository,
    new TokenCodec(HASH_KEY),
    () => new Date("2026-09-02T04:00:00.000Z"),
    3,
  );

  await assert.rejects(
    service.getBinding(principal("phone")),
    (error: unknown) => errorCode(error) === "HR-BIND-009",
  );
  assert.equal(
    (await service.resolveDevice(principal("phone"), repository.devices[1].deviceId)).id,
    repository.devices[1].id,
  );
  await assert.rejects(
    service.resolveDevice(principal("phone"), "missing-device"),
    (error: unknown) => errorCode(error) === "HR-BIND-011",
  );
  const selected = await service.selectDefaultDevice(
    principal("phone"),
    repository.devices[1].deviceId,
    "01ad901e-87d2-4fc7-825e-6408d4e0e7e8",
  );
  assert.equal(selected.isDefault, true);
  assert.equal(repository.selectedDefaultDeviceId, repository.devices[1].deviceId);
});

test("multi-device errors have stable recovery metadata and bilingual registry entries", async () => {
  const cases = [
    [accountErrors.deviceSelectionRequired(), "HR-BIND-009", false, "select_device"],
    [accountErrors.deviceCapacityReached(), "HR-BIND-010", false, "none"],
    [accountErrors.deviceNotFound(), "HR-BIND-011", false, "select_device"],
  ] as const;
  const registry = await readFile(resolve("../docs/ERROR_HANDLING.md"), "utf8");
  for (const [error, code, retryable, recoveryAction] of cases) {
    assert.equal(error.code, code);
    assert.equal(error.retryable, retryable);
    assert.equal(error.recoveryAction, recoveryAction);
    assert.equal(error.message.length > 0, true);
    const registryLine = registry.split("\n").find((line) => line.includes(`\`${code}\``));
    assert(registryLine);
    assert.match(registryLine, /[\u3400-\u9fff]/);
    assert.match(registryLine, /[A-Za-z]/);
  }
});

test("account control HTTP routes enforce recent authentication for managed phone revocation", async () => {
  const repository = new FakeControlRepository();
  repository.devices = [accountDevice()];
  const codec = new TokenCodec(HASH_KEY);
  const control = new AccountControlService(repository, codec);
  const accountService = {
    authenticate: async () => principal("desktop"),
  } as unknown as AccountService;
  const controller = new AccountHttpController(true, accountService, {
    controlEnabled: true,
    controlService: control,
    identityManagementEnabled: true,
    multiDeviceEnabled: true,
  });
  const grant = codec.issueReauthenticationGrant();
  const createResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("POST", {
      authorization: "Bearer test-access",
      "content-type": "application/json",
      "idempotency-key": "441da5ac-6449-4626-b366-a927ef20fb9a",
    }, JSON.stringify({
      desktopInstallationId: DESKTOP_ID,
      displayName: "Replacement Mac",
      connectorPublicKey: Buffer.alloc(32, 8).toString("base64url"),
      keyAlgorithm: "Ed25519",
      grant,
    })),
    createResponse.asServerResponse(),
    new URL("http://localhost/v2/connector-binding/replacement-requests"),
  );
  assert.equal(createResponse.status, 201);
  assert.equal((createResponse.json() as { state: string }).state, "replacement_pending");

  const confirmResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("POST", {
      authorization: "Bearer test-access",
      "idempotency-key": "bf2b7b25-f613-4d09-b2a1-d8a51aa513af",
    }),
    confirmResponse.asServerResponse(),
    new URL("http://localhost/v2/connector-binding/replacement-requests/30000000-0000-4000-8000-000000000003/confirm"),
  );
  assert.equal(confirmResponse.status, 200);
  assert.equal((confirmResponse.json() as { state: string }).state, "bound");

  const unbindResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("DELETE", {
      authorization: "Bearer test-access",
      "content-type": "application/json",
      "idempotency-key": "d3e36747-b206-455d-98d1-f505a663570f",
    }, JSON.stringify({ grant })),
    unbindResponse.asServerResponse(),
    new URL("http://localhost/v2/connector-binding"),
  );
  assert.equal(unbindResponse.status, 204);

  const currentResponse = new MemoryResponse();
  const accessToken = codec.issueAccessToken();
  await controller.handle(
    memoryRequest("DELETE", {
      authorization: `Bearer ${accessToken}`,
      "idempotency-key": "77f19703-0500-4281-b134-9b510ded8232",
    }),
    currentResponse.asServerResponse(),
    new URL("http://localhost/v2/installations/current"),
  );
  assert.equal(currentResponse.status, 204);

  const targetPhoneID = "879d7035-9ba5-456f-979a-98ab28ae89ec";
  const missingGrantResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("DELETE", {
      authorization: "Bearer test-access",
      "content-type": "application/json",
      "idempotency-key": "af3fc248-47ea-45b4-976a-aaef1fb6127e",
    }, JSON.stringify({})),
    missingGrantResponse.asServerResponse(),
    new URL(`http://localhost/v2/installations/${targetPhoneID}`),
  );
  assert.equal(missingGrantResponse.status, 400);
  assert.equal(
    (missingGrantResponse.json() as { error: { code: string } }).error.code,
    "HR-ACCOUNT-004",
  );
  assert.equal(repository.revokedAccountInstallationID, undefined);

  const managedPhoneResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("DELETE", {
      authorization: "Bearer test-access",
      "content-type": "application/json",
      "idempotency-key": "85d92f97-4404-4713-97f4-30a33da91997",
    }, JSON.stringify({ grant })),
    managedPhoneResponse.asServerResponse(),
    new URL(`http://localhost/v2/installations/${targetPhoneID}`),
  );
  assert.equal(managedPhoneResponse.status, 204);
  assert.equal(repository.revokedAccountInstallationID, targetPhoneID);
  assert.equal(repository.accountInstallationGrantHash, codec.hashReauthenticationGrant(grant));
  assert.equal(repository.accountInstallationRequiredKind, "phone");

  const devicesResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("GET", { authorization: "Bearer test-access" }),
    devicesResponse.asServerResponse(),
    new URL("http://localhost/v2/devices"),
  );
  assert.equal(devicesResponse.status, 200);
  assert.equal((devicesResponse.json() as { items: AccountDevice[] }).items[0].deviceId, accountDevice().deviceId);

  const defaultResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("POST", {
      authorization: "Bearer test-access",
      "idempotency-key": "21c6fca5-356e-4346-bfa8-b67ecdd2e09c",
    }),
    defaultResponse.asServerResponse(),
    new URL(`http://localhost/v2/devices/${accountDevice().deviceId}/select-default`),
  );
  assert.equal(defaultResponse.status, 200);
  assert.equal((defaultResponse.json() as { device: AccountDevice }).device.isDefault, true);

  const removeResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("DELETE", {
      authorization: "Bearer test-access",
      "content-type": "application/json",
      "idempotency-key": "ab52c266-e53e-4a77-9875-5adfc2f4d07d",
    }, JSON.stringify({ grant })),
    removeResponse.asServerResponse(),
    new URL(`http://localhost/v2/devices/${accountDevice().deviceId}`),
  );
  assert.equal(removeResponse.status, 204);
});

class FakeControlRepository implements AccountControlRepository {
  createIdempotency?: IdempotencyMaterial;
  createdPublicKey?: Buffer;
  proofMaterial?: BindingProofMaterial;
  keyProofRecorded = false;
  currentAccessTokenHash?: string;
  replacementGrantHash?: string;
  unbindGrantHash?: string;
  revokedAccountInstallationID?: string;
  accountInstallationGrantHash?: string;
  accountInstallationRequiredKind?: ManagedInstallation["kind"];
  devices: AccountDevice[] = [];
  selectedDefaultDeviceId?: string;

  async revokeCurrentPhoneInstallation(
    accessTokenHash: string,
    _idempotency: IdempotencyMaterial,
  ): Promise<CurrentInstallationRevocationResult> {
    this.currentAccessTokenHash = accessTokenHash;
    return { status: "completed" };
  }

  async listInstallations(_principal: AccountPrincipal): Promise<ManagedInstallation[]> {
    return [];
  }

  async revokeAccountInstallation(
    _principal: AccountPrincipal,
    targetInstallationId: string,
    grantTokenHash: string,
    _idempotency: IdempotencyMaterial,
    requiredKind?: ManagedInstallation["kind"],
  ): Promise<AccountInstallationRevocationResult> {
    this.revokedAccountInstallationID = targetInstallationId;
    this.accountInstallationGrantHash = grantTokenHash;
    this.accountInstallationRequiredKind = requiredKind;
    return { status: "completed" };
  }

  async getBinding(_principal: AccountPrincipal): Promise<BindingState> {
    return { state: "no_binding" };
  }

  async listDevices(_principal: AccountPrincipal): Promise<AccountDevice[]> {
    return this.devices;
  }

  async getDevice(_principal: AccountPrincipal, deviceId: string): Promise<AccountDevice | undefined> {
    return this.devices.find((device) => device.deviceId === deviceId);
  }

  async selectDefaultDevice(
    _principal: AccountPrincipal,
    deviceId: string,
  ): Promise<{ status: "completed"; device: AccountDevice } | { status: "not_found" }> {
    this.selectedDefaultDeviceId = deviceId;
    const device = this.devices.find((candidate) => candidate.deviceId === deviceId);
    return device
      ? { status: "completed", device: { ...device, isDefault: true } }
      : { status: "not_found" };
  }

  async createPendingBinding(
    _principal: AccountPrincipal,
    input: {
      bindingId: string;
      displayName: string;
      deviceId: string;
      publicKey: Buffer;
      publicKeyFingerprint: string;
      expiresAt: Date;
    },
    idempotency: IdempotencyMaterial,
  ): Promise<CreateBindingResult> {
    this.createIdempotency = idempotency;
    this.createdPublicKey = input.publicKey;
    return { status: "created", binding: candidate() };
  }

  async confirmPendingBinding(
    _principal: AccountPrincipal,
    _bindingId: string,
    _generation: number,
    _idempotency: IdempotencyMaterial,
  ): Promise<ConfirmBindingResult> {
    return { status: "activated", binding: activeBinding() };
  }

  async createReplacementRequest(
    _principal: AccountPrincipal,
    input: { grantTokenHash: string },
  ): Promise<CreateReplacementResult> {
    this.replacementGrantHash = input.grantTokenHash;
    return {
      status: "created",
      request: {
        id: "30000000-0000-4000-8000-000000000003",
        state: "replacement_pending",
        expiresAt: "2026-09-02T04:10:00.000Z",
        previousBinding: activeBinding(),
        candidate: { ...candidate(), generation: 2 },
      },
    };
  }

  async confirmReplacementRequest(): Promise<ConfirmReplacementResult> {
    return { status: "activated", binding: { ...activeBinding(), generation: 2 } };
  }

  async unbindConnector(
    _principal: AccountPrincipal,
    grantTokenHash: string,
  ): Promise<UnbindResult> {
    this.unbindGrantHash = grantTokenHash;
    return { status: "completed" };
  }

  async unbindDevice(
    _principal: AccountPrincipal,
    deviceId: string,
  ): Promise<UnbindResult> {
    this.devices = this.devices.filter((device) => device.deviceId !== deviceId);
    return { status: "completed" };
  }

  async loadBindingProofMaterial(
    _bindingId: string,
    _generation: number,
    _fingerprint: string,
  ): Promise<BindingProofMaterial | undefined> {
    return this.proofMaterial;
  }

  async recordBindingKeyProof(
    _bindingId: string,
    _generation: number,
    _fingerprint: string,
  ): Promise<boolean> {
    this.keyProofRecorded = true;
    return true;
  }

  async recordBindingHealth(): Promise<boolean> {
    return true;
  }

  async recordBindingDisconnected(): Promise<boolean> {
    return true;
  }

  async ingestAccountLifecycleEvent(): Promise<{ status: "stored" }> {
    return { status: "stored" };
  }

  async listAccountLifecycleEvents(): Promise<{
    events: [];
    nextCursor: number;
    hasMore: false;
  }> {
    return { events: [], nextCursor: 0, hasMore: false };
  }

  async markAccountLifecycleEvents(): Promise<number> {
    return 0;
  }
}

function principal(kind: "phone" | "desktop" | "browser"): AccountPrincipal {
  const desktop = kind === "desktop";
  const browser = kind === "browser";
  return {
    account: { id: "account-1" },
    installation: {
      id: desktop
        ? DESKTOP_ID
        : browser ? "8c4b5ac1-40c0-4ace-a8f4-10f65fdf13c4" : "b5791214-1583-4737-a809-b3f2f03b3c61",
      kind,
      platform: desktop ? "macos" : browser ? "web" : "android",
      displayName: desktop ? "Mac mini" : browser ? "Web browser" : "Phone",
    },
    sessionId: desktop ? "session-desktop" : browser ? "session-browser" : "session-phone",
    refreshFamilyId: desktop ? "family-desktop" : browser ? "family-browser" : "family-phone",
  };
}

function candidate(): BindingCandidate {
  return {
    id: BINDING_ID,
    generation: 1,
    deviceId: `hermes-${BINDING_ID}`,
    displayName: "Mac mini",
    publicKeyFingerprint: "a".repeat(64),
    state: "binding_pending",
    expiresAt: "2026-09-02T04:10:00.000Z",
    keyProved: false,
    healthVerified: false,
  };
}

function activeBinding(): ActiveBinding {
  return {
    id: BINDING_ID,
    generation: 1,
    deviceId: `hermes-${BINDING_ID}`,
    desktopDisplayName: "Mac mini",
    publicKeyFingerprint: "a".repeat(64),
    connector: { online: true },
    hermes: { reachable: true },
    gateway: { latencyMs: 1 },
    endToEnd: { healthy: true },
  };
}

function accountDevice(): AccountDevice {
  return { ...activeBinding(), access: "owner", isDefault: true };
}

function errorCode(error: unknown): unknown {
  return typeof error === "object" && error !== null && "code" in error
    ? (error as { code: unknown }).code
    : undefined;
}

function memoryRequest(
  method: string,
  headers: Record<string, string> = {},
  body = "",
): IncomingMessage {
  return {
    method,
    headers,
    socket: { remoteAddress: "127.0.0.1" },
    async *[Symbol.asyncIterator]() {
      if (body.length > 0) yield Buffer.from(body);
    },
  } as unknown as IncomingMessage;
}

class MemoryResponse {
  status = 0;
  body = "";
  writableEnded = false;

  asServerResponse(): ServerResponse {
    return this as unknown as ServerResponse;
  }

  writeHead(status: number): this {
    this.status = status;
    return this;
  }

  end(value?: string): this {
    this.body = value ?? "";
    this.writableEnded = true;
    return this;
  }

  json(): unknown {
    return JSON.parse(this.body);
  }
}
