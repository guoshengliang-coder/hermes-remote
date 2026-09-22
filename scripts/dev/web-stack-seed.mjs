// Seeds the local Web stack (scripts/dev/web-stack.sh): signs the development email in through
// the real Web login once, so its account exists, then binds a mock "Mac" to that account and
// writes the account-mode Connector credential. Local development values only.
//
// Usage: node scripts/dev/web-stack-seed.mjs  (configured through the environment below)
import { createHash, generateKeyPairSync, randomUUID } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import pg from "pg";

const origin = required("WEB_STACK_ORIGIN");
const email = required("WEB_STACK_EMAIL");
const sink = required("HR_DEV_EMAIL_SINK");
const databaseUrl = required("WEB_STACK_DATABASE_URL");
const credentialFile = required("WEB_STACK_CREDENTIAL_FILE");
const deviceId = process.env.WEB_STACK_DEVICE_ID ?? "dev-mac";

const jar = new Map();
const accountId = await signIn();
await bindMac(accountId);
console.log(`seeded account ${accountId} with device ${deviceId}`);

async function signIn() {
  const session = await call("GET", "/v2/web/session");
  const csrf = (await session.json()).csrfToken;
  const challengeResponse = await call("POST", "/v2/web/auth/email/challenges", csrf, { email });
  if (challengeResponse.status !== 202) throw new Error(`challenge failed: ${await challengeResponse.text()}`);
  const { challenge } = await challengeResponse.json();
  const code = latestCode();
  const exchange = await call("POST", "/v2/web/auth/email/exchange", csrf, {
    challengeId: challenge.challengeId,
    email,
    code,
    displayName: "web-stack seed",
  }, { "idempotency-key": randomUUID() });
  if (exchange.status !== 200) throw new Error(`exchange failed: ${await exchange.text()}`);
  return (await exchange.json()).account.id;
}

async function bindMac(account) {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const publicDer = publicKey.export({ format: "der", type: "spki" });
  const rawPublicKey = publicDer.subarray(publicDer.byteLength - 32);
  const privateDer = privateKey.export({ format: "der", type: "pkcs8" });
  const seed = privateDer.subarray(privateDer.byteLength - 32);
  const fingerprint = createHash("sha256").update(rawPublicKey).digest("hex");
  const desktop = randomUUID();
  const bindingId = randomUUID();
  const client = new pg.Client({ connectionString: databaseUrl });
  await client.connect();
  try {
    await client.query("BEGIN");
    await client.query(
      `UPDATE connector_bindings SET status = 'revoked', revoked_at = now()
        WHERE account_id = $1 AND status = 'active'`,
      [account],
    );
    await client.query(
      `INSERT INTO installations
         (id, account_id, client_installation_id, kind, platform, display_name, app_version)
       VALUES ($1, $2, $1, 'desktop', 'macos', 'Dev Mac', 'web-stack')`,
      [desktop, account],
    );
    await client.query(
      `INSERT INTO connector_bindings
         (id, account_id, desktop_installation_id, display_name, device_id, public_key,
          key_algorithm, public_key_fingerprint, generation, status, activated_at)
       VALUES ($1, $2, $3, 'Dev Mac', $4, $5, 'Ed25519', $6, 1, 'active', now())`,
      [bindingId, account, desktop, deviceId, rawPublicKey, fingerprint],
    );
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    await client.end();
  }
  writeFileSync(credentialFile, JSON.stringify({
    schemaVersion: 1,
    bindingId,
    generation: 1,
    publicKeyFingerprint: fingerprint,
    privateKey: seed.toString("base64url"),
  }), { mode: 0o600 });
}

function latestCode() {
  const lines = readFileSync(sink, "utf8").trim().split("\n");
  const code = JSON.parse(lines.at(-1)).code;
  if (!/^\d{6}$/.test(code ?? "")) throw new Error("no login code captured");
  return code;
}

async function call(method, path, csrf, body, extra = {}) {
  const response = await fetch(`${origin}${path}`, {
    method,
    headers: {
      cookie: [...jar].map(([name, value]) => `${name}=${value}`).join("; "),
      ...(method === "GET" ? { "sec-fetch-site": "same-origin" } : {
        origin,
        "sec-fetch-site": "same-origin",
        "x-hermes-csrf": csrf,
        "content-type": "application/json",
      }),
      ...extra,
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  for (const setCookie of response.headers.getSetCookie()) {
    const [pair] = setCookie.split(";");
    const separator = pair.indexOf("=");
    const name = pair.slice(0, separator);
    const value = pair.slice(separator + 1);
    if (value) jar.set(name, value);
    else jar.delete(name);
  }
  return response;
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} must be set`);
  return value;
}
