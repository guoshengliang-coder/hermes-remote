// Read-only evidence for the Web component of a compatibility combination.
// The caller supplies the release pipeline's original package and fixed expected identity.
// This is unsigned evidence, not a trusted release inventory or a Hermes approval.
import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { gunzipSync } from 'node:zlib';
import { validateManifest, readTar } from '../deploy/publish-web-app.mjs';
import { compatibilityErrors, compatibilityHash, compatibilityVersion } from '../gateway/dist/account/hermes-compatibility-model.js';
import { compatibilityOrigin } from '../gateway/dist/account/hermes-compatibility-publication.js';

const MAX_BYTES = 64 * 1024 * 1024;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export async function collectWebReleaseEvidence(input, fetcher = fetch, now = new Date()) {
  let stage = 'package';
  try {
    const {manifest, archive, origin, expected} = input;
    compatibilityOrigin(origin);
    if (!expected || Object.keys(expected).sort().join(',') !== 'artifactSha256,name,version'
        || expected.name !== 'web' || !Buffer.isBuffer(archive) || archive.length > MAX_BYTES
        || !(now instanceof Date) || !Number.isFinite(now.getTime())) throw 0;
    compatibilityVersion(expected.version); compatibilityHash(expected.artifactSha256);
    validateManifest(manifest);
    if (manifest.version !== expected.version || manifest.archiveSha256 !== expected.artifactSha256
        || digest(archive) !== expected.artifactSha256
        || manifest.archiveFile !== `Hermes-Web-${manifest.releaseId}.tar.gz`) throw 0;
    const entries = readTar(gunzipSync(archive, {maxOutputLength: MAX_BYTES}));
    const remaining = new Map(manifest.files.map(file => [file.path,file]));
    if (entries.length !== remaining.size) throw 0;
    for (const entry of entries) {
      const file = remaining.get(entry.path);
      if (!file || entry.data.length !== file.size || digest(entry.data) !== file.sha256) throw 0;
      remaining.delete(entry.path);
    }
    if (remaining.size) throw 0;

    stage = 'public_readback';
    const deadline = AbortSignal.timeout(120_000);
    async function checkFile(file) {
      const url = `${origin}/app/${file.path === 'index.html' ? '' : file.path}`;
      const response = await fetcher(url, {method:'GET', redirect:'error', credentials:'omit',
        headers:{'cache-control':'no-cache'}, signal:AbortSignal.any([deadline,AbortSignal.timeout(15_000)])});
      if (response.status !== 200 || response.redirected || !response.body) {
        await response.body?.cancel().catch(()=>{}); throw 0;
      }
      let size=0; const hash=createHash('sha256'), reader=response.body.getReader();
      try {
        while (true) {
          const next=await reader.read(); if(next.done)break;
          size+=next.value.length; if(size>file.size)throw 0;
          hash.update(next.value);
        }
      } finally { await reader.cancel().catch(()=>{}); }
      if(size!==file.size || hash.digest('hex')!==file.sha256)throw 0;
    }
    const index=manifest.files.find(file=>file.path==='index.html');
    await checkFile(index);
    for(const file of manifest.files)if(file.path!=='index.html')await checkFile(file);
    // Detect an index switch while its assets were being checked. This is a point-in-time
    // observation; a later publication must recheck rather than treating it as a lease.
    await checkFile(index);
    return {schemaVersion:1,kind:'published-web-readback',origin,
      component:{name:'web',version:manifest.version,artifactSha256:manifest.archiveSha256},
      sourceCommit:manifest.sourceCommit,manifestSha256:digest(Buffer.from(JSON.stringify(manifest))),
      checkedFiles:manifest.files.length,observedAt:now.toISOString().replace(/\.\d{3}Z$/,'Z'),
      compatibilityApproved:false};
  } catch {
    // Keep the failed boundary for diagnostics, never response bodies, URLs or raw exceptions.
    const error=compatibilityErrors.evidenceIncomplete();
    error.details={component:'web',stage}; throw error;
  }
}

async function readBounded(path, maximum) {
  const handle=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW);
  try {
    const stat=await handle.stat();
    if(!stat.isFile()||stat.size>maximum)throw 0;
    // Bound the read itself as well as stat, so a growing file cannot defeat the limit.
    const buffer=Buffer.alloc(stat.size+1);
    let size=0;
    while(size<buffer.length){const result=await handle.read(buffer,size,buffer.length-size,null);if(!result.bytesRead)break;size+=result.bytesRead;}
    if(size!==stat.size)throw 0;
    return buffer.subarray(0,size);
  } finally { await handle.close(); }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try {
    const {values}=parseArgs({options:{manifest:{type:'string'},archive:{type:'string'},origin:{type:'string'},
      version:{type:'string'},sha256:{type:'string'}},allowPositionals:false});
    if(Object.values(values).some(value=>!value)||Object.keys(values).length!==5)throw 0;
    const manifest=JSON.parse((await readBounded(values.manifest,512*1024)).toString('utf8'));
    const archive=await readBounded(values.archive,MAX_BYTES);
    console.log(JSON.stringify(await collectWebReleaseEvidence({manifest,archive,origin:values.origin,
      expected:{name:'web',version:values.version,artifactSha256:values.sha256}})));
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();
    console.error(JSON.stringify({code:error.code,summaryZh:error.message,summaryEn:error.summaryEn,
      retryable:error.retryable,action:error.action}));process.exitCode=1;
  }
}
