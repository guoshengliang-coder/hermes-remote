import {createHash, randomUUID} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {copyFile, mkdir, open, readFile, rename, rm, stat} from 'node:fs/promises';
import {hostname} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {MAX_VERSIONS, validateIndex, validateVersion} from '../release-server/src/schema.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function digest(file) { const hash=createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);return hash.digest('hex'); }
async function matches(file,metadata){try{const info=await stat(file);return info.isFile()&&info.size===metadata.sizeBytes&&await digest(file)===metadata.sha256.toLowerCase();}catch(error){if(error?.code==='ENOENT')return false;throw error;}}
async function durableCopy(source,target,temporary){await copyFile(source,temporary);const handle=await open(temporary,'r+');try{await handle.sync();}finally{await handle.close();}await rename(temporary,target);}
async function syncDirectory(root){const handle=await open(root,'r');try{await handle.sync();}finally{await handle.close();}}
async function atomicJson(target,value,temporary){const handle=await open(temporary,'wx',0o600);try{await handle.writeFile(JSON.stringify(value,null,2)+'\n');await handle.sync();}finally{await handle.close();}await rename(temporary,target);}
const normalized=value=>JSON.stringify(value,Object.keys(value).sort());
const unique=()=>`${process.pid}-${Date.now()}-${randomUUID()}`;

// The publisher never retires a catalog entry on its own: removing a version revokes an APK that a
// tester may still be installing. It warns while slots remain and fails closed once they run out,
// so retention stays an explicit operator decision (docs/APP_UPDATE.md).
export const CATALOG_WARNING_SLOTS=10;
const LOCK_DIR='.publish.lock';
const OWNER_FILE='owner.json';

async function readOwner(lock){
  try{const owner=JSON.parse(await readFile(path.join(lock,OWNER_FILE),'utf8'));return typeof owner?.token==='string'?owner:null;}
  catch{return null;}
}
// A publisher may only delete the lock it still owns. The token is checked before the rename and
// again on the renamed copy, so an old publisher can never remove a live successor.
async function releaseLock(lock,token){
  if((await readOwner(lock))?.token!==token){console.warn(`publish-release: ${lock} now belongs to another publisher; leaving it untouched`);return false;}
  const finished=`${lock}.released-${unique()}`;
  try{await rename(lock,finished);}catch(error){if(error?.code==='ENOENT')return false;throw error;}
  if((await readOwner(finished))?.token!==token){
    try{await rename(finished,lock);}catch{console.warn(`publish-release: could not restore ${lock}; left ${finished} for the operator`);}
    return false;
  }
  await rm(finished,{recursive:true,force:true});return true;
}
async function startOwnership(lock,token){
  const identity={token,pid:process.pid,host:hostname(),createdAt:new Date().toISOString()};
  try{await atomicJson(path.join(lock,OWNER_FILE),identity,path.join(lock,`${OWNER_FILE}.tmp-${unique()}`));}
  catch(error){await rm(lock,{recursive:true,force:true});throw error;}
  return{
    token,path:lock,
    async assertHeld(){if((await readOwner(lock))?.token!==token)throw new Error('lost the publish lock before the transaction completed');},
    async release(){return releaseLock(lock,token);},
  };
}

// mkdir is the in-process atomic gate. This layer deliberately never steals an existing directory:
// observe-then-rename stale recovery has an unavoidable race with a new owner. Production callers
// hold a Linux flock, whose kernel lifetime safely handles crashes, before clearing any stale directory.
export async function acquireLock(root,{waitMs=10_000,pollMs=25}={}){
  const lock=path.join(root,LOCK_DIR),token=`${hostname()}-${process.pid}-${randomUUID()}`,started=Date.now();
  while(true){
    try{await mkdir(lock,{mode:0o700});}
    catch(error){
      if(error?.code!=='EEXIST')throw error;
      if(Date.now()-started>=waitMs)throw new Error('Timed out waiting for publish lock');
      await sleep(pollMs);continue;
    }
    return startOwnership(lock,token);
  }
}

export async function publishRelease(root,apkPath,metadata,options={}){
  validateVersion(metadata);const info=await stat(apkPath);if(!info.isFile()||info.size!==metadata.sizeBytes)throw new Error('APK size mismatch');if(await digest(apkPath)!==metadata.sha256.toLowerCase())throw new Error('APK SHA-256 mismatch');
  await mkdir(root,{recursive:true});const lock=await acquireLock(root,options);
  try{
    const indexPath=path.join(root,'index.json');let old={schemaVersion:1,channel:metadata.channel,latestVersionCode:0,generatedAt:metadata.publishedAt,versions:[]};
    try{old=validateIndex(JSON.parse(await readFile(indexPath,'utf8')));}catch(error){if(error?.code!=='ENOENT')throw error;}
    const same=old.versions.find(v=>v.versionCode===metadata.versionCode||v.versionName===metadata.versionName);if(same&&normalized(same)!==normalized(metadata))throw new Error('version conflict');
    const suffix=`.tmp-${unique()}`,target=path.join(root,metadata.fileName),previousPath=`${indexPath}.prev`;
    if(same){await lock.assertHeld();if(!await matches(target,metadata))await durableCopy(apkPath,target,target+suffix);await syncDirectory(root);return old;}
    const versions=[metadata,...old.versions].sort((a,b)=>b.versionCode-a.versionCode);
    if(versions.length>MAX_VERSIONS)throw new Error(`release catalog is full: ${versions.length} versions exceed the ${MAX_VERSIONS} the release server accepts. Retire an older release first (docs/APP_UPDATE.md); the publisher never deletes one for you.`);
    if(MAX_VERSIONS-versions.length<=CATALOG_WARNING_SLOTS)console.warn(`publish-release: ${versions.length}/${MAX_VERSIONS} catalog slots used; plan release retention before publishing fails closed`);
    const next={schemaVersion:1,channel:metadata.channel,latestVersionCode:versions[0].versionCode,generatedAt:metadata.publishedAt,versions};validateIndex(next);
    await lock.assertHeld();
    try{
      await durableCopy(apkPath,target,target+suffix);
      // Keep a durable rollback point: write index.json.prev through a temporary and rename it, so a
      // crash can never leave a half-written previous index where the operator expects a valid one.
      try{await durableCopy(indexPath,previousPath,previousPath+suffix);await syncDirectory(root);}catch(error){if(error?.code!=='ENOENT')throw error;}
      await atomicJson(indexPath,next,indexPath+suffix);await syncDirectory(root);
    }finally{await rm(target+suffix,{force:true});await rm(previousPath+suffix,{force:true});await rm(indexPath+suffix,{force:true});}
    return next;
  }finally{await lock.release();}
}

if(process.argv[1]===fileURLToPath(import.meta.url)){
  const [apk,metaPath]=process.argv.slice(2),root=process.env.RELEASE_DATA_ROOT;
  if(!apk||!metaPath||!root)throw new Error('usage: RELEASE_DATA_ROOT=... publish-release.mjs APK METADATA');
  // The official shell caller sets this only while Linux flock holds the data-root kernel lock.
  // Under that exclusive fence, removing a directory left by a crashed prior process is race-free.
  if(process.env.PUBLISH_FLOCK_HELD==='1')await rm(path.join(root,LOCK_DIR),{recursive:true,force:true});
  await publishRelease(root,apk,JSON.parse(await readFile(metaPath,'utf8')));
}
