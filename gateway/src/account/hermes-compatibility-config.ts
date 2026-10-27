import {compatibilityErrors,parseValidationIdentity} from "./hermes-compatibility-model.js";
import type {CompatibilityCITrust} from "./hermes-compatibility-oidc.js";
import type {CompatibilityRecipe} from "./hermes-compatibility-service.js";

export function parseCompatibilityCIConfiguration(raw:string):{trust:CompatibilityCITrust;recipes:CompatibilityRecipe[]} {
  try{
    if(Buffer.byteLength(raw)>64*1024)throw compatibilityErrors.invalid();
    const config=exact(JSON.parse(raw),["schemaVersion","trust","recipes"]);
    const trust=exact(config.trust,["repository","repositoryId","audience"]);
    if(config.schemaVersion!==1||trust.repository!=="guoshengliang-coder/hermes-remote"
      ||typeof trust.repositoryId!=="string"||!/^[1-9][0-9]{0,19}$/.test(trust.repositoryId)
      ||typeof trust.audience!=="string"||trust.audience.length>256
      ||!Array.isArray(config.recipes)||config.recipes.length<1||config.recipes.length>10)throw compatibilityErrors.invalid();
    const audience=new URL(trust.audience);
    if(audience.protocol!=="https:"||audience.username||audience.password||audience.search||audience.hash
      ||audience.pathname!=="/hermes-compatibility")throw compatibilityErrors.invalid();
    const recipes=config.recipes.map((item):CompatibilityRecipe=>{
      const recipe=exact(item,["id","identity"]);
      if(typeof recipe.id!=="string"||!/^[a-z0-9][a-z0-9-]{0,63}$/.test(recipe.id))throw compatibilityErrors.invalid();
      const value=exact(recipe.identity,["schemaVersion","maintenancePolicy","sourceCommit","goCommit","suiteSha256","environment","components"]);
      const parsed=parseValidationIdentity({...value,candidateId:"10000000-0000-4000-8000-000000000001",version:"0.0.0",
        targetCommit:value.sourceCommit==="f".repeat(40)?"e".repeat(40):"f".repeat(40),installerSha256:"a".repeat(64)});
      return {id:recipe.id,identity:{schemaVersion:1,maintenancePolicy:parsed.maintenancePolicy,sourceCommit:parsed.sourceCommit,goCommit:parsed.goCommit,
        suiteSha256:parsed.suiteSha256,environment:parsed.environment,components:parsed.components}};
    });
    if(new Set(recipes.map(r=>r.id)).size!==recipes.length)throw compatibilityErrors.invalid();
    return {trust:trust as unknown as CompatibilityCITrust,recipes};
  }catch{throw compatibilityErrors.invalid();}
}
function exact(value:unknown,keys:string[]):Record<string,unknown>{
  if(!value||typeof value!=="object"||Array.isArray(value)||Object.keys(value).length!==keys.length
    ||Object.keys(value).some(k=>!keys.includes(k)))throw compatibilityErrors.invalid();
  return value as Record<string,unknown>;
}
