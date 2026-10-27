// Protected release producer: collect all five original release identities afresh before signing.
// The CLI only prepares an unsigned draft; it has no key, upload, administrator or deployment action.
import { constants } from 'node:fs';
import { open, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { collectClientReleaseEvidence } from './hermes-compatibility-client-evidence.mjs';
import { collectConnectorReleaseEvidence } from './hermes-compatibility-connector-evidence.mjs';
import { collectGatewayReleaseEvidence } from './hermes-compatibility-gateway-evidence.mjs';
import { collectWebReleaseEvidence } from './hermes-compatibility-web-evidence.mjs';
import { COMPONENT_NAMES, parseTestedComponents, compatibilityDigest, compatibilityErrors } from '../gateway/dist/account/hermes-compatibility-model.js';
import { compatibilityOrigin, RELEASE_INVENTORY_PURPOSE, parseReleaseInventory } from '../gateway/dist/account/hermes-compatibility-publication.js';

const DAY=24*60*60_000,FRESH=15*60_000;
const SOURCE_KEYS={desktop:['manifest'],android:['manifest'],connector:['releaseVersion','layout','architecture','channel','keyId','publicKey'],
  gateway:['manifestPath','containerName'],web:['manifestPath','archivePath']};
const KINDS={desktop:'pinned-public-client-archive',android:'pinned-public-client-archive',connector:'published-signed-connector-artifact',
  gateway:'gateway-oci-runtime-observation',web:'published-web-readback'};
function exact(value,keys) {
  if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==keys.length||Object.keys(value).some(k=>!keys.includes(k)))throw 0;
}
async function readBounded(path,limit) {
  const handle=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW);
  try {
    const info=await handle.stat();if(!info.isFile()||info.size<1||info.size>limit)throw 0;
    const data=Buffer.alloc(info.size+1);let size=0;
    while(size<data.length){const next=await handle.read(data,size,data.length-size,null);if(!next.bytesRead)break;size+=next.bytesRead;}
    const after=await handle.stat();if(size!==info.size||after.size!==info.size||after.ctimeMs!==info.ctimeMs||after.mtimeMs!==info.mtimeMs)throw 0;
    return data.subarray(0,size);
  }finally{await handle.close();}
}
const DEFAULT_COLLECTORS={
  desktop:collectClientReleaseEvidence,android:collectClientReleaseEvidence,connector:collectConnectorReleaseEvidence,
  gateway:(input,_fetcher,now)=>collectGatewayReleaseEvidence(input,undefined,now),
  web:async(input,fetcher,now)=>collectWebReleaseEvidence({origin:input.origin,expected:input.expected,
    manifest:JSON.parse((await readBounded(input.manifestPath,512*1024)).toString('utf8')),
    archive:await readBounded(input.archivePath,64*1024*1024)},fetcher,now),
};
/** Dependency injection is for tests/embedding trusted code, never accepted by the CLI or administrator API. */
export async function prepareReleaseInventory(raw,{fetcher=fetch,clock=()=>new Date(),collectors={}}={}) {
  let stage='policy';
  try {
    const policy=JSON.parse(JSON.stringify(raw));exact(policy,['schemaVersion','origin','combinations']);
    compatibilityOrigin(policy.origin);
    if(policy.schemaVersion!==1||!Array.isArray(policy.combinations)||policy.combinations.length>100)throw 0;
    const combinations=policy.combinations.map(row=>{
      exact(row,['components','sources']);const components=parseTestedComponents(row.components);exact(row.sources,COMPONENT_NAMES);
      for(const name of COMPONENT_NAMES){exact(row.sources[name],SOURCE_KEYS[name]);
        for(const key of SOURCE_KEYS[name])if(key.endsWith('Path')&&(typeof row.sources[name][key]!=='string'||!row.sources[name][key].startsWith('/')))throw 0;
      }
      return {components,sources:row.sources};
    });
    if(new Set(combinations.map(c=>compatibilityDigest(c.components))).size!==combinations.length)throw 0;
    const evidence=[],cache=new Map();
    for(const row of combinations)for(const component of row.components) {
      const source=row.sources[component.name],cacheKey=compatibilityDigest({component,source});
      if(cache.has(cacheKey))continue;
      stage=component.name;
      const observed=clock();if(!(observed instanceof Date)||!Number.isFinite(observed.getTime()))throw 0;
      const result=await (collectors[component.name]??DEFAULT_COLLECTORS[component.name])({...source,origin:policy.origin,expected:component},fetcher,observed);
      if(result?.schemaVersion!==1||result.kind!==KINDS[component.name]||result.origin!==policy.origin
        ||result.compatibilityApproved!==false||compatibilityDigest(result.component)!==compatibilityDigest(component)
        ||(component.name==='gateway'&&result.runningImageVerified!==true))throw 0;
      // Persist only the identity, source kind, bounded time and digest; no raw source documents or diagnostics.
      const observation={component:{...component},kind:result.kind,observedAt:result.observedAt,evidenceSha256:compatibilityDigest(result)};
      if(component.name==='connector')observation.releaseExpiresAt=result.releaseExpiresAt;
      evidence.push(observation);cache.set(cacheKey,observation);
    }
    stage='freshness';const now=clock();if(!(now instanceof Date)||!Number.isFinite(now.getTime()))throw 0;
    let expires=now.getTime()+DAY;
    for(const item of evidence) {
      const time=Date.parse(item.observedAt);
      if(typeof item.observedAt!=='string'||!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(item.observedAt)
        ||!Number.isFinite(time)||new Date(time).toISOString().replace('.000Z','Z')!==item.observedAt
        ||time>now.getTime()+30_000||now.getTime()-time>FRESH)throw 0;
      expires=Math.min(expires,time+DAY);
      if(item.component.name==='connector') {
        const end=Date.parse(item.releaseExpiresAt);
        if(typeof item.releaseExpiresAt!=='string'||!Number.isFinite(end)||end<=now.getTime())throw 0;
        expires=Math.min(expires,end);
      }
    }
    const format=time=>new Date(time).toISOString().replace(/\.\d{3}Z$/,'Z');
    const payload=parseReleaseInventory({schemaVersion:1,purpose:RELEASE_INVENTORY_PURPOSE,origin:policy.origin,
      issuedAt:format(now.getTime()),expiresAt:format(expires),combinations:combinations.map(c=>c.components)},policy.origin,now);
    return {schemaVersion:1,payload,payloadSha256:compatibilityDigest(payload),policySha256:compatibilityDigest(policy),evidence};
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();error.details={stage};throw error;
  }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try {
    const [action,policyPath,output,...extra]=process.argv.slice(2);
    if(action!=='prepare'||!policyPath||!output||extra.length)throw 0;
    const policy=JSON.parse((await readBounded(policyPath,512*1024)).toString('utf8'));
    const draft=await prepareReleaseInventory(policy);
    await writeFile(output,JSON.stringify(draft)+'\n',{flag:'wx',mode:0o600});
    console.log(JSON.stringify({prepared:true,payloadSha256:draft.payloadSha256}));
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();
    console.error(JSON.stringify({code:error.code,summaryZh:error.message,summaryEn:error.summaryEn,retryable:error.retryable,action:error.action}));process.exitCode=1;
  }
}
