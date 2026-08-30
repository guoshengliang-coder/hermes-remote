import {createHash} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {copyFile, mkdir, open, readFile, rename, rm, stat} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateIndex, validateVersion} from '../release-server/src/schema.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function digest(file) { const hash=createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);return hash.digest('hex'); }
async function matches(file,metadata){try{const info=await stat(file);return info.isFile()&&info.size===metadata.sizeBytes&&await digest(file)===metadata.sha256.toLowerCase();}catch(error){if(error?.code==='ENOENT')return false;throw error;}}
async function durableCopy(source,target,temporary){await copyFile(source,temporary);const handle=await open(temporary,'r+');try{await handle.sync();}finally{await handle.close();}await rename(temporary,target);}
async function syncDirectory(root){const handle=await open(root,'r');try{await handle.sync();}finally{await handle.close();}}
async function atomicJson(target,value,temporary){const handle=await open(temporary,'wx',0o600);try{await handle.writeFile(JSON.stringify(value,null,2)+'\n');await handle.sync();}finally{await handle.close();}await rename(temporary,target);}
const normalized=value=>JSON.stringify(value,Object.keys(value).sort());

async function acquireLock(root,{waitMs=10_000,staleMs=60_000}={}){
  const lock=path.join(root,'.publish.lock');const started=Date.now();
  while(true){
    try{await mkdir(lock,{mode:0o700});await atomicJson(path.join(lock,'owner.json'),{pid:process.pid,createdAt:new Date().toISOString()},path.join(lock,`owner-${process.pid}.tmp`));return async()=>rm(lock,{recursive:true,force:true});}
    catch(error){if(error?.code!=='EEXIST')throw error;const age=Date.now()-(await stat(lock)).mtimeMs;if(age>staleMs){await rename(lock,`${lock}.stale-${process.pid}-${Date.now()}`).catch(()=>{});continue;}if(Date.now()-started>=waitMs)throw new Error('Timed out waiting for publish lock');await sleep(25);}
  }
}

export async function publishRelease(root,apkPath,metadata,options={}){
  validateVersion(metadata);const info=await stat(apkPath);if(!info.isFile()||info.size!==metadata.sizeBytes)throw new Error('APK size mismatch');if(await digest(apkPath)!==metadata.sha256.toLowerCase())throw new Error('APK SHA-256 mismatch');
  await mkdir(root,{recursive:true});const unlock=await acquireLock(root,options);
  try{
    const indexPath=path.join(root,'index.json');let old={schemaVersion:1,channel:metadata.channel,latestVersionCode:0,generatedAt:metadata.publishedAt,versions:[]};
    try{old=validateIndex(JSON.parse(await readFile(indexPath,'utf8')));}catch(error){if(error?.code!=='ENOENT')throw error;}
    const same=old.versions.find(v=>v.versionCode===metadata.versionCode||v.versionName===metadata.versionName);if(same&&normalized(same)!==normalized(metadata))throw new Error('version conflict');
    const suffix=`.tmp-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`,target=path.join(root,metadata.fileName);
    if(same){if(!await matches(target,metadata))await durableCopy(apkPath,target,target+suffix);await syncDirectory(root);return old;}
    const versions=[metadata,...old.versions].sort((a,b)=>b.versionCode-a.versionCode);const next={schemaVersion:1,channel:metadata.channel,latestVersionCode:versions[0].versionCode,generatedAt:metadata.publishedAt,versions};validateIndex(next);
    try{await durableCopy(apkPath,target,target+suffix);try{await copyFile(indexPath,indexPath+'.prev');const previous=await open(indexPath+'.prev','r+');try{await previous.sync();}finally{await previous.close();}}catch(error){if(error?.code!=='ENOENT')throw error;}await atomicJson(indexPath,next,indexPath+suffix);await syncDirectory(root);}finally{await rm(target+suffix,{force:true});await rm(indexPath+suffix,{force:true});}
    return next;
  }finally{await unlock();}
}

if(process.argv[1]===fileURLToPath(import.meta.url)){const [apk,metaPath]=process.argv.slice(2);if(!apk||!metaPath||!process.env.RELEASE_DATA_ROOT)throw new Error('usage: RELEASE_DATA_ROOT=... publish-release.mjs APK METADATA');await publishRelease(process.env.RELEASE_DATA_ROOT,apk,JSON.parse(await readFile(metaPath,'utf8')));}
