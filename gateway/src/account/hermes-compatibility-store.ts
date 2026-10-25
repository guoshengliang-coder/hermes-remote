import { randomUUID } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import { accountErrors, type AccountPrincipal } from "./model.js";
import { makeCompatibilityRecommendation,parseRecommendation,verifyCompatibilityEnvelope,
  type CompatibilityPublicationPolicy,type CompatibilityEnvelope } from "./hermes-compatibility-publication.js";
import {
  compatibilityCommit, compatibilityDigest, compatibilityErrors, compatibilityHash,
  compatibilityRevision, compatibilityUUID, compatibilityVersion, parseValidationIdentity,
  parseValidationReport, requireApprovableReport,
  type TestedComponent, type ValidationIdentity, type ValidationReport,
} from "./hermes-compatibility-model.js";

export interface OfficialCandidate {
  version: string; tag: string; tagObject: string; targetCommit: string; installerSha256: string;
}
export interface CompatibilityMutation { operationId: string; expectedRevision: number }
export class HermesCompatibilityStore {
  constructor(private readonly pool: Pool) {}

  async access(actor: AccountPrincipal) {
    return this.transaction(async (db, revision) => {
      await this.requireSession(db, actor);
      return { schemaVersion: 1, canManage: await this.isAdmin(db, actor.account.id), revision };
    });
  }
  async snapshot(actor: AccountPrincipal) {
    return this.transaction(async (db, revision) => {
      await this.requireAdmin(db, actor);
      const candidates = await db.query(`SELECT id,version,tag,tag_object AS "tagObject",target_commit AS "targetCommit",
        installer_sha256 AS "installerSha256",identity_conflict AS "identityConflict",discovered_at AS "discoveredAt"
        FROM hermes_compatibility_candidates ORDER BY discovered_at DESC LIMIT 25`);
      const runs = await db.query(`SELECT id,candidate_id AS "candidateId",identity,state,created_at AS "createdAt",
        started_at AS "startedAt",finished_at AS "finishedAt",report_sha256 AS "reportSha256"
        FROM hermes_compatibility_runs ORDER BY created_at DESC LIMIT 25`);
      const decisions = await db.query(`SELECT id,run_id AS "runId",content,content_sha256 AS "contentSha256",state,
        approved_at AS "approvedAt",revoked_at AS "revokedAt" FROM hermes_compatibility_decisions ORDER BY approved_at DESC LIMIT 25`);
      const audit = await db.query(`SELECT id::text,revision::text,action,record_id AS "recordId",occurred_at AS "occurredAt"
        FROM hermes_compatibility_audit ORDER BY id DESC LIMIT 50`);
      const publications=await db.query(`SELECT task_id AS id,revision::text,state,workflow_run_id AS "workflowRunId",
        envelope IS NOT NULL AS signed,created_at AS "createdAt",published_at AS "publishedAt"
        FROM hermes_compatibility_snapshots ORDER BY revision DESC LIMIT 10`);
      return { schemaVersion: 1, revision, candidates: candidates.rows, runs: runs.rows, decisions: decisions.rows, audit: audit.rows,publications:publications.rows };
    });
  }
  async report(actor: AccountPrincipal, runId: string) {
    compatibilityUUID(runId);
    return this.transaction(async (db) => {
      await this.requireAdmin(db, actor);
      const result = await db.query("SELECT id,state,identity,report,report_sha256 AS \"reportSha256\" FROM hermes_compatibility_runs WHERE id=$1", [runId]);
      if (!result.rows[0]) throw accountErrors.resourceNotFound();
      return result.rows[0];
    });
  }

