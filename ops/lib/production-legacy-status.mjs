const ATTEMPTS = 20;

export async function verifyPreservedLegacyStatus({ fetchImpl, url, appToken, sleep, expectedState = null }) {
  for (let attempt = 0; attempt < ATTEMPTS; attempt += 1) {
    const state = await probeLegacyStatus(fetchImpl, url, appToken);
    if (state !== null && (expectedState === null || state === expectedState)) return state;
    if (attempt < ATTEMPTS - 1) await sleep(250);
  }
  return null;
}

async function probeLegacyStatus(fetchImpl, url, appToken) {
  try {
    const response = await fetchImpl(url, {
      headers: { "x-hermes-session-token": appToken },
      signal: AbortSignal.timeout(3_000),
    });
    let body;
    try {
      body = await response.json();
    } catch {
      return null;
    }
    if (response.ok && (body?.status === "ok" || (body?.overall === "ok" && body?.gateway_running === true))) {
      return "healthy";
    }
    if (response.status === 503 && body?.error === "device_offline") return "device_offline";
  } catch {}
  return null;
}
