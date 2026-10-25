import { createPrivateKey, createPublicKey, sign, verify, type KeyObject } from "node:crypto";
import { openSync, closeSync, fstatSync, readFileSync, constants } from "node:fs";
import { compatibilityCommit, compatibilityDigest, compatibilityErrors, compatibilityHash, compatibilityRevision, compatibilityUUID,
  parseTestedComponents, parseValidationIdentity, type TestedComponent, type ValidationIdentity } from "./hermes-compatibility-model.js";

export const RECOMMENDATION_PURPOSE = "hermes-go.compatibility.recommendations.v1";
export const RELEASE_INVENTORY_PURPOSE = "hermes-go.compatibility.released-components.v1";
export interface CompatibilityEnvelope { payload: string; keyId: string; algorithm: "Ed25519"; signature: string }
export interface CompatibilityDecision {
  schemaVersion: 1; id: string; runId: string; identity: ValidationIdentity; reportSha256: string;
}
export interface CompatibilityRecommendation {
  schemaVersion: 1; purpose: typeof RECOMMENDATION_PURPOSE; origin: string; revision: number;
  issuedAt: string; expiresAt: string; entries: CompatibilityDecision[];
}
function exact(value: unknown, keys: string[]): Record<string,unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length
      || Object.keys(value).some(key => !keys.includes(key))) throw compatibilityErrors.invalid();
  return value as Record<string,unknown>;
}
export function compatibilityOrigin(value: unknown): string {
  try { if (typeof value !== "string") throw 0;
    const url = new URL(value);
    if (url.protocol !== "https:" || url.origin !== value || url.username || url.password || url.port) throw 0;
    return value;
  } catch { throw compatibilityErrors.invalid(); }
}
function keyID(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9._-]{1,64}$/.test(value)) throw compatibilityErrors.invalid();
  return value;
}
function decode(value: unknown, max: number): Buffer {
  if (typeof value !== "string" || !value.length || value.length > max * 2 || !/^[A-Za-z0-9_-]+$/.test(value)) throw compatibilityErrors.invalid();
  const bytes = Buffer.from(value,"base64url");
  if (bytes.length > max || bytes.toString("base64url") !== value) throw compatibilityErrors.invalid();
  return bytes;
}
function lifetime(value: Record<string,unknown>, now: Date) {
  for (const key of ["issuedAt","expiresAt"]) {
    if (typeof value[key] !== "string" || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(value[key] as string)
        || !Number.isFinite(Date.parse(value[key] as string))
        || new Date(value[key] as string).toISOString()!==(value[key] as string).replace("Z",".000Z")) throw compatibilityErrors.invalid();
  }
  const issued = Date.parse(value.issuedAt as string), expires = Date.parse(value.expiresAt as string);
  if (issued > now.getTime() + 30_000 || expires <= now.getTime() || expires <= issued
      || expires - issued > 24 * 60 * 60_000) throw compatibilityErrors.evidenceIncomplete();
}
export function parseCompatibilityDecision(value: unknown): CompatibilityDecision {
  const v = exact(value,["schemaVersion","id","runId","identity","reportSha256"]);
  if (v.schemaVersion !== 1) throw compatibilityErrors.invalid();
  return {schemaVersion:1,id:compatibilityUUID(v.id),runId:compatibilityUUID(v.runId),
    identity:parseValidationIdentity(v.identity),reportSha256:compatibilityHash(v.reportSha256)};
}
export function parseRecommendation(value: unknown, origin: string, now = new Date()): CompatibilityRecommendation {
  const v = exact(value,["schemaVersion","purpose","origin","revision","issuedAt","expiresAt","entries"]);
  if (v.schemaVersion !== 1 || v.purpose !== RECOMMENDATION_PURPOSE || v.origin !== compatibilityOrigin(origin)
      || !Array.isArray(v.entries) || v.entries.length > 100 || compatibilityRevision(v.revision) === 0) throw compatibilityErrors.invalid();
  lifetime(v,now);
  const entries = v.entries.map(parseCompatibilityDecision);
  if (new Set(entries.map(e=>e.id)).size !== entries.length) throw compatibilityErrors.invalid();
  return {...v,entries} as unknown as CompatibilityRecommendation;
}
export function makeCompatibilityRecommendation(origin:string,revision:number,entries:unknown[],now=new Date()):CompatibilityRecommendation {
  const format=(date:Date)=>date.toISOString().replace(/\.\d{3}Z$/,"Z");
  return parseRecommendation({schemaVersion:1,purpose:RECOMMENDATION_PURPOSE,origin,revision,
    issuedAt:format(now),expiresAt:format(new Date(now.getTime()+24*60*60_000)),entries},origin,now);
}
/** Keys are configured by the release operator, never supplied by Desktop or a validation job. */
export function verifyCompatibilityEnvelope(raw: unknown, keys: ReadonlyMap<string,KeyObject>): unknown {
  const envelope = exact(raw,["payload","keyId","algorithm","signature"]);
  if (envelope.algorithm !== "Ed25519") throw compatibilityErrors.invalid();
  const key = keys.get(keyID(envelope.keyId)), payload = decode(envelope.payload,256*1024), signature = decode(envelope.signature,64);
  if (!key || key.asymmetricKeyType !== "ed25519" || signature.length !== 64 || !verify(null,payload,key,signature)) throw compatibilityErrors.evidenceIncomplete();
  try { return unambiguousPayload(new TextDecoder("utf-8",{fatal:true}).decode(payload)); }
  catch { throw compatibilityErrors.invalid(); }
}
/** JSON.parse keeps the last duplicate key; Swift must never interpret the same signed bytes differently. */
function unambiguousPayload(text:string):unknown {
  const value:unknown=JSON.parse(text); // Validate JSON grammar before walking its key tokens.
  const tokens=text.match(/"(?:\\[\s\S]|[^"\\])*"|true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?|[{}\[\],:]/g)??[];
  if(tokens.length>32768)throw compatibilityErrors.invalid();
  let index=0;
  function take(expected:string){if(tokens[index++]!==expected)throw compatibilityErrors.invalid();}
  function walk(depth:number){
    if(depth>32)throw compatibilityErrors.invalid();
    const token=tokens[index++];
    if(token==="{"){
      const keys=new Set<string>();
      if(tokens[index]!=="}")while(true){
        const key:unknown=JSON.parse(tokens[index++]);
        if(typeof key!=="string"||keys.has(key))throw compatibilityErrors.invalid();
        keys.add(key);take(":");walk(depth+1);
        if(tokens[index]!==",")break;index++;
      }
      take("}");
    }else if(token==="["){
      if(tokens[index]!=="]")while(true){walk(depth+1);if(tokens[index]!==",")break;index++;}
      take("]");
    }else if(token===undefined||["}","]",",",":"].includes(token))throw compatibilityErrors.invalid();
  }
  walk(0);if(index!==tokens.length)throw compatibilityErrors.invalid();return value;
}
export function releasedInventory(raw: unknown, keys: ReadonlyMap<string,KeyObject>, origin: string,
  identity: ValidationIdentity, now = new Date()): TestedComponent[] {
  const v = exact(verifyCompatibilityEnvelope(raw,keys),["schemaVersion","purpose","origin","issuedAt","expiresAt","combinations"]);
  if (v.schemaVersion !== 1 || v.purpose !== RELEASE_INVENTORY_PURPOSE || v.origin !== compatibilityOrigin(origin)
      || !Array.isArray(v.combinations) || v.combinations.length > 100) throw compatibilityErrors.evidenceIncomplete();
  lifetime(v,now);
  const combinations = v.combinations.map(parseTestedComponents);
  return combinations.find(c=>compatibilityDigest(c)===compatibilityDigest(identity.components)) ?? [];
}
export class CompatibilitySigner {
  readonly publicKey: KeyObject;
  private readonly privateKey: KeyObject;
  constructor(readonly id: string, pem: string, readonly origin: string) {
    keyID(id); compatibilityOrigin(origin);
    this.privateKey = createPrivateKey(pem); this.publicKey = createPublicKey(this.privateKey);
    if (this.privateKey.asymmetricKeyType !== "ed25519") throw compatibilityErrors.invalid();
  }
  recommendation(revision: number, rawEntries: unknown[], now = new Date()): {payload:CompatibilityRecommendation;envelope:CompatibilityEnvelope} {
    const payload = makeCompatibilityRecommendation(this.origin,revision,rawEntries,now);
    return {payload,envelope:this.signRecommendation(payload,now)};
  }
  signRecommendation(raw:unknown,now=new Date()):CompatibilityEnvelope {
    const payload=parseRecommendation(raw,this.origin,now);
    const bytes = Buffer.from(JSON.stringify(payload));
    if (bytes.length > 256*1024) throw compatibilityErrors.publicationIncomplete();
    const envelope:CompatibilityEnvelope = {payload:bytes.toString("base64url"),keyId:this.id,algorithm:"Ed25519",
      signature:sign(null,bytes,this.privateKey).toString("base64url")};
    // Publication verifies the actual serialized bytes before they become visible.
    parseRecommendation(verifyCompatibilityEnvelope(envelope,new Map([[this.id,this.publicKey]])),this.origin,now);
    return envelope;
  }
}