  /** Operator CLI only. The verified email is a one-time cross-check, never a role lookup key. */
  async setAdministrator(accountId: string, expectedVerifiedEmail: string, granted: boolean) {
    compatibilityUUID(accountId);
    if (typeof expectedVerifiedEmail !== "string" || expectedVerifiedEmail.length > 254
        || !/^[^\s@]+@[^\s@]+$/.test(expectedVerifiedEmail)) throw compatibilityErrors.invalid();
    return this.transaction(async (db, revision) => {
      const account = await db.query("SELECT status FROM accounts WHERE id=$1 FOR UPDATE", [accountId]);
      const identities = await db.query(`SELECT id FROM external_identities WHERE account_id=$1
        AND lower(email)=lower($2) AND last_verified_at IS NOT NULL`, [accountId, expectedVerifiedEmail]);
      if (account.rows[0]?.status !== "active" || identities.rowCount === 0) throw compatibilityErrors.forbidden();
      await db.query(`INSERT INTO hermes_compatibility_admins(account_id,revoked_at) VALUES($1,CASE WHEN $2 THEN NULL ELSE now() END)
        ON CONFLICT(account_id) DO UPDATE SET granted_at=CASE WHEN $2 THEN now() ELSE hermes_compatibility_admins.granted_at END,
        revoked_at=CASE WHEN $2 THEN NULL ELSE now() END`, [accountId, granted]);
      await this.advance(db, revision, null, granted ? "admin_granted" : "admin_revoked", accountId);
      return { accountId, canManage: granted, revision: revision + 1 };
    });
  }

