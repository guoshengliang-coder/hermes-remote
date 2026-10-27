import { compatibilityErrors, compatibilityUUID, compatibilityRevision, parseValidationIdentity,
  type ValidationIdentity, type TestedComponent } from "./hermes-compatibility-model.js";
import { HermesCompatibilityStore, type CompatibilityMutation, type OfficialCandidate } from "./hermes-compatibility-store.js";
import { discoverOfficialHermes } from "./hermes-compatibility-discovery.js";
import type { AccountPrincipal } from "./model.js";
import { releasedInventory,type CompatibilityPublicationPolicy } from "./hermes-compatibility-publication.js";

export interface CompatibilityRecipe {
  id: string;
  /** Validated, server-controlled fixed input. Candidate identity is resolved at request time. */
  identity: Omit<ValidationIdentity,"candidateId" | "version" | "targetCommit" | "installerSha256">;
}
export class HermesCompatibilityService {
  constructor(private readonly store: HermesCompatibilityStore,
    private readonly recipes: readonly CompatibilityRecipe[] = [],
    private readonly discovery: () => Promise<OfficialCandidate> = discoverOfficialHermes,
    private readonly releasedComponents?: (identity: ValidationIdentity) => Promise<TestedComponent[]>,
    private readonly publication?: CompatibilityPublicationPolicy) {}

  access(actor: AccountPrincipal) { return this.store.access(actor); }
  async snapshot(actor: AccountPrincipal) {
    const snapshot = await this.store.snapshot(actor);
    return { ...snapshot, recipes: this.recipes };
  }
  report(actor: AccountPrincipal, runId: string) { return this.store.report(actor, runId); }
  async sync(actor: AccountPrincipal, body: Record<string, unknown>) {
    const mutation = parseMutation(body, []);
    // Reject before network activity; the store reauthorizes after discovery inside the mutation.
    if (!(await this.store.access(actor)).canManage) throw compatibilityErrors.forbidden();
    return this.store.discover(actor, mutation, await this.discovery());
  }
  async requestRun(actor: AccountPrincipal, body: Record<string, unknown>) {
    const mutation = parseMutation(body,["candidateId","recipeId"]);
    const snapshot = await this.store.snapshot(actor);
    const candidateId = compatibilityUUID(body.candidateId);
    const candidate = snapshot.candidates.find(c => c.id === candidateId);
    const recipe = this.recipes.find(r => r.id === body.recipeId);
    if (!candidate || !recipe) throw compatibilityErrors.invalid();
    const identity = parseValidationIdentity({ ...recipe.identity,candidateId,
      version:candidate.version,targetCommit:candidate.targetCommit,installerSha256:candidate.installerSha256 });
    return this.store.requestRun(actor,mutation,identity);
  }
  async approve(actor: AccountPrincipal, body: Record<string, unknown>) {
    const mutation = parseMutation(body,["runId","reportSha256"]), runId = compatibilityUUID(body.runId);
    const run = await this.store.report(actor,runId);
    if (typeof body.reportSha256 !== "string") throw compatibilityErrors.invalid();
    const identity=parseValidationIdentity(run.identity);
    const releases = this.releasedComponents ? await this.releasedComponents(identity) : this.publication
      ? releasedInventory(this.publication.readInventory(),this.publication.releaseKeys,this.publication.origin,identity) : [];
    return this.store.approve(actor,mutation,runId,body.reportSha256,releases);
  }
  revoke(actor:AccountPrincipal,body:Record<string,unknown>){
    return this.store.revoke(actor,parseMutation(body,["decisionId"]),compatibilityUUID(body.decisionId));
  }
  async publish(actor:AccountPrincipal,body:Record<string,unknown>){
    const mutation=parseMutation(body,[]);
    if(!(await this.store.access(actor)).canManage)throw compatibilityErrors.forbidden();
    const publication=this.publication;if(!publication)throw compatibilityErrors.publicationIncomplete();
    const inventory=publication.readInventory();
    return this.store.preparePublication(actor,mutation,publication,identity=>
      releasedInventory(inventory,publication.releaseKeys,publication.origin,identity));
  }
  recommendation(revision?:number){
    if(!this.publication)throw compatibilityErrors.publicationIncomplete();
    return this.store.recommendation(this.publication.origin,revision);
  }
}
function parseMutation(body: Record<string, unknown>, fields: string[]): CompatibilityMutation {
  const keys = ["operationId","expectedRevision",...fields];
  if (Object.keys(body).length !== keys.length || Object.keys(body).some(k=>!keys.includes(k))) throw compatibilityErrors.invalid();
  return { operationId:compatibilityUUID(body.operationId),expectedRevision:compatibilityRevision(body.expectedRevision) };
}
