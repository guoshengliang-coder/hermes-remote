// Called by the Python kernel-lock launcher. The signing key never enters this host-side writer.
import { constants, fstatSync } from 'node:fs';
import { open, lstat, realpath, rename, unlink } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { randomBytes, createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { compatibilityDigest, compatibilityErrors } from '../gateway/dist/account/hermes-compatibility-model.js';
import { parseCompatibilityPublicationConfiguration, parseReleaseInventory, verifyCompatibilityEnvelope } from '../gateway/dist/account/hermes-compatibility-publication.js';

const LIMIT=512*1024;
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
function safe(info,directory=false){
  if((directory?!info.isDirectory():!info.isFile())||info.uid!==process.geteuid()||(info.mode&0o022)!==0||(!directory&&info.nlink!==1))throw 0;
}
async function readProtected(path,missing=false){
  let file;
  try{file=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW);}
  catch(error){if(missing&&error.code==='ENOENT')return null;throw error;}
  try{
    const before=await file.stat();safe(before);if(before.size<1||before.size>LIMIT)throw 0;
    const bytes=Buffer.alloc(before.size+1);let size=0;
    while(size<bytes.length){const r=await file.read(bytes,size,bytes.length-size,null);if(!r.bytesRead)break;size+=r.bytesRead;}
    const after=await file.stat();
    if(size!==before.size||after.size!==before.size||after.ctimeMs!==before.ctimeMs||after.mtimeMs!==before.mtimeMs)throw 0;
    const data=bytes.subarray(0,size);
    return {bytes:data,digest:hash(data),raw:JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(data))};
  }finally{await file.close();}
}
/** hook is for fault tests in trusted code; no environment or CLI option can inject it. */
export async function installReleaseInventory({configurationPath,envelopePath,initialize=false,lockFd},
  {clock=()=>new Date(),hook=async()=>{}}={}) {
  let stage='preflight',temporary,directory;
  try{
    if(typeof configurationPath!=='string'||!configurationPath.startsWith('/')||await realpath(configurationPath)!==configurationPath
      ||typeof envelopePath!=='string'||!envelopePath.startsWith('/')||await realpath(envelopePath)!==envelopePath
      ||typeof initialize!=='boolean'||!Number.isSafeInteger(lockFd)||lockFd<3)throw 0;
    const configuration=await readProtected(configurationPath),policy=parseCompatibilityPublicationConfiguration(configuration.raw);
    const target=configuration.raw.inventoryFile,parent=dirname(target),name=basename(target);
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$/.test(name)||await realpath(parent)!==parent)throw 0;
    directory=await open(parent,constants.O_RDONLY|constants.O_DIRECTORY|constants.O_NOFOLLOW);
    const parentIdentity=await directory.stat();safe(parentIdentity,true);
    const lockPath=join(parent,'.'+name+'.lock');
    async function fence(){
      const current=await lstat(parent);safe(current,true);
      if(current.dev!==parentIdentity.dev||current.ino!==parentIdentity.ino)throw 0;
      const held=fstatSync(lockFd),named=await lstat(lockPath);safe(held);safe(named);
      if(held.dev!==named.dev||held.ino!==named.ino)throw 0;
      if((await readProtected(configurationPath)).digest!==configuration.digest)throw 0;
    }
    await fence();
    const incoming=await readProtected(envelopePath);
    const parsed=verifyCompatibilityEnvelope(incoming.raw,policy.releaseKeys);
    const payload=parseReleaseInventory(parsed,policy.origin,clock()),digest=compatibilityDigest(payload);
    const old=await readProtected(target,true);
    if(old){
      const previous=verifyCompatibilityEnvelope(old.raw,policy.releaseKeys);
      // Expired, previously trusted inventory still provides an ordering boundary; it grants no access.
      const history=parseReleaseInventory(previous,policy.origin,new Date(previous?.issuedAt));
      if(compatibilityDigest(history)===digest){
        await fence();if((await readProtected(target)).digest!==old.digest)throw 0;
        await directory.sync();parseReleaseInventory(parsed,policy.origin,clock());
        return {status:'unchanged',payloadSha256:digest,issuedAt:payload.issuedAt,expiresAt:payload.expiresAt};
      }
      if(initialize||Date.parse(payload.issuedAt)<=Date.parse(history.issuedAt))throw 0;
    }else if(!initialize)throw 0;
    stage='write';const candidatePath=join(parent,`.${name}.${randomBytes(16).toString('hex')}.next`);
    const output=await open(candidatePath,constants.O_CREAT|constants.O_EXCL|constants.O_WRONLY|constants.O_NOFOLLOW,0o644);
    temporary=candidatePath; // Clean up only a file this invocation actually created.
    try{await output.chmod(0o644);await output.writeFile(incoming.bytes);await output.sync();}finally{await output.close();}
    await hook('prepared');
    stage='commit';await fence();parseReleaseInventory(parsed,policy.origin,clock());
    const current=await readProtected(target,true);
    if((current?.digest??null)!==(old?.digest??null)||(await readProtected(temporary)).digest!==incoming.digest)throw 0;
    await rename(temporary,target);temporary=undefined;
    await hook('switched');await directory.sync();
    stage='readback';await fence();
    const readback=await readProtected(target);
    if(readback.digest!==incoming.digest||compatibilityDigest(parseReleaseInventory(
      verifyCompatibilityEnvelope(readback.raw,policy.releaseKeys),policy.origin,clock()))!==digest)throw 0;
    return {status:'installed',payloadSha256:digest,issuedAt:payload.issuedAt,expiresAt:payload.expiresAt};
  }catch{
    const error=compatibilityErrors.publicationIncomplete();error.details={stage};throw error;
  }finally{
    if(temporary)await unlink(temporary).catch(()=>{});
    await directory?.close().catch(()=>{});
  }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try{
    const [action,configurationPath,envelopePath,...extra]=process.argv.slice(2);
    if(!['initialize','update'].includes(action)||extra.length)throw 0;
    const result=await installReleaseInventory({configurationPath,envelopePath,initialize:action==='initialize',
      lockFd:Number(process.env.HERMES_INVENTORY_LOCK_FD)});
    console.log(JSON.stringify(result));
  }catch{
    const error=compatibilityErrors.publicationIncomplete();
    console.error(JSON.stringify({code:error.code,summaryZh:error.message,summaryEn:error.summaryEn,retryable:error.retryable,action:error.action}));process.exitCode=1;
  }
}