  /** Caller must be the bounded official discovery service; never a Desktop-supplied record. */
  async discover(actor: AccountPrincipal, mutation: CompatibilityMutation, candidate: OfficialCandidate) {
    compatibilityVersion(candidate.version); compatibilityCommit(candidate.tagObject);
    compatibilityCommit(candidate.targetCommit); compatibilityHash(candidate.installerSha256);
    if (candidate.tag !== `v${candidate.version}`) throw compatibilityErrors.invalid();
    return this.mutate(actor, mutation, { action: "discover", candidate }, async (db, revision) => {
      const prior = (await db.query("SELECT * FROM hermes_compatibility_candidates WHERE tag=$1", [candidate.tag])).rows[0];
      if (prior) {
        const conflict = prior.target_commit !== candidate.targetCommit || prior.tag_object !== candidate.tagObject
          || prior.installer_sha256 !== candidate.installerSha256;
        if (conflict) await db.query("UPDATE hermes_compatibility_candidates SET identity_conflict=true WHERE id=$1", [prior.id]);
        await this.advance(db, revision, actor.account.id, conflict ? "candidate_conflict" : "candidate_discovered", prior.id);
        return { revision: revision + 1, candidateId: prior.id as string, identityConflict: conflict || prior.identity_conflict as boolean };
      }
      const id = randomUUID();
      await db.query(`INSERT INTO hermes_compatibility_candidates(id,version,tag,tag_object,target_commit,installer_sha256)
        VALUES($1,$2,$3,$4,$5,$6)`, [id,candidate.version,candidate.tag,candidate.tagObject,candidate.targetCommit,candidate.installerSha256]);
      await this.advance(db, revision, actor.account.id, "candidate_discovered", id);
      return { revision: revision + 1, candidateId: id, identityConflict: false };
    });
  }
  async requestRun(actor: AccountPrincipal, mutation: CompatibilityMutation, raw: ValidationIdentity) {
    const identity = parseValidationIdentity(raw);
    return this.mutate(actor, mutation, { action: "request_run", identity }, async (db, revision) => {
      const candidate = (await db.query("SELECT * FROM hermes_compatibility_candidates WHERE id=$1", [identity.candidateId])).rows[0];
      if (!candidate || candidate.identity_conflict || candidate.version !== identity.version
          || candidate.target_commit !== identity.targetCommit || candidate.installer_sha256 !== identity.installerSha256) throw compatibilityErrors.conflict();
      const digest = compatibilityDigest(identity);
      const active = (await db.query("SELECT id FROM hermes_compatibility_runs WHERE identity_sha256=$1 AND state IN ('queued','running')", [digest])).rows[0];
      const id = active?.id ?? randomUUID();
      if (!active) await db.query(`INSERT INTO hermes_compatibility_runs(id,candidate_id,identity,identity_sha256,state,requested_by)
        VALUES($1,$2,$3,$4,'queued',$5)`, [id,identity.candidateId,identity,digest,actor.account.id]);
      await this.advance(db, revision, actor.account.id, "run_requested", id, digest);
      return { revision: revision + 1, runId: id as string };
    });
  }
  /** Trusted worker boundary only; no administrator HTTP route can call this. */
  async completeRun(raw: ValidationReport) {
    const report = parseValidationReport(raw), digest = compatibilityDigest(report);
    return this.transaction(async (db, revision) => {
      const run = (await db.query("SELECT * FROM hermes_compatibility_runs WHERE id=$1", [report.runId])).rows[0];
      if (!run || run.identity_sha256 !== compatibilityDigest(report.identity)) throw compatibilityErrors.conflict();
      if (run.report_sha256 === digest) return { revision, reportSha256: digest };
      if (run.state !== "running" || run.report_sha256) throw compatibilityErrors.conflict();
      // A worker claiming passed with an incomplete report cannot create a passing run.
      if (report.outcome === "passed") requireApprovableReport({ runId: run.id, identity: run.identity,
        report, releasedComponents: report.identity.components });
      await db.query(`UPDATE hermes_compatibility_runs SET state=$2,report=$3,report_sha256=$4,finished_at=now() WHERE id=$1`,
        [run.id,report.outcome,report,digest]);
      await this.advance(db, revision, null, "run_completed", run.id, digest);
      return { revision: revision + 1, reportSha256: digest };
    });
  }
  /** Trusted fixed-workflow dispatcher only. Queue remains durable across process restarts. */
  async startRun(runId: string, workflowRunId: string, workflowRunAttempt = "1") {
    compatibilityUUID(runId);
    if (!/^[1-9][0-9]{0,19}$/.test(workflowRunId) || workflowRunAttempt !== "1") throw compatibilityErrors.invalid();
    return this.transaction(async (db, revision) => {
      const run = (await db.query("SELECT * FROM hermes_compatibility_runs WHERE id=$1", [runId])).rows[0];
      if (run?.state === "running" && run.workflow_run_id === workflowRunId && run.workflow_run_attempt === workflowRunAttempt) return { revision };
      if (run?.state !== "queued") throw compatibilityErrors.conflict();
      await db.query("UPDATE hermes_compatibility_runs SET state='running',workflow_run_id=$2,workflow_run_attempt=$3,started_at=now() WHERE id=$1", [runId,workflowRunId,workflowRunAttempt]);
      await this.advance(db, revision, null, "run_started", runId);
      return { revision: revision + 1 };
    });
  }
  /** No HTTP exposure without CI authentication. Identities contain no user data or credentials. */
  async workerRun(runId: string) {
    compatibilityUUID(runId);
    const result = await this.pool.query(`SELECT id,identity,state,workflow_run_id AS "workflowRunId",
      workflow_run_attempt AS "workflowRunAttempt",created_at AS "createdAt" FROM hermes_compatibility_runs WHERE id=$1`,[runId]);
    const row=result.rows[0];
    if(!row)throw compatibilityErrors.conflict();
    return {id:row.id as string,identity:parseValidationIdentity(row.identity),state:row.state as string,
      workflowRunId:row.workflowRunId as string|null,workflowRunAttempt:row.workflowRunAttempt as string|null,createdAt:row.createdAt as Date};
  }
  async reserveDispatch() {
    return this.transaction(async(db,revision)=>{
      const row=(await db.query(`SELECT id,identity FROM hermes_compatibility_runs WHERE state='queued'
        AND dispatch_attempts<3 AND dispatch_after<=now() ORDER BY created_at LIMIT 1`)).rows[0];
      if(!row)return null;
      await db.query(`UPDATE hermes_compatibility_runs SET dispatch_attempts=dispatch_attempts+1,
        dispatch_after=now()+interval '5 minutes' WHERE id=$1`,[row.id]);
      await this.advance(db,revision,null,"run_dispatched",row.id);
      return {id:row.id as string,identity:parseValidationIdentity(row.identity)};
    });
  }
  async expireRuns() {
    return this.transaction(async(db,revision)=>{
      const rows=(await db.query(`SELECT id FROM hermes_compatibility_runs WHERE
        (state='queued' AND dispatch_attempts>=3 AND dispatch_after<=now()) OR
        (state='running' AND started_at<now()-interval '150 minutes') ORDER BY created_at LIMIT 20`)).rows;
      for(const [index,row] of rows.entries()){
        await db.query("UPDATE hermes_compatibility_runs SET state='infrastructure_failed',finished_at=now() WHERE id=$1",[row.id]);
        await this.advance(db,revision+index,null,"run_completed",row.id);
      }
      return rows.length;
    });
  }
  async approve(actor: AccountPrincipal, mutation: CompatibilityMutation, runId: string,
    expectedReportSha256: string, releasedComponents: TestedComponent[]) {
    compatibilityUUID(runId); compatibilityHash(expectedReportSha256);
    return this.mutate(actor, mutation, { action: "approve", runId, expectedReportSha256 }, async (db, revision) => {
      const run = (await db.query(`SELECT r.*,c.identity_conflict FROM hermes_compatibility_runs r
        JOIN hermes_compatibility_candidates c ON c.id=r.candidate_id WHERE r.id=$1`, [runId])).rows[0];
      if (!run || run.state !== "passed" || run.identity_conflict || run.report_sha256 !== expectedReportSha256) throw compatibilityErrors.evidenceIncomplete();
      const reportSha256 = requireApprovableReport({ runId, identity: run.identity, report: run.report, releasedComponents });
      if (reportSha256 !== expectedReportSha256) throw compatibilityErrors.evidenceIncomplete();
      if ((await db.query("SELECT id FROM hermes_compatibility_decisions WHERE run_id=$1", [runId])).rowCount) throw compatibilityErrors.conflict();
      const id = randomUUID(), content = { schemaVersion: 1, id, runId, identity: run.identity, reportSha256 };
      const digest = compatibilityDigest(content);
      await db.query(`INSERT INTO hermes_compatibility_decisions(id,run_id,report_sha256,content,content_sha256,approved_by,state)
        VALUES($1,$2,$3,$4,$5,$6,'approved')`, [id,runId,reportSha256,content,digest,actor.account.id]);
      await this.advance(db, revision, actor.account.id, "decision_approved", id, digest);
      return { revision: revision + 1, decisionId: id, state: "approved" };
    });
  }

