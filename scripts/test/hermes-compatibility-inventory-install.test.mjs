import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { generateKeyPairSync } from 'node:crypto';
import { spawnSync,spawn } from 'node:child_process';
import { mkdtemp,writeFile,readFile,open,rm,readdir,realpath,chmod,symlink,link,unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join,resolve } from 'node:path';
import { installReleaseInventory } from '../hermes-compatibility-inventory-install.mjs';
import { CompatibilitySigner, RELEASE_INVENTORY_PURPOSE,readCompatibilityPublicationFile } from '../../gateway/dist/account/hermes-compatibility-publication.js';
import { COMPONENT_NAMES } from '../../gateway/dist/account/hermes-compatibility-model.js';

const launcher=resolve('scripts/hermes-compatibility-inventory-install.py');
const modulePath=resolve('scripts/hermes-compatibility-inventory-install.mjs');
const origin='https://release.example';
const stamp=time=>new Date(time).toISOString().replace(/\.\d{3}Z$/,'Z');
const refused=e=>e.code==='HR-HERMESCOMPAT-006'&&e.retryable===true&&e.action==='review_publication';
async function fixture(t){
  const root=await realpath(await mkdtemp(join(tmpdir(),'hermes-inventory-install-')));t.after(()=>rm(root,{recursive:true,force:true}));
  const pair=generateKeyPairSync('ed25519'),signer=new CompatibilitySigner('test',pair.privateKey.export({format:'pem',type:'pkcs8'}),origin);
  const key={id:'test',publicKeyPem:pair.publicKey.export({format:'pem',type:'spki'})};
  const configurationPath=join(root,'publication.json'),envelopePath=join(root,'candidate.json'),target=join(root,'inventory.json'),lockPath=join(root,'.inventory.json.lock');
  await writeFile(configurationPath,JSON.stringify({schemaVersion:1,origin,goCommit:'a'.repeat(40),signingKeys:[key],releaseKeys:[key],inventoryFile:target}),{mode:0o600});
  const lock=await open(lockPath,'wx+',0o600);t.after(()=>lock.close());
  const clock=Date.now();
  const envelope=(offset=0,version='1.0.0',expired=false)=>{
    const issued=clock-60_000+offset*1000,expires=expired?clock-30_000:clock+60*60_000;
    const payload={schemaVersion:1,purpose:RELEASE_INVENTORY_PURPOSE,origin,issuedAt:stamp(issued),expiresAt:stamp(expires),
      combinations:[COMPONENT_NAMES.map(name=>({name,version,artifactSha256:'b'.repeat(64)}))]};
    return signer.signInventory(payload,new Date(issued));
  };
  const options={configurationPath,envelopePath,lockFd:lock.fd};
  const candidate=async value=>writeFile(envelopePath,JSON.stringify(value)+'\n',{mode:0o600});
  return {root,configurationPath,envelopePath,target,lockPath,options,envelope,candidate};
}
function cli(f,action='update'){
  return spawnSync('python3',['-I','-B',launcher,action,f.configurationPath,f.envelopePath],{encoding:'utf8',timeout:10_000});
}

test('kernel-locked CLI initializes, updates and retries without changing identical inventory bytes',async t=>{
  const f=await fixture(t);await f.candidate(f.envelope());
  assert.equal(cli(f).status,1,'update cannot bootstrap a missing inventory');
  const first=cli(f,'initialize');assert.equal(first.status,0,first.stderr);assert.equal(JSON.parse(first.stdout).status,'installed');
  const before=await readFile(f.target);assert.deepEqual(readCompatibilityPublicationFile(f.target),JSON.parse(before));
  assert.equal(fs.statSync(f.target).mode&0o777,0o644);
  assert.equal(JSON.parse(cli(f,'initialize').stdout).status,'unchanged');assert.deepEqual(await readFile(f.target),before);
  await f.candidate(f.envelope(1,'1.0.1'));assert.equal(cli(f,'initialize').status,1);
  const update=cli(f);assert.equal(update.status,0,update.stderr);assert.equal(JSON.parse(update.stdout).status,'installed');
  assert.equal((await readdir(f.root)).filter(n=>n.endsWith('.next')).length,0);
});

test('rollback, same-time replacement, expired and invalid signatures preserve the current file',async t=>{
  const f=await fixture(t);await f.candidate(f.envelope(2));await installReleaseInventory({...f.options,initialize:true});
  const before=await readFile(f.target);
  for(const value of [f.envelope(1),f.envelope(2,'1.0.1'),f.envelope(3,'1.0.1',true),{...f.envelope(3),signature:'a'.repeat(86)}]){
    await f.candidate(value);await assert.rejects(installReleaseInventory(f.options),refused);assert.deepEqual(await readFile(f.target),before);
  }
  await writeFile(f.target,JSON.stringify(f.envelope(0,'1.0.0',true)),{mode:0o644});
  await f.candidate(f.envelope(3,'1.0.1'));assert.equal((await installReleaseInventory(f.options)).status,'installed','expired trusted inventory still permits a newer generation');
});

test('write and post-switch failures leave complete recoverable state and retry uses the same signed payload',async t=>{
  const f=await fixture(t);await f.candidate(f.envelope());await installReleaseInventory({...f.options,initialize:true});
  const before=await readFile(f.target);await f.candidate(f.envelope(1,'1.0.1'));
  await assert.rejects(installReleaseInventory(f.options,{hook:async stage=>{if(stage==='prepared')throw new Error('synthetic-private');}}),e=>{
    assert.equal(JSON.stringify(e).includes('synthetic-private'),false);return refused(e);
  });
  assert.deepEqual(await readFile(f.target),before);assert.equal((await readdir(f.root)).filter(n=>n.endsWith('.next')).length,0);
  await assert.rejects(installReleaseInventory(f.options,{hook:async stage=>{if(stage==='switched')throw 0;}}),refused);
  assert.deepEqual(await readFile(f.target),await readFile(f.envelopePath));assert.equal((await installReleaseInventory(f.options)).status,'unchanged');
});

