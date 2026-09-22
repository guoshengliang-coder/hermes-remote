// Development-only preload for the Gateway (`node --import ./scripts/dev/resend-capture.mjs`):
// intercepts the transactional-email request to Resend and appends the login code to a local file
// instead, so email sign-in works on a machine with no mail provider. Nothing in gateway/src knows
// about it; production never loads it.
//
// Refuses to load unless HR_DEV_EMAIL_SINK names the file and the Gateway's Web origin is a
// loopback host, so it cannot be left switched on in front of real users by accident.
import { appendFileSync } from "node:fs";
import { randomBytes } from "node:crypto";

const sink = process.env.HR_DEV_EMAIL_SINK;
const webOrigin = process.env.ACCOUNT_WEB_ORIGIN ?? "";
if (!sink) throw new Error("resend-capture: HR_DEV_EMAIL_SINK must name the file that receives codes");
let host = "";
try {
  host = new URL(webOrigin).hostname;
} catch {
  // handled below
}
if (host !== "localhost" && host !== "127.0.0.1") {
  throw new Error("resend-capture: only allowed when ACCOUNT_WEB_ORIGIN is a localhost origin");
}

const realFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  if (!url.startsWith("https://api.resend.com/")) return realFetch(input, init);
  const body = JSON.parse(String(init?.body ?? "{}"));
  const code = /\b(\d{6})\b/.exec(String(body.text ?? ""))?.[1];
  const to = Array.isArray(body.to) ? body.to.join(",") : String(body.to ?? "");
  appendFileSync(sink, `${JSON.stringify({ at: new Date().toISOString(), to, code })}\n`, { mode: 0o600 });
  return new Response(JSON.stringify({ id: `dev_${randomBytes(8).toString("hex")}` }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
};
