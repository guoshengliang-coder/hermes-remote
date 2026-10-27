import assert from "node:assert/strict";
import { randomUUID,createHash } from "node:crypto";
import { Readable } from "node:stream";
import type { IncomingMessage,ServerResponse } from "node:http";
import test from "node:test";
import { AccountHttpController } from "./account/account-http-controller.js";
import { AccountService } from "./account/account-service.js";
import { HermesCompatibilityService } from "./account/hermes-compatibility-service.js";
import { HermesCompatibilityStore } from "./account/hermes-compatibility-store.js";
import { compatibilityErrors } from "./account/hermes-compatibility-model.js";
import { discoverOfficialHermes } from "./account/hermes-compatibility-discovery.js";
import { accountErrors,type AccountPrincipal } from "./account/model.js";

test("compatibility HTTP uses native bearer authorization, stays default off and localizes without raw values", async () => {
  const actor:AccountPrincipal={account:{id:randomUUID()},installation:{id:randomUUID(),platform:"macos",kind:"desktop",displayName:"Test"},sessionId:randomUUID(),refreshFamilyId:randomUUID()};
  let canManage=false, discoveries=0;
  const store={
    access:async()=>({schemaVersion:1,revision:0,canManage}),
    snapshot:async()=>{if(!canManage)throw compatibilityErrors.forbidden();return {schemaVersion:1,revision:0,candidates:[],runs:[],decisions:[],audit:[]};},
  } as unknown as HermesCompatibilityStore;
  const compatibility = new HermesCompatibilityService(store,[],async()=>{discoveries++;throw compatibilityErrors.unavailable();});
  const auth={authenticate:async(header:unknown)=>{if(header!=="Bearer test")throw accountErrors.sessionExpired();return actor;}} as unknown as AccountService;
  const controller=new AccountHttpController(true,auth,{hermesCompatibility:compatibility});
  async function call(path:string,method="GET",body?:unknown,header:string|undefined="Bearer test",target=controller) {
    const req=Readable.from(body===undefined?[]:[JSON.stringify(body)]) as IncomingMessage;
    req.method=method;req.headers={"content-type":"application/json",...(header?{authorization:header}:{cookie:"hga=fake"})};
    let status=0,data="",headers:Record<string,string>={};
    const res={writeHead:(s:number,h:Record<string,string>)=>{status=s;headers=h;},end:(v:string)=>{data=v;}} as unknown as ServerResponse;
    await target.handle(req,res,new URL(`/v2/hermes-compatibility/${path}`,"https://gateway.invalid"));
    return {status,body:JSON.parse(data),headers};
  }
  assert.equal((await call("access","GET",undefined,"Bearer test",new AccountHttpController(true,auth))).status,503);
  assert.equal((await call("snapshot","GET",undefined,"invalid")).status,401);
  assert.equal((await call("snapshot","GET",undefined,"")).status,401);
  assert.equal((await call("access")).body.canManage,false);
  const forbidden=await call("snapshot");
  assert.equal(forbidden.status,403);assert.equal(forbidden.body.error.code,"HR-HERMESCOMPAT-001");
  assert.match(forbidden.body.error.summaryZh,/管理权限/);assert.match(forbidden.body.error.summaryEn,/cannot manage/);
  assert.equal(forbidden.body.error.action,"refresh_account");assert.equal(forbidden.body.error.retryable,false);
  const mutation={operationId:randomUUID(),expectedRevision:0};
  assert.equal((await call("sync","POST",mutation)).status,403);assert.equal(discoveries,0);
  assert.equal((await call("publish","POST",mutation)).status,403);
  assert.equal((await call("publish","POST",mutation,"")).status,401);
  // Public recommendation reads need no account token, but absent signing configuration stays unavailable.
  assert.equal((await call("recommendations","GET",undefined,"")).body.error.code,"HR-HERMESCOMPAT-006");
  assert.equal((await call("snapshots/12","GET",undefined,"")).body.error.code,"HR-HERMESCOMPAT-006");
  canManage=true;
  const snapshot=await call("snapshot");assert.equal(snapshot.status,200);assert.equal(snapshot.headers["cache-control"],"no-store");
  const raw=await call("sync","POST",{...mutation,email:"owner@example.invalid",command:"secret-never-echo"});
  assert.equal(raw.status,400);assert.equal(JSON.stringify(raw).includes("secret-never-echo"),false);
  assert.equal((await call("sync","POST",mutation)).body.error.code,"HR-HERMESCOMPAT-005");
  assert.equal(discoveries,1);
  assert.equal((await call("reports","POST",{outcome:"passed"})).status,404);
});

test("official discovery dereferences annotated tags, hashes fixed installer and never accepts redirects or arbitrary URLs",async()=>{
  const tag="a".repeat(40),commit="b".repeat(40),bytes=Buffer.from("synthetic official installer");
  const requests:string[]=[];
  const replies:unknown[]=[{draft:false,prerelease:false,tag_name:"v0.22.5"},{object:{type:"tag",sha:tag}},
    {object:{type:"commit",sha:commit}},{type:"file",encoding:"base64",size:bytes.length,content:bytes.toString("base64")+"\n"}];
  const fetcher=(async(input:unknown,init:RequestInit)=>{
    assert.equal(init.redirect,"error");assert.ok(init.signal);assert.equal((init.headers as Record<string,string>).authorization,undefined);
    requests.push(String(input));return new Response(JSON.stringify(replies.shift()),{status:200});
  }) as typeof fetch;
  assert.deepEqual(await discoverOfficialHermes(fetcher),{version:"0.22.5",tag:"v0.22.5",tagObject:tag,targetCommit:commit,installerSha256:createHash("sha256").update(bytes).digest("hex")});
  assert.ok(requests.every(url=>url.startsWith("https://api.github.com/repos/NousResearch/hermes-agent/")));
  assert.ok(requests.at(-1)?.endsWith(`?ref=${commit}`));
  for(const value of [{draft:false,prerelease:true,tag_name:"v0.22.5"},{draft:false,prerelease:false,tag_name:"v0.22.5\n"},"secret raw body"]){
    await assert.rejects(discoverOfficialHermes((async()=>new Response(JSON.stringify(value))) as typeof fetch),e=>
      !!e&&typeof e==="object"&&"code" in e&&e.code==="HR-HERMESCOMPAT-005"&&!String(e).includes("secret"));
  }
});
