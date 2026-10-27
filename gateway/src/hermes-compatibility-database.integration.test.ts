import assert from "node:assert/strict";
import { randomUUID, createHash,generateKeyPairSync } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";
import { Pool } from "pg";
import { HermesCompatibilityStore } from "./account/hermes-compatibility-store.js";
import { REQUIRED_COMPATIBILITY_CHECKS, COMPONENT_NAMES, type ValidationIdentity, type ValidationReport } from "./account/hermes-compatibility-model.js";
import type { AccountPrincipal } from "./account/model.js";
import {CompatibilitySigner,verifyCompatibilityEnvelope} from "./account/hermes-compatibility-publication.js";

const url = process.env.ACCOUNT_TEST_DATABASE_URL;
const hasCode = (code: string) => (e: unknown) => !!e && typeof e === "object" && "code" in e && e.code === code;
test("PostgreSQL compatibility: real grants, revocation, immutable identity, CAS, idempotency and approval evidence",
  { skip: url ? false : "set ACCOUNT_TEST_DATABASE_URL to disposable PostgreSQL 18" }, async () => {
    const schema = `compat_${randomUUID().replaceAll("-", "")}`;
    const admin = new Pool({ connectionString: url, max: 1 });
    const pool = new Pool({ connectionString: url, max: 8, options: `-c search_path=${schema}` });
    await admin.query(`CREATE SCHEMA "${schema}"`);
    try {
      for (const file of (await readdir(resolve("migrations"))).filter(f => /^\d{3}_.*\.sql$/.test(f)).sort()) {
        await pool.query(await readFile(resolve("migrations", file), "utf8"));
      }
      const store = new HermesCompatibilityStore(pool);
      async function account(email: string): Promise<AccountPrincipal> {
        const a = randomUUID(), i = randomUUID(), s = randomUUID(), family = randomUUID();
        await pool.query("INSERT INTO accounts(id) VALUES($1)", [a]);
        await pool.query(`INSERT INTO external_identities(id,account_id,provider,issuer,subject,email)
          VALUES($1,$2,'google','https://accounts.google.com',$3,$3)`, [randomUUID(),a,email]);
        await pool.query(`INSERT INTO installations(id,account_id,client_installation_id,kind,platform,display_name,app_version)
          VALUES($1,$2,$3,'desktop','macos','Synthetic','0.0.0')`, [i,a,randomUUID()]);
        await pool.query(`INSERT INTO account_sessions(id,account_id,installation_id,refresh_family_id,access_token_hash,access_expires_at)
          VALUES($1,$2,$3,$4,$5,now()+interval '1 hour')`, [s,a,i,family,createHash("sha256").update(s).digest("hex")]);
        return { account: { id: a, email }, installation: { id:i,kind:"desktop",platform:"macos",displayName:"Synthetic" },sessionId:s,refreshFamilyId:family };
      }
      const owner = await account("owner@example.invalid"), ordinary = await account("ordinary@example.invalid");
      assert.equal((await store.access(owner)).canManage, false);
      await assert.rejects(store.setAdministrator(ordinary.account.id,"owner@example.invalid",true),hasCode("HR-HERMESCOMPAT-001"));
      await store.setAdministrator(owner.account.id,"owner@example.invalid",true);
      assert.equal((await store.access(owner)).canManage,true);
      await assert.rejects(store.snapshot({ ...ordinary,account:{...ordinary.account,email:"owner@example.invalid"} }),hasCode("HR-HERMESCOMPAT-001"));
      const mutation = async () => ({ operationId: randomUUID(), expectedRevision: (await store.access(owner)).revision });
      const candidate = { version:"0.22.5",tag:"v0.22.5",tagObject:"a".repeat(40),targetCommit:"b".repeat(40),installerSha256:"c".repeat(64) };
      const command = await mutation();
      const [first, retry] = await Promise.all([store.discover(owner,command,candidate),store.discover(owner,command,candidate)]);
      assert.deepEqual(first,retry);
      await assert.rejects(store.discover(owner,command,{...candidate,targetCommit:"d".repeat(40)}),hasCode("HR-HERMESCOMPAT-003"));
      await assert.rejects(store.discover(owner,{...command,operationId:randomUUID()},candidate),hasCode("HR-HERMESCOMPAT-003"));
      const identity: ValidationIdentity = { schemaVersion:1,maintenancePolicy:"go-controlled-window-v1",candidateId:first.candidateId,version:candidate.version,
        sourceCommit:"e".repeat(40),targetCommit:candidate.targetCommit,goCommit:"f".repeat(40),suiteSha256:"a".repeat(64),
        installerSha256:candidate.installerSha256,environment:{os:"macos",arch:"arm64",osVersion:"26.0.1",installation:"legacy-venv-to-pm"},
        components:COMPONENT_NAMES.map(name=>({name,version:"1.2.3",artifactSha256:"b".repeat(64)})) };
      await assert.rejects(store.requestRun(owner,await mutation(),{...identity,targetCommit:"a".repeat(40)}),hasCode("HR-HERMESCOMPAT-003"));
      const requested = await store.requestRun(owner,await mutation(),identity);
      const deduped = await store.requestRun(owner,await mutation(),identity);
      assert.equal(deduped.runId,requested.runId);
      const dispatches = await Promise.all([store.reserveDispatch(),store.reserveDispatch()]);
      assert.equal(dispatches.filter(Boolean).length,1);
      assert.equal(dispatches.find(Boolean)?.id,requested.runId);
      assert.equal((await store.workerRun(requested.runId)).identity.goCommit,identity.goCommit);
      await assert.rejects(store.approve(owner,await mutation(),requested.runId,"b".repeat(64),identity.components),hasCode("HR-HERMESCOMPAT-004"));
      const report: ValidationReport = {schemaVersion:1,runId:requested.runId,identity,startedAt:"2026-10-25T00:00:00Z",
        finishedAt:"2026-10-25T00:01:00Z",outcome:"passed",checks:REQUIRED_COMPATIBILITY_CHECKS.map(name=>({name,status:"passed",evidenceSha256:"c".repeat(64)})),evidenceBundleSha256:"d".repeat(64)};
      await assert.rejects(store.completeRun(report),hasCode("HR-HERMESCOMPAT-003"));
      await store.startRun(requested.runId,"12345");
      await assert.rejects(store.startRun(requested.runId,"12345","2"),hasCode("HR-HERMESCOMPAT-002"));
      await assert.rejects(store.completeRun({...report,identity:{...identity,goCommit:"a".repeat(40)}}),hasCode("HR-HERMESCOMPAT-003"));
      await assert.rejects(store.completeRun({...report,checks:[]}),hasCode("HR-HERMESCOMPAT-004"));
      const completed = await store.completeRun(report);
      assert.equal((await store.completeRun(report)).reportSha256,completed.reportSha256);
      await assert.rejects(store.completeRun({...report,outcome:"failed"}),hasCode("HR-HERMESCOMPAT-003"));
      await assert.rejects(store.approve(owner,await mutation(),requested.runId,completed.reportSha256,[]),hasCode("HR-HERMESCOMPAT-004"));
      const approval = await mutation();
      const approved = await store.approve(owner,approval,requested.runId,completed.reportSha256,identity.components);
      assert.equal(approved.state,"approved"); // Never published by clicking approve.
      assert.deepEqual(await store.approve(owner,approval,requested.runId,completed.reportSha256,identity.components),approved);
      const snapshot = await store.snapshot(owner);
      assert.equal(snapshot.runs.length,1); assert.equal(snapshot.decisions.length,1);
      assert.equal(snapshot.audit.filter(x=>x.action==="decision_approved").length,1);
      const key=generateKeyPairSync("ed25519");
      const signer=new CompatibilitySigner("test",key.privateKey.export({type:"pkcs8",format:"pem"}).toString(),"https://relay.example");
      const policy={origin:signer.origin,goCommit:identity.goCommit,signingKeys:new Map([[signer.id,key.publicKey]]),releaseKeys:new Map([[signer.id,key.publicKey]]),readInventory:()=>null};
      await assert.rejects(store.preparePublication(owner,await mutation(),policy,()=>[]),hasCode("HR-HERMESCOMPAT-004"));
      const publishCommand=await mutation();
      const prepared=await store.preparePublication(owner,publishCommand,policy,()=>identity.components);
      assert.equal(prepared.state,"pending");
      assert.deepEqual(await store.preparePublication(owner,publishCommand,policy,()=>identity.components),prepared);
      await assert.rejects(store.recommendation(signer.origin),hasCode("HR-HERMESCOMPAT-006"));
      await assert.rejects(store.recommendation(signer.origin,prepared.revision),hasCode("HR-HERMESCOMPAT-006"));
      const claims=await store.startPublication(prepared.taskId,"12348","1");
      await store.attachPublicationEnvelope(prepared.taskId,signer.signRecommendation(claims.payload),policy);
      const preparedEnvelope=await store.recommendation(signer.origin,prepared.revision);
      assert.ok(verifyCompatibilityEnvelope(preparedEnvelope,new Map([[signer.id,key.publicKey]])));
      await assert.rejects(store.completePublication(owner,prepared.revision,prepared.payloadSha256,signer.origin,()=>[]),hasCode("HR-HERMESCOMPAT-004"));
      const published=await store.completePublication(owner,prepared.revision,prepared.payloadSha256,signer.origin,()=>identity.components);
      assert.equal(published.state,"published");
      assert.deepEqual(await store.recommendation(signer.origin),preparedEnvelope);
      assert.equal((await store.snapshot(owner)).decisions[0].state,"published");
      // Revocation immediately prevents serving the old recommendation, even before a new signature.
      await store.revoke(owner,await mutation(),approved.decisionId);
      await assert.rejects(store.recommendation(signer.origin),hasCode("HR-HERMESCOMPAT-006"));
      const withdrawn=await store.preparePublication(owner,await mutation(),policy,()=>identity.components);
      const withdrawnTask=await store.startPublication(withdrawn.taskId,"12349","1");
      await store.attachPublicationEnvelope(withdrawn.taskId,signer.signRecommendation(withdrawnTask.payload),policy);
      await store.completePublication(owner,withdrawn.revision,withdrawn.payloadSha256,signer.origin,()=>identity.components);
      const withdrawnPayload=verifyCompatibilityEnvelope(await store.recommendation(signer.origin),new Map([[signer.id,key.publicKey]])) as {entries:unknown[]};
      assert.deepEqual(withdrawnPayload.entries,[]);
      assert.equal((await store.snapshot(owner)).decisions[0].state,"revoked");
      const waiting=await store.preparePublication(owner,await mutation(),policy,()=>identity.components);
      const publicationDispatches=await Promise.all([store.reservePublicationDispatch(),store.reservePublicationDispatch()]);
      assert.equal(publicationDispatches.filter(Boolean).length,1);
      await store.publicationDispatchAccepted(waiting.taskId);
      await pool.query("UPDATE hermes_compatibility_snapshots SET dispatch_after=now()-interval '1 hour' WHERE task_id=$1",[waiting.taskId]);
      assert.equal(await store.reservePublicationDispatch(),null); // Waiting for environment approval must not dispatch duplicates.
      await pool.query("UPDATE hermes_compatibility_snapshots SET created_at=now()-interval '25 hours' WHERE task_id=$1",[waiting.taskId]);
      assert.equal(await store.expirePublications(),1);
      await assert.rejects(store.startPublication(waiting.taskId,"12350","1"),hasCode("HR-HERMESCOMPAT-003"));
      const slow = await store.requestRun(owner,await mutation(),{...identity,suiteSha256:"2".repeat(64)});
      await store.startRun(slow.runId,"12346");
      await pool.query("UPDATE hermes_compatibility_runs SET started_at=now()-interval '125 minutes' WHERE id=$1",[slow.runId]);
      assert.equal(await store.expireRuns(),0); // prepare + 105-minute validation + reporter, plus dispatch/queue margin.
      await pool.query("UPDATE hermes_compatibility_runs SET started_at=now()-interval '151 minutes' WHERE id=$1",[slow.runId]);
      assert.equal(await store.expireRuns(),1);
      assert.equal((await store.report(owner,slow.runId)).state,"infrastructure_failed");
      assert.equal((await store.report(owner,slow.runId)).report,null);
      const abandoned = await store.requestRun(owner,await mutation(),{...identity,suiteSha256:"3".repeat(64)});
      await pool.query("UPDATE hermes_compatibility_runs SET dispatch_attempts=3,dispatch_after=now()-interval '1 minute' WHERE id=$1",[abandoned.runId]);
      assert.equal(await store.expireRuns(),1);
      await assert.rejects(store.startRun(abandoned.runId,"12347"),hasCode("HR-HERMESCOMPAT-003"));
      const conflict = await store.discover(owner,await mutation(),{...candidate,targetCommit:"d".repeat(40)});
      assert.equal(conflict.identityConflict,true);
      assert.equal((await store.snapshot(owner)).candidates[0].targetCommit,candidate.targetCommit);
      await assert.rejects(store.requestRun(owner,await mutation(),identity),hasCode("HR-HERMESCOMPAT-003"));
      // Revocation is committed before any later request, including a replay with old credentials.
      await store.setAdministrator(owner.account.id,"owner@example.invalid",false);
      assert.equal((await store.access(owner)).canManage,false);
      await assert.rejects(store.approve(owner,approval,requested.runId,completed.reportSha256,identity.components),hasCode("HR-HERMESCOMPAT-001"));
      await store.setAdministrator(owner.account.id,"owner@example.invalid",true);
      await pool.query("UPDATE external_identities SET email='changed@example.invalid' WHERE account_id=$1",[owner.account.id]);
      assert.equal((await store.access(owner)).canManage,true); // Stable account ID survives email change.
      await pool.query("UPDATE account_sessions SET revoked_at=now() WHERE id=$1",[owner.sessionId]);
      await assert.rejects(store.snapshot(owner),hasCode("HR-AUTH-004"));
    } finally {
      await pool.end(); await admin.query(`DROP SCHEMA "${schema}" CASCADE`); await admin.end();
    }
  });
