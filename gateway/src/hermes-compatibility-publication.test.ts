import assert from "node:assert/strict";
import { generateKeyPairSync,sign,randomUUID,createPublicKey } from "node:crypto";
import {readFile} from "node:fs/promises";
import test from "node:test";
import { CompatibilitySigner,RECOMMENDATION_PURPOSE,RELEASE_INVENTORY_PURPOSE,parseRecommendation,
  verifyCompatibilityEnvelope,releasedInventory,verifyPublicationReadback,type CompatibilityPublicationPolicy } from "./account/hermes-compatibility-publication.js";
import { COMPONENT_NAMES,compatibilityDigest,type ValidationIdentity } from "./account/hermes-compatibility-model.js";
const pair=generateKeyPairSync("ed25519");
const signer=new CompatibilitySigner("compat-test",pair.privateKey.export({type:"pkcs8",format:"pem"}).toString(),"https://relay.example");
const keys=new Map([[signer.id,pair.publicKey]]);
const now=new Date("2026-10-25T00:00:00Z");
const identity:ValidationIdentity={schemaVersion:1,maintenancePolicy:"go-controlled-window-v1",candidateId:randomUUID(),version:"0.22.5",sourceCommit:"a".repeat(40),
  targetCommit:"b".repeat(40),goCommit:"c".repeat(40),suiteSha256:"d".repeat(64),installerSha256:"e".repeat(64),
  environment:{os:"macos",arch:"arm64",osVersion:"26.0.0",installation:"legacy-venv-to-pm"},
  components:COMPONENT_NAMES.map(name=>({name,version:"1.0.0",artifactSha256:"f".repeat(64)}))};
const decision={schemaVersion:1,id:randomUUID(),runId:randomUUID(),identity,reportSha256:"0".repeat(64)};
function envelope(value:unknown){const bytes=Buffer.from(JSON.stringify(value));return {payload:bytes.toString("base64url"),keyId:signer.id,algorithm:"Ed25519",signature:sign(null,bytes,pair.privateKey).toString("base64url")};}

test("shared TypeScript/Swift fixture preserves exact signature and payload contract",async()=>{
  const fixture=JSON.parse(await readFile(new URL("../../scripts/fixtures/hermes-compatibility/signed-recommendation.json",import.meta.url),"utf8"));
  const key=createPublicKey({key:{kty:"OKP",crv:"Ed25519",x:fixture.publicKey},format:"jwk"});
  const payload=parseRecommendation(verifyCompatibilityEnvelope(fixture.envelope,new Map([[fixture.envelope.keyId,key]])),"https://fixture.invalid",new Date(fixture.now));
  assert.equal(payload.revision,7);assert.equal(payload.entries[0].identity.version,"0.22.5");
});

test("recommendations bind signed bytes, purpose, origin, exact identity, revision and lifetime",()=>{
  const signed=signer.recommendation(7,[decision],now);
  assert.deepEqual(parseRecommendation(verifyCompatibilityEnvelope(signed.envelope,keys),signer.origin,now),signed.payload);
  assert.throws(()=>verifyCompatibilityEnvelope({...signed.envelope,signature:"a".repeat(86)},keys));
  assert.throws(()=>verifyCompatibilityEnvelope({...signed.envelope,keyId:"unknown"},keys));
  assert.throws(()=>verifyCompatibilityEnvelope({...signed.envelope,payload:signed.envelope.payload+"="},keys));
  assert.throws(()=>parseRecommendation({...signed.payload,purpose:RELEASE_INVENTORY_PURPOSE},signer.origin,now));
  assert.throws(()=>parseRecommendation(signed.payload,"https://other.example",now));
  assert.throws(()=>parseRecommendation({...signed.payload,revision:0},signer.origin,now));
  assert.throws(()=>parseRecommendation({...signed.payload,entries:[decision,decision]},signer.origin,now));
  assert.throws(()=>parseRecommendation(signed.payload,signer.origin,new Date("2026-10-26T00:00:00Z")));
  assert.throws(()=>parseRecommendation({...signed.payload,expiresAt:"2026-10-27T00:00:00Z"},signer.origin,now));
  assert.throws(()=>parseRecommendation({...signed.payload,issuedAt:"2026-10-25T01:00:00Z"},signer.origin,now));
});

test("release inventory has independent purpose and requires an exact published component combination",()=>{
  const inventory={schemaVersion:1,purpose:RELEASE_INVENTORY_PURPOSE,origin:signer.origin,issuedAt:"2026-10-25T00:00:00Z",expiresAt:"2026-10-26T00:00:00Z",combinations:[identity.components]};
  assert.deepEqual(releasedInventory(envelope(inventory),keys,signer.origin,identity,now),identity.components);
  assert.deepEqual(releasedInventory(envelope(inventory),keys,signer.origin,{...identity,components:identity.components.map(c=>({...c,artifactSha256:"1".repeat(64)}))},now),[]);
  assert.throws(()=>releasedInventory(envelope({...inventory,purpose:RECOMMENDATION_PURPOSE}),keys,signer.origin,identity,now));
  assert.throws(()=>releasedInventory(envelope(inventory),keys,signer.origin,identity,new Date("2026-10-26T00:00:00Z")));
  assert.throws(()=>releasedInventory(envelope({...inventory,combinations:[identity.components.slice(1)]}),keys,signer.origin,identity,now));
});

test("signed duplicate JSON keys are rejected consistently with the Swift consumer",()=>{
  const payload=signer.recommendation(7,[decision],now).payload;
  const raw=Buffer.from(JSON.stringify(payload).replace('"revision":7','"revision":1,"revision":7'));
  const ambiguous={payload:raw.toString("base64url"),keyId:signer.id,algorithm:"Ed25519",signature:sign(null,raw,pair.privateKey).toString("base64url")};
  assert.throws(()=>verifyCompatibilityEnvelope(ambiguous,keys));
  const nested=Buffer.from(JSON.stringify(payload).replace('"schemaVersion":1','"schemaVersion":1,"schema\\u0056ersion":1'));
  assert.throws(()=>verifyCompatibilityEnvelope({...ambiguous,payload:nested.toString("base64url"),signature:sign(null,nested,pair.privateKey).toString("base64url")},keys));
});

test("public readback verifies exact envelope before publication and rejects redirects/errors/mismatches",async()=>{
  const signed=signer.recommendation(9,[decision]);
  const requests:string[]=[];
  const publication:CompatibilityPublicationPolicy={origin:signer.origin,goCommit:identity.goCommit,signingKeys:keys,releaseKeys:keys,readInventory:()=>null,fetcher:(async(url,init)=>{
    requests.push(String(url));assert.equal(init?.redirect,"error");assert.ok(init?.signal);
    assert.equal((init?.headers as Record<string,string>).authorization,undefined);
    return new Response(JSON.stringify(signed.envelope));
  }) as typeof fetch};
  await verifyPublicationReadback(publication,9,compatibilityDigest(signed.payload));
  assert.equal(requests[0],"https://relay.example/v2/hermes-compatibility/snapshots/9");
  await assert.rejects(verifyPublicationReadback(publication,8,compatibilityDigest(signed.payload)));
  await assert.rejects(verifyPublicationReadback({...publication,fetcher:(async()=>new Response("{}",{status:503})) as typeof fetch},9,compatibilityDigest(signed.payload)));
  await assert.rejects(verifyPublicationReadback({...publication,fetcher:(async()=>new Response(JSON.stringify({...signed.envelope,signature:"a".repeat(86)}))) as typeof fetch},9,compatibilityDigest(signed.payload)));
});
