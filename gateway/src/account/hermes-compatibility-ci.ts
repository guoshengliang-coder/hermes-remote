import { HermesCompatibilityStore } from "./hermes-compatibility-store.js";
import { compatibilityErrors,compatibilityUUID,parseValidationReport } from "./hermes-compatibility-model.js";
import { CompatibilityOIDCKeys,verifyCompatibilityJobToken,compatibilityWorkerError,HERMES_COMPATIBILITY_WORKFLOW,
  type CompatibilityCITrust } from "./hermes-compatibility-oidc.js";

export class HermesCompatibilityCI {
  private timer?: ReturnType<typeof setInterval>;
  private dispatching=false;
  constructor(private readonly store:HermesCompatibilityStore,private readonly trust:CompatibilityCITrust,
    private readonly dispatchToken:string,private readonly keys=new CompatibilityOIDCKeys(),private readonly fetcher:typeof fetch=fetch) {
    if(!/^[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+$/.test(trust.repository)||trust.repository.includes("..")
      ||!/^[1-9][0-9]{0,19}$/.test(trust.repositoryId)||!trust.audience||!dispatchToken)throw compatibilityErrors.invalid();
  }
  start() {
    if(this.timer)return;
    this.timer=setInterval(()=>{void this.dispatchPending().catch(()=>{});},30_000);this.timer.unref();
    void this.dispatchPending().catch(()=>{});
  }
  stop(){if(this.timer)clearInterval(this.timer);this.timer=undefined;}
  async dispatchPending(){
    if(this.dispatching)return;
    this.dispatching=true;
    try{
      await this.store.expireRuns();
      const next=await this.store.reserveDispatch();if(!next)return;
      const ref=await this.github("/git/ref/heads/main");
      if(record(ref.object).sha!==next.identity.goCommit)throw compatibilityErrors.conflict();
      await this.github(`/actions/workflows/${HERMES_COMPATIBILITY_WORKFLOW.split("/").at(-1)}/dispatches`,
        {ref:"main",inputs:{run_id:next.id}});
    }finally{this.dispatching=false;}
  }
  async claim(taskId:string,authorization:string|undefined){
    const {run,proof}=await this.authenticate(taskId,authorization);
    await this.store.startRun(run.id,proof.runId,proof.runAttempt);
    return {schemaVersion:1,runId:run.id,identity:run.identity};
  }
  async complete(taskId:string,authorization:string|undefined,raw:unknown){
    const {run,proof}=await this.authenticate(taskId,authorization);
    if(run.workflowRunId!==proof.runId||run.workflowRunAttempt!==proof.runAttempt)throw compatibilityWorkerError();
    const report=parseValidationReport(raw);
    if(report.runId!==run.id)throw compatibilityWorkerError();
    return this.store.completeRun(report);
  }
  private async authenticate(taskId:string,authorization:string|undefined){
    try{
      compatibilityUUID(taskId);
      if(!authorization?.startsWith("Bearer ")||authorization.length>16400)throw compatibilityWorkerError();
      const run=await this.store.workerRun(taskId);
      const proof=verifyCompatibilityJobToken(authorization.slice(7),await this.keys.load(),this.trust,run.identity.goCommit);
      if(proof.runAttempt!=="1")throw compatibilityWorkerError();
      // JWT binds workflow code, not workflow inputs. Independently bind the task UUID in run_name.
      const metadata=await this.github(`/actions/runs/${proof.runId}`);
      if(metadata.id?.toString()!==proof.runId||metadata.run_attempt?.toString()!==proof.runAttempt
        ||metadata.head_sha!==run.identity.goCommit||metadata.event!=="workflow_dispatch"
        ||metadata.head_branch!=="main"||metadata.path!==HERMES_COMPATIBILITY_WORKFLOW
        ||metadata.display_title!==`hermes-compatibility-${run.id}`
        ||record(metadata.repository).id?.toString()!==this.trust.repositoryId
        ||typeof metadata.created_at!=="string"||!Number.isFinite(Date.parse(metadata.created_at))
        ||Date.parse(metadata.created_at)<run.createdAt.getTime()-30_000)throw compatibilityWorkerError();
      return {run,proof};
    }catch{throw compatibilityWorkerError();}
  }
  private async github(path:string,body?:unknown):Promise<Record<string,unknown>>{
    return compatibilityGitHubRequest(this.trust,this.dispatchToken,this.fetcher,path,body);
  }
}
export async function compatibilityGitHubRequest(trust:CompatibilityCITrust,dispatchToken:string,fetcher:typeof fetch,
  path:string,body?:unknown):Promise<Record<string,unknown>> {
    try{
      const response=await fetcher(`https://api.github.com/repos/${trust.repository}${path}`,{
        method:body===undefined?"GET":"POST",redirect:"error",signal:AbortSignal.timeout(15_000),
        headers:{accept:"application/vnd.github+json",authorization:`Bearer ${dispatchToken}`,
          "content-type":"application/json","user-agent":"hermes-go-compatibility","x-github-api-version":"2022-11-28"},
        ...(body===undefined?{}:{body:JSON.stringify(body)})});
      if(!response.ok)throw compatibilityErrors.unavailable();
      if(response.status===204)return {};
      if(!response.body)throw compatibilityErrors.unavailable();
      const reader=response.body.getReader(),chunks:Uint8Array[]=[];let size=0;
      try{while(true){const next=await reader.read();if(next.done)break;size+=next.value.byteLength;if(size>512*1024)throw compatibilityErrors.unavailable();chunks.push(next.value);}}
      finally{await reader.cancel().catch(()=>{});}
      return record(JSON.parse(Buffer.concat(chunks).toString("utf8")));
    }catch{throw compatibilityErrors.unavailable();}
}
function record(value:unknown):Record<string,unknown>{
  if(!value||typeof value!=="object"||Array.isArray(value))throw compatibilityWorkerError();return value as Record<string,unknown>;
}
