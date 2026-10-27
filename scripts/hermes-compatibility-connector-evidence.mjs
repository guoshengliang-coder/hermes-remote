// Verify a published Connector artifact against the existing Desktop release signing trust.
// No installation, archive execution, compatibility approval or inventory signing occurs here.
import { createHash, createPublicKey } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { validateDesktopManagedPayload, validateDesktopComponentPayloadV2 } from './lib/desktop-managed-release.mjs';
import { compatibilityErrors, compatibilityHash, compatibilityVersion } from '../gateway/dist/account/hermes-compatibility-model.js';
import { compatibilityOrigin, verifyCompatibilityEnvelope } from '../gateway/dist/account/hermes-compatibility-publication.js';

const MANIFEST_LIMIT=256*1024;
export async function collectConnectorReleaseEvidence(input, fetcher=fetch, now=new Date()) {
  let stage='input';
  try {
    const {origin,releaseVersion,layout,architecture,channel,keyId,publicKey,expected}=input;
    compatibilityOrigin(origin);compatibilityVersion(releaseVersion);
    if (!['bundled','components'].includes(layout)||!['arm64','x86_64','universal'].includes(architecture)
        ||typeof channel!=='string'||!/^[A-Za-z0-9._-]{1,32}$/.test(channel)
        ||typeof keyId!=='string'||!/^[A-Za-z0-9._-]{1,64}$/.test(keyId)
        ||typeof publicKey!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(publicKey)
        ||!expected||Object.keys(expected).sort().join(',')!=='artifactSha256,name,version'||expected.name!=='connector'
        ||!(now instanceof Date)||!Number.isFinite(now.getTime()))throw 0;
    compatibilityVersion(expected.version);compatibilityHash(expected.artifactSha256);
    const keyBytes=Buffer.from(publicKey,'base64url');
    if(keyBytes.length!==32||keyBytes.toString('base64url')!==publicKey)throw 0;
    const key=createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),keyBytes]),format:'der',type:'spki'});
    const directory=layout==='bundled'?'releases':'components';
    const prefix=`${origin}/desktop/${directory}/${releaseVersion}/`;
    const manifestURL=`${prefix}Hermes-Desktop-${layout==='bundled'?'':'Components-'}${releaseVersion}-${architecture}.manifest.json`;
    const deadline=AbortSignal.timeout(120_000);
    async function read(url,limit,keep=false) {
      const response=await fetcher(url,{method:'GET',redirect:'error',credentials:'omit',cache:'no-store',
        headers:{'cache-control':'no-cache'},signal:AbortSignal.any([deadline,AbortSignal.timeout(90_000)])});
      if(response.status!==200||response.redirected||!response.body){await response.body?.cancel().catch(()=>{});throw 0;}
      let size=0;const digest=createHash('sha256'),chunks=[],reader=response.body.getReader();
      try {
        while(true){const next=await reader.read();if(next.done)break;
          size+=next.value.length;if(size>limit)throw 0;
          digest.update(next.value);if(keep)chunks.push(next.value);
        }
      } finally {await reader.cancel().catch(()=>{});}
      return {size,sha256:digest.digest('hex'),bytes:keep?Buffer.concat(chunks):undefined};
    }
    stage='signed_manifest';
    const original=await read(manifestURL,MANIFEST_LIMIT,true);
    const payload=verifyCompatibilityEnvelope(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(original.bytes)),new Map([[keyId,key]]));
    const validate=layout==='bundled'?validateDesktopManagedPayload:validateDesktopComponentPayloadV2;
    validate(payload,{expectedOrigin:origin,expectedChannel:channel,expectedArchitecture:architecture,now});
    if(payload.releaseVersion!==releaseVersion||payload.architecture!==architecture
        ||Date.parse(payload.createdAt)>now.getTime()+30_000||Date.parse(payload.expiresAt)<=now.getTime())throw 0;
    const connector=(layout==='bundled'?payload.artifacts:payload.components).find(c=>(c.component??c.kind)==='connector');
    if(!connector||connector.version!==expected.version||connector.sha256!==expected.artifactSha256
        ||connector.downloadURL!==prefix+connector.fileName)throw 0;
    stage='public_artifact';
    const artifact=await read(connector.downloadURL,connector.sizeBytes);
    if(artifact.size!==connector.sizeBytes||artifact.sha256!==expected.artifactSha256)throw 0;
    stage='manifest_recheck';
    const recheck=await read(manifestURL,MANIFEST_LIMIT);
    if(recheck.sha256!==original.sha256||recheck.size!==original.size)throw 0;
    return {schemaVersion:1,kind:'published-signed-connector-artifact',origin,component:{...expected},
      releaseVersion,layout,architecture,channel,keyId,manifestSha256:original.sha256,
      releaseExpiresAt:payload.expiresAt,observedAt:now.toISOString().replace(/\.\d{3}Z$/,'Z'),
      compatibilityApproved:false};
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();error.details={component:'connector',stage};throw error;
  }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try {
    const {values}=parseArgs({options:Object.fromEntries(['origin','release','layout','architecture','channel','key-id','public-key','version','sha256']
      .map(key=>[key,{type:'string'}])),allowPositionals:false});
    if(Object.keys(values).length!==9||Object.values(values).some(value=>!value))throw 0;
    console.log(JSON.stringify(await collectConnectorReleaseEvidence({origin:values.origin,releaseVersion:values.release,layout:values.layout,
      architecture:values.architecture,channel:values.channel,keyId:values['key-id'],publicKey:values['public-key'],
      expected:{name:'connector',version:values.version,artifactSha256:values.sha256}})));
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();
    console.error(JSON.stringify({code:error.code,summaryZh:error.message,summaryEn:error.summaryEn,retryable:error.retryable,action:error.action}));
    process.exitCode=1;
  }
}
