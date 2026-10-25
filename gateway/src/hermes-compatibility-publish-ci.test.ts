import assert from "node:assert/strict";
import {generateKeyPairSync,sign,randomUUID} from "node:crypto";
import test from "node:test";
import {HermesCompatibilityPublishCI} from "./account/hermes-compatibility-publish-ci.js";
import {CompatibilityOIDCKeys,HERMES_COMPATIBILITY_PUBLISH_WORKFLOW} from "./account/hermes-compatibility-oidc.js";
import {CompatibilitySigner,type CompatibilityPublicationPolicy} from "./account/hermes-compatibility-publication.js";
import {compatibilityDigest} from "./account/hermes-compatibility-model.js";
import {HermesCompatibilityStore} from "./account/hermes-compatibility-store.js";

test("protected publication dispatch, claim and readback bind task, environment, exact workflow and commit",async()=>{
  const taskId=randomUUID(),commit="a".repeat(40),actor=randomUUID();
  const trust={repository:"example/hermes",repositoryId:"12345",audience:"https://relay.example/hermes-compatibility"};
  const rsa=generateKeyPairSync("rsa",{modulusLength:2048}),jwk={...rsa.publicKey.export({format:"jwk"}),kid:"test"};
  const signing=generateKeyPairSync("ed25519"),signer=new CompatibilitySigner("test",signing.privateKey.export({type:"pkcs8",format:"pem"}).toString(),"https://relay.example");
  const signed=signer.recommendation(7,[]),digest=compatibilityDigest(signed.payload);
  const policy:CompatibilityPublicationPolicy={origin:signer.origin,goCommit:commit,signingKeys:new Map([[signer.id,signing.publicKey]]),releaseKeys:new Map(),readInventory:()=>null,
    fetcher:(async()=>new Response(JSON.stringify(signed.envelope))) as typeof fetch};
  const task={id:taskId,goCommit:commit,createdAt:new Date(Date.now()-60_000),workflowRunId:null as string|null,workflowRunAttempt:null as string|null};
  let reserved=false,accepted=false,completed=0;
  const store={expirePublications:async()=>0,reservePublicationDispatch:async()=>{if(reserved)return null;reserved=true;return{id:taskId,goCommit:commit};},
    publicationDispatchAccepted:async()=>{accepted=true;},publicationTask:async()=>task,
    startPublication:async(_id:string,run:string,attempt:string)=>{task.workflowRunId=run;task.workflowRunAttempt=attempt;return{payload:signed.payload};},
    attachPublicationEnvelope:async()=>({revision:7,payloadSha256:digest,requestedBy:actor}),completePublication:async()=>{completed++;return{state:"published"};}} as unknown as HermesCompatibilityStore;
  const metadata={id:98765,run_attempt:1,head_sha:commit,event:"workflow_dispatch",head_branch:"main",path:HERMES_COMPATIBILITY_PUBLISH_WORKFLOW,
    display_title:`hermes-compatibility-publish-${taskId}`,repository:{id:12345},created_at:task.createdAt.toISOString()};
  const fetcher=(async(url,init)=>{
    assert.equal(init?.redirect,"error");
    if(String(url).endsWith("/dispatches")){assert.deepEqual(JSON.parse(init?.body as string),{ref:"main",inputs:{task_id:taskId}});return new Response(null,{status:204});}
    return new Response(JSON.stringify(String(url).includes("/git/ref/")?{object:{sha:commit}}:metadata));
  }) as typeof fetch;
  const keys=new CompatibilityOIDCKeys((async()=>new Response(JSON.stringify({keys:[jwk]}))) as typeof fetch);
  const ci=new HermesCompatibilityPublishCI(store,trust,"dispatch-only",policy,keys,fetcher);
  await Promise.all([ci.dispatchPending(),ci.dispatchPending()]);assert.equal(accepted,true);
  function bearer(environment="desktop-release"){
    const now=Math.floor(Date.now()/1000);
    const claims={iss:"https://token.actions.githubusercontent.com",aud:trust.audience,sub:`repo:${trust.repository}:environment:${environment}`,environment,
      repository:trust.repository,repository_id:trust.repositoryId,ref:"refs/heads/main",event_name:"workflow_dispatch",runner_environment:"github-hosted",
      workflow_ref:`${trust.repository}/${HERMES_COMPATIBILITY_PUBLISH_WORKFLOW}@refs/heads/main`,workflow_sha:commit,sha:commit,run_id:"98765",run_attempt:"1",iat:now-1,exp:now+299};
    const body=[{alg:"RS256",kid:"test"},claims].map(v=>Buffer.from(JSON.stringify(v)).toString("base64url")).join(".");
    return `Bearer ${body}.${sign("RSA-SHA256",Buffer.from(body),rsa.privateKey).toString("base64url")}`;
  }
  await assert.rejects(ci.claim(taskId,"Bearer hga_admin"));await assert.rejects(ci.claim(taskId,bearer("other")));
  await ci.claim(taskId,bearer());await ci.complete(taskId,bearer(),signed.envelope);assert.equal(completed,1);
  policy.fetcher=(async()=>new Response("{}",{status:503})) as typeof fetch;
  await assert.rejects(ci.complete(taskId,bearer(),signed.envelope));assert.equal(completed,1);
  metadata.display_title=`hermes-compatibility-publish-${randomUUID()}`;
  await assert.rejects(ci.claim(taskId,bearer()));
});
