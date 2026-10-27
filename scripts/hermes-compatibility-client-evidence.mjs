// Original release metadata comes from the protected publication pipeline, not an administrator.
// This proves public bytes for an already pinned release, not package signing, installation or runtime identity.
import { createHash } from 'node:crypto';
import { validateVersion } from '../release-server/src/schema.mjs';
import { compatibilityOrigin } from '../gateway/dist/account/hermes-compatibility-publication.js';
import { compatibilityErrors, compatibilityVersion, compatibilityHash, compatibilityCommit } from '../gateway/dist/account/hermes-compatibility-model.js';

export async function collectClientReleaseEvidence(input,fetcher=fetch,now=new Date()) {
  let stage='release_metadata';
  try {
    const {origin,expected}=input;compatibilityOrigin(origin);
    if(!expected||Object.keys(expected).sort().join(',')!=='artifactSha256,name,version'
      ||!['desktop','android'].includes(expected.name)||!(now instanceof Date)||!Number.isFinite(now.getTime()))throw 0;
    compatibilityVersion(expected.version);compatibilityHash(expected.artifactSha256);
    const manifest=JSON.parse(JSON.stringify(input.manifest));
    let url,size,sourceCommit,version,sha256,releaseDate;
    if(expected.name==='android') {
      validateVersion(manifest);
      ({downloadUrl:url,sizeBytes:size,sourceCommit,versionName:version,sha256,publishedAt:releaseDate}=manifest);
      if(new URL(url).origin!==origin)throw 0;
    }else{
      const keys=['schemaVersion','channel','architecture','appVersion','buildNumber','minimumMacOS','downloadURL','sizeBytes','sha256','releaseNotes','sourceCommit','updatedAt'];
      if(!manifest||Object.keys(manifest).length!==keys.length||Object.keys(manifest).some(k=>!keys.includes(k))
        ||manifest.schemaVersion!==1||manifest.channel!=='internal'||!['arm64','x86_64','universal'].includes(manifest.architecture)
        ||!Number.isSafeInteger(manifest.buildNumber)||manifest.buildNumber<1
        ||typeof manifest.minimumMacOS!=='string'||!/^\d+\.\d+(\.\d+)?$/.test(manifest.minimumMacOS)
        ||!Array.isArray(manifest.releaseNotes)||manifest.releaseNotes.length>20
        ||manifest.releaseNotes.some(n=>typeof n!=='string'||!n.length||Buffer.byteLength(n)>500||/[\x00-\x1f\x7f]/.test(n)))throw 0;
      ({downloadURL:url,sizeBytes:size,sourceCommit,appVersion:version,sha256,updatedAt:releaseDate}=manifest);
      if(url!==`${origin}/desktop/apps/${expected.version}/Hermes-Go-Desktop-${expected.version}.dmg`)throw 0;
    }
    compatibilityCommit(sourceCommit);
    if(version!==expected.version||sha256!==expected.artifactSha256||!Number.isSafeInteger(size)||size<1||size>2*1024**3
      ||typeof releaseDate!=='string'||!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(releaseDate)
      ||!Number.isFinite(Date.parse(releaseDate))||Date.parse(releaseDate)>now.getTime()+30_000
      ||![new Date(releaseDate).toISOString(),new Date(releaseDate).toISOString().replace('.000Z','Z')].includes(releaseDate))throw 0;
    stage='public_archive';
    const response=await fetcher(url,{method:'GET',redirect:'error',credentials:'omit',cache:'no-store',
      headers:{'cache-control':'no-cache'},signal:AbortSignal.timeout(120_000)});
    if(response.status!==200||response.redirected||!response.body){await response.body?.cancel().catch(()=>{});throw 0;}
    const digest=createHash('sha256'),reader=response.body.getReader();let total=0;
    try {while(true){const next=await reader.read();if(next.done)break;total+=next.value.length;if(total>size)throw 0;digest.update(next.value);}}
    finally {await reader.cancel().catch(()=>{});}
    if(total!==size||digest.digest('hex')!==expected.artifactSha256)throw 0;
    return {schemaVersion:1,kind:'pinned-public-client-archive',origin,component:{...expected},sourceCommit,
      manifestSha256:createHash('sha256').update(JSON.stringify(manifest)).digest('hex'),
      observedAt:now.toISOString().replace(/\.\d{3}Z$/,'Z'),compatibilityApproved:false};
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();error.details={component:'client',stage};throw error;
  }
}
