import assert from "node:assert/strict";
import { generateKeyPairSync,sign,type JsonWebKey } from "node:crypto";
import test from "node:test";
import { CompatibilityOIDCKeys,verifyCompatibilityJobToken,HERMES_COMPATIBILITY_WORKFLOW,HERMES_COMPATIBILITY_PUBLISH_WORKFLOW } from "./account/hermes-compatibility-oidc.js";

const {privateKey,publicKey}=generateKeyPairSync("rsa",{modulusLength:2048});
const key:JsonWebKey={...publicKey.export({format:"jwk"}),kid:"test-key",use:"sig",alg:"RS256"};
const trust={repository:"example/hermes",repositoryId:"12345",audience:"https://gateway.invalid/hermes-compatibility"};
const commit="a".repeat(40), now=Date.parse("2026-10-25T00:00:00Z");
const claims={iss:"https://token.actions.githubusercontent.com",aud:trust.audience,sub:`repo:${trust.repository}:ref:refs/heads/main`,
  repository:trust.repository,repository_id:trust.repositoryId,ref:"refs/heads/main",event_name:"workflow_dispatch",runner_environment:"github-hosted",
  workflow_ref:`${trust.repository}/${HERMES_COMPATIBILITY_WORKFLOW}@refs/heads/main`,workflow_sha:commit,sha:commit,
  run_id:"654321",run_attempt:"1",iat:now/1000-5,nbf:now/1000-5,exp:now/1000+295};
function token(change:Record<string,unknown>={},header:Record<string,unknown>={}){
  const body=[{alg:"RS256",kid:"test-key",typ:"JWT",...header},{...claims,...change}].map(v=>Buffer.from(JSON.stringify(v)).toString("base64url")).join(".");
  return body+"."+sign("RSA-SHA256",Buffer.from(body),privateKey).toString("base64url");
}
const rejected=(e:unknown)=>!!e&&typeof e==="object"&&"code" in e&&e.code==="HR-HERMESCOMPAT-007";
test("worker authentication binds issuer, workflow, repository identity, exact GO commit, run and short lifetime",()=>{
  assert.deepEqual(verifyCompatibilityJobToken(token(),[key],trust,commit,now),{runId:"654321",runAttempt:"1",sourceCommit:commit});
  for(const change of [
    {iss:"https://attacker.invalid"},{aud:"other"},{aud:[trust.audience]},{repository:"other/hermes"},{repository_id:"999"},
    {sub:`repo:${trust.repository}:pull_request`},{ref:"refs/pull/1/merge"},{event_name:"pull_request_target"},{runner_environment:"self-hosted"},
    {workflow_ref:`${trust.repository}/.github/workflows/other.yml@refs/heads/main`},{workflow_sha:"b".repeat(40)},
    {sha:"b".repeat(40)},{run_id:"../other"},{run_id:654321},{run_attempt:"0"},{run_attempt:1},
    {exp:now/1000},{exp:now/1000+3600},{iat:now/1000+90},{iat:now/1000-601},{nbf:now/1000+90},
  ])assert.throws(()=>verifyCompatibilityJobToken(token(change),[key],trust,commit,now),rejected);
  for(const header of [{alg:"none"},{alg:"HS256"},{kid:"unknown"},{crit:["custom"]}]) {
    assert.throws(()=>verifyCompatibilityJobToken(token({},header),[key],trust,commit,now),rejected);
  }
});
test("worker rejects unsigned/bad/oversized JWTs and duplicate keys without echoing bearer values",()=>{
  const valid=token();
  for(const value of ["Bearer hga_secret",valid.slice(0,-10),valid.replace(valid.split(".")[1],Buffer.from(JSON.stringify({...claims,run_id:"777"})).toString("base64url")),"x".repeat(16385)]) {
    assert.throws(()=>verifyCompatibilityJobToken(value,[key],trust,commit,now),e=>rejected(e)&&!String(e).includes("hga_secret"));
  }
  assert.throws(()=>verifyCompatibilityJobToken(valid,[key,key],trust,commit,now),rejected);
  const other=generateKeyPairSync("rsa",{modulusLength:2048}).publicKey.export({format:"jwk"});
  assert.throws(()=>verifyCompatibilityJobToken(valid,[{...other,kid:"test-key"}],trust,commit,now),rejected);
});
test("publication proof requires the protected desktop-release environment and cannot reuse validation identity",()=>{
  const changes={workflow_ref:`${trust.repository}/${HERMES_COMPATIBILITY_PUBLISH_WORKFLOW}@refs/heads/main`,
    sub:`repo:${trust.repository}:environment:desktop-release`,environment:"desktop-release"};
  assert.equal(verifyCompatibilityJobToken(token(changes),[key],trust,commit,now,"publication").runId,"654321");
  assert.throws(()=>verifyCompatibilityJobToken(token(),[key],trust,commit,now,"publication"),rejected);
  assert.throws(()=>verifyCompatibilityJobToken(token(changes),[key],trust,commit,now,"validation"),rejected);
  for(const change of [{environment:"other"},{environment:undefined},{sub:claims.sub},{workflow_ref:claims.workflow_ref}])
    assert.throws(()=>verifyCompatibilityJobToken(token({...changes,...change}),[key],trust,commit,now,"publication"),rejected);
});
test("JWKS is fetched only from the official origin, without redirects or token-controlled URLs",async()=>{
  let count=0;
  const loader=new CompatibilityOIDCKeys((async(url,init)=>{
    count++;assert.equal(url,"https://token.actions.githubusercontent.com/.well-known/jwks");
    assert.equal(init?.redirect,"error");assert.ok(init?.signal);
    return new Response(JSON.stringify({keys:[key]}));
  }) as typeof fetch,()=>now);
  const [a,b]=await Promise.all([loader.load(),loader.load()]);
  assert.equal(count,1);assert.deepEqual(a,b);await loader.load();assert.equal(count,1);
  const bad=new CompatibilityOIDCKeys((async()=>new Response("x".repeat(65537))) as typeof fetch);
  await assert.rejects(bad.load(),rejected);
});
