import assert from "node:assert/strict";
import {randomUUID,generateKeyPairSync,sign} from "node:crypto";
import test from "node:test";
import {HermesCompatibilityCI} from "./account/hermes-compatibility-ci.js";
import {CompatibilityOIDCKeys,HERMES_COMPATIBILITY_WORKFLOW} from "./account/hermes-compatibility-oidc.js";
import {HermesCompatibilityStore} from "./account/hermes-compatibility-store.js";
import {COMPONENT_NAMES,REQUIRED_COMPATIBILITY_CHECKS,type ValidationIdentity,type ValidationReport} from "./account/hermes-compatibility-model.js";
import {parseCompatibilityCIConfiguration} from "./account/hermes-compatibility-config.js";

const trust={repository:"example/hermes",repositoryId:"12345",audience:"https://gateway.invalid/hermes-compatibility"};
const commit="a".repeat(40),taskId=randomUUID(),candidateId=randomUUID();
const identity:ValidationIdentity={schemaVersion:1,candidateId,version:"0.22.5",sourceCommit:"b".repeat(40),targetCommit:"c".repeat(40),goCommit:commit,
  suiteSha256:"d".repeat(64),installerSha256:"e".repeat(64),environment:{os:"macos",arch:"arm64",osVersion:"26.0.1",installation:"legacy-venv-to-pm"},
  components:COMPONENT_NAMES.map(name=>({name,version:"1.2.3",artifactSha256:"f".repeat(64)}))};