  async revoke(actor: AccountPrincipal, mutation: CompatibilityMutation, decisionId: string) {
    compatibilityUUID(decisionId);
    return this.mutate(actor,mutation,{action:"revoke",decisionId},async(db,revision)=>{
      const row=(await db.query("SELECT id,state FROM hermes_compatibility_decisions WHERE id=$1",[decisionId])).rows[0];
      if(!row)throw compatibilityErrors.invalid();
      if(row.state!=="revoked") await db.query("UPDATE hermes_compatibility_decisions SET state='revoked',revoked_at=now() WHERE id=$1",[decisionId]);
      await this.advance(db,revision,actor.account.id,"decision_revoked",decisionId);
      return {revision:revision+1,decisionId,state:"revoked"};
    });
  }

  async publicationTask(taskId:string) {
    compatibilityUUID(taskId);
    return this.transaction(async(db)=>{
      const row=(await db.query(`SELECT task_id AS id,revision::text,payload,payload_sha256 AS "payloadSha256",state,
        requested_by AS "requestedBy",go_commit AS "goCommit",workflow_run_id AS "workflowRunId",workflow_run_attempt AS "workflowRunAttempt",
        created_at AS "createdAt" FROM hermes_compatibility_snapshots WHERE task_id=$1`,[taskId])).rows[0];
      if(!row)throw compatibilityErrors.invalid();return row;
    });
  }
  async reservePublicationDispatch() {
    return this.transaction(async(db,revision)=>{
      const row=(await db.query(`SELECT task_id,go_commit FROM hermes_compatibility_snapshots WHERE state='pending'
        AND workflow_run_id IS NULL AND dispatch_accepted_at IS NULL AND dispatch_attempts<3 AND dispatch_after<=now() ORDER BY revision LIMIT 1`)).rows[0];
      if(!row)return null;
      await db.query("UPDATE hermes_compatibility_snapshots SET dispatch_attempts=dispatch_attempts+1,dispatch_after=now()+interval '5 minutes' WHERE task_id=$1",[row.task_id]);
      await this.advance(db,revision,null,"publication_dispatched",row.task_id);
      return {id:row.task_id as string,goCommit:row.go_commit as string};
    });
  }
  async publicationDispatchAccepted(taskId:string){
    compatibilityUUID(taskId);
    return this.transaction(async(db)=>{await db.query("UPDATE hermes_compatibility_snapshots SET dispatch_accepted_at=now() WHERE task_id=$1 AND state='pending'",[taskId]);});
  }
  async expirePublications(){
    return this.transaction(async(db,revision)=>{
      const rows=(await db.query(`SELECT task_id,payload FROM hermes_compatibility_snapshots WHERE state='pending' AND
        ((dispatch_accepted_at IS NULL AND dispatch_attempts>=3 AND dispatch_after<=now()) OR created_at<now()-interval '24 hours')
        ORDER BY revision LIMIT 20`)).rows;
      for(const [index,row] of rows.entries()){
        await db.query("UPDATE hermes_compatibility_snapshots SET state='failed' WHERE task_id=$1",[row.task_id]);
        for(const entry of row.payload.entries)await db.query("UPDATE hermes_compatibility_decisions SET state='publication_failed' WHERE id=$1 AND state='publishing'",[entry.id]);
        await this.advance(db,revision+index,null,"publication_failed",row.task_id);
      }return rows.length;
    });
  }
  async startPublication(taskId:string,workflowRunId:string,attempt:string) {
    compatibilityUUID(taskId);
    if(!/^[1-9][0-9]{0,19}$/.test(workflowRunId)||attempt!=="1")throw compatibilityErrors.invalid();
    return this.transaction(async(db)=>{
      const row=(await db.query("SELECT * FROM hermes_compatibility_snapshots WHERE task_id=$1",[taskId])).rows[0];
      if(!row||row.state!=="pending"||(row.workflow_run_id&&row.workflow_run_id!==workflowRunId))throw compatibilityErrors.conflict();
      await this.requirePublisherAccount(db,row.requested_by);
      await this.requireLiveEntries(db,row.payload.entries);
      await db.query("UPDATE hermes_compatibility_snapshots SET workflow_run_id=$2,workflow_run_attempt=$3 WHERE task_id=$1",[taskId,workflowRunId,attempt]);
      return {schemaVersion:1,taskId,payload:row.payload,payloadSha256:row.payload_sha256,goCommit:row.go_commit};
    });
  }
  async attachPublicationEnvelope(taskId:string,envelope:unknown,policy:CompatibilityPublicationPolicy) {
    compatibilityUUID(taskId);
    const payload=parseRecommendation(verifyCompatibilityEnvelope(envelope,policy.signingKeys),policy.origin);
    return this.transaction(async(db)=>{
      const row=(await db.query("SELECT * FROM hermes_compatibility_snapshots WHERE task_id=$1",[taskId])).rows[0];
      if(!row||!row.workflow_run_id||!["pending","published"].includes(row.state)||row.payload_sha256!==compatibilityDigest(payload)
        ||(row.envelope&&compatibilityDigest(row.envelope)!==compatibilityDigest(envelope)))throw compatibilityErrors.conflict();
      await this.requirePublisherAccount(db,row.requested_by);
      await this.requireLiveEntries(db,payload.entries);
      await db.query("UPDATE hermes_compatibility_snapshots SET envelope=$2 WHERE task_id=$1",[taskId,envelope]);
      return {revision:Number(row.revision),payloadSha256:row.payload_sha256 as string,requestedBy:row.requested_by as string};
    });
  }
  private async requirePublisherAccount(db:PoolClient,accountId:unknown) {
    compatibilityUUID(accountId);
    const row=(await db.query("SELECT status FROM accounts WHERE id=$1 FOR UPDATE",[accountId])).rows[0];
    if(row?.status!=="active"||!await this.isAdmin(db,accountId as string))throw compatibilityErrors.forbidden();
  }

