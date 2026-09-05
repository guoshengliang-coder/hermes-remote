const CHECK_PATTERN = /^[a-z][a-z0-9_]{0,79}$/;

export class GatewayCandidateSmokeError extends Error {
  constructor(check) {
    const safeCheck = CHECK_PATTERN.test(check) ? check : "unexpected";
    super(`smoke_check=${safeCheck}`);
    this.name = "GatewayCandidateSmokeError";
    this.check = safeCheck;
  }
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