const {privateKey,publicKey}=generateKeyPairSync("rsa",{modulusLength:2048});
const jwk={...publicKey.export({format:"jwk"}),kid:"fixture"};
function bearer(){
  const now=Math.floor(Date.now()/1000);
  const claims={iss:"https://token.actions.githubusercontent.com",aud:trust.audience,sub:`repo:${trust.repository}:ref:refs/heads/main`,
    repository:trust.repository,repository_id:trust.repositoryId,ref:"refs/heads/main",event_name:"workflow_dispatch",runner_environment:"github-hosted",
    workflow_ref:`${trust.repository}/${HERMES_COMPATIBILITY_WORKFLOW}@refs/heads/main`,workflow_sha:commit,sha:commit,
    run_id:"98765",run_attempt:"1",iat:now-1,exp:now+299};
  const body=[{alg:"RS256",kid:"fixture"},claims].map(v=>Buffer.from(JSON.stringify(v)).toString("base64url")).join(".");
  return `Bearer ${body}.${sign("RSA-SHA256",Buffer.from(body),privateKey).toString("base64url")}`;
}
test("CI configuration only enables fixed repository recipes and never supplies scripts, commands or credentials",()=>{
  const {candidateId:_candidate,version:_version,targetCommit:_target,installerSha256:_installer,...recipeIdentity}=identity;
  const config={schemaVersion:1,trust:{...trust,repository:"guoshengliang-coder/hermes-remote"},recipes:[{id:"macos-arm64-legacy",identity:recipeIdentity}]};
  assert.equal(parseCompatibilityCIConfiguration(JSON.stringify(config)).recipes[0].identity.goCommit,commit);
  const rejected=(e:unknown)=>!!e&&typeof e==="object"&&"code" in e&&e.code==="HR-HERMESCOMPAT-002";
  for(const change of [{command:"anything"},{trust:{...config.trust,repository:"other/repo"}},{trust:{...config.trust,audience:"http://gateway.invalid/hermes-compatibility"}},
    {recipes:[...config.recipes,...config.recipes]},{recipes:[{...config.recipes[0],identity:{...recipeIdentity,script:"secret"}}]},
    {recipes:[{...config.recipes[0],identity:{...recipeIdentity,goCommit:"main"}}]}]){
    assert.throws(()=>parseCompatibilityCIConfiguration(JSON.stringify({...config,...change})),rejected);
  }
});
test("CI dispatch only sends the persisted task ID to a fixed workflow at the expected main commit",async()=>{
  const requests:Array<{url:string;body:unknown}>=[];let reserved=true;
  const store={expireRuns:async()=>0,reserveDispatch:async()=>{if(!reserved)return null;reserved=false;return{id:taskId,identity};}} as unknown as HermesCompatibilityStore;
  const fetcher=(async(url,init)=>{
    assert.equal(init?.redirect,"error");assert.equal((init?.headers as Record<string,string>).authorization,"Bearer dispatch-test");
    requests.push({url:String(url),body:init?.body?JSON.parse(init.body as string):null});
    return init?.method==="POST"?new Response(null,{status:204}):new Response(JSON.stringify({object:{sha:commit}}));
  }) as typeof fetch;
  const ci=new HermesCompatibilityCI(store,trust,"dispatch-test",undefined,fetcher);
  await Promise.all([ci.dispatchPending(),ci.dispatchPending()]);
  assert.equal(requests.length,2);
  assert.ok(requests[1].url.endsWith("/actions/workflows/hermes-compatibility.yml/dispatches"));
  assert.deepEqual(requests[1].body,{ref:"main",inputs:{run_id:taskId}});
});
test("worker proof additionally binds GitHub run inputs and the store's already-claimed run",async()=>{
  const createdAt=new Date(Date.now()-60_000);let started=0,completed=0;
  const metadata={id:98765,run_attempt:1,head_sha:commit,event:"workflow_dispatch",head_branch:"main",path:HERMES_COMPATIBILITY_WORKFLOW,
    display_title:`hermes-compatibility-${taskId}`,repository:{id:12345},created_at:createdAt.toISOString()};
  const run={id:taskId,identity,state:"queued",workflowRunId:null as string|null,workflowRunAttempt:null as string|null,createdAt};
  const store={workerRun:async()=>run,startRun:async(_id:string,id:string,attempt:string)=>{started++;run.workflowRunId=id;run.workflowRunAttempt=attempt;},
    completeRun:async()=>{completed++;return{reportSha256:"a".repeat(64)};}} as unknown as HermesCompatibilityStore;
  const keys=new CompatibilityOIDCKeys((async()=>new Response(JSON.stringify({keys:[jwk]}))) as typeof fetch);
  const ci=new HermesCompatibilityCI(store,trust,"dispatch-test",keys,(async()=>new Response(JSON.stringify(metadata))) as typeof fetch);
  const reject=(e:unknown)=>!!e&&typeof e==="object"&&"code" in e&&e.code==="HR-HERMESCOMPAT-007";
  await assert.rejects(ci.claim(taskId,"Bearer hga_admin"),reject);assert.equal(started,0);
  assert.deepEqual(await ci.claim(taskId,bearer()),{schemaVersion:1,runId:taskId,identity});assert.equal(started,1);
  const report:ValidationReport={schemaVersion:1,runId:taskId,identity,startedAt:"2026-10-25T00:00:00Z",finishedAt:"2026-10-25T00:01:00Z",outcome:"passed",
    checks:REQUIRED_COMPATIBILITY_CHECKS.map(name=>({name,status:"passed",evidenceSha256:"b".repeat(64)})),evidenceBundleSha256:"c".repeat(64)};
  await ci.complete(taskId,bearer(),report);assert.equal(completed,1);
  await assert.rejects(ci.complete(taskId,bearer(),{...report,runId:randomUUID()}),reject);
  run.workflowRunId="other";await assert.rejects(ci.complete(taskId,bearer(),report),reject);assert.equal(completed,1);
  for(const [field,bad] of Object.entries({display_title:`hermes-compatibility-${randomUUID()}`,head_sha:"b".repeat(40),event:"push",path:"other.yml",run_attempt:2,repository:{id:555}})){
    const old=(metadata as Record<string,unknown>)[field];(metadata as Record<string,unknown>)[field]=bad;
    await assert.rejects(ci.claim(taskId,bearer()),reject);(metadata as Record<string,unknown>)[field]=old;
  }
});