  /** Immutable unsigned candidate; only the protected release workflow may sign it. */
  async preparePublication(actor: AccountPrincipal, mutation: CompatibilityMutation, policy: Pick<CompatibilityPublicationPolicy,"origin"|"goCommit">,
    released: (identity:ValidationIdentity)=>TestedComponent[]) {
    return this.mutate(actor,mutation,{action:"publish"},async(db,revision)=>{
      const rows=(await db.query(`SELECT d.*,r.report,r.state AS run_state FROM hermes_compatibility_decisions d
        JOIN hermes_compatibility_runs r ON r.id=d.run_id JOIN hermes_compatibility_candidates c ON c.id=r.candidate_id
        WHERE d.state IN ('approved','publishing','published','publication_failed') AND NOT c.identity_conflict
        ORDER BY d.id LIMIT 101`)).rows;
      if(rows.length>100)throw compatibilityErrors.publicationIncomplete();
      for(const row of rows){
        const identity=parseValidationIdentity(row.content.identity);
        const releasedComponents=released(identity);
        if(row.run_state!=="passed"||requireApprovableReport({runId:row.run_id,identity,report:row.report,releasedComponents})!==row.report_sha256
          ||row.content.reportSha256!==row.report_sha256)
          throw compatibilityErrors.evidenceIncomplete();
        if(compatibilityDigest(row.content)!==row.content_sha256)throw compatibilityErrors.evidenceIncomplete();
      }
      const payload=makeCompatibilityRecommendation(policy.origin,revision+1,rows.map(r=>r.content));
      const digest=compatibilityDigest(payload);
      const taskId=randomUUID();compatibilityCommit(policy.goCommit);
      await db.query(`INSERT INTO hermes_compatibility_snapshots(revision,payload,payload_sha256,task_id,requested_by,go_commit,state)
        VALUES($1,$2,$3,$4,$5,$6,'pending')`,[revision+1,payload,digest,taskId,actor.account.id,policy.goCommit]);
      for(const row of rows)if(row.state!=="published")await db.query("UPDATE hermes_compatibility_decisions SET state='publishing' WHERE id=$1",[row.id]);
      await this.advance(db,revision,actor.account.id,"publication_started",randomUUID(),digest);
      return {revision:revision+1,state:"pending",payloadSha256:digest,taskId};
    });
  }