test('symlinks, hardlinks and writable state or changed configuration cannot be replaced',async t=>{
  const f=await fixture(t);await f.candidate(f.envelope());await installReleaseInventory({...f.options,initialize:true});
  const before=await readFile(f.target);await f.candidate(f.envelope(1));
  await link(f.target,join(f.root,'linked'));await assert.rejects(installReleaseInventory(f.options),refused);await unlink(join(f.root,'linked'));
  await chmod(f.target,0o666);await assert.rejects(installReleaseInventory(f.options),refused);await chmod(f.target,0o644);
  await unlink(f.target);await symlink(f.envelopePath,f.target);await assert.rejects(installReleaseInventory(f.options),refused);await unlink(f.target);
  await writeFile(f.target,before,{mode:0o644});
  await assert.rejects(installReleaseInventory(f.options,{hook:async stage=>{if(stage==='prepared')await writeFile(f.configurationPath,(await readFile(f.configurationPath,'utf8'))+' ');}}),refused);
  assert.deepEqual(await readFile(f.target),before);
});

test('a reader opened before atomic replacement can finish its unlinked signed snapshot; hardlinks remain rejected',async t=>{
  const f=await fixture(t);await writeFile(f.target,JSON.stringify(f.envelope()),{mode:0o644});
  const original=fs.fstatSync;
  try{
    fs.fstatSync=fd=>{fs.unlinkSync(f.target);return original(fd);};syncBuiltinESMExports();
    assert.equal(readCompatibilityPublicationFile(f.target).algorithm,'Ed25519');
  }finally{fs.fstatSync=original;syncBuiltinESMExports();}
  await writeFile(f.target,JSON.stringify(f.envelope()),{mode:0o644});await link(f.target,join(f.root,'linked'));
  assert.throws(()=>readCompatibilityPublicationFile(f.target));
});

async function waitLine(child){
  return new Promise((accept,reject)=>{
    const timer=setTimeout(()=>reject(new Error('fixture timeout')),10_000);
    child.once('exit',()=>{clearTimeout(timer);reject(new Error('fixture exited'));});
    child.stdout.once('data',()=>{clearTimeout(timer);accept();});
  });
}
// Same exec/inherited-fd scheme as the launcher, with a trusted test-only hook in the Node writer.
async function stalledWriter(f,phase){
  const worker=join(f.root,'worker.mjs');
  await writeFile(worker,`import {installReleaseInventory} from ${JSON.stringify(modulePath)};
await installReleaseInventory({configurationPath:${JSON.stringify(f.configurationPath)},envelopePath:${JSON.stringify(f.envelopePath)},lockFd:Number(process.env.LOCK_FD)},
{hook:async stage=>{if(stage===${JSON.stringify(phase)}){console.log('ready');await new Promise(()=>{setInterval(()=>{},1000);});}}});`);
  const py='import os,fcntl,sys\nf=os.open(sys.argv[1],os.O_RDWR);fcntl.flock(f,fcntl.LOCK_EX);os.set_inheritable(f,True)\nos.execve(sys.argv[2],[sys.argv[2],sys.argv[3]],{"PATH":"/usr/bin:/bin","LOCK_FD":str(f)})';
  return spawn('python3',['-I','-B','-c',py,f.lockPath,process.execPath,worker],{stdio:['ignore','pipe','pipe']});
}
test('live writers exclude concurrent CLI updates and SIGKILL releases the kernel lock without exposing partial bytes',async t=>{
  const f=await fixture(t);await f.candidate(f.envelope());assert.equal(cli(f,'initialize').status,0);
  for(const [i,phase] of ['prepared','switched'].entries()){
    const before=await readFile(f.target);await f.candidate(f.envelope(i+1,'1.0.'+(i+1)));
    const child=await stalledWriter(f,phase);t.after(()=>child.kill('SIGKILL'));await waitLine(child);
    assert.equal(cli(f).status,1,'a live writer owns the actual kernel lock');
    assert.deepEqual(await readFile(f.target),phase==='prepared'?before:await readFile(f.envelopePath));
    const exited=new Promise(resolve=>child.once('exit',resolve));child.kill('SIGKILL');await exited;
    const retried=cli(f);assert.equal(retried.status,0,retried.stderr);
    assert.equal(JSON.parse(retried.stdout).status,phase==='prepared'?'installed':'unchanged');
  }
});

test('direct unlocked CLI and a replaced lock inode cannot change installed state',async t=>{
  const f=await fixture(t);await f.candidate(f.envelope());assert.equal(cli(f,'initialize').status,0);
  const before=await readFile(f.target);await f.candidate(f.envelope(1));
  const direct=spawnSync(process.execPath,[modulePath,'update',f.configurationPath,f.envelopePath],{encoding:'utf8',env:{},timeout:10_000});
  assert.equal(direct.status,1);const error=JSON.parse(direct.stderr);assert.equal(error.code,'HR-HERMESCOMPAT-006');
  assert.ok(error.summaryZh);assert.ok(error.summaryEn);assert.equal(error.retryable,true);assert.equal(error.action,'review_publication');
  await assert.rejects(installReleaseInventory(f.options,{hook:async stage=>{if(stage==='prepared'){
    await unlink(f.lockPath);await writeFile(f.lockPath,'',{mode:0o600});
  }}}),refused);
  assert.deepEqual(await readFile(f.target),before);
});
