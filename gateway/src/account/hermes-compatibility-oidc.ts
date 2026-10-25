import { createPublicKey, verify, type JsonWebKey } from "node:crypto";
import { CompatibilityError, compatibilityCommit } from "./hermes-compatibility-model.js";

const ISSUER = "https://token.actions.githubusercontent.com";
const JWKS = ISSUER + "/.well-known/jwks";
export const HERMES_COMPATIBILITY_WORKFLOW = ".github/workflows/hermes-compatibility.yml";
export const HERMES_COMPATIBILITY_PUBLISH_WORKFLOW = ".github/workflows/hermes-compatibility-publish.yml";
export interface CompatibilityCITrust {
  repository: string;
  repositoryId: string;
  audience: string;
}
export interface VerifiedCompatibilityJob { runId: string; runAttempt: string; sourceCommit: string }
export function compatibilityWorkerError() {
  return new CompatibilityError(401,"007","无法核实验证任务身份，请检查任务配置后重试。",
    "The validation job identity could not be verified. Review the job configuration and retry.",false,"review_job");
}
function segment(value: string): Buffer {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw compatibilityWorkerError();
  const bytes = Buffer.from(value,"base64url");
  if (bytes.toString("base64url") !== value) throw compatibilityWorkerError();
  return bytes;
}
function record(value: unknown): Record<string,unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw compatibilityWorkerError();
  return value as Record<string,unknown>;
}

/** JWT signature + fixed trust. Caller separately binds this to the persisted task and GitHub run metadata. */
export function verifyCompatibilityJobToken(token: string, keys: JsonWebKey[], trust: CompatibilityCITrust,
  expectedCommit: string, now = Date.now(), scope:"validation"|"publication"="validation"): VerifiedCompatibilityJob {
  try {
    compatibilityCommit(expectedCommit);
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(trust.repository) || !/^[1-9][0-9]*$/.test(trust.repositoryId)
        || !trust.audience || token.length > 16_384) throw compatibilityWorkerError();
    const parts = token.split(".");
    if (parts.length !== 3) throw compatibilityWorkerError();
    const header = record(JSON.parse(segment(parts[0]).toString("utf8")));
    const claims = record(JSON.parse(segment(parts[1]).toString("utf8")));
    if (header.alg !== "RS256" || typeof header.kid !== "string" || header.kid.length > 256
        || header.crit !== undefined) throw compatibilityWorkerError();
    const matching = keys.filter(key => key.kid === header.kid && key.kty === "RSA"
      && (key.alg === undefined || key.alg === "RS256") && (key.use === undefined || key.use === "sig"));
    if (matching.length !== 1) throw compatibilityWorkerError();
    const key = createPublicKey({key:matching[0],format:"jwk"});
    if ((key.asymmetricKeyDetails?.modulusLength ?? 0) < 2048
        || !verify("RSA-SHA256",Buffer.from(parts[0]+"."+parts[1]),key,segment(parts[2]))) throw compatibilityWorkerError();
    const seconds = Math.floor(now/1000), exp=claims.exp, iat=claims.iat, nbf=claims.nbf;
    if (!Number.isSafeInteger(exp) || !Number.isSafeInteger(iat) || (exp as number) <= seconds
        || (iat as number) > seconds+30 || (iat as number) < seconds-600
        || (exp as number)-(iat as number) > 600 || (exp as number) <= (iat as number)
        || (nbf !== undefined && (!Number.isSafeInteger(nbf) || (nbf as number)>seconds+30))) throw compatibilityWorkerError();
    const workflow=scope==="publication"?HERMES_COMPATIBILITY_PUBLISH_WORKFLOW:HERMES_COMPATIBILITY_WORKFLOW;
    const subject=scope==="publication"?`repo:${trust.repository}:environment:desktop-release`:`repo:${trust.repository}:ref:refs/heads/main`;
    if(scope==="publication"&&claims.environment!=="desktop-release")throw compatibilityWorkerError();
    if (claims.iss !== ISSUER || claims.aud !== trust.audience || claims.repository !== trust.repository
        || claims.repository_id !== trust.repositoryId || claims.ref !== "refs/heads/main"
        || claims.event_name !== "workflow_dispatch" || claims.runner_environment !== "github-hosted"
        || claims.workflow_ref !== `${trust.repository}/${workflow}@refs/heads/main`
        || claims.workflow_sha !== expectedCommit || claims.sha !== expectedCommit
        || claims.sub !== subject
        || typeof claims.run_id !== "string" || !/^[1-9][0-9]{0,19}$/.test(claims.run_id)
        || typeof claims.run_attempt !== "string" || !/^[1-9][0-9]{0,5}$/.test(claims.run_attempt)) throw compatibilityWorkerError();
    return {runId:claims.run_id,runAttempt:claims.run_attempt,sourceCommit:expectedCommit};
  } catch { throw compatibilityWorkerError(); }
}

/** Fixed issuer keys only; never follows jku/x5u from an untrusted JWT. One bounded refresh per minute. */
export class CompatibilityOIDCKeys {
  private keys: JsonWebKey[] = [];
  private fetchedAt = 0;
  private inFlight?: Promise<JsonWebKey[]>;
  constructor(private readonly fetcher: typeof fetch = fetch, private readonly now = Date.now) {}
  async load(): Promise<JsonWebKey[]> {
    if (this.keys.length && this.now()-this.fetchedAt < 60_000) return this.keys;
    if (this.inFlight) return this.inFlight;
    this.inFlight = this.download();
    try { return await this.inFlight; } finally { this.inFlight=undefined; }
  }
  private async download(): Promise<JsonWebKey[]> {
    try {
      const response=await this.fetcher(JWKS,{redirect:"error",signal:AbortSignal.timeout(10_000),headers:{accept:"application/json"}});
      if (!response.ok || !response.body) throw compatibilityWorkerError();
      const reader=response.body.getReader(),chunks:Uint8Array[]=[];let size=0;
      try {
        while(true){const next=await reader.read();if(next.done)break;size+=next.value.byteLength;if(size>64*1024)throw compatibilityWorkerError();chunks.push(next.value);}
      } finally {await reader.cancel().catch(()=>{});}
      const body=record(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      if(!Array.isArray(body.keys)||body.keys.length<1||body.keys.length>20)throw compatibilityWorkerError();
      this.keys=body.keys.map(k=>record(k) as JsonWebKey);this.fetchedAt=this.now();return this.keys;
    } catch {throw compatibilityWorkerError();}
  }
}