  /** Called after the service independently fetched and verified the public immutable URL. */
  async completePublication(actor:AccountPrincipal|string,snapshotRevision:number,digest:string,origin:string,
    released:(identity:ValidationIdentity)=>TestedComponent[]) {
    compatibilityRevision(snapshotRevision);compatibilityHash(digest);
    return this.transaction(async(db,revision)=>{
      const actorId=typeof actor==="string"?actor:actor.account.id;
      if(typeof actor==="string")await this.requirePublisherAccount(db,actor);else await this.requireAdmin(db,actor);
      const row=(await db.query("SELECT * FROM hermes_compatibility_snapshots WHERE revision=$1",[snapshotRevision])).rows[0];
      if(!row||row.payload_sha256!==digest||!row.envelope||row.requested_by!==actorId)throw compatibilityErrors.publicationIncomplete();
      if(row.state==="published")return {revision,state:"published",snapshotRevision};
      if(row.state!=="pending"||(await db.query("SELECT 1 FROM hermes_compatibility_snapshots WHERE revision>$1 AND state IN ('pending','published')",[snapshotRevision])).rowCount)
        throw compatibilityErrors.conflict();
      const payload=parseRecommendation(row.payload,origin);
      await this.requireLiveEntries(db,payload.entries);
      for(const entry of payload.entries)if(compatibilityDigest(released(entry.identity))!==compatibilityDigest(entry.identity.components))
        throw compatibilityErrors.evidenceIncomplete();
      await db.query("UPDATE hermes_compatibility_snapshots SET state='published',published_at=now() WHERE revision=$1",[snapshotRevision]);
      for(const entry of payload.entries)await db.query("UPDATE hermes_compatibility_decisions SET state='published' WHERE id=$1",[entry.id]);
      await this.advance(db,revision,actorId,"publication_completed",row.task_id,digest);
      return {revision:revision+1,state:"published",snapshotRevision};
    });
  }

  /** Public read still checks live revocations; a cached old signed payload cannot authorize a fresh offer. */
  async recommendation(origin:string, requestedRevision?:number):Promise<CompatibilityEnvelope> {
    if(requestedRevision!==undefined)compatibilityRevision(requestedRevision);
    return this.transaction(async(db)=>{
      const row=(await db.query(`SELECT payload,payload_sha256,envelope FROM hermes_compatibility_snapshots
        WHERE ${requestedRevision===undefined?"state='published'":"state IN ('pending','published') AND revision=$1"} ORDER BY revision DESC LIMIT 1`,
        requestedRevision===undefined?[]:[requestedRevision])).rows[0];
      if(!row||!row.envelope)throw compatibilityErrors.publicationIncomplete();
      const payload=parseRecommendation(row.payload,origin);
      if(compatibilityDigest(payload)!==row.payload_sha256)throw compatibilityErrors.publicationIncomplete();
      await this.requireLiveEntries(db,payload.entries);
      return row.envelope as CompatibilityEnvelope;
    });
  }
  private async requireLiveEntries(db:PoolClient,entries:ReturnType<typeof parseRecommendation>["entries"]) {
      for(const entry of entries){
        const current=(await db.query(`SELECT d.state,d.content_sha256,c.identity_conflict FROM hermes_compatibility_decisions d
          JOIN hermes_compatibility_runs r ON r.id=d.run_id JOIN hermes_compatibility_candidates c ON c.id=r.candidate_id WHERE d.id=$1`,[entry.id])).rows[0];
        if(!["published","publishing"].includes(current?.state)||current.identity_conflict||current.content_sha256!==compatibilityDigest(entry))
          throw compatibilityErrors.publicationIncomplete();
      }
  }