/** Production Gateway policy: public trust and an immutable release inventory, never a private key. */
export interface CompatibilityPublicationPolicy {
  origin:string;
  goCommit:string;
  signingKeys:ReadonlyMap<string,KeyObject>;
  releaseKeys:ReadonlyMap<string,KeyObject>;
  readInventory:()=>unknown;
  fetcher?:typeof fetch;
}
export function readCompatibilityPublicationFile(path:string):unknown {
  let fd:number|undefined;
  try {
    fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
    const stat=fstatSync(fd);
    if(!stat.isFile()||stat.size>512*1024||stat.nlink!==1||(stat.mode&0o022)!==0)throw compatibilityErrors.invalid();
    return JSON.parse(readFileSync(fd,"utf8"));
  }catch{throw compatibilityErrors.publicationIncomplete();}
  finally{if(fd!==undefined)closeSync(fd);}
}
export function parseCompatibilityPublicationConfiguration(raw:unknown):CompatibilityPublicationPolicy {
  const v=exact(raw,["schemaVersion","origin","goCommit","signingKeys","releaseKeys","inventoryFile"]);
  if(v.schemaVersion!==1
    ||typeof v.inventoryFile!=="string"||!v.inventoryFile.startsWith("/")||v.inventoryFile.includes("\0"))throw compatibilityErrors.invalid();
  function parseKeys(raw:unknown){
    if(!Array.isArray(raw)||raw.length<1||raw.length>8)throw compatibilityErrors.invalid();
    const keys=new Map<string,KeyObject>();
    for(const value of raw){
      const k=exact(value,["id","publicKeyPem"]),id=keyID(k.id);
      if(typeof k.publicKeyPem!=="string"||k.publicKeyPem.length>4096||keys.has(id))throw compatibilityErrors.invalid();
      const key=createPublicKey(k.publicKeyPem);
      if(key.asymmetricKeyType!=="ed25519")throw compatibilityErrors.invalid();
      keys.set(id,key);
    }return keys;
  }
  const path=v.inventoryFile;
  return {origin:compatibilityOrigin(v.origin),goCommit:compatibilityCommit(v.goCommit),signingKeys:parseKeys(v.signingKeys),releaseKeys:parseKeys(v.releaseKeys),
    readInventory:()=>readCompatibilityPublicationFile(path)};
}
export async function verifyPublicationReadback(publication:Pick<CompatibilityPublicationPolicy,"origin"|"signingKeys"|"fetcher">,revision:number,digest:string):Promise<void> {
  compatibilityRevision(revision);compatibilityHash(digest);
  try {
    const response=await (publication.fetcher??fetch)(`${publication.origin}/v2/hermes-compatibility/snapshots/${revision}`,
      {redirect:"error",signal:AbortSignal.timeout(15_000),headers:{accept:"application/json","cache-control":"no-cache"}});
    if(!response.ok||!response.body)throw compatibilityErrors.publicationIncomplete();
    const reader=response.body.getReader(),chunks:Uint8Array[]=[];let size=0;
    try{while(true){const next=await reader.read();if(next.done)break;size+=next.value.length;if(size>512*1024)throw compatibilityErrors.publicationIncomplete();chunks.push(next.value);}}
    finally{await reader.cancel().catch(()=>{});}
    const raw=JSON.parse(Buffer.concat(chunks).toString("utf8"));
    const payload=parseRecommendation(verifyCompatibilityEnvelope(raw,publication.signingKeys),publication.origin);
    if(payload.revision!==revision||compatibilityDigest(payload)!==digest)throw compatibilityErrors.publicationIncomplete();
  }catch{throw compatibilityErrors.publicationIncomplete();}
}
