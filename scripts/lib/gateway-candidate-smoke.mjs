const CHECK_PATTERN = /^[a-z][a-z0-9_]{0,79}$/;

export class GatewayCandidateSmokeError extends Error {
  constructor(check) {
    const safeCheck = CHECK_PATTERN.test(check) ? check : "unexpected";
    super(`smoke_check=${safeCheck}`);
    this.name = "GatewayCandidateSmokeError";
    this.check = safeCheck;
  }
}

export function gatewaySmokeRoutePolicy(routeMode = "private") {
  if (!new Set(["private", "public"]).has(routeMode)) {
    throw new GatewayCandidateSmokeError("configuration");
  }
  return {
    routeMode,
    verifyPrivateSurface: routeMode === "private",
  };
}

export function gatewayRuntimePolicy(runtimeMode = "disabled") {
  if (runtimeMode === "disabled") {
    return {
      runtimeMode,
      readiness: {
        status: "ready",
        checks: {
          config: "ok",
          database: "disabled",
          migrations: "not_required",
          postgresql: "not_required",
        },
      },
      accountAuthEnabled: false,
      accountProviders: null,
      bindingEnabled: false,
      desktopBootstrapRuntimeContract: null,
    };
  }
  if (new Set(["email_otp", "email_binding", "email_multi_device", "email_identity_web", "email_sharing"]).has(runtimeMode)) {
    const bindingEnabled = runtimeMode !== "email_otp";
    return {
      runtimeMode,
      readiness: {
        status: "ready",
        checks: {
          config: "ok",
          database: "ok",
          migrations: "ok",
          postgresql: "supported",
        },
      },
      accountAuthEnabled: true,
      accountProviders: ["email_otp"],
      bindingEnabled,
      desktopBootstrapRuntimeContract: bindingEnabled ? "hermes-serve-v1" : null,
    };
  }
  throw new GatewayCandidateSmokeError("configuration");
}

export function verifyGatewayCapabilities(capabilities, runtimePolicy, expectedVersion) {
  const binding = capabilities?.binding;
  const desktopBootstrap = capabilities?.desktopBootstrap;
  const multiDeviceEnabled = new Set(["email_multi_device", "email_identity_web", "email_sharing"]).has(runtimePolicy.runtimeMode);
  const identityWebEnabled = new Set(["email_identity_web", "email_sharing"]).has(runtimePolicy.runtimeMode);
  const sharingEnabled = runtimePolicy.runtimeMode === "email_sharing";
  const valid = capabilities?.accountAuth?.enabled === runtimePolicy.accountAuthEnabled
    && (runtimePolicy.accountProviders === null
      || JSON.stringify(capabilities.accountAuth?.providers) === JSON.stringify(runtimePolicy.accountProviders))
    && capabilities?.accountAuth?.identityManagement === identityWebEnabled
    && capabilities?.accountAuth?.webAccountCenter === identityWebEnabled
    && (identityWebEnabled
      ? capabilities?.accountAuth?.webSessions === true
      : capabilities?.accountAuth?.webSessions !== true)
    && capabilities?.accountAuth?.accountDeletion !== true
    && binding?.enabled === runtimePolicy.bindingEnabled
    && binding?.replacement === runtimePolicy.bindingEnabled
    && binding?.maxActiveConnectorsPerAccount === (multiDeviceEnabled ? 3 : 1)
    && (multiDeviceEnabled
      ? binding?.supportsDeviceSelection === true
      : !Object.hasOwn(binding ?? {}, "supportsDeviceSelection"))
    && (sharingEnabled
      ? (binding?.supportsDeviceSharing === true
        && binding?.maxSharedDevices === 10
        && binding?.maxGranteesPerDevice === 5)
      : (!Object.hasOwn(binding ?? {}, "supportsDeviceSharing")
        && !Object.hasOwn(binding ?? {}, "maxSharedDevices")
        && !Object.hasOwn(binding ?? {}, "maxGranteesPerDevice")))
    && (runtimePolicy.desktopBootstrapRuntimeContract === null
      ? desktopBootstrap === undefined
      : desktopBootstrap?.runtimeContract === runtimePolicy.desktopBootstrapRuntimeContract)
    && capabilities?.legacy?.appTokenAccepted === true
    && capabilities?.legacy?.connectorTokenAccepted === true
    && capabilities?.server?.version === expectedVersion;
  if (!valid) throw new GatewayCandidateSmokeError("capabilities");
}

export async function runGatewaySmokeCheck(check, operation) {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof GatewayCandidateSmokeError) throw error;
    throw new GatewayCandidateSmokeError(check);
  }
}

export async function waitForGatewayForwarding({
  baseUrl,
  appToken,
  fetchImpl = fetch,
  sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  attempts = 20,
  intervalMilliseconds = 250,
  statusMode = "mock",
}) {
  if (typeof baseUrl !== "string" || !/^https?:\/\//.test(baseUrl)
      || typeof appToken !== "string" || appToken.length < 32
      || !Number.isSafeInteger(attempts) || attempts < 1 || attempts > 80
      || !Number.isSafeInteger(intervalMilliseconds) || intervalMilliseconds < 0 || intervalMilliseconds > 2_000
      || !new Set(["mock", "live"]).has(statusMode)) {
    throw new GatewayCandidateSmokeError("configuration");
  }

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    let response;
    try {
      response = await fetchImpl(`${baseUrl}/api/status`, {
        headers: { "x-hermes-session-token": appToken },
        signal: AbortSignal.timeout(1_000),
      });
    } catch {
      if (attempt < attempts - 1) await sleep(intervalMilliseconds);
      continue;
    }

    if (response.status === 401 || response.status === 403) {
      throw new GatewayCandidateSmokeError("rest_forward_auth");
    }
    if (!response.ok) {
      if (response.status >= 500 && response.status <= 599) {
        if (attempt < attempts - 1) await sleep(intervalMilliseconds);
        continue;
      }
      throw new GatewayCandidateSmokeError(`rest_forward_http_${safeStatus(response.status)}`);
    }

    let body;
    try {
      body = await response.json();
    } catch {
      throw new GatewayCandidateSmokeError("rest_forward_response");
    }
    const mockStatus = statusMode === "mock"
      && body?.status === "ok"
      && body?.version === "mock-hermes"
      && Object.keys(body).length === 2;
    const liveStatus = statusMode === "live"
      && body !== null
      && typeof body === "object"
      && !Array.isArray(body)
      && Object.keys(body).length > 0;
    if (mockStatus || liveStatus) {
      return body;
    }
    throw new GatewayCandidateSmokeError("rest_forward_contract");
  }
  throw new GatewayCandidateSmokeError("rest_forward_ready_timeout");
}

function safeStatus(value) {
  return Number.isSafeInteger(value) && value >= 100 && value <= 599 ? String(value) : "unknown";
}