  private async mutate<T>(actor: AccountPrincipal, mutation: CompatibilityMutation, command: unknown,
    run: (db: PoolClient, revision: number) => Promise<T>): Promise<T> {
    compatibilityUUID(mutation.operationId); compatibilityRevision(mutation.expectedRevision);
    const digest = compatibilityDigest({ expectedRevision: mutation.expectedRevision, command });
    return this.transaction(async (db, revision) => {
      await this.requireAdmin(db, actor);
      const prior = (await db.query("SELECT request_sha256,result FROM hermes_compatibility_operations WHERE actor_id=$1 AND operation_id=$2",
        [actor.account.id,mutation.operationId])).rows[0];
      if (prior) {
        if (prior.request_sha256 !== digest) throw compatibilityErrors.conflict();
        return prior.result as T;
      }
      if (revision !== mutation.expectedRevision) throw compatibilityErrors.conflict();
      const result = await run(db, revision);
      await db.query("INSERT INTO hermes_compatibility_operations(actor_id,operation_id,request_sha256,result) VALUES($1,$2,$3,$4)",
        [actor.account.id,mutation.operationId,digest,result]);
      return result;
    });
  }
  private async requireSession(db: PoolClient, actor: AccountPrincipal) {
    const row = (await db.query(`SELECT a.status,s.revoked_at AS session_revoked,i.revoked_at AS installation_revoked,
      s.access_expires_at>clock_timestamp() AS live FROM account_sessions s JOIN accounts a ON a.id=s.account_id
      JOIN installations i ON i.id=s.installation_id AND i.account_id=a.id
      WHERE s.id=$1 AND a.id=$2 AND i.id=$3 FOR UPDATE OF s,a,i`,
      [actor.sessionId,actor.account.id,actor.installation.id])).rows[0];
    if (!row || !row.live) throw accountErrors.sessionExpired();
    if (row.status !== "active") throw accountErrors.accountDisabled();
    if (row.session_revoked || row.installation_revoked) throw accountErrors.sessionRevoked();
  }
  private async isAdmin(db: PoolClient, accountId: string) {
    return (await db.query("SELECT account_id FROM hermes_compatibility_admins WHERE account_id=$1 AND revoked_at IS NULL", [accountId])).rowCount === 1;
  }
  private async requireAdmin(db: PoolClient, actor: AccountPrincipal) {
    await this.requireSession(db, actor);
    if (!await this.isAdmin(db, actor.account.id)) throw compatibilityErrors.forbidden();
  }
  private async advance(db: PoolClient, revision: number, actor: string | null, action: string,
    recordId: string, digest: string | null = null) {
    await db.query("UPDATE hermes_compatibility_state SET revision=revision+1 WHERE singleton=true");
    await db.query("INSERT INTO hermes_compatibility_audit(actor_id,revision,action,record_id,content_sha256) VALUES($1,$2,$3,$4,$5)",
      [actor,revision + 1,action,recordId,digest]);
  }
  private async transaction<T>(run: (db: PoolClient, revision: number) => Promise<T>): Promise<T> {
    const db = await this.pool.connect();
    try {
      await db.query("BEGIN");
      await db.query("SET LOCAL statement_timeout='10s'");
      await db.query("SET LOCAL lock_timeout='5s'");
      const state = await db.query("SELECT revision::text FROM hermes_compatibility_state WHERE singleton=true FOR UPDATE");
      if (state.rowCount !== 1) throw compatibilityErrors.unavailable();
      const result = await run(db, compatibilityRevision(Number(state.rows[0].revision)));
      await db.query("COMMIT");
      return result;
    } catch (error) {
      await db.query("ROLLBACK");
      throw error;
    } finally { db.release(); }
  }
}
