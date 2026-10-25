import { HermesCompatibilityStore } from "./hermes-compatibility-store.js";
import { compatibilityErrors,compatibilityUUID } from "./hermes-compatibility-model.js";
import { CompatibilityOIDCKeys,HERMES_COMPATIBILITY_PUBLISH_WORKFLOW,verifyCompatibilityJobToken,
  compatibilityWorkerError,type CompatibilityCITrust } from "./hermes-compatibility-oidc.js";
import { compatibilityGitHubRequest } from "./hermes-compatibility-ci.js";
import { releasedInventory,verifyPublicationReadback,type CompatibilityPublicationPolicy } from "./hermes-compatibility-publication.js";

/** Gateway only dispatches and verifies. Signing runs in GitHub's existing desktop-release environment. */
export class HermesCompatibilityPublishCI {
  private timer?:ReturnType<typeof setInterval>;
  private dispatching=false;
  constructor(private readonly store:HermesCompatibilityStore,private readonly trust:CompatibilityCITrust,
    private readonly token:string,private readonly policy:CompatibilityPublicationPolicy,
    private readonly keys=new CompatibilityOIDCKeys(),private readonly fetcher:typeof fetch=fetch) {}
  start(){if(this.timer)return;this.timer=setInterval(()=>{void this.dispatchPending().catch(()=>{});},30_000);this.timer.unref();void this.dispatchPending().catch(()=>{});}
  stop(){if(this.timer)clearInterval(this.timer);this.timer=undefined;}
  async dispatchPending(){
    if(this.dispatching)return;this.dispatching=true;
    try{
      await this.store.expirePublications();
      const next=await this.store.reservePublicationDispatch();if(!next)return;
      const ref=await this.github("/git/ref/heads/main");
      if((ref.object as {sha?:unknown})?.sha!==next.goCommit)throw compatibilityErrors.conflict();
      await this.github(`/actions/workflows/${HERMES_COMPATIBILITY_PUBLISH_WORKFLOW.split("/").at(-1)}/dispatches`,{ref:"main",inputs:{task_id:next.id}});
      await this.store.publicationDispatchAccepted(next.id);
    }finally{this.dispatching=false;}
  }
  async claim(taskId:string,authorization:string|undefined){
    const {proof}=await this.authenticate(taskId,authorization);
    return this.store.startPublication(taskId,proof.runId,proof.runAttempt);
  }
  async complete(taskId:string,authorization:string|undefined,envelope:unknown){
    const {task,proof}=await this.authenticate(taskId,authorization);
    if(task.workflowRunId!==proof.runId||task.workflowRunAttempt!==proof.runAttempt)throw compatibilityWorkerError();
    const attached=await this.store.attachPublicationEnvelope(taskId,envelope,this.policy);
    await verifyPublicationReadback(this.policy,attached.revision,attached.payloadSha256);
    const inventory=this.policy.readInventory();
    return this.store.completePublication(attached.requestedBy,attached.revision,attached.payloadSha256,this.policy.origin,identity=>
      releasedInventory(inventory,this.policy.releaseKeys,this.policy.origin,identity));
  }
  private async authenticate(taskId:string,authorization:string|undefined){
    try{
      compatibilityUUID(taskId);
      if(!authorization?.startsWith("Bearer ")||authorization.length>16400)throw compatibilityWorkerError();
      const task=await this.store.publicationTask(taskId);
      const proof=verifyCompatibilityJobToken(authorization.slice(7),await this.keys.load(),this.trust,task.goCommit,Date.now(),"publication");
      if(proof.runAttempt!=="1")throw compatibilityWorkerError();
      const metadata=await this.github(`/actions/runs/${proof.runId}`);
      if(metadata.id?.toString()!==proof.runId||metadata.run_attempt?.toString()!==proof.runAttempt||metadata.head_sha!==task.goCommit
        ||metadata.event!=="workflow_dispatch"||metadata.head_branch!=="main"||metadata.path!==HERMES_COMPATIBILITY_PUBLISH_WORKFLOW
        ||metadata.display_title!==`hermes-compatibility-publish-${taskId}`
        ||(metadata.repository as {id?:unknown})?.id?.toString()!==this.trust.repositoryId
        ||typeof metadata.created_at!=="string"||!Number.isFinite(Date.parse(metadata.created_at))
        ||Date.parse(metadata.created_at)<task.createdAt.getTime()-30_000)throw compatibilityWorkerError();
      return {task,proof};
    }catch{throw compatibilityWorkerError();}
  }
  private github(path:string,body?:unknown){return compatibilityGitHubRequest(this.trust,this.token,this.fetcher,path,body);}
}
