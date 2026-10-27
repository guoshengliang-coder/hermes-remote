import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { Pool } from "pg";
import { HermesCompatibilityStore } from "../account/hermes-compatibility-store.js";
import { compatibilityErrors, CompatibilityError } from "../account/hermes-compatibility-model.js";

/** Explicit operator action. No automatic email-based role assignment on login or startup. */
export async function manageCompatibilityAdministrator(args: string[], environment = process.env) {
  if (args.length !== 5 || !["grant","revoke"].includes(args[0]) || args[1] !== "--account-id" || args[3] !== "--expected-verified-email") {
    throw compatibilityErrors.invalid();
  }
  const connectionString = environment.ACCOUNT_DATABASE_URL ?? (environment.ACCOUNT_DATABASE_URL_FILE
    ? (await readFile(environment.ACCOUNT_DATABASE_URL_FILE,"utf8")).trim() : undefined);
  if (!connectionString || ![undefined,"0","1"].includes(environment.ACCOUNT_DATABASE_SSL)) throw compatibilityErrors.unavailable();
  const pool = new Pool({ connectionString, max: 1, connectionTimeoutMillis: 3000,
    ssl: environment.ACCOUNT_DATABASE_SSL === "1" ? {rejectUnauthorized:true} : undefined });
  try {
    return await new HermesCompatibilityStore(pool).setAdministrator(args[2],args[4],args[0] === "grant");
  } finally { await pool.end(); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  manageCompatibilityAdministrator(process.argv.slice(2)).then(result=>console.log(JSON.stringify(result))).catch(error=>{
    const safe = error instanceof CompatibilityError ? error : compatibilityErrors.unavailable();
    console.error(JSON.stringify({ code:safe.code,summaryZh:safe.message,summaryEn:safe.summaryEn,retryable:safe.retryable,action:safe.action }));
    process.exitCode=1;
  });
}
